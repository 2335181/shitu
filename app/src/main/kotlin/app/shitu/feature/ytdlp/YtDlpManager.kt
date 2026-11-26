package app.shitu.feature.ytdlp

import android.content.Context
import app.shitu.core.UserFacingException
import app.shitu.data.YtDlpChannel
import app.shitu.download.DownloadSource
import app.shitu.download.YtDlpExecutor
import app.shitu.feature.account.CookieVault
import app.shitu.feature.link.LinkMedia
import app.shitu.feature.link.LinkMediaType
import app.shitu.feature.link.LinkParser
import app.shitu.feature.link.LinkResult
import app.shitu.feature.link.YtDlpInfoProvider
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * youtubedl-android 封装：初始化、版本、应用内更新、读信息、下载。
 * 每次调用前把 Cookie 保险箱解密成临时 cookies.txt，用完删除。
 */
class YtDlpManager(
    private val context: Context,
    private val vault: CookieVault,
) : YtDlpExecutor, YtDlpInfoProvider {

    sealed interface State {
        data object Initializing : State
        data class Ready(val version: String) : State
        data class Updating(val from: String) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initLock = Mutex()
    private var initialized = false
    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        scope.launch { ensureInit() }
    }

    private suspend fun ensureInit() = initLock.withLock {
        if (initialized) return@withLock
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            initialized = true
            _state.value = State.Ready((YoutubeDL.getInstance().version(context) ?: "内置版"))
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "初始化失败")
            throw UserFacingException("yt-dlp 初始化失败：${t.message}")
        }
    }

    /** 返回更新后的版本说明。 */
    suspend fun update(channel: YtDlpChannel): String = withContext(Dispatchers.IO) {
        ensureInit()
        val from = (YoutubeDL.getInstance().version(context) ?: "内置版")
        _state.value = State.Updating(from)
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                if (channel == YtDlpChannel.NIGHTLY) YoutubeDL.UpdateChannel._NIGHTLY else YoutubeDL.UpdateChannel._STABLE,
            )
            val now = (YoutubeDL.getInstance().version(context) ?: "内置版")
            _state.value = State.Ready(now)
            if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "已经是最新版 $now" else "已更新到 $now"
        } catch (t: Throwable) {
            _state.value = State.Ready(from)
            throw UserFacingException("更新失败：${t.message?.lineSequence()?.firstOrNull() ?: "网络错误"}")
        }
    }

    private fun <T> withCookies(block: (File?) -> T): T {
        val dir = File(context.cacheDir, "ytdlp-cookies").apply { mkdirs() }
        val cookies = vault.writeTempNetscape(dir)
        try {
            return block(cookies)
        } finally {
            cookies?.delete()
        }
    }

    override suspend fun info(url: String): LinkResult = withContext(Dispatchers.IO) {
        ensureInit()
        val info = withCookies { cookies ->
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                cookies?.let { addOption("--cookies", it.absolutePath) }
            }
            runCatching { YoutubeDL.getInstance().getInfo(req) }.getOrElse { e ->
                throw UserFacingException(cleanError(e.message))
            }
        }
        val title = info.title ?: info.fulltitle ?: "未命名"
        val thumb = info.thumbnail.orEmpty()
        val media = mutableListOf<LinkMedia>()
        val formats = info.formats.orEmpty()
        val hasVideo = formats.any { (it.vcodec ?: "none") != "none" } || formats.isEmpty()
        if (hasVideo) {
            media += LinkMedia(LinkMediaType.VIDEO, url, thumb, "最佳画质", viaYtDlp = true, ytFormat = "bv*+ba/b")
            val heights = formats.mapNotNull { it.height.takeIf { h -> h > 0 } }.distinct().sortedDescending()
            heights.drop(1).take(3).forEach { h ->
                media += LinkMedia(LinkMediaType.VIDEO, url, thumb, "${h}p", viaYtDlp = true, ytFormat = "bv*[height<=$h]+ba/b[height<=$h]")
            }
        }
        if (formats.any { (it.acodec ?: "none") != "none" }) {
            media += LinkMedia(LinkMediaType.AUDIO, url, thumb, "仅音频", viaYtDlp = true, ytFormat = "ba/b")
        }
        if (thumb.isNotEmpty()) media += LinkMedia(LinkMediaType.IMAGE, thumb, thumb, "封面")
        LinkResult(
            platform = info.extractorKey ?: LinkParser.platformOf(url),
            title = title,
            author = info.uploader.orEmpty(),
            cover = thumb,
            media = media,
            sourceUrl = url,
            note = info.duration.takeIf { it > 0 }?.let { "时长 ${it / 60}:${"%02d".format(it % 60)}" }.orEmpty(),
        )
    }

    override suspend fun download(
        source: DownloadSource.YtDlp,
        workDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureInit()
        val processId = "dl-${System.nanoTime()}"
        try {
            withCookies { cookies ->
                val req = YoutubeDLRequest(source.url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("-o", File(workDir, "%(title).80B.%(ext)s").absolutePath)
                    if (source.audioOnly) {
                        addOption("-f", source.format ?: "ba/b")
                        addOption("-x")
                        addOption("--audio-format", "m4a")
                    } else {
                        addOption("-f", source.format ?: "bv*+ba/b")
                        addOption("--merge-output-format", "mp4")
                    }
                    cookies?.let { addOption("--cookies", it.absolutePath) }
                }
                try {
                    YoutubeDL.getInstance().execute(req, processId) { progress, _, _ ->
                        // yt-dlp 只给百分比，换算成 0..1000 的虚拟字节，界面按百分比显示
                        if (progress >= 0) onProgress((progress * 10).toLong(), 1000)
                    }
                } catch (e: YoutubeDL.CanceledException) {
                    throw kotlinx.coroutines.CancellationException("已取消")
                } catch (e: Exception) {
                    throw UserFacingException(cleanError(e.message))
                }
            }
            workDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.startsWith("cookies") }
                ?.maxByOrNull { it.length() }
                ?: throw UserFacingException("yt-dlp 没有产出文件")
        } finally {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
    }

    private fun cleanError(raw: String?): String {
        val line = raw?.lineSequence()?.lastOrNull { it.contains("ERROR") } ?: raw?.lineSequence()?.firstOrNull()
        val msg = line?.substringAfter("ERROR:")?.trim().orEmpty()
        return when {
            msg.contains("Sign in", true) || msg.contains("login", true) || msg.contains("cookies", true) ->
                "这个内容需要登录，去「我的 → 账号与登录」登录对应站点后再试"
            msg.contains("Unsupported URL", true) -> "yt-dlp 不支持这个网站"
            msg.contains("HTTP Error 403") -> "被网站拒绝（403），试试登录或更新 yt-dlp"
            msg.isBlank() -> "yt-dlp 解析失败"
            else -> msg.take(160)
        }
    }

    @Suppress("unused")
    private suspend fun never(): Nothing = suspendCancellableCoroutine { }
}
