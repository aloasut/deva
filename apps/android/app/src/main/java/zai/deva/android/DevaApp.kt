package zai.deva.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import zai.deva.android.core.DevaClient
import zai.deva.android.core.EncryptedSecureStore
import zai.deva.android.core.EventStream
import zai.deva.android.core.SecureStore
import zai.deva.android.core.ServerConfig
import zai.deva.android.core.ServerStore
import zai.deva.android.core.SessionStore

/**
 * Application 入口：启动时装载服务器列表（设计 §4），初始化进程级服务定位器 AppGraph。
 */
class DevaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/**
 * 进程级服务定位器：SecureStore（token）/ ServerStore（服务器列表）/ SessionStore（会话与时间线）/
 * EventStream（SSE）/ 当前服务器与 client 工厂。单 Activity + Compose，Composable 直接取用。
 */
object AppGraph {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** REST 复用一个 client（连接池共享）。 */
    private val restHttp = OkHttpClient()

    lateinit var secure: SecureStore
        private set
    lateinit var servers: ServerStore
        private set
    lateinit var sessions: SessionStore
        private set

    /** 当前选中服务器（null = 还没添加）。 */
    val current: StateFlow<ServerConfig?> by lazy {
        combine(servers.servers, servers.currentId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    fun init(app: Application) {
        secure = EncryptedSecureStore(app)
        servers = ServerStore(app).also { it.load() }
        sessions = SessionStore(sse)
    }

    private fun baseUrl(): String? = current.value?.let { "http://${it.host}:${it.port}" }

    private fun token(): String? =
        current.value?.deviceId?.takeIf { it.isNotEmpty() }?.let { secure.loadToken(it) }

    /** SSE 事件流（baseUrl/token 跟随当前服务器；换服务器时由 UI 侧 pause 后重连）。 */
    private val sse: EventStream by lazy {
        EventStream(baseUrlProvider = ::baseUrl, tokenProvider = ::token)
    }

    /** 绑定当前服务器的 REST 客户端；未添加服务器时为 null。 */
    fun client(): DevaClient? = current.value?.let { srv ->
        DevaClient(
            baseUrl = "http://${srv.host}:${srv.port}",
            tokenProvider = { srv.deviceId.takeIf { it.isNotEmpty() }?.let { secure.loadToken(it) } },
            http = restHttp
        )
    }
}
