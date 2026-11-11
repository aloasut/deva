import { ipcMain } from 'electron'
import QRCode from 'qrcode'
import type { ChannelState } from '../remote/types'
import { deleteSecretsByPrefix } from '../secrets'
import { invalidateTokenCache } from './auth'
import { getAndroidServerConfig, removeDevice } from './config'
import { cancelPairCode, issuePairCode, lanHost } from './pairing'
import { startServer, stopServer } from './server'
import { startEventStream, stopEventStream } from './sse'

/**
 * 安卓客户端的入站通道（设计 §3）：把已存在的 chatRuntime / chat-bus 暴露给已配对设备。
 * 生命周期与 IM 通道同形——状态用 ChannelState 同形（off / connecting / connected / error）；
 * 停在出站通道之后（主进程退出时序：stopAllChannels → stopChannelServer）。
 * 桌面设置页（P2）经这里的 IPC 显示配对码 / 设备列表；密钥只写不读。
 */

export interface AndroidDeviceView {
  id: string
  name: string
  pairedAt: number
}

export interface AndroidServerView {
  enabled: boolean
  host: string
  port: number
  state: ChannelState
  error?: string
  devices: AndroidDeviceView[]
}

let state: ChannelState = 'off'
let stateError: string | undefined
let running = false
const listeners = new Set<() => void>()

function setState(s: ChannelState, error?: string): void {
  state = s
  stateError = error
  for (const l of listeners) l()
}

export function getChannelServerState(): AndroidServerView {
  const cfg = getAndroidServerConfig()
  return {
    enabled: cfg.enabled,
    host: cfg.host,
    port: cfg.port,
    state,
    ...(stateError ? { error: stateError } : {}),
    devices: cfg.devices.map((d) => ({ id: d.id, name: d.name, pairedAt: d.pairedAt }))
  }
}

export function onChannelServerChange(l: () => void): () => void {
  listeners.add(l)
  return () => listeners.delete(l)
}

/** 幂等启动；androidServer.enabled=false 时保持 off（不占端口）。 */
export async function startChannelServer(): Promise<void> {
  if (running) return
  const cfg = getAndroidServerConfig()
  if (!cfg.enabled) {
    setState('off')
    return
  }
  running = true
  startEventStream()
  setState('connecting')
  try {
    await startServer({ host: cfg.host, port: cfg.port }, {
      onState: (s, error) => {
        if (s === 'error') running = false
        setState(s, error)
      }
    })
  } catch (e) {
    running = false
    setState('error', (e as Error)?.message ?? String(e))
  }
}

export async function stopChannelServer(): Promise<void> {
  running = false
  stopEventStream()
  await stopServer()
  setState('off')
}

export function registerChannelServerIpc(): void {
  ipcMain.handle('android:get-state', (): AndroidServerView => getChannelServerState())

  // 生成一次性配对码 + 二维码（内容 deva://pair?host=<host>&port=<port>&code=<code>，设计 §3.2）
  ipcMain.handle(
    'android:pair-code',
    async (): Promise<
      | { ok: true; code: string; expiresAt: number; qr: string; host: string; port: number }
      | { ok: false; error: string }
    > => {
      const cfg = getAndroidServerConfig()
      if (!cfg.enabled) return { ok: false, error: '安卓通道未启用。' }
      const { code, expiresAt } = issuePairCode()
      const host = lanHost()
      const qr = await QRCode.toDataURL(
        `deva://pair?host=${encodeURIComponent(host)}&port=${cfg.port}&code=${code}`,
        { margin: 1, width: 360 }
      )
      return { ok: true, code, expiresAt, qr, host, port: cfg.port }
    }
  )

  ipcMain.handle('android:pair-cancel', (): { ok: true } => {
    cancelPairCode()
    return { ok: true }
  })

  // 吊销设备：删配置 + 删密钥（android:<id>:token），该设备的请求从此 401
  ipcMain.handle('android:remove-device', async (_e, id: string): Promise<{ ok: true }> => {
    removeDevice(id)
    await deleteSecretsByPrefix(`android:${id}:`)
    invalidateTokenCache()
    return { ok: true }
  })

  // 随主进程启动（enabled=false 或端口冲突都只置状态、记日志，不阻塞启动）
  void startChannelServer().catch((e) => console.warn('[channel-server] 启动失败：', (e as Error)?.message ?? e))
}
