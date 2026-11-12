import { randomBytes, randomInt } from 'node:crypto'
import { createReadStream, promises as fs } from 'node:fs'
import { hostname } from 'node:os'
import { basename, extname, isAbsolute } from 'node:path'
import { createServer as createHttpServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { app } from 'electron'
import { getChatRuntime, parseAskQuestions } from '../chat'
import { ensureSession, getSession, listSessions, save as saveProject } from '../chat-store'
import type { StoredSession } from '../chat-store'
import { resolveDefaultEffort, resolveDefaultModel, resolveModelRefOrNull } from '../model-resolve'
import { getPersona } from '../personas'
import { setSecret } from '../secrets'
import type { Message } from '../../providers/types'
import { isEffortLevel } from '../../../shared/effort'
import type { ChannelState } from '../remote/types'
import { deviceIdForToken, invalidateTokenCache } from './auth'
import { noteDeviceSeen, noteDeviceStream } from './activity'
import { addDevice, type AndroidServerConfig } from './config'
import { consumePairCode, pairAttemptAllowed, verifyPairCode } from './pairing'
import { openTurnStream } from './sse'

/**
 * 入站 HTTP 服务器（node:http，REST + SSE 同一监听，设计 §3.1/3.3）。
 * 只做转发：列会话调 chat-store 的既有函数、busy 调 chatRuntime——不复制业务规则，
 * 不新增任何会话语义。异常只回 500 / 上报状态，不抛给 chatRuntime（设计 §3.5）。
 */

export interface ServerHooks {
  onState(state: ChannelState, error?: string): void
}

let server: Server | null = null

const errText = (e: unknown): string => (e as Error)?.message ?? String(e)

function json(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(body))
}

/** 读 JSON 请求体（超限 / 非法 JSON 返回 null）。 */
function readJson<T>(req: IncomingMessage, limit = 64 * 1024): Promise<T | null> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = []
    let size = 0
    req.on('data', (c: Buffer) => {
      size += c.length
      if (size > limit) {
        resolve(null)
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => {
      try {
        resolve(chunks.length ? (JSON.parse(Buffer.concat(chunks).toString('utf8')) as T) : null)
      } catch {
        resolve(null)
      }
    })
    req.on('error', () => resolve(null))
  })
}

function genDeviceId(): string {
  return `dev_${Date.now().toString(36)}_${randomInt(0, 36 ** 4).toString(36)}`
}

/** 回填给手机端的展示条目（不含工具输出/思考等模型上下文细节，见 projectMessages）。 */
interface BackfillItem {
  i: number
  role: 'user' | 'assistant'
  text?: string
  /** 工具调用名列表（assistant 消息；手机端折叠成一行「正在…」）。 */
  tools?: string[]
  /** 图片 / 文档附件个数（正文之外的内容块）。 */
  attachments?: number
  /**
   * 未决交互卡（ask_user / exit_plan）：仅当边车（asks / plans）无该 toolUseId 终态时才回填——
   * 已答过的卡手机端按普通工具行折叠。挂载请求卡不入历史（权限闸门内存产物，见设计 §3.3）。
   */
  card?: { kind: 'ask'; key: string; questions: ReturnType<typeof parseAskQuestions> } | {
    kind: 'plan'
    key: string
    plan: string
  }
  /**
   * 交付文件（present_files，设计 §5-5）：路径来自会话边车 delivered（与桌面交付卡同源，
   * 绝不接受请求指定任意路径）。有此字段时本条目的 tools 不再含 present_files——手机端整行画成交付卡。
   */
  files?: DeliverableFileJson[]
}

/** 交付文件条目（回填与 /deliveries 同形）。size=null 表示文件已被移走或删除。 */
interface DeliverableFileJson {
  path: string
  name: string
  size: number | null
  image: boolean
}

/** 手机端能内联预览的图片扩展名（与桌面 deliverables 的图片集合对齐；svg 手机端不预览）。 */
const IMAGE_EXTS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.tif', '.tiff', '.avif', '.heic'])

/**
 * present_files 一次交付的文件清单（绝对路径、已解析）：优先会话边车 delivered，
 * 缺失时回落历史里该调用的绝对路径参数（早期版本把解析后的路径直接写进了参数）。
 * 与 deliverables.ts 的 deliveredPaths 同源同口径，不在通道里改写业务规则。
 */
