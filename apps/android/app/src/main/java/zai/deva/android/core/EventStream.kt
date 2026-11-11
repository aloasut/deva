package zai.deva.android.core

/**
 * SSE 事件流客户端（设计 §3.4 / §4）：连 /api/turns/:turnId/events，
 * 支持 Last-Event-ID 续传与指数退避重连（1s→2s→…→30s，抖动 ±20%）。
 * 事件形状与桌面端 ChatStreamEvent 一一对齐（见 ui/Timeline），未知类型忽略并计数。
 *
 * P2 起实现：OKHttp 事件源或手解 text/event-stream；replay_reset 触发整体回填。
 */
class EventStream
