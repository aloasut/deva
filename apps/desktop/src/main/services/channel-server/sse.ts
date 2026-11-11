import type { ServerResponse } from 'node:http'
import { onAppNotice, onChatEvent, type AppNotice } from '../chat-bus'

/**
 * SSE 事件流（设计 §3.4）：channel-server 对 chat-bus 的唯一订阅在这里，
 * 每个 SSE 连接只是「按回合过滤的视图」，不各自订阅。
 *
 *  · 所有事件先进进程内环形缓冲（每会话 2000 条），连接断开后照常积累，重连续传靠它；
 *  · 帧形状：id 单调递增（进程内计数器），event 仅 chat / notice 两种，
 *    data 把 sessionId / turnId 平铺进 JSON、ChatStreamEvent / AppNotice 原样透传，不新增事件形状；
 *  · GET /api/turns/:turnId/events：投递本回合的事件 + 该会话的会话级事件（turnId 为空串的 chat
 *    与带 sessionId 的 notice）；客户端带 Last-Event-ID 补发增量，超出缓冲回 replay_reset
 *    （客户端重新拉 /messages 回填）。
 */

const RING_CAPACITY = 2000
/** turnId → sessionId 索引的容量上限（防长期运行无界增长；淘汰最老的）。 */
const TURN_INDEX_MAX = 2000
const HEARTBEAT_MS = 15_000

interface Frame {
  id: number
  /** SSE 的 event 名。 */
  kind: 'chat' | 'notice'
  sessionId?: string
  turnId?: string
  data: string
}

interface Connection {
  res: ServerResponse
  turnId: string
  /** 首个本回合事件到达后锁定其会话；会话级事件只投给已锁定会话的连接。 */
  sessionId?: string
  heartbeat: ReturnType<typeof setInterval>
}

let nextId = 1
const rings = new Map<string, Frame[]>()
const turnIndex = new Map<string, string>()
const connections = new Set<Connection>()
let unsubscribers: (() => void)[] = []

function lockSession(conn: Connection, f: Frame): void {
  if (conn.sessionId === undefined && f.kind === 'chat' && f.turnId === conn.turnId && f.sessionId)
    conn.sessionId = f.sessionId
}

function matches(conn: Connection, f: Frame): boolean {
  // 本回合的事件
  if (f.kind === 'chat' && f.turnId === conn.turnId) return true
  // 已锁定会话后的：该会话的会话级事件（turnId 为空串）与会话通知
  if (conn.sessionId !== undefined && f.sessionId === conn.sessionId) {
    if (f.kind === 'notice') return true
    if (f.turnId === '') return true
  }
  return false
}

function push(f: Frame): void {
  if (!f.sessionId) return // 无会话归属的通知只做实时投递，不进缓冲
  let ring = rings.get(f.sessionId)
  if (!ring) {
    ring = []
    rings.set(f.sessionId, ring)
  }
  ring.push(f)
  if (ring.length > RING_CAPACITY) ring.splice(0, ring.length - RING_CAPACITY)
  if (f.kind === 'chat' && f.turnId && !turnIndex.has(f.turnId)) {
    turnIndex.set(f.turnId, f.sessionId)
    if (turnIndex.size > TURN_INDEX_MAX) {
      const oldest = turnIndex.keys().next().value
      if (oldest !== undefined) turnIndex.delete(oldest)
    }
  }
}

function writeFrame(conn: Connection, f: Frame): void {
  if (conn.res.destroyed || conn.res.writableEnded) return
  conn.res.write(`id: ${f.id}\nevent: ${f.kind}\ndata: ${f.data}\n\n`)
}

function deliver(f: Frame): void {
  for (const conn of connections) {
    lockSession(conn, f)
    if (matches(conn, f)) writeFrame(conn, f)
  }
}

/** 订阅 chat-bus（通道启动时调一次；停止时退订并断开全部连接）。 */
export function startEventStream(): void {
  if (unsubscribers.length) return
  unsubscribers.push(
    onChatEvent(({ sessionId, turnId, event }) => {
      const f: Frame = {
        id: nextId++,
        kind: 'chat',
        sessionId,
        turnId,
        data: JSON.stringify({ sessionId, turnId, ...event })
      }
      push(f)
      deliver(f)
    }),
    onAppNotice((n: AppNotice) => {
      const f: Frame = { id: nextId++, kind: 'notice', sessionId: n.sessionId, data: JSON.stringify(n) }
      push(f)
      deliver(f)
    })
  )
}

/**
 * 接管一条 SSE 响应：补发增量（仅当带了 Last-Event-ID）→ 挂上实时投递 → 心跳。
 * replay_reset 语义（设计 §3.4）：Last-Event-ID 早于缓冲最老一帧（超出缓冲）或来自上一进程
 * （计数器已重置）时，发一帧 notice 让客户端重新拉 /messages。
 */
export function openTurnStream(res: ServerResponse, turnId: string, lastEventId: number): void {
  res.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
    'x-accel-buffering': 'no'
  })
  res.write('retry: 3000\n\n')

  const conn: Connection = {
    res,
    turnId,
    heartbeat: setInterval(() => {
      if (!res.destroyed && !res.writableEnded) res.write(': ping\n\n')
    }, HEARTBEAT_MS)
  }
  connections.add(conn)

  if (lastEventId > 0) {
    const session = turnIndex.get(turnId)
    const ring = session ? rings.get(session) : undefined
    const stale =
      lastEventId >= nextId || !ring || ring.length === 0 || lastEventId + 1 < ring[0].id
    if (stale) {
      writeFrame(conn, {
        id: nextId++,
        kind: 'notice',
        data: JSON.stringify({ type: 'replay_reset', ...(session ? { sessionId: session } : {}) })
      })
    } else {
      for (const f of ring) {
        lockSession(conn, f)
        if (f.id > lastEventId && matches(conn, f)) writeFrame(conn, f)
      }
    }
  }

  res.on('close', () => {
    clearInterval(conn.heartbeat)
    connections.delete(conn)
  })
}

export function stopEventStream(): void {
  for (const u of unsubscribers) u()
  unsubscribers = []
  for (const conn of connections) {
    clearInterval(conn.heartbeat)
    if (!conn.res.writableEnded) conn.res.end()
  }
  connections.clear()
  rings.clear()
  turnIndex.clear()
}
