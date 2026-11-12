package zai.deva.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zai.deva.android.AppGraph
import zai.deva.android.core.ApiResult
import zai.deva.android.core.DeliverableFile
import zai.deva.android.core.FileSaver
import zai.deva.android.core.TimelineItem

/**
 * 交付卡（设计 §5-5 / P4）：文件名 + 大小，点击下载到 Downloads/Deva/；图片内联预览。
 * 清单来自 /deliveries（直播）或 /messages 的 files 字段（回填），路径由桌面端边车白名单把守。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeliverableRow(item: TimelineItem.Deliverable, sessionId: String, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busyFile by remember { mutableStateOf<String?>(null) }

    // 下载走状态机：task = 待执行；ask = 先要存储权限（API <29）。授权与否都继续——
    // 没权限时 FileSaver 自己回落应用私有目录，不让用户点两次。
    var task by remember { mutableStateOf<DeliverableFile?>(null) }
    var ask by remember { mutableStateOf<DeliverableFile?>(null) }
    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            ask?.let { task = it }
            ask = null
        }

    fun download(f: DeliverableFile) {
        if (busyFile != null) return
        val needPerm = Build.VERSION.SDK_INT < 29 &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needPerm) {
            ask = f
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            task = f
        }
    }

    LaunchedEffect(task) {
        val f = task ?: return@LaunchedEffect
        val client = AppGraph.client()
        if (client == null) {
            task = null
            return@LaunchedEffect
        }
        busyFile = f.path
        val target = FileSaver.open(context, f.name)
        val r = client.download(f.path, sessionId, target.stream)
        task = null
        when (r) {
            is ApiResult.Ok -> {
                target.finish(true)
                scope.launch { snackbar.showSnackbar("已下载：${target.display}") }
            }
            is ApiResult.Err -> {
                target.finish(false)
                scope.launch { snackbar.showSnackbar(r.message) }
            }
        }
        busyFile = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            when {
                item.failed -> "📎 交付清单加载失败"
                item.files.isEmpty() -> "📎 正在获取交付清单…"
                else -> "📎 交付文件（${item.files.size}）"
            },
            style = MaterialTheme.typography.titleSmall
        )
        if (item.failed) {
            Text(
                "点此重试",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .heightIn(min = 44.dp)
                    .combinedClickable(onClick = {
                        AppGraph.sessions.retryDeliveries(sessionId, item.toolUseId)
                    })
            )
            return@Column
        }
        item.files.forEachIndexed { idx, f ->
            if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (f.isImage) {
                DeliverableImage(
                    file = f,
                    sessionId = sessionId,
                    onLongPress = { clipboard.setText(AnnotatedString(f.name)) },
                    onClick = { download(f) }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .combinedClickable(
                        onClick = { download(f) },
                        onLongClick = { clipboard.setText(AnnotatedString(f.path)) }
                    )
                    .padding(vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(
                        buildString {
                            append(humanSize(f.size))
                            append(" · 点击下载")
                            if (busyFile == f.path) append("（下载中…）")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 图片内联预览：拉字节缩采样解码（长边 ≤1024），失败静默（文件行仍可下载）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeliverableImage(
    file: DeliverableFile,
    sessionId: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var bmp by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) {
        val client = AppGraph.client() ?: return@LaunchedEffect
        when (val r = client.fileBytes(file.path, sessionId)) {
            is ApiResult.Ok -> bmp = withContext(Dispatchers.Default) { decodeScaled(r.value) }
            is ApiResult.Err -> Unit
        }
    }
    bmp?.let {
        Image(
            bitmap = it,
            contentDescription = file.name,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
        )
    }
}

/** 长边压到 ≤1024 的解码（inSampleSize 粗采样，足够预览，省内存）。 */
private fun decodeScaled(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

internal fun humanSize(size: Long?): String = when {
    size == null -> "大小未知"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / 1024.0 / 1024.0)
}
