import { getConfig, setConfig } from '../config'

/**
 * 通用设备通道的持久化配置（config.json → generalServer 段，非敏感项）。
 * 设备 token 是密钥，另存 secrets.json，键 `general:<deviceId>:token`（secrets.ts，safeStorage 加密），
 * 不与远程通道的 `remote:<botId>:*` 复用（设计 §3.2）。
 *
 * enabled 默认 true：入站面在未配对时只有 /api/pair（限速 + 一次性码）与 401，配对后才可访问；
 * host 默认 0.0.0.0（局域网可连），写 '127.0.0.1' 即「仅本机」开关。
 */

export interface GeneralDevice {
  id: string
  name: string
  pairedAt: number
}

export interface GeneralServerConfig {
  enabled: boolean
  port: number
  host: string
  devices: GeneralDevice[]
}

export const DEFAULT_ANDROID_PORT = 4317

const DEFAULTS: Omit<GeneralServerConfig, 'devices'> = {
  enabled: true,
  port: DEFAULT_ANDROID_PORT,
  host: '0.0.0.0'
}

function normalize(raw: unknown): GeneralServerConfig {
  const devices: GeneralDevice[] = []
  if (raw && typeof raw === 'object') {
    const r = raw as Record<string, unknown>
    if (Array.isArray(r.devices))
      for (const d of r.devices) {
        const c = d as Record<string, unknown>
        if (c && typeof c.id === 'string' && c.id)
          devices.push({
            id: c.id,
            name: typeof c.name === 'string' ? c.name : '',
            pairedAt: typeof c.pairedAt === 'number' ? c.pairedAt : 0
          })
      }
    return {
      enabled: r.enabled !== false,
      port:
        typeof r.port === 'number' && Number.isInteger(r.port) && r.port > 0 && r.port < 65536
          ? r.port
          : DEFAULTS.port,
      host: typeof r.host === 'string' && r.host ? r.host : DEFAULTS.host,
      devices
    }
  }
  return { ...DEFAULTS, devices }
}

export function getGeneralServerConfig(): GeneralServerConfig {
  return normalize(getConfig().generalServer)
}

function write(cfg: GeneralServerConfig): void {
  setConfig({
    generalServer: { enabled: cfg.enabled, port: cfg.port, host: cfg.host, devices: cfg.devices }
  })
}

export function patchGeneralServer(patch: Partial<Omit<GeneralServerConfig, 'devices'>>): void {
  write({ ...getGeneralServerConfig(), ...patch })
}

/** 配对成功落设备（同 id 覆盖）；吊销走 removeDevice + 删密钥。 */
export function addDevice(d: GeneralDevice): void {
  const cfg = getGeneralServerConfig()
  write({ ...cfg, devices: [...cfg.devices.filter((x) => x.id !== d.id), d] })
}

export function removeDevice(id: string): void {
  const cfg = getGeneralServerConfig()
  write({ ...cfg, devices: cfg.devices.filter((d) => d.id !== id) })
}
