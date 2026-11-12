/**
 * 设备活动追踪（设计 §3.5「在线设备数与最近活动时间」）。
 * lastSeen = 最近一次带鉴权的请求时间；online = 有存活的 SSE 连接。
 * 进程内态，不落盘——重启后归零，设备重连即刷新。
 */
const lastSeen = new Map<string, number>()
const online = new Set<string>()

export function noteDeviceSeen(deviceId: string): void {
  if (!deviceId) return
  lastSeen.set(deviceId, Date.now())
}

export function noteDeviceStream(deviceId: string, open: boolean): void {
  if (!deviceId) return
  if (open) {
    online.add(deviceId)
    noteDeviceSeen(deviceId)
  } else {
    online.delete(deviceId)
  }
}

export function deviceActivity(deviceId: string): { online: boolean; lastSeenAt: number } {
  return { online: online.has(deviceId), lastSeenAt: lastSeen.get(deviceId) ?? 0 }
}
