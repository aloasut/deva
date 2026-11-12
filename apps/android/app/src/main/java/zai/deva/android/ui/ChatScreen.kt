package zai.deva.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import zai.deva.android.AppGraph
import zai.deva.android.core.ApiResult
import zai.deva.android.core.TimelineItem

/**
 * 对话流 + 输入框 + 停止（设计 §5-3）：气泡流（用户右、助手左），工具调用折叠成一行，
 * 底部固定输入框；停止按钮只在有活动回合时出现。断线由 EventStream 自动退避重连，
 * replay_reset 触发整体回填（SessionStore.open(force=true)）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(sessionId: String, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val items by AppGraph.sessions.timeline(sessionId).collectAsState()
    val streamState by AppGraph.sessions.streamState.collectAsState()
    val activeTurn by AppGraph.sessions.activeTurnFlow(sessionId).collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val client = AppGraph.client()

    // 卡片作答回调：结果由 interaction_resolved 帧收起（桌面/手机同机制，先到的答复生效）
    val onAnswer = { kind: String, key: String, payload: JSONObject ->
        val c = AppGraph.client()
        if (c != null) {
            scope.launch {
                when (val r = c.answer(sessionId, key, kind, payload)) {
                    is ApiResult.Err -> snackbar.showSnackbar(r.message)
                    is ApiResult.Ok -> Unit
                }
            }
        }
        Unit
    }

    // 进入会话：回填历史（失败给整屏错误态，可重试 / 401 引导去设置）；有活动回合则恢复订阅
    LaunchedEffect(sessionId) {
        if (client == null) return@LaunchedEffect
        when (val r = AppGraph.sessions.open(client, sessionId)) {
            is ApiResult.Err -> loadError = r.message
            is ApiResult.Ok -> loadError = null
        }
        if (loadError != null) return@LaunchedEffect
        AppGraph.sessions.currentTurn(sessionId)?.let { turn ->
            AppGraph.sessions.watch(sessionId, turn, onReplayReset = {
                AppGraph.scope.launch {
                    AppGraph.sessions.open(AppGraph.client() ?: return@launch, sessionId, force = true)
                }
            })
        }
    }

    // 新内容到达时滚到底
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    // 离开会话：停流（builder 与 lastEventId 保留，回来可 Last-Event-ID 续传）
    androidx.compose.runtime.DisposableEffect(sessionId) {
        onDispose { AppGraph.sessions.pause() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val title = AppGraph.sessions.sessions.value
                            .firstOrNull { it.id == sessionId }?.title
                        Text(title?.ifBlank { "对话" } ?: "对话", style = MaterialTheme.typography.titleMedium)
                        when {
                            streamState == "unauthorized" -> Text(
                                "凭据无效，点此去设置重新配对",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.combinedClickable(onClick = onOpenSettings)
                            )
                            activeTurn != null && streamState == "reconnecting" -> Text(
                                "连接中断，正在重连…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (items.isEmpty() && loadError != null) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(loadError ?: "", style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loadError?.contains("重新配对") == true) {
                            TextButton(onClick = onOpenSettings) { Text("去设置") }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val c = AppGraph.client() ?: return@launch
                                when (val r = AppGraph.sessions.open(c, sessionId, force = true)) {
                                    is ApiResult.Err -> loadError = r.message
                                    is ApiResult.Ok -> loadError = null
                                }
                            }
                        }) { Text("重试") }
                    }
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items) { item -> TimelineRow(item, onAnswer, sessionId, snackbar) }
            }
            }

            // Waiting 态（有未决卡）：输入框禁用并提示，防止用户打字却被静默丢弃（设计 §4 状态机）
            val hasPendingCard = items.any { it is TimelineItem.Card && it.resolved == null }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (hasPendingCard) "等待你在下方卡片中作答…" else "发消息…") },
                    enabled = !hasPendingCard,
                    maxLines = 5
                )
                if (activeTurn != null) {
                    IconButton(
                        onClick = {
                            val turn = activeTurn ?: return@IconButton
                            val c = AppGraph.client() ?: return@IconButton
                            scope.launch {
                                when (val r = c.abort(turn)) {
                                    is ApiResult.Err -> snackbar.showSnackbar(r.message)
                                    is ApiResult.Ok -> Unit // done 帧会收尾 activeTurn
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "停止")
                    }
                } else {
                    IconButton(
                        enabled = !hasPendingCard,
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) return@IconButton
                            val c = AppGraph.client() ?: return@IconButton
                            input = ""
                            scope.launch {
                                when (val r = c.send(sessionId, text)) {
                                    is ApiResult.Ok -> {
                                        AppGraph.sessions.sendStarted(sessionId, r.value, text) {
                                            AppGraph.scope.launch {
                                                AppGraph.sessions.open(
                                                    AppGraph.client() ?: return@launch,
                                                    sessionId,
                                                    force = true
                                                )
                                            }
                                        }
                                    }
                                    is ApiResult.Err -> {
                                        input = text // 发不出去，放回输入框
                                        snackbar.showSnackbar(r.message)
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(
    item: TimelineItem,
    onAnswer: (String, String, JSONObject) -> Unit,
    sessionId: String,
    snackbar: SnackbarHostState
) {
    val copyScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // 长按复制（设计 §5）：正文 / 思考 / 工具行都可用，复制后轻提示
    fun copyOnLong(base: Modifier, text: String): Modifier = base.combinedClickable(
        onClick = {},
        onLongClick = {
            clipboard.setText(AnnotatedString(text))
            copyScope.launch { snackbar.showSnackbar("已复制") }
        }
    )
    when (item) {
        is TimelineItem.Text -> Bubble(item.text, item.fromUser, copyOnLong(Modifier, item.text))
        is TimelineItem.Thinking -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = copyOnLong(Modifier.fillMaxWidth().padding(horizontal = 4.dp), item.text)
        )
        is TimelineItem.Tool -> Text(
            item.summary?.let { "🔧 ${item.name} — $it" } ?: "🔧 正在 ${item.name}…",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = copyOnLong(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                item.summary?.let { "🔧 ${item.name} — $it" } ?: "🔧 正在 ${item.name}…"
            )
        )
        is TimelineItem.Card -> CardRow(item, onAnswer)
        is TimelineItem.Error -> Text(
            item.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
        is TimelineItem.Deliverable -> DeliverableRow(item, sessionId, snackbar)
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, extra: Modifier = Modifier) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .then(extra)
                .widthIn(max = 300.dp)
                .background(
                    if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(
                        topStart = 14.dp, topEnd = 14.dp,
                        bottomStart = if (fromUser) 14.dp else 2.dp,
                        bottomEnd = if (fromUser) 2.dp else 14.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
