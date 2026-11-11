package zai.deva.android.core

/**
 * 服务器条目（设计 §4）：一台已添加的 Deva 桌面端。
 * 只存非敏感信息；token 明文由 SecureStore 按 deviceId 保管，不进这里。
 */
data class ServerConfig(
    /** 本地生成的条目 id（与桌面端 deviceId 无关）。 */
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** 配对成功后桌面端返回的设备 id；空串 = 尚未配对。 */
    val deviceId: String = ""
)
