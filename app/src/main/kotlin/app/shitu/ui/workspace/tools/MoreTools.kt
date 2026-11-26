package app.shitu.ui.workspace.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.core.Http
import app.shitu.core.formatBytes
import app.shitu.core.friendlyMessage
import app.shitu.core.getText
import app.shitu.core.openUrl
import app.shitu.core.toast
import app.shitu.download.DownloadRequest
import app.shitu.download.DownloadSource
import app.shitu.download.MediaKind
import app.shitu.download.MediaSaver
import app.shitu.feature.bili.arr
import app.shitu.feature.bili.str
import app.shitu.feature.convert.FfmpegRunner
import app.shitu.feature.convert.MotionPhoto
import app.shitu.feature.convert.UgoiraConverter
import app.shitu.feature.link.Pixiv
import app.shitu.feature.link.UgoiraInfo
import app.shitu.feature.sticker.StickerFormat
import app.shitu.feature.sticker.TgStickerSet
import app.shitu.feature.wallpaper.Pkg
import app.shitu.feature.wallpaper.TexOutput
import app.shitu.feature.wallpaper.WallpaperEngine
import app.shitu.tools.ToolCatalog
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.Page
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.OutlinePill
import app.shitu.ui.design.PaperCard
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.design.SettingRow
import app.shitu.ui.design.SoftButton
import app.shitu.ui.theme.LocalShituPalette
import app.shitu.ui.workspace.rememberToolListState
import app.shitu.ui.workspace.rememberToolState
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// ---------------- 公共小件 ----------------

/** 把 SAF 选中的文件复制到私有缓存，返回本地文件与显示名。 */
internal suspend fun copyToCache(context: Context, uri: Uri, dirName: String): Pair<File, String> = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val dir = File(context.cacheDir, dirName).apply { deleteRecursively(); mkdirs() }
    val out = File(dir, MediaSaver.sanitize(name))
    context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
    out to name
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressLine(progress: Float?) {
    progress?.let {
        LinearWavyProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        Gap(12.dp)
    }
}

