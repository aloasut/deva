package zai.deva.android.core

/**
 * HTTP 客户端（设计 §3.3 的 REST 面）：配对、sessions、send、answer、abort、files。
 * 传输用 OkHttp + 原生 org.json（设计 §4，不引序列化框架）。
 *
 * P2 起实现：pair() / info() / sessions() / send() / answer() / abort() / files()，
 * 全部带 Authorization: Bearer <token>；错误统一翻成人话文案。
 */
class DevaClient(
    private val host: String,
    private val port: Int,
    /** token 取自 SecureStore；未配对时为 null，只有 pair 可用。 */
    private val tokenProvider: () -> String?
)