function deliveredPaths(session: StoredSession, toolUseId: string): string[] {
  const recorded = session.delivered?.[toolUseId]
  if (recorded?.length) return recorded
  for (const m of session.messages) {
    if (m.role !== 'assistant' || typeof m.content === 'string') continue
    for (const p of m.content) {
      if (p.type !== 'tool_use' || p.id !== toolUseId) continue
      if (p.name !== 'present_files') return []
      const paths = (p.input as { paths?: unknown } | null)?.paths
      return Array.isArray(paths)
        ? paths.filter((x): x is string => typeof x === 'string' && isAbsolute(x))
        : []
    }
  }
  return []
}

async function deliveredFiles(session: StoredSession, toolUseId: string): Promise<DeliverableFileJson[]> {
  const paths = deliveredPaths(session, toolUseId)
  return Promise.all(
    paths.map(async (p) => {
      const st = await fs.stat(p).catch(() => null)
      return {
        path: p,
        name: basename(p),
        size: st?.isFile() ? st.size : null,
        image: IMAGE_EXTS.has(extname(p).toLowerCase())
      }
    })
  )
}

/**
 * 把主进程的 Message[]（模型上下文真源）投影成手机端可显示的线性列表：
 * 正文取 text 块；assistant 的 tool_use 折叠成工具名；user 消息里的 tool_result（provider 约定）
 * 不是人话，跳过；图片/文档只报个数（v1 不回填正文附件）。i 为投影后序号，供分页定位。
 * 未决交互卡判定只读边车终态（asks/plans），不在通道里复制「循环暂停」语义。
 * 交付文件（present_files）读边车 delivered：有交付时该调用整行升格为 files 卡。
 */
async function projectMessages(messages: Message[], session: StoredSession): Promise<BackfillItem[]> {
  const out: BackfillItem[] = []
  for (const m of messages) {
    const texts: string[] = []
    const tools: string[] = []
    const files: DeliverableFileJson[] = []
    let attachments = 0
    let card: BackfillItem['card']
    if (typeof m.content === 'string') {
      if (m.content.trim()) texts.push(m.content)
    } else {
      for (const p of m.content) {
        if (p.type === 'text') {
          if (p.text.trim()) texts.push(p.text)
        } else if (p.type === 'tool_use') {
          if (p.name === 'present_files') {
            const f = await deliveredFiles(session, p.id)
            if (f.length) {
              files.push(...f)
              continue // 有交付：tools 不再带 present_files，手机端整行画交付卡
            }
          }
          tools.push(p.name)
          if (m.role === 'assistant' && !card) {
            // 一对话同时最多一张未决卡（交互工具会暂停循环）；终态在边车的不再当未决回填
            if (p.name === 'ask_user' && session.asks?.[p.id] === undefined)
              card = { kind: 'ask', key: p.id, questions: parseAskQuestions(p.input) }
            else if (p.name === 'exit_plan' && session.plans?.[p.id] === undefined) {
              const plan =
                typeof (p.input as { plan?: unknown })?.plan === 'string'
                  ? (p.input as { plan: string }).plan
                  : ''
              if (plan.trim()) card = { kind: 'plan', key: p.id, plan }
            }
          }
        } else if (p.type === 'image' || p.type === 'document') {
          attachments++
        }
        // tool_result：工具输出，桌面端折叠展示，v1 手机端不回填
      }
    }
    if (texts.length || tools.length || attachments || card || files.length)
      out.push({
        i: out.length,
        role: m.role,
        ...(texts.length ? { text: texts.join('\n\n') } : {}),
        ...(tools.length ? { tools } : {}),
        ...(attachments ? { attachments } : {}),
        ...(card ? { card } : {}),
        ...(files.length ? { files } : {})
      })
  }
  return out
}

/** 下载响应的 Content-Type（够用的最小集；未知一律 octet-stream）。 */
const CONTENT_TYPES: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.bmp': 'image/bmp',
  '.svg': 'image/svg+xml',
  '.pdf': 'application/pdf',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/plain; charset=utf-8',
  '.log': 'text/plain; charset=utf-8',
  '.csv': 'text/plain; charset=utf-8',
  '.json': 'application/json',
  '.html': 'text/html; charset=utf-8',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.zip': 'application/zip'
}

