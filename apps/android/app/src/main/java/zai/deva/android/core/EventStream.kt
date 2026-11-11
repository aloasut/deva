package zai.deva.android.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/** 一帧 SSE 事件（event 仅 chat / notice 两种，data 为服务端平铺后的 JSON）。 */
data class SseFrame(val id: Long, val event: String, val data: JSONObject)

/**
 * SSE 事件流客户端（设计 §3.4 / §4）：连 /api/turns/:turnId/events，
 * 支持 Last-Event-ID 续传与指数退避重连（1s→2s→…→30s，抖动 ±20%）。
 * 手解 text/event-stream（服务端 data 恒为单行 JSON，仍按规范支持多行 data 拼接）。
 * 收到任何一帧即重置退避；notice 的 replay_reset 清零 lastEventId 并回调（触发整体回填）。
 * 同一时刻只承载一个回合的流；stop() 后可对新的 turnId 再次 start()。
 */
class EventStream(
    private val baseUrlProvider: () -> String?,
    private val tokenProvider: () -> String?,
    /** 独立 client：SSE 长连接不设读超时。 */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var abortHandle: okhttp3.Call? = null

    /** 最近收到的一帧 id（Last-Event-ID 续传用）；进程内重连持有，replay_reset 时清零。 */
    @Volatile var lastEventId: Long = 0
        private set

    /**
     * 开始（幂等：已在跑则先停旧的）。
     * onFrame 在 SSE 线程回调（转发方自行保证线程安全）；onState: connecting/live/reconnecting/stopped。
     */
    @Synchronized
    fun start(
        turnId: String,
        onFrame: (SseFrame) -> Unit,
        onReplayReset: () -> Unit,
        onState: (String) -> Unit = {}
    ) {
        stop()
        running.set(true)
        onState("connecting")
        worker = thread(name = "deva-sse", isDaemon = true) {
            var attempt = 0
            while (running.get()) {
                val base = baseUrlProvider() ?: break
                val b = Request.Builder().url("$base/api/turns/$turnId/events").get()
                tokenProvider()?.takeIf { it.isNotEmpty() }?.let { b.header("Authorization", "Bearer $it") }
                if (lastEventId > 0) b.header("Last-Event-ID", lastEventId.toString())
                val call = http.newCall(b.build())
                abortHandle = call
                try {
                    call.execute().use { resp ->
                        if (resp.code != 200) {
                            onState("reconnecting")
                        } else {
                            onState("live")
                            val src = resp.body?.source() ?: return@use
                            var evName = ""
                            var evId = 0L
                            val data = StringBuilder()
                            while (running.get()) {
                                val line = src.readUtf8Line() ?: break
                                when {
                                    line.isEmpty() -> {
                                        // 帧边界：注释/心跳（: ping）也会走到这，data 为空时直接丢弃
                                        if (data.isNotEmpty()) {
                                            dispatch(evName, evId, data.toString(), onFrame, onReplayReset)
                                            attempt = 0 // 有真帧 = 连接活过一轮，退避归零
                                        }
                                        evName = ""
                                        evId = 0L
                                        data.setLength(0)
                                    }
                                    line.startsWith(":") -> Unit // 注释 / 心跳
                                    line.startsWith("id:") -> evId = line.substring(3).trim().toLongOrNull() ?: evId
                                    line.startsWith("event:") -> evName = line.substring(6).trim()
                                    line.startsWith("data:") -> {
                                        if (data.isNotEmpty()) data.append('\n')
                                        data.append(line.substring(5).removePrefix(" "))
                                    }
                                    line.startsWith("retry:") -> Unit // 服务端建议的 retry，重连节奏走自己的退避
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    // cancel() / 断线都走这：走退避重连
                } finally {
                    abortHandle = null
                }
                if (!running.get()) break
                onState("reconnecting")
                try {
                    Thread.sleep(backoffMs(attempt))
                } catch (_: InterruptedException) {
                    break
                }
                attempt++
            }
            onState("stopped")
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        abortHandle?.cancel()
        worker?.interrupt()
        worker = null
    }

    private fun dispatch(
        evName: String,
        evId: Long,
        data: String,
        onFrame: (SseFrame) -> Unit,
        onReplayReset: () -> Unit
    ) {
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            return
        }
        if (evName == "notice") {
            if (json.optString("type") == "replay_reset") {
                lastEventId = 0
                onReplayReset()
            }
            return
        }
        if (evId > 0) lastEventId = evId
        onFrame(SseFrame(id = evId, event = evName, data = json))
    }

    /** 1s→2s→…→30s 封顶，±20% 抖动（设计 §4）。 */
    private fun backoffMs(attempt: Int): Long {
        val base = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        val jitter = (base * 0.2).toLong()
        return base + Random.nextLong(-jitter, jitter + 1)
    }
}
