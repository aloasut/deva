package zai.deva.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import zai.deva.android.AppGraph
import zai.deva.android.core.ApiResult
import zai.deva.android.core.ServerConfig
import zai.deva.android.core.SessionMeta

/**
 * 服务器 + 会话列表（设计 §5-1/2）：顶部当前服务器与连接状态，列表按更新时间倒序
 * （服务端已排），每项显示标题、角色徽标、最后消息摘要、忙/闲、相对时间；FAB 新建会话。
 * 长按会话项弹出归档 / 取消归档（服务端忙时归档回 409 提示）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onOpenChat: (String) -> Unit, onOpenSettings: () -> Unit) {
    // onOpenSettings 既用于空态引导，也用于错误横幅的「去设置」（P5 错误态）
    val server by AppGraph.current.collectAsState()
    val sessions by AppGraph.sessions.sessions.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    // 长按弹出的会话操作目标（null = 无）
    var actionTarget by remember { mutableStateOf<SessionMeta?>(null) }

    // 进入 / 回到首页时刷新列表（失败给横幅：可重试；401 引导去设置重新配对）
    LaunchedEffect(Unit) {
        val client = AppGraph.client() ?: return@LaunchedEffect
        when (val r = AppGraph.sessions.refresh(client)) {
            is ApiResult.Err -> refreshError = r.message
            is ApiResult.Ok -> refreshError = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server?.name ?: "未连接服务器", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = serverStatusText(server),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (server != null) {
                // 显式钉主色：此 material3 版本的 ExtendedFAB 默认容器色不随 colorScheme.primary 走
                ExtendedFloatingActionButton(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = {
                        val client = AppGraph.client() ?: return@ExtendedFloatingActionButton
                        if (creating) return@ExtendedFloatingActionButton
                        creating = true
                        scope.launch {
                            when (val r = client.createSession()) {
                                is ApiResult.Ok -> {
                                    AppGraph.sessions.refresh(client)
                                    onOpenChat(r.value)
                                }
                                is ApiResult.Err -> snackbar.showSnackbar(r.message)
                            }
                            creating = false
                        }
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("新建会话")
                }
            }
        }
    ) { pad ->
        val srv = server
        if (srv == null) {
            EmptyState(pad, onOpenSettings)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 错误横幅（P5 错误态）：连不上 / 401 都在此提示，不用 snackbar 一闪而过
                refreshError?.let { msg ->
                    item(key = "error") {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (msg.contains("重新配对")) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (msg.contains("重新配对")) {
                                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val client = AppGraph.client() ?: return@launch
                                        when (val r = AppGraph.sessions.refresh(client)) {
                                            is ApiResult.Err -> refreshError = r.message
                                            is ApiResult.Ok -> refreshError = null
                                        }
                                    }
                                }) { Text("重试") }
                            }
                        }
                    }
                }
                items(sessions, key = { it.id }) { s ->
                    SessionRow(
                        s,
                        onClick = { onOpenChat(s.id) },
                        onLongClick = { actionTarget = s }
                    )
                }
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            "还没有会话。点右下角「新建会话」开始。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
            }
        }
    }

    // 长按会话项 → 归档 / 取消归档（409 = 会话忙，桌面同口径）
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.title.ifBlank { "新对话" }) },
            text = { Text(if (target.archived) "取消归档后该会话恢复出现在列表顶部。" else "归档后该会话不再出现在列表里，历史保留可随时取消。") },
            confirmButton = {
                TextButton(onClick = {
                    val client = AppGraph.client()
                    if (client != null) {
                        scope.launch {
                            when (val r = client.setArchived(target.id, !target.archived)) {
                                is ApiResult.Err -> snackbar.showSnackbar(r.message)
                                is ApiResult.Ok -> Unit
                            }
                            AppGraph.sessions.refresh(client)
                        }
                    }
                    actionTarget = null
                }) { Text(if (target.archived) "取消归档" else "归档") }
            },
            dismissButton = { TextButton(onClick = { actionTarget = null }) { Text("关闭") } }
        )
    }
}

private fun serverStatusText(server: ServerConfig?): String =
    when {
        server == null -> "先去设置里添加并配对一台 Deva"
        server.deviceId.isEmpty() -> "${server.host}:${server.port} · 未配对"
        else -> "${server.host}:${server.port} · 已配对"
    }

@Composable
private fun EmptyState(pad: PaddingValues, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有添加服务器", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "在电脑端 Deva「设置 → 远程设备」里开启安卓通道并生成配对码，" +
                    "然后到这里添加服务器（host:port）并输入配对码。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            ElevatedCard(onClick = onOpenSettings) {
                Text(
                    "去添加服务器",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionMeta, onClick: () -> Unit, onLongClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.title.ifBlank { "新对话" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                s.lastMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    s.personaName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    if (s.archived) {
                        Text(
                            "已归档",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                            s.updatedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (s.busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}
