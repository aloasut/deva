package zai.deva.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import zai.deva.android.core.PersonaInfo
import zai.deva.android.core.ServerConfig
import zai.deva.android.core.SessionMeta

/** 会话列表分组维度。 */
private enum class GroupMode { NONE, PERSONA, FOLDER }

/** 会话列表排序方式。 */
private enum class SortMode { RECENT, ACTIVE_FIRST }

/**
 * 服务器 + 会话列表（设计 §5-1/2）：顶部当前服务器与连接状态；分组（综合/按角色/按目录）与
 * 排序（最近/活跃优先）由本页按服务端字段自定；每项显示标题、待操作角标、角色徽标、目录、
 * 最后消息摘要、忙/闲、相对时间。FAB 新建会话（可选角色——绑定沿桌面语义，首发后不再改）。
 * 长按会话项：重命名 / 归档 / 删除（删除二次确认；服务端忙时归档回 409 提示）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onOpenChat: (String) -> Unit, onOpenSettings: () -> Unit, onOpenTasks: () -> Unit = {}) {
    val server by AppGraph.current.collectAsState()
    val sessions by AppGraph.sessions.sessions.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var refreshError by remember { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<SessionMeta?>(null) }
    var showNewSession by remember { mutableStateOf(false) }
    var groupMode by remember { mutableStateOf(GroupMode.NONE) }
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }

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
                    IconButton(onClick = onOpenTasks) {
                        Icon(Icons.Default.List, contentDescription = "定时任务")
                    }
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
                    onClick = { showNewSession = true }
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

                // 分组 / 排序控制行（需求 1：按角色/目录分类，活跃优先排序）
                item(key = "controls") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = sortMode == SortMode.RECENT,
                            onClick = { sortMode = SortMode.RECENT },
                            label = { Text("最近") }
                        )
                        FilterChip(
                            selected = sortMode == SortMode.ACTIVE_FIRST,
                            onClick = { sortMode = SortMode.ACTIVE_FIRST },
                            label = { Text("活跃优先") }
                        )
                        FilterChip(
                            selected = groupMode == GroupMode.NONE,
                            onClick = { groupMode = GroupMode.NONE },
                            label = { Text("综合") }
                        )
                        FilterChip(
                            selected = groupMode == GroupMode.PERSONA,
                            onClick = { groupMode = GroupMode.PERSONA },
                            label = { Text("按角色") }
                        )
                        FilterChip(
                            selected = groupMode == GroupMode.FOLDER,
                            onClick = { groupMode = GroupMode.FOLDER },
                            label = { Text("按目录") }
                        )
                    }
                }

                val display = buildDisplayRows(sessions, groupMode, sortMode)
                items(display, key = { it.key }) { entry ->
                    when (entry) {
                        is DisplayRow.Header -> Text(
                            entry.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        is DisplayRow.Item -> SessionRow(
                            entry.session,
                            onClick = { onOpenChat(entry.session.id) },
                            onLongClick = { actionTarget = entry.session }
                        )
                    }
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

    // 长按会话项 → 重命名 / 归档 / 删除（删除走二次确认；409 = 忙时归档）
    actionTarget?.let { target ->
        var renameText by remember(target.id) { mutableStateOf(target.title) }
        var confirmingDelete by remember(target.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.title.ifBlank { "新对话" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("重命名（留空恢复自动标题）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        TextButton(onClick = { confirmingDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (confirmingDelete) {
                        Text(
                            "删除「${target.title.ifBlank { "新对话" }}」？对话与历史将一并删除，不可恢复。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                val client = AppGraph.client()
                                if (client != null) {
                                    scope.launch {
                                        when (val r = client.deleteSession(target.id)) {
                                            is ApiResult.Err -> snackbar.showSnackbar(r.message)
                                            is ApiResult.Ok -> Unit
                                        }
                                        AppGraph.sessions.refresh(client)
                                    }
                                }
                                actionTarget = null
                            }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { confirmingDelete = false }) { Text("再想想") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val client = AppGraph.client()
                    if (client != null) {
                        scope.launch {
                            when (val r = client.renameSession(target.id, renameText.trim())) {
                                is ApiResult.Err -> snackbar.showSnackbar(r.message)
                                is ApiResult.Ok -> Unit
                            }
                            AppGraph.sessions.refresh(client)
                        }
                    }
                    actionTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { actionTarget = null }) { Text("关闭") } }
        )
    }

    if (showNewSession) {
        NewSessionDialog(
            snackbar = snackbar,
            onDismiss = { showNewSession = false },
            onCreated = { id ->
                showNewSession = false
                scope.launch {
                    AppGraph.client()?.let { AppGraph.sessions.refresh(it) }
                    onOpenChat(id)
                }
            }
        )
    }
}

/** 列表展示条目（分组头或会话行）。 */
private sealed interface DisplayRow {
    val key: String

    data class Header(val label: String) : DisplayRow {
        override val key: String = "h:$label"
    }

    data class Item(val session: SessionMeta) : DisplayRow {
        override val key: String = session.id
    }
}

private fun buildDisplayRows(
    sessions: List<SessionMeta>,
    groupMode: GroupMode,
    sortMode: SortMode
): List<DisplayRow> {
    val sorted = if (sortMode == SortMode.ACTIVE_FIRST) {
        sessions.sortedWith(compareByDescending<SessionMeta> { it.busy }.thenByDescending { it.updatedAt })
    } else {
        sessions.sortedByDescending { it.updatedAt }
    }
    if (groupMode == GroupMode.NONE) return sorted.map { DisplayRow.Item(it) }
    val labelOf: (SessionMeta) -> String = when (groupMode) {
        GroupMode.PERSONA -> { s -> s.personaName ?: "默认" }
        GroupMode.FOLDER -> { s -> s.focusRoot?.substringAfterLast('/')?.ifBlank { "目录" } ?: "全机" }
        GroupMode.NONE -> return sorted.map { DisplayRow.Item(it) }
    }
    // 组内保持排序序；组间按组内最新的 updatedAt 排（活跃的组在前）
    val groups = sorted.groupBy(labelOf)
    return groups.entries
        .sortedByDescending { (_, v) -> v.maxOf { it.updatedAt } }
        .flatMap { (label, list) -> listOf(DisplayRow.Header(label)) + list.map { DisplayRow.Item(it) } }
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
                    if (s.pendingCard) {
                        Text(
                            "待操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
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
                    s.focusRoot?.let {
                        Text(
                            it.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

/** 新建会话弹窗：可选启用中的角色（拉取失败则只给「不选角色」直建）。 */
@Composable
private fun NewSessionDialog(
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var personas by remember { mutableStateOf<List<PersonaInfo>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val client = AppGraph.client() ?: return@LaunchedEffect
        when (val r = client.personas()) {
            is ApiResult.Ok -> personas = r.value
            is ApiResult.Err -> snackbar.showSnackbar(r.message)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "选择角色（可选；绑定后该对话固定用它，首发后不可再改）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (val p = personas) {
                    null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selected == null,
                            onClick = { selected = null },
                            label = { Text("不选角色") }
                        )
                        p.forEach { persona ->
                            FilterChip(
                                selected = selected == persona.id,
                                onClick = { selected = persona.id },
                                label = { Text(persona.name, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating,
                onClick = {
                    val client = AppGraph.client() ?: return@TextButton
                    creating = true
                    scope.launch {
                        when (val r = client.createSession(selected)) {
                            is ApiResult.Ok -> onCreated(r.value)
                            is ApiResult.Err -> snackbar.showSnackbar(r.message)
                        }
                        creating = false
                    }
                }
            ) { Text(if (creating) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
