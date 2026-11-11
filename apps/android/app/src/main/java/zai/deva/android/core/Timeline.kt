package zai.deva.android.core

/**
 * 事件折叠成时间线（设计 §4）：ChatStreamEvent 流 → 正文/思考/工具/卡片/交付物五类节点。
 * 事件类型全集见设计 §3.4（与桌面端 ChatStreamEvent 同形）；未知类型忽略并计数，不抛异常。
 *
 * P2 起实现：text_delta 拼接正文、tool_call/tool_result 折叠成一行状态、交互卡与交付物成卡。
 */
sealed interface TimelineItem {
    data class Text(val text: String) : TimelineItem
    data class Thinking(val text: String) : TimelineItem
    data class Tool(val name: String, val summary: String) : TimelineItem
    data class Card(val kind: String, val key: String) : TimelineItem
    data class Deliverable(val name: String, val size: Long) : TimelineItem
}
