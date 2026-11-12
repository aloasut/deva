package zai.deva.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 统一返回：Err.message 已翻成人话文案，UI 可直接展示。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val message: String) : ApiResult<Nothing>
}

data class SessionMeta(
    val id: String,
    val title: String,
    val personaId: String?,
    val updatedAt: Long,
    val busy: Boolean,
    val archived: Boolean
)

/** 回填条目里的未决交互卡（服务端只回填 ask / plan 两种；mount 不入历史）。 */
data class BackfillCard(val kind: String, val key: String, val questions: List<AskQuestion>, val plan: String)

/** /api/sessions/:id/messages 的回填条目（服务端 projectMessages 的投影，见 channel-server/server.ts）。 */
data class BackfillMsg(
    val i: Int,
    val role: String,
    val text: String?,
    val tools: List<String>,
    val attachments: Int,
    val card: BackfillCard? = null
)

data class Backfill(val total: Int, val start: Int, val messages: List<BackfillMsg>)

data class PairSuccess(val deviceId: String, val token: String)

data class ServerInfo(val version: String, val hostname: String, val platform: String, val firstRun: Boolean)

/**
 * HTTP 客户端（设计 §3.3 的 REST 面）：配对、info、sessions、messages、send、abort。
 * 传输用 OkHttp + 原生 org.json（设计 §4，不引序列化框架）。
 * 除 pair 外全部带 Authorization: Bearer <token>；错误统一翻成人话文案。
 */
class DevaClient(
    private val baseUrl: String,
    /** token 取自 SecureStore；未配对时为 null，只有 pair 可用。 */
    private val tokenProvider: () -> String?,
    /** 可注入共享 client（AppGraph 复用连接池）；缺省自建。 */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    suspend fun pair(code: String, deviceName: String): ApiResult<PairSuccess> {
        val body = JSONObject().put("code", code.trim()).put("deviceName", deviceName.trim())
        return when (val r = request("POST", "/api/pair", body, auth = false)) {
            is ApiResult.Ok -> {
                val o = r.value ?: return ApiResult.Err("配对响应为空。")
                ApiResult.Ok(PairSuccess(deviceId = o.getString("deviceId"), token = o.getString("token")))
            }
            is ApiResult.Err -> r
        }
    }

    suspend fun info(): ApiResult<ServerInfo> =
        when (val r = request("GET", "/api/info")) {
            is ApiResult.Ok -> {
                val o = r.value ?: return ApiResult.Err("响应为空。")
                ApiResult.Ok(
                    ServerInfo(
                        version = o.optString("version"),
                        hostname = o.optString("hostname"),
                        platform = o.optString("platform"),
                        firstRun = o.optBoolean("firstRun")
                    )
                )
            }
            is ApiResult.Err -> r
        }

    suspend fun sessions(): ApiResult<List<SessionMeta>> =
        when (val r = request("GET", "/api/sessions")) {
            is ApiResult.Ok -> {
                val o = r.value ?: return ApiResult.Err("响应为空。")
                val arr = o.optJSONArray("sessions") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    SessionMeta(
                        id = s.getString("id"),
                        title = s.optString("title"),
                        personaId = s.optString("personaId").takeIf { it.isNotEmpty() },
                        updatedAt = s.optLong("updatedAt"),
                        busy = s.optBoolean("busy"),
                        archived = s.optBoolean("archived")
                    )
                }
                ApiResult.Ok(list)
            }
            is ApiResult.Err -> r
        }

    /** 新建空会话，返回会话 id。 */
    suspend fun createSession(): ApiResult<String> =
        when (val r = request("POST", "/api/sessions", JSONObject())) {
            is ApiResult.Ok -> {
                val o = r.value ?: return ApiResult.Err("响应为空。")
                ApiResult.Ok(o.getString("id"))
            }
            is ApiResult.Err -> r
        }

    /** 历史回填（进入会话时调；start 缺省 = 服务端给最新一段）。 */
    suspend fun messages(sessionId: String, start: Int? = null, limit: Int = 400): ApiResult<Backfill> {
        val q = StringBuilder("/api/sessions/").append(enc(sessionId)).append("/messages?limit=").append(limit)
        if (start != null) q.append("&start=").append(start)
        return when (val r = request("GET", q.toString())) {
            is ApiResult.Ok -> {
                val o = r.value ?: return ApiResult.Err("响应为空。")
                val arr = o.optJSONArray("messages") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    val tools = mutableListOf<String>()
                    val tarr = m.optJSONArray("tools")
                    if (tarr != null) for (j in 0 until tarr.length()) tools.add(tarr.optString(j))
                    val card = m.optJSONObject("card")?.let { c ->
                        BackfillCard(
                            kind = c.optString("kind"),
                            key = c.optString("key"),
                            questions = parseQuestions(c.optJSONArray("questions")),
                            plan = c.optString("plan")
                        )
                    }
                    BackfillMsg(
                        i = m.optInt("i"),
                        role = m.optString("role"),
                        text = m.optString("text").takeIf { it.isNotEmpty() },
                        tools = tools,
                        attachments = m.optInt("attachments", 0),
                        card = card
                    )
                }
                ApiResult.Ok(Backfill(total = o.optInt("total"), start = o.optInt("start"), messages = list))
            }
            is ApiResult.Err -> r
        }
    }

    /** 发消息，返回本轮 turnId（服务端已解析默认模型，见 channel-server 的 send 路由）。 */
    suspend fun send(sessionId: String, text: String): ApiResult<String> {
        val body = JSONObject().put("text", text)
        return when (val r = request("POST", "/api/sessions/${enc(sessionId)}/send", body)) {
            is ApiResult.Ok -> {
                val o = r.value ?: return ApiResult.Err("响应为空。")
                ApiResult.Ok(o.getString("turnId"))
            }
            is ApiResult.Err -> r
        }
    }

    suspend fun abort(turnId: String): ApiResult<Unit> =
        when (val r = request("POST", "/api/turns/${enc(turnId)}/abort", JSONObject())) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }

    /**
     * 交互卡作答（设计 §3.3）：kind = ask / plan / mount，payload 形状随 kind
     * （ask: {answers: string[]|null}；plan: {decision, feedback?}；mount: {path: string|null}）。
     * 服务端 409（卡已结束/不存在）走 Err 返回。
     */
    suspend fun answer(sessionId: String, key: String, kind: String, payload: JSONObject): ApiResult<Unit> {
        val body = JSONObject().put("key", key).put("kind", kind).put("payload", payload)
        return when (val r = request("POST", "/api/sessions/${enc(sessionId)}/answer", body)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> r
        }
    }

    // ── 内部 ──

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true
    ): ApiResult<JSONObject?> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(baseUrl + path)
        when (method) {
            "GET" -> b.get()
            else -> b.method(
                method,
                (body?.toString() ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        }
        if (auth) tokenProvider()?.takeIf { it.isNotEmpty() }?.let { b.header("Authorization", "Bearer $it") }
        try {
            http.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code == 401) return@withContext ApiResult.Err("未配对或凭据无效，请重新配对。")
                val json: JSONObject? = if (text.isNotEmpty()) {
                    try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                if (!resp.isSuccessful) {
                    ApiResult.Err(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "请求失败（HTTP ${resp.code}）。")
                } else {
                    ApiResult.Ok(json)
                }
            }
        } catch (e: IOException) {
            ApiResult.Err("连不上服务器：${e.message ?: "网络错误"}")
        }
    }
}
