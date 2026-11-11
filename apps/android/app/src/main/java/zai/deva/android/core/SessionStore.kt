package zai.deva.android.core

/**
 * 会话列表与消息缓存（设计 §4）：v1 只用内存，不进 Room。
 * P2 起实现：列表按 updatedAt 倒序、按 sessionId 缓存时间线、断线重连后整体回填。
 */
class SessionStore
