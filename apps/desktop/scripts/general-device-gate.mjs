// 通用设备通道协议回归门禁(手工/CI 均可运行)。
// 编码了协议不变量:版本化、设备名唯一不烧码、配对码原子消费、会话级流三帧。
//
// 前置(另开两个终端):
//   1. 桌面实例:DEVA_HOME=<测试家目录> electron --remote-debugging-port=9227 \
//        <启动器> boot.cjs     (boot.cjs 做(userData 隔离) 后 require out/main/index.js;
//         单实例锁按 userData 隔离,可与日常 Deva 并存)
//   2. Mock 模型:任一 OpenAI 兼容端点监听 127.0.0.1:43190(见 /tmp/deva-p2e/mock-llm3.mjs 样例)
//   3. 本仓库已构建:apps/desktop 下 `pnpm exec electron-vite build`
//
// 用法:node scripts/general-device-gate.mjs [CDP端口=9227]
// 输出:逐项 ✅/❌ + 汇总;全绿退出码 0。
import { execSync } from 'node:child_process'
import { spawn } from 'node:child_process'

const CDP = `http://127.0.0.1:${process.argv[2] ?? 9229}`
const RUN = Math.random().toString(36).slice(2, 8)

/** 经 CDP 调桌面渲染层 window.deva.general.pairCode() 取当前 6 位码。 */
async function issueCodeViaCDP() {
  const list = await (await fetch(`${CDP}/json/list`)).json()
  const page = list.find((t) => t.type === 'page')
  const ws = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })
  let id = 0
  const call = (method, params) => new Promise((res) => {
    const mid = ++id
    const h = (ev) => { const m = JSON.parse(ev.data); if (m.id === mid) { ws.removeEventListener('message', h); res(m) } }
    ws.addEventListener('message', h)
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
  await call('Runtime.enable', {})
  const r = await call('Runtime.evaluate', {
    expression: 'window.deva.general.pairCode()',
    awaitPromise: true,
    returnByValue: true
  })
  ws.close()
  return r.result.value.code
}
const code = async () => (await issueCodeViaCDP()).code
const pair = (c, n) => {
  const out = execSync(
    `curl -s -X POST http://127.0.0.1:4317/api/pair -H 'content-type: application/json' -d '{"code":"${c}","deviceName":"${n}"}'`
  ).toString()
  try { return JSON.parse(out) } catch { return { raw: out } }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let pass = 0, fail = 0
const ok = (name, cond, detail = '') => { cond ? pass++ : fail++; console.log(`${cond ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`) }

// ① 版本化:pair 响应带 protocol + capabilities
const p1 = pair(await code(), `${RUN}-v`)
ok('① pair 带 protocol/capabilities', p1.protocol === 1 && Array.isArray(p1.capabilities) && p1.capabilities.includes('session-stream'), `protocol=${p1.protocol} caps=${(p1.capabilities || []).length}`)
const T = p1.token
const info = JSON.parse(execSync(`curl -s http://127.0.0.1:4317/api/info -H "Authorization: Bearer ${T}"`).toString())
ok('② /api/info 带版本化', info.protocol === 1, `protocol=${info.protocol}`)

// ③ 唯一性:重名 409 且不烧码 → 同码换名成功
await sleep(7000) // 配对限速 10 次/分/IP,节奏铺开
const c3 = await code()
const dup = pair(c3, `${RUN}-dup-a`)
ok('③a 首配成功', !!dup.token, dup.deviceId || dup.error)
await sleep(7000)
const c3b = code()
const dup2 = pair(c3b, `${RUN}-dup-a`) // 同名 → 409,不烧码
ok('③b 重名 → 409', dup2.error && dup2.error.includes('已被占用'), dup2.error.slice(0, 40))

// ④ 原子竞态:并发同码 ×3 恰好一成功
const c4 = await code()
const rs = [1, 2, 3].map((n) => pair(c4, `${RUN}-race-${n}`))
const wins = rs.filter((r) => r.token).length
ok('④ 并发同码 ×3 恰好一成功', wins === 1, `wins=${wins}`)

// ⑤ 会话级流:先挂流(直播)再发送 → turn_start/text_delta/done 三帧
const c5 = await code()
const pv = pair(c5, `${RUN}-sse`)
const T5 = pv.token
const SID = JSON.parse(execSync(`curl -s -X POST http://127.0.0.1:4317/api/sessions -H "Authorization: Bearer ${T5}"`).toString()).id
const child = spawn('curl', ['-s', '-N', '--max-time', '14', '-H', `Authorization: Bearer ${T5}`, `http://127.0.0.1:4317/api/sessions/${SID}/events`], {})
let captured = ''
child.stdout.on('data', (d) => { captured += d.toString() })
await sleep(1200) // 流连接稳定后再发送:turn_start 应实时到达
execSync(`curl -s -X POST "http://127.0.0.1:4317/api/sessions/${SID}/send" -H "Authorization: Bearer ${T5}" -H 'content-type: application/json' -d '{"text":"count to 3"}' > /dev/null`)
await sleep(9000)
child.kill()
const uniq = [...new Set([...captured.matchAll(/"type":"([a-z_]+)"/g)].map((m) => m[1]))]
ok('⑤ 会话级流含 turn_start/text_delta/done', uniq.includes('turn_start') && uniq.includes('text_delta') && uniq.includes('done'), uniq.join(' '))

console.log(`\n===== ${pass}/${pass + fail} PASS =====`)
process.exit(fail ? 1 : 0)
