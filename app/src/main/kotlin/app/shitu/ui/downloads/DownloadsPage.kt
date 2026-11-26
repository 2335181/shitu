package app.shitu.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.core.formatBytes
import app.shitu.core.toast
import app.shitu.download.DownloadSource
import app.shitu.download.DownloadTask
import app.shitu.download.TaskState
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Gap
import app.shitu.ui.design.Lede
import app.shitu.ui.design.PageTopBar
import app.shitu.ui.design.SoftButton
import app.shitu.ui.design.StatusBadge
import app.shitu.ui.theme.LocalShituPalette
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

private enum class Filter(val label: String) { ALL("全部"), ACTIVE("下载中"), DONE("已完成"), FAILED("失败") }

private fun DownloadTask.matches(f: Filter) = when (f) {
    Filter.ALL -> true
    Filter.ACTIVE -> active
    Filter.DONE -> state == TaskState.SUCCEEDED
    Filter.FAILED -> state == TaskState.FAILED || state == TaskState.CANCELLED
}

@Composable
fun DownloadsPage(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val dm = container.downloadManager
    val tasks by dm.tasks.collectAsStateWithLifecycle()
    val active = tasks.count { it.active }
    val failed = tasks.count { it.state == TaskState.FAILED || it.state == TaskState.CANCELLED }
    val done = tasks.count { it.state == TaskState.SUCCEEDED }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val shown = tasks.filter { it.matches(filter) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { PageTopBar("下载", onBack = { nav.pop() }) }
        item {
            Gap(8.dp)
            EditorialTitle(if (active > 0) "还有 *$active* 个\n在路上。" else "下载\n*队列*。", style = MaterialTheme.typography.displaySmall)
            Gap(10.dp)
            Lede("默认存到 Download/拾图：图片在「图片」、视频在「视频」，或你在设置里选的文件夹。")
            Gap(18.dp)
            // 分类：两段式胶囊同款的分段切换
            Row(
                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
            ) {
                Filter.entries.forEach { f ->
                    val n = when (f) { Filter.ALL -> tasks.size; Filter.ACTIVE -> active; Filter.DONE -> done; Filter.FAILED -> failed }
                    val on = filter == f
                    Text(
                        "${f.label} $n",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { filter = f }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (filter) {
                    Filter.ALL -> {
                        if (failed > 0) SoftButton("全部重试", { dm.retryAll() }, icon = Icons.Rounded.Refresh, compact = true)
                        if (active > 0) SoftButton("全部取消", { dm.cancelAll() }, compact = true)
                        if (tasks.size > active) SoftButton("清除已结束", { dm.clearFinished() }, compact = true)
                    }
                    Filter.ACTIVE -> if (active > 0) SoftButton("全部取消", { dm.cancelAll() }, compact = true)
                    Filter.DONE -> if (done > 0) SoftButton("清除已完成", { tasks.filter { it.state == TaskState.SUCCEEDED }.forEach { dm.remove(it.id) } }, compact = true)
                    Filter.FAILED -> if (failed > 0) {
                        SoftButton("全部重试", { dm.retryAll() }, icon = Icons.Rounded.Refresh, compact = true)
                        SoftButton("清除失败", { tasks.filter { it.matches(Filter.FAILED) }.forEach { dm.remove(it.id) } }, compact = true)
                    }
                }
            }
            Gap(18.dp)
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    when (filter) {
                        Filter.ALL -> "队列是空的。在工具里选好素材点「下载」就会出现在这里。"
                        Filter.ACTIVE -> "没有正在下载的任务。"
                        Filter.DONE -> "还没有下载完成的文件。"
                        Filter.FAILED -> "没有失败的任务。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.id }) { t ->
            TaskRow(t)
            Gap(10.dp)
        }
    }
}

@Composable
private fun TaskRow(t: DownloadTask) {
    val dm = LocalContainer.current.downloadManager
    val scheme = MaterialTheme.colorScheme
    val palette = LocalShituPalette.current
    val referer = (t.request.source as? DownloadSource.Direct)?.headers?.get("Referer")
    val context = androidx.compose.ui.platform.LocalContext.current
    val done = t.state == TaskState.SUCCEEDED
    val thumb = t.savedUri?.takeIf { done && t.savedMime.orEmpty().let { m -> m.startsWith("image/") || m.startsWith("video/") } } ?: t.request.thumbnail
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceContainerLowest)
            .then(if (done) Modifier.clickable(onClickLabel = "用默认应用打开") { openSaved(context, t) } else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(scheme.surfaceContainer)) {
            thumb?.let { url ->
                AsyncImage(
                    ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(url).apply {
                        if (referer != null) httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
                    }.build(),
                    null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.request.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.request.group, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Gap(6.dp)
            when (t.state) {
                TaskState.RUNNING -> {
                    val p = t.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Gap(4.dp)
                    val ytdlp = t.request.source !is DownloadSource.Direct
                    Text(
                        when {
                            ytdlp && p != null -> "%.0f%%".format(p * 100)
                            p != null -> "${formatBytes(t.downloaded)} / ${formatBytes(t.total)}"
                            else -> "已下载 ${formatBytes(t.downloaded)}"
                        },
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    )
                }
                TaskState.QUEUED -> StatusBadge("排队中", scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
                TaskState.SUCCEEDED -> Text("已保存 · ${t.savedTo.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = palette.success, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TaskState.FAILED -> Text(t.error ?: "失败", style = MaterialTheme.typography.labelSmall, color = scheme.error, maxLines = 2)
                TaskState.CANCELLED -> StatusBadge("已取消", scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
            }
        }
        when (t.state) {
            TaskState.QUEUED, TaskState.RUNNING -> IconButton(onClick = { dm.cancel(t.id) }) { Icon(Icons.Rounded.Close, "取消") }
            TaskState.FAILED, TaskState.CANCELLED -> IconButton(onClick = { dm.retry(t.id) }) { Icon(Icons.Rounded.Refresh, "重试") }
            TaskState.SUCCEEDED -> IconButton(onClick = { dm.remove(t.id) }) { Icon(Icons.Rounded.Close, "移除记录") }
        }
    }
}


/** 用系统默认应用打开已保存的文件（图片、视频、音频、GIF…）。 */
private fun openSaved(context: android.content.Context, t: DownloadTask) {
    val uri = t.savedUri?.let(android.net.Uri::parse) ?: findInMediaStore(context, t.savedTo)
    if (uri == null) {
        context.toast("找不到这个文件，可能已被删除或移动")
        return
    }
    val mime = t.savedMime ?: context.contentResolver.getType(uri) ?: "*/*"
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // 没有能打开这种类型的应用时让用户自己选
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(intent.setDataAndType(uri, "*/*"), "打开方式")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { context.toast("没有能打开这个文件的应用") }
    }
}

/** 旧版本的记录没有存 content:// 地址，按「相对路径/文件名」去 MediaStore 里找。 */
private fun findInMediaStore(context: android.content.Context, savedTo: String?): android.net.Uri? {
    savedTo ?: return null
    val name = savedTo.substringAfterLast('/')
    val dir = savedTo.substringBeforeLast('/', "") + "/"
    val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    return runCatching {
        context.contentResolver.query(
            collection, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, dir), null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }.getOrNull()
}
