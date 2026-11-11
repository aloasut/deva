package zai.deva.android.core

import org.json.JSONObject

/**
 * 事件折叠成时间线（设计 §4）：ChatStreamEvent 流 → 正文/思考/工具/卡片/交付物五类节点。
 * 事件类型全集见设计 §3.4（与桌面端 ChatStreamEvent 同形）；未知类型忽略并计数，不抛异常。
 * P2 覆盖：text_delta / thinking_delta / tool_call / tool_result / turn_start / error / done；
 * 卡片与交付物节点为 P3/P4 预留形状。
 */
sealed interface TimelineItem {
    data class Text(val text: String, val fromUser: Boolean) : TimelineItem
    data class Thinking(val text: String) : TimelineItem
    /** summary = null 表示工具还在跑（「正在…」）；tool_result 到达后定格。 */
    data class Tool(val id: String, val name: String, val summary: String?) : TimelineItem
    data class Card(val kind: String, val key: String) : TimelineItem
    data class Deliverable(val name: String, val size: Long) : TimelineItem
    data class Error(val message: String) : TimelineItem
}

/**
 * 折叠器：/messages 回填种子 + SSE chat 帧增量 → 展示列表。
 * SSE 帧在 EventStream 线程到达、UI 在主线程读快照，方法全部同步化。
 */
class TimelineBuilder {

    private val items = mutableListOf<TimelineItem>()
    var unknownEvents = 0
        private set

    val snapshot: List<TimelineItem>
        @Synchronized get() = items.toList()

    /** 历史回填种子（进入会话时调一次；重连 replay_reset 后 force 重种前先 clear）。 */
    @Synchronized
    fun seedBackfill(msgs: List<BackfillMsg>) {
        items.clear()
        for (m in msgs) {
            if (m.role == "user") {
                m.text?.let { items.add(TimelineItem.Text(it, fromUser = true)) }
            } else {
                m.text?.let { items.add(TimelineItem.Text(it, fromUser = false)) }
                for (t in m.tools) items.add(TimelineItem.Tool(id = "hist:$t", name = t, summary = "已完成"))
                if (m.attachments > 0) {
                    items.add(TimelineItem.Text("（含 ${m.attachments} 个附件）", fromUser = m.role == "user"))
                }
            }
        }
    }

    /** 本机发出的消息：send 成功后乐观上屏（同回合的 turn_start 据此去重）。 */
    @Synchronized
    fun addUser(text: String) {
        items.add(TimelineItem.Text(text, fromUser = true))
    }

    /**
     * 处理一帧 chat 事件（data 已平铺 sessionId/turnId，形状同服务端 sse.ts）。
     * isOwnTurn = 本帧 turnId 是本机发起的那一轮：turn_start 不再重复画用户气泡。
     */
    @Synchronized
    fun onChatEvent(data: JSONObject, isOwnTurn: Boolean) {
        when (data.optString("type")) {
            "text_delta" -> appendToLastAssistant(data.optString("text"))
            "thinking_delta" -> appendThinking(data.optString("text"))
            "tool_call" -> items.add(
                TimelineItem.Tool(
                    id = data.optString("id"),
                    name = data.optString("name"),
                    summary = null
                )
            )
            "tool_result" -> finishTool(
                data.optString("id"),
                (if (data.optBoolean("isError")) "✗ " else "") + data.optString("summary")
            )
            "turn_start" -> {
                if (isOwnTurn) return // 自己发起的：发送时已乐观上屏
                val user = data.optJSONObject("user") ?: return
                val text = user.optString("text")
                if (text.isNotEmpty()) items.add(TimelineItem.Text(text, fromUser = true))
            }
            "error" -> items.add(TimelineItem.Error(data.optString("message")))
            // done / usage / todos / compacted 等：P2 不展示（done 由 SessionStore 消费以收尾状态）
            else -> unknownEvents++
        }
    }

    private fun appendToLastAssistant(text: String) {
        if (text.isEmpty()) return
        val last = items.lastOrNull()
        if (last is TimelineItem.Text && !last.fromUser) {
            items[items.size - 1] = last.copy(text = last.text + text)
        } else {
            items.add(TimelineItem.Text(text, fromUser = false))
        }
    }

    private fun appendThinking(text: String) {
        if (text.isEmpty()) return
        val last = items.lastOrNull()
        if (last is TimelineItem.Thinking) {
            items[items.size - 1] = last.copy(text = last.text + text)
        } else {
            items.add(TimelineItem.Thinking(text))
        }
    }

    private fun finishTool(id: String, summary: String) {
        val idx = items.indexOfLast { it is TimelineItem.Tool && it.id == id }
        if (idx >= 0) {
            val t = items[idx] as TimelineItem.Tool
            items[idx] = t.copy(summary = summary)
        }
    }
}