async function route(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? '/', 'http://local')
  const method = req.method ?? 'GET'

  try {
    // ── 配对：唯一免鉴权接口（设计 §3.2）──
    if (method === 'POST' && url.pathname === '/api/pair') {
      const ip = req.socket.remoteAddress ?? ''
      if (!pairAttemptAllowed(ip)) return json(res, 429, { error: '配对尝试太频繁，请一分钟后再试。' })
      const body = await readJson<{ code?: unknown; deviceName?: unknown }>(req)
      const code = typeof body?.code === 'string' ? body.code.trim() : ''
      if (!code) return json(res, 400, { error: '缺少配对码。' })
      if (!verifyPairCode(code))
        return json(res, 400, { error: '配对码不正确或已过期，请在电脑端重新生成。' })
      const deviceId = genDeviceId()
      const token = randomBytes(32).toString('base64url')
      const saved = await setSecret(`android:${deviceId}:token`, token)
      if (!saved.ok)
        return json(res, 500, { error: '系统密钥存储不可用，无法安全保存设备凭据。' })
      const name =
        typeof body?.deviceName === 'string' && body.deviceName.trim()
          ? body.deviceName.trim().slice(0, 40)
          : 'Android 设备'
      addDevice({ id: deviceId, name, pairedAt: Date.now() })
      invalidateTokenCache()
      consumePairCode() // 一次性：配对成功即作废当前码
      return json(res, 200, { deviceId, token })
    }

    // ── 鉴权中间件：其余一律 Bearer token（设计 §3.2）──
    const header = req.headers.authorization ?? ''
    const token = header.startsWith('Bearer ') ? header.slice(7).trim() : ''
    const deviceId = await deviceIdForToken(token)
    if (!deviceId) return json(res, 401, { error: '未配对或凭据无效。' })
    noteDeviceSeen(deviceId)

    // GET /api/info：版本、主机名、平台、是否首次运行（首次运行 = 还没有任何会话）
    if (method === 'GET' && url.pathname === '/api/info')
      return json(res, 200, {
        version: app.getVersion(),
        hostname: hostname(),
        platform: process.platform,
        firstRun: listSessions().length === 0
      })

    // GET /api/sessions：会话列表（转发 chat-store；成员会话不进手机列表，与 hub /list 同口径）
    if (method === 'GET' && url.pathname === '/api/sessions') {
      const rt = getChatRuntime()
      return json(res, 200, {
        sessions: listSessions()
          .filter((s) => !s.parentId)
          .sort((a, b) => b.updatedAt - a.updatedAt)
          .map((s) => ({
            id: s.id,
            title: s.title,
            personaId: s.personaId,
            updatedAt: s.updatedAt,
            busy: rt?.isBusy(s.id) ?? false,
            archived: s.archived === true
          }))
      })
    }

    // POST /api/sessions：新建空会话（可选 name 仅作提示——标题仍由桌面端 deriveTitle 首发时生成）。
    // 转发 chat-store 既有函数（ensureSession + saveProject，与 chat:create-session 同源），不新增语义。
    if (method === 'POST' && url.pathname === '/api/sessions') {
      const id = `sess_${Date.now().toString(36)}_${randomInt(0, 36 ** 4).toString(36)}`
      ensureSession(id)
      saveProject(id)
      return json(res, 200, { id })
    }

    // POST /api/sessions/:id/send：发消息（转调 chatRuntime.send，设计 §3.3——与桌面 IPC 同一入口）。
    // 模型解析照抄 hub 的链：请求显式 modelRef → 会话已存 → 角色偏好 → 全局默认（ChatSendRequest.model
    // 必填，入站通道必须自己解析出一个，不能把空值传进去）。
    const mSend = url.pathname.match(/^\/api\/sessions\/([^/]+)\/send$/)
    if (mSend && method === 'POST') {
      const rt = getChatRuntime()
      if (!rt) return json(res, 503, { error: 'Deva 还没准备好，请稍后再试。' })
      const sid = decodeURIComponent(mSend[1])
      const body = await readJson<{
        text?: unknown
        attachments?: unknown
        modelRef?: unknown
        effort?: unknown
      }>(req)
      const text = typeof body?.text === 'string' ? body.text.trim() : ''
      if (!text) return json(res, 400, { error: '缺少消息正文。' })
      const session = getSession(sid)
      if (!session) return json(res, 404, { error: '会话不存在。' })
      const persona = session.personaId ? getPersona(session.personaId) : null
      const explicitRef = typeof body?.modelRef === 'string' && body.modelRef ? body.modelRef : null
      const model =
        (explicitRef ? resolveModelRefOrNull(explicitRef) : null) ??
        resolveModelRefOrNull(session.model) ??
        resolveModelRefOrNull(persona?.model) ??
        resolveDefaultModel()
      if (!model) return json(res, 400, { error: '还没有可用的模型，请先在电脑端的设置里配置并选定模型。' })
      const attachments = Array.isArray(body?.attachments)
        ? body.attachments.filter((a): a is string => typeof a === 'string' && a.length > 0)
        : undefined
      try {
        const { turnId } = await rt.send({
          sessionId: sid,
          text,
          model,
          workspaceRoot: null,
          ...(attachments?.length ? { attachments } : {}),
          // 与桌面/hub 同：记下这一轮实际生效的模型与思考等级，此后本对话固定用它们
          modelRef: explicitRef ?? `${model.providerId}:${model.model}`,
          effort: isEffortLevel(body?.effort) ? body.effort : (session.effort ?? resolveDefaultEffort())
        })
        return json(res, 200, { turnId })
      } catch (e) {
        // busy 等业务错误从 chatRuntime 抛出：409 原样带文案回给手机端，不吞
        return json(res, 409, { error: errText(e) })
      }
    }

    // POST /api/sessions/:id/answer：交互卡作答（转调 answerAsk / decidePlan / answerMount，设计 §3.3）。
    // 卡键 = 交互工具的 toolUseId；ok=false（卡已结束/不存在）回 409，让手机端把卡收成已决态。
    // mount 的 path 手机端给不出可信目录（系统对话框在桌面），与远程通道同口径只传 null（暂不挂载）。
    const mAns = url.pathname.match(/^\/api\/sessions\/([^/]+)\/answer$/)
    if (mAns && method === 'POST') {
      const rt = getChatRuntime()
      if (!rt) return json(res, 503, { error: 'Deva 还没准备好，请稍后再试。' })
      if (!getSession(decodeURIComponent(mAns[1]))) return json(res, 404, { error: '会话不存在。' })
      const body = await readJson<{ key?: unknown; kind?: unknown; payload?: unknown }>(req)
      const key = typeof body?.key === 'string' ? body.key.trim() : ''
      if (!key) return json(res, 400, { error: '缺少卡片键（key）。' })
      const payload = (body?.payload ?? {}) as Record<string, unknown>
      if (body?.kind === 'ask') {
        const raw = Array.isArray(payload.answers) ? payload.answers : null
        // answers: 逐题字符串数组（与 questions 对齐）；null = 取消/中止（AskResponse 语义原样透传）
        const answers =
          raw === null ? null : raw.map((a) => (typeof a === 'string' ? a : String(a ?? '')))
        if (!rt.answerAsk({ key, answers })) return json(res, 409, { error: '这张问答卡已结束或不存在。' })
        return json(res, 200, { ok: true })
      }
      if (body?.kind === 'plan') {
        const decision = payload.decision
        if (decision !== 'approve' && decision !== 'keep' && decision !== 'ignore')
          return json(res, 400, { error: 'decision 必须是 approve / keep / ignore。' })
        const feedback = typeof payload.feedback === 'string' ? payload.feedback : undefined
        if (!rt.decidePlan({ key, decision, ...(feedback !== undefined ? { feedback } : {}) }))
          return json(res, 409, { error: '这张计划卡已结束或不存在。' })
        return json(res, 200, { ok: true })
      }
      if (body?.kind === 'mount') {
        const path = typeof payload.path === 'string' && payload.path.trim() ? payload.path.trim() : null
        if (!rt.answerMount({ key, path })) return json(res, 409, { error: '这张挂载卡已结束或不存在。' })
        return json(res, 200, { ok: true })
      }
      return json(res, 400, { error: 'kind 必须是 ask / plan / mount。' })
    }

    // POST /api/turns/:turnId/abort：停止回合（转调 chatRuntime.abort；未知 turnId 为 no-op，照常 ok）
    const mAbort = url.pathname.match(/^\/api\/turns\/([A-Za-z0-9_-]+)\/abort$/)
    if (mAbort && method === 'POST') {
      const rt = getChatRuntime()
      if (!rt) return json(res, 503, { error: 'Deva 还没准备好，请稍后再试。' })
      rt.abort(mAbort[1])
      return json(res, 200, { ok: true })
    }

    // GET /api/sessions/:id/messages：历史回填（投影展示条目；?start=&limit= 向后翻页，默认返回最新一段）
    const mMsg = url.pathname.match(/^\/api\/sessions\/([^/]+)\/messages$/)
    if (mMsg && method === 'GET') {
      const session = getSession(decodeURIComponent(mMsg[1]))
      if (!session) return json(res, 404, { error: '会话不存在。' })
      const items = await projectMessages(session.messages, session)
      const total = items.length
      const qLimit = Number.parseInt(url.searchParams.get('limit') ?? '', 10)
      const limit = Number.isFinite(qLimit) ? Math.min(Math.max(qLimit, 1), 2000) : 400
      const qStart = Number.parseInt(url.searchParams.get('start') ?? '', 10)
      const start = Number.isFinite(qStart)
        ? Math.min(Math.max(qStart, 0), Math.max(total - 1, 0))
        : Math.max(total - limit, 0)
      return json(res, 200, { sessionId: session.id, total, start, messages: items.slice(start, start + limit) })
    }

    // GET /api/sessions/:id/deliveries?toolUseId=…：直播回合的交付文件（手机收到 present_files
    // 的 tool_result 后拉这口补清单）。回填口 /messages 的 files 字段与其同形。
    const mDel = url.pathname.match(/^\/api\/sessions\/([^/]+)\/deliveries$/)
    if (mDel && method === 'GET') {
      const session = getSession(decodeURIComponent(mDel[1]))
      if (!session) return json(res, 404, { error: '会话不存在。' })
      const toolUseId = url.searchParams.get('toolUseId') ?? ''
      if (!toolUseId) return json(res, 400, { error: '缺少 toolUseId。' })
      return json(res, 200, { files: await deliveredFiles(session, toolUseId) })
    }

    // GET /api/files?path=…&session=…：下载交付文件 / 取图片预览（设计 §3.3）。
    // 路径必须出现在该会话边车 delivered 里——通道不提供任意路径读取（与桌面 deliverables IPC 同一边界）。
    if (url.pathname === '/api/files' && method === 'GET') {
      const sid = url.searchParams.get('session') ?? ''
      const p = url.searchParams.get('path') ?? ''
      const session = sid ? getSession(decodeURIComponent(sid)) : undefined
      if (!session) return json(res, 404, { error: '会话不存在。' })
      if (!p || !Object.values(session.delivered ?? {}).some((arr) => arr.includes(p)))
        return json(res, 403, { error: '只允许下载该对话交付过的文件。' })
      const st = await fs.stat(p).catch(() => null)
      if (!st?.isFile()) return json(res, 404, { error: '文件不存在或已被移动。' })
      res.writeHead(200, {
        'content-type': CONTENT_TYPES[extname(p).toLowerCase()] ?? 'application/octet-stream',
        'content-length': st.size
      })
      const stream = createReadStream(p)
      stream.on('error', () => res.destroy())
      stream.pipe(res)
      return
    }

    // GET /api/turns/:turnId/events：SSE（长连接，此处不写 JSON 结尾）
    const m = url.pathname.match(/^\/api\/turns\/([A-Za-z0-9_-]+)\/events$/)
    if (m && method === 'GET') {
      const h = req.headers['last-event-id']
      const raw = Array.isArray(h) ? h[0] : h
      const parsed = Number.parseInt(raw ?? '', 10)
      noteDeviceStream(deviceId, true)
      openTurnStream(res, m[1], Number.isFinite(parsed) ? parsed : 0, deviceId)
      return
    }

    return json(res, 404, { error: '接口不存在。' })
  } catch (e) {
    if (!res.headersSent) json(res, 500, { error: '服务器内部错误。' })
    else res.end()
    console.warn('[channel-server] 处理请求失败：', errText(e))
  }
}

/** 起监听（设计 §3.1：端口冲突明确报错，不静默换端口）。 */
export async function startServer(opts: { host: string; port: number }, hooks: ServerHooks): Promise<void> {
  await stopServer()
  const srv = createHttpServer((req, res) => {
    void route(req, res)
  })
  server = srv
  srv.on('error', (e) => {
    const code = (e as NodeJS.ErrnoException).code
    hooks.onState(
      'error',
      code === 'EADDRINUSE'
        ? `端口 ${opts.port} 已被占用，请在配置里换一个端口（config.json → androidServer.port）。`
        : errText(e)
    )
  })
  await new Promise<void>((resolve) => {
    srv.once('listening', () => {
      hooks.onState('connected')
      resolve()
    })
    srv.once('error', () => resolve())
    srv.listen(opts.port, opts.host)
  })
}

export async function stopServer(): Promise<void> {
  const srv = server
  server = null
  if (!srv) return
  // SSE 是长连接：先掐掉全部连接再 close，close 回调才会到来
  srv.closeAllConnections()
  await new Promise<void>((resolve) => srv.close(() => resolve()))
}

/** 供 channel-server/index.ts 组装配置（类型复用）。 */
export type { AndroidServerConfig, ChannelState }
