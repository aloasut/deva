import { randomBytes, randomInt } from 'node:crypto'
import { hostname } from 'node:os'
import { createServer as createHttpServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { app } from 'electron'
import { getChatRuntime } from '../chat'
import { listSessions } from '../chat-store'
import { setSecret } from '../secrets'
import type { ChannelState } from '../remote/types'
import { deviceIdForToken, invalidateTokenCache } from './auth'
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

    // GET /api/turns/:turnId/events：SSE（长连接，此处不写 JSON 结尾）
    const m = url.pathname.match(/^\/api\/turns\/([A-Za-z0-9_-]+)\/events$/)
    if (m && method === 'GET') {
      const h = req.headers['last-event-id']
      const raw = Array.isArray(h) ? h[0] : h
      const parsed = Number.parseInt(raw ?? '', 10)
      openTurnStream(res, m[1], Number.isFinite(parsed) ? parsed : 0)
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
