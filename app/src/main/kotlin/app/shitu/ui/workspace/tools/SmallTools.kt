package app.shitu.ui.workspace.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shitu.core.Clipboard
import app.shitu.core.friendlyMessage
import app.shitu.core.toast
import app.shitu.download.DownloadRequest
import app.shitu.download.DownloadSource
import app.shitu.download.MediaKind
import app.shitu.download.MediaSaver
import app.shitu.feature.convert.FfmpegRunner
import app.shitu.feature.link.DouyinLive
import app.shitu.feature.link.LinkResult
import app.shitu.feature.link.LiveRoom
import app.shitu.feature.link.MusicCover
import app.shitu.tools.ToolCatalog
import app.shitu.ui.LocalContainer
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.PaperCard
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.design.SoftButton
import app.shitu.ui.workspace.rememberToolState
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// ---------------- 音乐封面 ----------------

@Serializable
data class MusicState(val input: String = "", val result: LinkResult? = null)

@Composable
fun MusicCoverTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val holder = rememberToolState(ToolCatalog.MUSIC_COVER, MusicState.serializer()) { MusicState() } ?: return
    var st by holder
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState(st.input)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { input.text.toString() }.collect { st = st.copy(input = it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        header()
        item {
            EditorialTitle("专辑封面，\n要*原尺寸*的。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("网易云、QQ 音乐、Apple Music 的歌曲、专辑、歌单分享链接。Apple Music 的动态封面也一起列出来。")
            Gap(22.dp)
            InputCard(input, "粘贴音乐分享链接…", "取封面", {
                scope.launch {
                    loading = true; error = null
                    runCatching { MusicCover.parse(input.text.toString()) }
                        .onSuccess { st = st.copy(result = it) }
                        .onFailure { error = it.friendlyMessage() }
                    loading = false
                }
            }, loading)
            error?.let { Gap(12.dp); Text(it, color = MaterialTheme.colorScheme.error) }
        }
        st.result?.let { r ->
            item {
                Gap(26.dp)
                PaperCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
                    AsyncImage(
                        r.cover, null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large),
                        contentScale = ContentScale.Crop,
                    )
                    Gap(14.dp)
                    Text(r.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 6.dp))
                    Text(listOf(r.platform, r.author).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
                    Gap(14.dp)
                    r.media.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(m.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            SoftButton("下载", {
                                container.downloadManager.enqueue(
                                    DownloadRequest(
                                        if (m.viaYtDlp) DownloadSource.YtDlp(m.url) else DownloadSource.Direct(m.url),
                                        "${r.title}_${m.label}",
                                        if (m.viaYtDlp) MediaKind.VIDEO else MediaKind.IMAGE,
                                        r.platform, r.cover,
                                    ),
                                )
                                context.toast("已加入下载队列")
                            }, compact = true)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 抖音直播流 ----------------

@Serializable
data class LiveState(val input: String = "", val room: LiveRoom? = null)

@Composable
fun DouyinLiveTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val holder = rememberToolState(ToolCatalog.DOUYIN_LIVE, LiveState.serializer()) { LiveState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = app.shitu.ui.LocalContainer.current
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState(st.input)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { input.text.toString() }.collect { st = st.copy(input = it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        header()
        item {
            EditorialTitle("直播间，\n只取*地址*。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("贴抖音或 B站直播间的分享链接，列出各清晰度的 FLV / HLS 地址，复制到播放器或录制软件里用。拾图本身不录制。")
            Gap(22.dp)
            InputCard(input, "直播间链接 / 房间号…", "获取直播流", {
                scope.launch {
                    loading = true; error = null
                    val text = input.text.toString()
                    runCatching {
                        if (app.shitu.feature.link.BiliLive.matches(text)) {
                            app.shitu.feature.link.BiliLive.parse(text, container.cookieVault.headerFor("live.bilibili.com"))
                        } else DouyinLive.parse(text)
                    }
                        .onSuccess { st = st.copy(room = it) }
                        .onFailure { error = it.friendlyMessage() }
                    loading = false
                }
            }, loading, singleLine = true)
            error?.let { Gap(12.dp); Text(it, color = MaterialTheme.colorScheme.error) }
        }
        st.room?.let { room ->
            item {
                Gap(26.dp)
                MistCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(room.cover, null, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(room.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            DotTags(listOf(room.anchor, "直播中", "${room.streams.size} 档"))
                        }
                    }
                }
                SectionHeader("直播流")
            }
            items(room.streams) { s ->
                PaperCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), padding = PaddingValues(16.dp)) {
                    Text(s.label, style = MaterialTheme.typography.titleMedium)
                    Gap(8.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.flv.isNotBlank()) SoftButton("复制 FLV", { Clipboard.copy(context, s.flv); context.toast("已复制 FLV 地址") }, icon = Icons.Rounded.ContentCopy, compact = true)
                        if (s.hls.isNotBlank()) SoftButton("复制 HLS", { Clipboard.copy(context, s.hls); context.toast("已复制 HLS 地址") }, icon = Icons.Rounded.ContentCopy, compact = true)
                    }
                }
            }
        }
    }
}

// ---------------- 视频转 GIF ----------------

@Serializable
data class GifState(val fps: Int = 12, val width: Int = 480, val start: Float = 0f, val length: Float = 4f)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoGifTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val holder = rememberToolState(ToolCatalog.VIDEO_GIF, GifState.serializer()) { GifState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var local by remember { mutableStateOf<File?>(null) }
    var total by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val runner = remember { FfmpegRunner(context.applicationContext) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            video = uri; error = null
            scope.launch {
                val f = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "gif").apply { deleteRecursively(); mkdirs() }
                    File(dir, "input.mp4").also { out -> context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }
                }
                local = f
                total = runner.duration(f).toFloat()
                st = st.copy(start = 0f, length = minOf(st.length, total.coerceAtLeast(0.5f)))
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        header()
        item {
            EditorialTitle("把视频定格成\n*GIF*。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("选一段本地视频，截取几秒，两遍调色板编码，颜色比一般转换器干净得多。")
            Gap(22.dp)
            PaperCard(Modifier.fillMaxWidth()) {
                if (local == null) {
                    InkButton("选择视频", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Movie)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(video, null, Modifier.size(64.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Text("时长 %.1f 秒".format(total), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        SoftButton("换一个", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, compact = true)
                    }
                    Gap(16.dp)
                    LabeledSlider("起点 %.1f 秒".format(st.start), st.start, 0f..(total - 0.5f).coerceAtLeast(0.01f)) { st = st.copy(start = it) }
                    LabeledSlider("长度 %.1f 秒".format(st.length), st.length, 0.5f..minOf(15f, (total - st.start).coerceAtLeast(0.6f))) { st = st.copy(length = it) }
                    LabeledSlider("帧率 ${st.fps} fps", st.fps.toFloat(), 5f..30f, steps = 24) { st = st.copy(fps = it.toInt()) }
                    LabeledSlider("宽度 ${st.width} px", st.width.toFloat(), 240f..1080f, steps = 13) { st = st.copy(width = (it / 60).toInt() * 60) }
                    Gap(8.dp)
                    progress?.let {
                        LinearWavyProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                        Gap(12.dp)
                    }
                    InkButton(
                        if (progress != null) "正在转换…" else "生成 GIF",
                        {
                            val input = local ?: return@InkButton
                            scope.launch {
                                error = null; progress = 0f
                                runCatching {
                                    val out = File(input.parentFile, "out.gif")
                                    runner.videoToGif(input, out, st.start.toDouble(), st.length.toDouble(), st.fps, st.width) { progress = it }
                                    withContext(Dispatchers.IO) {
                                        MediaSaver(context).saveFile(out, "GIF_${System.currentTimeMillis()}.gif", "image/gif", MediaKind.IMAGE, container.settings.value.imageFolderUri)
                                    }
                                }.onSuccess { context.toast("已保存到 ${it.displayPath}") }
                                    .onFailure { error = it.friendlyMessage() }
                                progress = null
                            }
                        },
                        Modifier.fillMaxWidth(),
                        loading = progress != null,
                    )
                }
                error?.let { Gap(10.dp); Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}
