package zai.deva.android

import android.app.Application

/**
 * Application 入口：启动时装载服务器列表（设计 §4）。
 * P2 起：从 SecureStore 恢复已配对服务器（ServerConfig 列表）并自动重连最近一台。
 */
class DevaApp : Application()
