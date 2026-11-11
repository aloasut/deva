package zai.deva.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 会话列表与消息缓存（设计 §4）：v1 只用内存，不进 Room。
 * 列表按 updatedAt 倒序（服务端已排）；每会话一份 TimelineBuilder + StateFlow；
 * 离开会话只停流不丢状态，重进用 Last-Event-ID 续传，replay_reset 触发整体回填。
 */
class SessionStore(private val stream: EventStream) {

    private data class SessionState(
        val builder: TimelineBuilder,
        val flow: MutableStateFlow<List<TimelineItem>>,
        /** 正在流式的回合（本机发起，进入会话时据此恢复订阅）；null = 空闲。 */
        val active: MutableStateFlow<String?> = MutableStateFlow(null)
    )

    private val states = HashMap<String, SessionState>()

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions

    private val _streamState = MutableStateFlow("")
    /** 当前订阅的连接状态（connecting/live/reconnecting/stopped），仅活动会话有意义。 */
    val streamState: StateFlow<String> = _streamState

    fun timeline(sessionId: String): StateFlow<List<TimelineItem>> = state(sessionId).flow

    /** 活动回合流（UI 据此切换 停止/发送 按钮）；done 帧后归 null。 */
    fun activeTurnFlow(sessionId: String): StateFlow<String?> = state(sessionId).active

    private fun state(sessionId: String): SessionState = synchronized(states) {
        states.getOrPut(sessionId) {
            SessionState(builder = TimelineBuilder(), flow = MutableStateFlow(emptyList()))
        }
    }

    /** 刷新会话列表（进入首页 / 回到前台时调）。 */
    suspend fun refresh(client: DevaClient): ApiResult<Unit> =
        when (val r = client.sessions()) {
            is ApiResult.Ok -> {
                _sessions.value = r.value
                ApiResult.Ok(Unit)
            }
            is ApiResult.Err -> r
        }

    /**
     * 进入会话：回填历史（已有内容则不重复拉；增量靠 SSE 续传）。
     * force = replay_reset 后的整体回填：清掉流式增量重种。
     */
    suspend fun open(client: DevaClient, sessionId: String, force: Boolean = false): ApiResult<Unit> {
        val st = state(sessionId)
        if (!force && st.flow.value.isNotEmpty()) return ApiResult.Ok(Unit)
        return when (val r = client.messages(sessionId)) {
            is ApiResult.Ok -> {
                st.builder.seedBackfill(r.value.messages)
                st.flow.value = st.builder.snapshot
                ApiResult.Ok(Unit)
            }
            is ApiResult.Err -> r
        }
    }

    /**
     * 本机发送成功后调：乐观上屏用户气泡、标记本回合、开始订阅 SSE。
     * onReplayReset 在 SSE 线程回调（调用方自行切协程做整体回填）。
     */
    fun sendStarted(sessionId: String, turnId: String, text: String, onReplayReset: () -> Unit) {
        val st = state(sessionId)
        st.builder.addUser(text)
        st.flow.value = st.builder.snapshot
        st.active.value = turnId
        watch(sessionId, turnId, onReplayReset)
    }

    /**
     * 订阅一个回合的 SSE（重进会话恢复活动回合时也走这）。
     * 帧在 SSE 线程到达：builder 同步化，flow 赋值线程安全；done 后收尾并停流。
     */
    fun watch(sessionId: String, turnId: String, onReplayReset: () -> Unit) {
        val st = state(sessionId)
        st.active.value = turnId
        stream.start(
            turnId,
            onFrame = { f ->
                val own = f.data.optString("turnId") == turnId
                st.builder.onChatEvent(f.data, isOwnTurn = own)
                st.flow.value = st.builder.snapshot
                if (f.data.optString("type") == "done" && own) {
                    st.active.value = null
                    stream.stop()
                }
            },
            onReplayReset = {
                // 缓冲已断：清空后整体回填（Last-Event-ID 已清零，之后只跟直播）
                st.builder.seedBackfill(emptyList())
                st.flow.value = st.builder.snapshot
                onReplayReset()
            },
            onState = { s -> _streamState.value = s }
        )
    }

    /** 停止当前订阅（离开会话时调；builder 与 lastEventId 保留，重进可续传）。 */
    fun pause() {
        stream.stop()
    }

    /** 活动回合（本会话正在流式的 turnId；null = 空闲可发/无需停止）。 */
    fun currentTurn(sessionId: String): String? = state(sessionId).active.value
}