@Composable
private fun ErrorText(error: String?) {
    error?.let { Gap(12.dp); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

// ---------------- Pixiv 动图转 GIF ----------------

@Serializable
data class UgoiraState(val input: String = "", val info: UgoiraInfo? = null)

@Composable
fun UgoiraTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val holder = rememberToolState(ToolCatalog.UGOIRA, UgoiraState.serializer()) { UgoiraState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState(st.input)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val converter = remember { UgoiraConverter(context.applicationContext) }
    LaunchedEffect(Unit) { snapshotFlow { input.text.toString() }.collect { st = st.copy(input = it) } }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            error = null; progress = 0f
            runCatching {
                val (zip, name) = copyToCache(context, uri, "ugoira_local")
                val gif = converter.framesToGif(zip, null, 100, zip.parentFile!!) { progress = it }
                withContext(Dispatchers.IO) {
                    MediaSaver(context).saveFile(gif, name.substringBeforeLast('.') + ".gif", "image/gif", MediaKind.IMAGE, container.settings.value.imageFolderUri)
                }
            }.onSuccess { context.toast("已保存到 ${it.displayPath}") }
                .onFailure { error = it.friendlyMessage() }
            progress = null
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = rememberToolListState(ToolCatalog.UGOIRA), contentPadding = padding) {
        header()
        item {
            EditorialTitle("Pixiv 动图，\n合成 *GIF*。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("贴作品链接或作品 ID，自动取原尺寸帧包，按每一帧的原始延时合成 GIF。已经下载好的 ugoira 帧包（zip）也能直接转。")
            Gap(22.dp)
            InputCard(input, "pixiv.net/artworks/… 或作品 ID", "读取动图", {
                scope.launch {
                    loading = true; error = null
                    runCatching {
                        val id = Pixiv.illustId(input.text.toString()) ?: throw app.shitu.core.UserFacingException("没认出作品 ID")
                        Pixiv.ugoira(id, container.cookieVault.headerFor("pixiv.net"))
                    }.onSuccess { st = st.copy(info = it) }.onFailure { error = it.friendlyMessage() }
                    loading = false
                }
            }, loading, singleLine = true)
            Gap(12.dp)
            SoftButton("选本地帧包 ZIP", { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }, icon = Icons.Rounded.FolderOpen, compact = true)
            Gap(12.dp)
            ProgressLine(progress)
            ErrorText(error)
            if (error?.contains("登录") == true) { Gap(8.dp); SoftButton("去登录 Pixiv", { nav.push(Page.Accounts) }, compact = true) }
        }
        st.info?.let { info ->
            item {
                Gap(24.dp)
                PaperCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
                    if (info.preview.isNotBlank()) {
                        MediaTile(info.preview, info.title, false, {}, Modifier.fillMaxWidth(), referer = Pixiv.REFERER, ratio = 1.2f)
                        Gap(8.dp)
                    }
                    val total = info.frames.sumOf { it.delay }
                    DotTags(listOf("${info.frames.size} 帧", "%.1f 秒".format(total / 1000f), "作品 ${info.illustId}"), Modifier.padding(horizontal = 6.dp))
                    Gap(14.dp)
                    InkButton("转成 GIF 并保存", {
                        container.downloadManager.enqueue(
                            DownloadRequest(
                                DownloadSource.Ugoira(info.zipUrl, info.frames, mapOf("Referer" to Pixiv.REFERER)),
                                "Pixiv_${info.illustId}_${info.title}", MediaKind.IMAGE, "Pixiv 动图 · ${info.title}", info.preview,
                            ),
                        )
                        context.toast("已加入下载队列，转换完成后存到 Download/拾图/图片")
                    }, Modifier.fillMaxWidth())
                    Gap(8.dp)
                    SoftButton("只下载原始帧包", {
                        container.downloadManager.enqueue(
                            DownloadRequest(DownloadSource.Direct(info.zipUrl, headers = mapOf("Referer" to Pixiv.REFERER)), "Pixiv_${info.illustId}_frames", MediaKind.OTHER, "Pixiv 帧包", info.preview),
                        )
                        context.toast("已加入下载队列")
                    }, Modifier.fillMaxWidth(), compact = true)
                }
            }
        }
    }
}

// ---------------- 转实况照片 ----------------

@Serializable
data class LivePhotoState(val start: Float = 0f, val length: Float = 3f, val cover: Float = 0f, val audio: Boolean = true)

@Composable
fun LivePhotoTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val holder = rememberToolState(ToolCatalog.LIVE_PHOTO, LivePhotoState.serializer()) { LivePhotoState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var local by remember { mutableStateOf<File?>(null) }
    var total by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val motion = remember { MotionPhoto(context.applicationContext) }
    val runner = remember { FfmpegRunner(context.applicationContext) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        video = uri; error = null
        scope.launch {
            runCatching { copyToCache(context, uri, "live_in").first }
                .onSuccess { f ->
                    local = f
                    total = runner.duration(f).toFloat()
                    st = st.copy(start = 0f, length = minOf(3f, total.coerceAtLeast(0.5f)), cover = 0f)
                }.onFailure { error = it.friendlyMessage() }
        }
    }
    val splitPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            runCatching {
                val (img, name) = copyToCache(context, uri, "live_split")
                val mp4 = motion.extractVideo(img, File(img.parentFile, "video.mp4"))
                withContext(Dispatchers.IO) {
                    MediaSaver(context).saveFile(mp4, name.substringBeforeLast('.') + "_视频.mp4", "video/mp4", MediaKind.VIDEO, container.settings.value.videoFolderUri)
                }
            }.onSuccess { context.toast("已拆出视频：${it.displayPath}") }.onFailure { error = it.friendlyMessage() }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = rememberToolListState(ToolCatalog.LIVE_PHOTO), contentPadding = padding) {
        header()
        item {
            EditorialTitle("一段视频，\n变成*实况*。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("生成安卓动态照片（Motion Photo）：相册里显示为实况 / 动态照片，长按会动。小米、OPPO、vivo、三星、Google 相册都认。")
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
                    LabeledSlider("长度 %.1f 秒（实况一般 1.5–3 秒）".format(st.length), st.length, 0.5f..minOf(10f, (total - st.start).coerceAtLeast(0.6f))) { st = st.copy(length = it, cover = st.cover.coerceAtMost(it)) }
                    LabeledSlider("封面取第 %.1f 秒".format(st.cover), st.cover, 0f..st.length.coerceAtLeast(0.01f)) { st = st.copy(cover = it) }
                    SettingRow("保留声音", trailing = { Switch(st.audio, { st = st.copy(audio = it) }) })
                    Gap(8.dp)
                    ProgressLine(progress)
                    InkButton(
                        if (progress != null) "正在生成…" else "生成动态照片",
                        {
                            val input = local ?: return@InkButton
                            scope.launch {
                                error = null; progress = 0f
                                runCatching {
                                    val work = File(context.cacheDir, "live_out").apply { deleteRecursively(); mkdirs() }
                                    val out = motion.fromVideo(input, work, st.start.toDouble(), st.length.toDouble(), st.cover.toDouble(), st.audio) { progress = it }
                                    withContext(Dispatchers.IO) {
                                        MediaSaver(context).saveFile(out, out.name, "image/jpeg", MediaKind.IMAGE, container.settings.value.imageFolderUri)
                                    }
                                }.onSuccess { context.toast("已保存到 ${it.displayPath}") }.onFailure { error = it.friendlyMessage() }
                                progress = null
                            }
                        },
                        Modifier.fillMaxWidth(),
                        loading = progress != null,
                    )
                }
                ErrorText(error)
            }
            Gap(16.dp)
            MistCard(Modifier.fillMaxWidth(), onClick = { splitPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("反过来：拆出动态照片里的视频", style = MaterialTheme.typography.titleLarge)
                Gap(6.dp)
                Text("选一张实况 / 动态照片，把里面的那段视频单独存成 MP4。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------- Telegram 贴纸 ----------------

@Serializable
data class TgStickerState(val input: String = "", val set: TgStickerSet? = null, val keepOriginal: Boolean = false, val selected: Set<String> = emptySet())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TgStickerTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val holder = rememberToolState(ToolCatalog.TG_STICKER, TgStickerState.serializer()) { TgStickerState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = LocalContainer.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState(st.input)
    val token = rememberTextFieldState(settings.telegramBotToken)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val api = container.telegramStickers
    val thumbs = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) { snapshotFlow { input.text.toString() }.collect { st = st.copy(input = it) } }
    LaunchedEffect(Unit) { snapshotFlow { token.text.toString() }.collect { container.settingsRepository.setTelegramBotToken(it) } }

    st.set?.let { set -> 
    RegisterSelection(
                    count = st.selected.size, total = set.stickers.size,
                    onToggleAll = { st = st.copy(selected = if (st.selected.size == set.stickers.size) emptySet() else set.stickers.map { it.fileId }.toSet()) },
                    onDownload = {
                        val reqs = set.stickers.filter { it.fileId in st.selected }.mapIndexed { i, s ->
                            DownloadRequest(
                                DownloadSource.Sticker(s.fileId, s.format.name, st.keepOriginal),
                                "%s_%03d".format(set.name, i + 1), if (s.format == StickerFormat.STATIC) MediaKind.IMAGE else MediaKind.IMAGE,
                                "Telegram · ${set.title}", null, subDir = "Telegram贴纸/${set.name}",
                            )
                        }
                        context.toast("已加入下载队列 ${container.downloadManager.enqueue(reqs)} 个，存到 Download/拾图/图片/Telegram贴纸")
                    },
                )
    }
    LazyColumn(Modifier.fillMaxSize(), state = rememberToolListState(ToolCatalog.TG_STICKER), contentPadding = padding) {
        header()
        item {
            EditorialTitle("贴纸包，\n整包*带走*。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("贴 t.me/addstickers/… 链接或贴纸包名。静态贴纸转 PNG，TGS 动画和视频贴纸转透明 GIF；也可以保留原格式。")
            Gap(22.dp)
            if (settings.telegramBotToken.isBlank()) {
                PaperCard(Modifier.fillMaxWidth()) {
                    Text("先填一个机器人 Token", style = MaterialTheme.typography.titleLarge)
                    Gap(6.dp)
                    Text(
                        "Telegram 读取贴纸包必须通过机器人接口。在 Telegram 里找 @BotFather，发 /newbot 按提示起个名字，就会给你一串 Token，复制过来即可。只用于读贴纸，只发给 api.telegram.org。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap(12.dp)
                    OutlinedSecureTextField(state = token, label = { Text("Bot Token") }, modifier = Modifier.fillMaxWidth())
                    Gap(10.dp)
                    SoftButton("打开 @BotFather", { context.openUrl("https://t.me/BotFather") }, icon = Icons.AutoMirrored.Rounded.OpenInNew, compact = true)
                }
                Gap(16.dp)
            }
            InputCard(input, "t.me/addstickers/… 或贴纸包名", "读取贴纸包", {
                scope.launch {
                    loading = true; error = null
                    runCatching {
                        val name = api.setName(input.text.toString()) ?: throw app.shitu.core.UserFacingException("没认出贴纸包名")
                        api.load(name)
                    }.onSuccess { st = st.copy(set = it, selected = it.stickers.map { s -> s.fileId }.toSet()); thumbs.clear() }
                        .onFailure { error = it.friendlyMessage() }
                    loading = false
                }
            }, loading, singleLine = true)
            ErrorText(error)
            if (settings.telegramBotToken.isNotBlank()) {
                Gap(10.dp)
                SoftButton("更换 Token", { scope.launch { container.settingsRepository.setTelegramBotToken("") } }, compact = true)
            }
        }
        st.set?.let { set ->
            item {
                Gap(24.dp)
                MistCard(Modifier.fillMaxWidth()) {
                    Text(set.title, style = MaterialTheme.typography.titleLarge)
                    Gap(6.dp)
                    val counts = set.stickers.groupingBy { it.format }.eachCount()
                    DotTags(listOf(set.name) + counts.map { (k, v) -> "${k.label} $v" })
                }
                Gap(12.dp)
                SettingRow("保留原格式", subtitle = if (st.keepOriginal) "WebP / TGS / WebM 原文件" else "转成 PNG 与 GIF，聊天软件里能直接用", trailing = {
                    Switch(st.keepOriginal, { st = st.copy(keepOriginal = it) })
                })
                Gap(8.dp)
            }
            val rows = set.stickers.chunked(4)
            items(rows.size, key = { "tg$it" }) { i ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows[i].forEach { s ->
                        val id = s.thumbFileId ?: s.fileId.takeIf { s.format == StickerFormat.STATIC }
                        if (id != null) LaunchedEffect(id) {
                            if (thumbs[id] == null) runCatching { api.fileUrl(id) }.onSuccess { thumbs[id] = it }
                        }
                        MediaTile(
                            url = id?.let { thumbs[it] }.orEmpty(),
                            label = s.emoji,
                            selected = s.fileId in st.selected,
                            badge = if (s.format == StickerFormat.STATIC) null else if (s.format == StickerFormat.ANIMATED) "TGS" else "视频",
                            onClick = { st = st.copy(selected = if (s.fileId in st.selected) st.selected - s.fileId else st.selected + s.fileId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - rows[i].size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item {
                SelectionCount(st.selected.size, set.stickers.size)
            }
        }
    }
}

// ---------------- 动态 Emoji ----------------

@Serializable
data class EmojiState(val query: String = "", val format: String = "gif", val selected: Set<String> = emptySet())

private data class NotoEmoji(val code: String, val tags: List<String>, val category: String) {
    val char: String get() = code.split('_').joinToString("") { String(Character.toChars(it.toInt(16))) }
    fun url(format: String) = "https://fonts.gstatic.com/s/e/notoemoji/latest/$code/" + when (format) {
        "webp" -> "512.webp"; "lottie" -> "lottie.json"; else -> "512.gif"
    }
}

private var notoCache: List<NotoEmoji>? = null

private val LIBRARIES = listOf(
    Triple("Noto Animated Emoji", "Google 官方动态表情，GIF / WebP / Lottie，Apache-2.0。本工具的数据来源。", "https://googlefonts.github.io/noto-emoji-animation/"),
    Triple("Fluent Emoji", "微软 3D / 扁平 / 高对比表情，MIT。", "https://github.com/microsoft/fluentui-emoji"),
    Triple("Animated Fluent Emojis", "Fluent 风格的动态 PNG 表情合集。", "https://github.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis"),
    Triple("Twemoji", "Twitter / X 表情 SVG 与 PNG，CC-BY 4.0。", "https://github.com/jdecked/twemoji"),
    Triple("OpenMoji", "开源表情，SVG / PNG，CC BY-SA 4.0。", "https://openmoji.org/"),
    Triple("LottieFiles Emoji", "社区做的 Lottie 动画表情，注意各自的许可。", "https://lottiefiles.com/free-animations/emoji"),
    Triple("Emojipedia", "各平台表情样式对照与检索。", "https://emojipedia.org/"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimEmojiTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val holder = rememberToolState(ToolCatalog.ANIM_EMOJI, EmojiState.serializer()) { EmojiState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = LocalContainer.current
    val input = rememberTextFieldState(st.query)
    var all by remember { mutableStateOf(notoCache) }
    var error by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { input.text.toString() }.collect { st = st.copy(query = it) } }
    LaunchedEffect(Unit) {
        if (all == null) runCatching {
            val body = Http.client.getText("https://googlefonts.github.io/noto-emoji-animation/data/api.json")
            Http.json.parseToJsonElement(body).jsonObject.arr("icons").mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                NotoEmoji(o.str("codepoint") ?: return@mapNotNull null, o.arr("tags").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }, o.arr("categories").firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
            }
        }.onSuccess { notoCache = it; all = it }.onFailure { error = it.friendlyMessage() }
    }
    val q = st.query.trim().lowercase()
    val shown = all.orEmpty().filter { e ->
        (category == null || e.category == category) &&
            (q.isEmpty() || e.char == st.query.trim() || e.tags.any { it.contains(q) } || e.code.contains(q))
    }

    if (shown.isNotEmpty()) {
    RegisterSelection(
                count = st.selected.size, total = shown.size,
                onToggleAll = { st = st.copy(selected = if (shown.all { it.code in st.selected }) st.selected - shown.map { it.code }.toSet() else st.selected + shown.map { it.code }) },
                onDownload = {
                    val byCode = all.orEmpty().associateBy { it.code }
                    val reqs = st.selected.mapNotNull { byCode[it] }.map { e ->
                        DownloadRequest(
                            DownloadSource.Direct(e.url(st.format)), "emoji_${e.code}_${e.tags.firstOrNull()?.trim(':').orEmpty()}",
                            if (st.format == "lottie") MediaKind.OTHER else MediaKind.IMAGE, "Noto 动态 Emoji", e.url("webp"), subDir = "动态Emoji",
                        )
                    }
                    context.toast("已加入下载队列 ${container.downloadManager.enqueue(reqs)} 个")
                    st = st.copy(selected = emptySet())
                },
            )
    }
    LazyColumn(Modifier.fillMaxSize(), state = rememberToolListState(ToolCatalog.ANIM_EMOJI), contentPadding = padding) {
        header()
        item {
            EditorialTitle("会动的 *Emoji*，\n随手取用。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("Google Noto 动态表情全集。按英文关键词（smile、cat、heart）或直接输入表情符号搜索，选好格式批量下载。")
            Gap(22.dp)
            InputCard(input, "smile / cat / 😂 …", "搜索", {}, false, singleLine = true)
            Gap(14.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("gif" to "GIF", "webp" to "WebP", "lottie" to "Lottie JSON").forEach { (k, l) ->
                    OutlinePill(l, onClick = { st = st.copy(format = k) }, selected = st.format == k)
                }
            }
            Gap(10.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinePill("全部", onClick = { category = null }, selected = category == null)
                all.orEmpty().map { it.category }.distinct().forEach { c ->
                    OutlinePill(CATEGORY_ZH[c] ?: c, onClick = { category = c }, selected = category == c)
                }
            }
            ErrorText(error)
            Gap(16.dp)
            if (all == null && error == null) Text("正在读取表情目录…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val rows = shown.take(400).chunked(5)
        items(rows.size, key = { "e$it" }) { i ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rows[i].forEach { e ->
                    MediaTile(
                        url = e.url("webp"), label = e.tags.firstOrNull()?.trim(':').orEmpty(),
                        selected = e.code in st.selected,
                        onClick = { st = st.copy(selected = if (e.code in st.selected) st.selected - e.code else st.selected + e.code) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(5 - rows[i].size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            if (shown.size > 400) Text("只显示前 400 个，换个关键词缩小范围。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Gap(8.dp)
            SelectionCount(st.selected.size, shown.size)
        }
        item {
            SectionHeader("公开素材库")
            Text("点一下在浏览器里打开。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(10.dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LIBRARIES.forEach { (name, desc, url) ->
                    MistCard(Modifier.fillMaxWidth(), onClick = { context.openUrl(url) }, padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleLarge)
                                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(url.removePrefix("https://").trimEnd('/'), style = MaterialTheme.typography.bodySmall, color = LocalShituPalette.current.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = LocalShituPalette.current.accent)
                        }
                    }
                }
            }
        }
    }
}

private val CATEGORY_ZH = mapOf(
    "Smileys and emotions" to "表情", "People" to "人物", "Animals and nature" to "动物自然", "Food and drink" to "食物",
    "Travel and places" to "旅行地点", "Activities and events" to "活动", "Objects" to "物品", "Symbols" to "符号", "Flags" to "旗帜",
)

// ---------------- Wallpaper Engine ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WallpaperEngineTool(padding: PaddingValues, header: LazyListScope.() -> Unit) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var pkg by remember { mutableStateOf<Pkg?>(null) }
    var single by remember { mutableStateOf<Pair<File, String>?>(null) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var previews by remember { mutableStateOf<List<File>>(emptyList()) }

    suspend fun export(onlyMedia: Boolean) {
        val p = pkg
        val saver = MediaSaver(context)
        val cfg = container.settings.value
        val sub = "WallpaperEngine/" + title.substringBeforeLast('.')
        var saved = 0
        val files = mutableListOf<File>()
        val work = File(context.cacheDir, "we_out").apply { deleteRecursively(); mkdirs() }
        val entries: List<Pair<String, () -> ByteArray>> = if (p != null) {
            p.entries.map { e -> e.path to { WallpaperEngine.readEntry(p, e) } }
        } else listOfNotNull(single?.let { (f, n) -> n to { f.readBytes() } })
        withContext(Dispatchers.IO) {
            entries.forEachIndexed { i, (path, read) ->
                progress = i.toFloat() / entries.size.coerceAtLeast(1)
                val ext = path.substringAfterLast('.', "").lowercase()
                val base = path.replace('/', '_').substringBeforeLast('.')
                runCatching {
                    if (ext == "tex") {
                        when (val o = WallpaperEngine.decodeTex(read())) {
                            is TexOutput.Image -> {
                                val f = File(work, "$base.png"); f.writeBytes(WallpaperEngine.png(o.bitmap)); o.bitmap.recycle()
                                saver.saveFile(f, f.name, "image/png", MediaKind.IMAGE, cfg.imageFolderUri, sub); files += f; saved++
                            }
                            is TexOutput.Encoded -> {
                                val f = File(work, "$base.${o.ext}"); f.writeBytes(o.bytes)
                                val mime = MediaSaver.mimeForExtension(o.ext)
                                val kind = if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE
                                saver.saveFile(f, f.name, mime, kind, if (kind == MediaKind.VIDEO) cfg.videoFolderUri else cfg.imageFolderUri, sub)
                                if (kind == MediaKind.IMAGE) files += f
                                saved++
                            }
                        }
                    } else {
                        val mime = MediaSaver.mimeForExtension(ext)
                        val kind = when {
                            mime.startsWith("image/") -> MediaKind.IMAGE
                            mime.startsWith("video/") -> MediaKind.VIDEO
                            mime.startsWith("audio/") || ext in setOf("mp3", "ogg", "wav") -> MediaKind.AUDIO
                            else -> MediaKind.OTHER
                        }
                        if (onlyMedia && kind == MediaKind.OTHER) return@runCatching
                        val f = File(work, path.replace('/', '_')); f.writeBytes(read())
                        saver.saveFile(f, f.name, mime, kind, if (kind == MediaKind.IMAGE) cfg.imageFolderUri else cfg.videoFolderUri, sub)
                        if (kind == MediaKind.IMAGE) files += f
                        saved++
                    }
                }
            }
        }
        previews = files.take(12)
        progress = null
        context.toast("已导出 $saved 个文件到 Download/拾图/…/WallpaperEngine")
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            error = null; pkg = null; single = null; previews = emptyList()
            runCatching {
                val (f, name) = copyToCache(context, uri, "we_in")
                title = name
                if (name.endsWith(".pkg", true)) pkg = withContext(Dispatchers.IO) { WallpaperEngine.readPkg(f) }
                else if (name.endsWith(".tex", true)) single = f to name
                else throw app.shitu.core.UserFacingException("请选 .pkg 或 .tex 文件")
            }.onFailure { error = it.friendlyMessage() }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = rememberToolListState(ToolCatalog.WALLPAPER_ENGINE), contentPadding = padding) {
        header()
        item {
            EditorialTitle("壁纸包，\n*拆开*看看。", style = MaterialTheme.typography.displaySmall)
            Gap(12.dp)
            Lede("选 Wallpaper Engine 创意工坊壁纸里的 scene.pkg，或单个 .tex 贴图。TEX 转成 PNG，里面的视频、音频、图片原样导出。文件一般在电脑的 steamapps/workshop/content/431960/ 下，传到手机再选。")
            Gap(22.dp)
            PaperCard(Modifier.fillMaxWidth()) {
                InkButton(if (pkg == null && single == null) "选择 .pkg / .tex 文件" else "换一个文件", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PhotoLibrary)
                ErrorText(error)
            }
        }
        val p = pkg
        if (p != null || single != null) {
            item {
                Gap(20.dp)
                MistCard(Modifier.fillMaxWidth()) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Gap(6.dp)
                    if (p != null) {
                        val byExt = p.entries.groupingBy { it.path.substringAfterLast('.', "其他").lowercase() }.eachCount()
                        DotTags(listOf(p.version, "${p.entries.size} 个文件", formatBytes(p.entries.sumOf { it.size.toLong() })) + byExt.entries.sortedByDescending { it.value }.take(4).map { "${it.key} ${it.value}" })
                    } else DotTags(listOf("单个 TEX 贴图"))
                }
                Gap(14.dp)
                ProgressLine(progress)
                InkButton("导出图片、视频和音频", { scope.launch { runCatching { export(true) }.onFailure { error = it.friendlyMessage(); progress = null } } }, Modifier.fillMaxWidth(), loading = progress != null)
                if (p != null) {
                    Gap(8.dp)
                    SoftButton("全部导出（含 json、着色器等）", { scope.launch { runCatching { export(false) }.onFailure { error = it.friendlyMessage(); progress = null } } }, Modifier.fillMaxWidth(), compact = true)
                }
            }
            if (p != null) item {
                SectionHeader("包内文件")
                p.entries.take(60).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(e.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(e.size.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (p.entries.size > 60) Text("…还有 ${p.entries.size - 60} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (previews.isNotEmpty()) {
            item { SectionHeader("刚导出的图片") }
            val rows = previews.chunked(3)
            items(rows.size, key = { "wp$it" }) { i ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows[i].forEach { f ->
                        AsyncImage(f, f.name, Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer), contentScale = ContentScale.Crop)
                    }
                    repeat(3 - rows[i].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
