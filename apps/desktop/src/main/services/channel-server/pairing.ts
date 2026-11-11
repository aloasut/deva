import { randomInt } from 'node:crypto'
import { networkInterfaces } from 'node:os'

/**
 * 安卓端的配对码（与 IM 通道的邀请码同构但独立）：6 位数字、5 分钟有效（设计 §3.2），
 * 只在桌面「远程设备」面板显示；新码生成即顶替旧码，配对成功即作废（一次性）。
 * 暴力猜测由限速兜底：同 IP 每分钟 10 次配对尝试，超限 429（server.ts 调 pairAttemptAllowed）。
 */

const PAIR_TTL_MS = 5 * 60_000
const PAIR_WINDOW_MS = 60_000
const PAIR_MAX_ATTEMPTS = 10

let current: { code: string; expiresAt: number } | null = null
/** 同 IP 的尝试时间戳（滑动窗口）。 */
const attempts = new Map<string, number[]>()

export function issuePairCode(): { code: string; expiresAt: number } {
  const code = String(randomInt(0, 1_000_000)).padStart(6, '0')
  current = { code, expiresAt: Date.now() + PAIR_TTL_MS }
  return { ...current }
}

/** 取消配对：当前码立即作废（离开面板时调，与 IM 通道的 pairCancel 同语义）。 */
export function cancelPairCode(): void {
  current = null
}

export function verifyPairCode(code: string): boolean {
  if (!current) return false
  if (current.expiresAt < Date.now()) {
    current = null
    return false
  }
  return code === current.code
}

/** 配对成功后把当前码作废（一次性）。 */
export function consumePairCode(): void {
  current = null
}

/** 滑动窗口限速；放行返回 true，超限 false（HTTP 429）。 */
export function pairAttemptAllowed(ip: string): boolean {
  const now = Date.now()
  const list = (attempts.get(ip) ?? []).filter((t) => now - t < PAIR_WINDOW_MS)
  attempts.set(ip, list)
  if (list.length >= PAIR_MAX_ATTEMPTS) return false
  list.push(now)
  // 表过大时清掉已出窗的旧 IP，防长期运行缓慢泄漏
  if (attempts.size > 1000)
    for (const [k, v] of attempts) if (v.length === 0 || now - v[v.length - 1] >= PAIR_WINDOW_MS) attempts.delete(k)
  return true
}

/** 局域网 IPv4（二维码里的 host）：取第一个非内部地址；拿不到则回退 127.0.0.1（同机调试仍可用）。 */
export function lanHost(): string {
  for (const list of Object.values(networkInterfaces()))
    for (const net of list ?? []) if (net.family === 'IPv4' && !net.internal) return net.address
  return '127.0.0.1'
}
