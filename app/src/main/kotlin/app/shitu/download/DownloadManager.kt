package app.shitu.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.shitu.core.Http
import app.shitu.core.await
import app.shitu.core.friendlyMessage
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request

data class DownloadConfig(
    val concurrency: Int = 3,
    val imageTree: String? = null,
    val videoTree: String? = null,
)

/** yt-dlp 下载由 ytdlp 模块实现，下载到临时文件后交回这里统一保存。 */
fun interface YtDlpExecutor {
    suspend fun download(
        source: DownloadSource.YtDlp,
        workDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File
}

/** Pixiv 动图转换由 convert 模块实现。onProgress 为 0..1。 */
fun interface UgoiraExecutor {
    suspend fun convert(source: DownloadSource.Ugoira, workDir: File, onProgress: (Float) -> Unit): File
}

/** Telegram 贴纸下载 + 格式转换。返回文件与 MIME。 */
fun interface StickerExecutor {
    suspend fun convert(source: DownloadSource.Sticker, workDir: File, onProgress: (Float) -> Unit): Pair<File, String>
}

/**
 * 全局下载队列：单一队列、可调并发、取消/重试、进程重启后保留记录。
 * 文件先下到私有缓存，校验类型后再写入相册或自定义文件夹，失败不留半成品。
 */
class DownloadManager(
    private val context: Context,
    private val config: () -> DownloadConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saver = MediaSaver(context, record = false)
    private val store = File(context.filesDir, "downloads.json")
    private val workRoot = File(context.cacheDir, "downloads").apply { mkdirs() }
    private val mutex = Mutex()
    private val running = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val ids = AtomicLong(System.currentTimeMillis())

    var ytDlpExecutor: YtDlpExecutor? = null
    var ugoiraExecutor: UgoiraExecutor? = null
    var stickerExecutor: StickerExecutor? = null

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _notices = MutableStateFlow<String?>(null)
    /** 最近一条需要提示用户的消息（例如自定义文件夹失效回退）。 */
    val notices: StateFlow<String?> = _notices.asStateFlow()

    init {
        scope.launch {
            val restored = runCatching {
                Http.json.decodeFromString(ListSerializer(DownloadTask.serializer()), store.readText())
            }.getOrDefault(emptyList())
            _tasks.value = restored.map {
                if (it.active) it.copy(state = TaskState.FAILED, error = "应用被关闭，点重试继续") else it
            }
        }
    }

    fun enqueue(requests: List<DownloadRequest>): Int {
        if (requests.isEmpty()) return 0
        val newTasks = requests.map { DownloadTask(id = ids.incrementAndGet(), request = it) }
        _tasks.update { newTasks + it }
        persist()
        pump()
        return newTasks.size
    }

    fun enqueue(request: DownloadRequest) = enqueue(listOf(request))

    /** 记录工具里直接生成并保存的文件（不需要下载），出现在「已完成」里。 */
    fun record(saved: MediaSaver.Saved, name: String, mime: String, kind: MediaKind) {
        val req = DownloadRequest(DownloadSource.Local(saved.uri.toString()), name, kind, "本机处理")
        val task = DownloadTask(
            id = ids.incrementAndGet(), request = req, state = TaskState.SUCCEEDED,
            savedTo = saved.displayPath, savedUri = saved.uri.toString(), savedMime = mime,
        )
        _tasks.update { listOf(task) + it }
        persist()
    }

    fun cancel(id: Long) {
        running.remove(id)?.cancel()
        mutate(id) { if (it.active) it.copy(state = TaskState.CANCELLED) else it }
        pump()
    }

    fun retry(id: Long) {
        mutate(id) {
            if (it.state == TaskState.FAILED || it.state == TaskState.CANCELLED) {
                it.copy(state = TaskState.QUEUED, error = null, downloaded = 0, total = -1)
            } else it
        }
        pump()
    }

    fun retryAll() {
        _tasks.value.filter { it.state == TaskState.FAILED || it.state == TaskState.CANCELLED }.forEach { retry(it.id) }
    }

    fun cancelAll() {
        _tasks.value.filter { it.active }.forEach { cancel(it.id) }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active } }
        persist()
    }

    fun remove(id: Long) {
        running.remove(id)?.cancel()
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun consumeNotice() {
        _notices.value = null
    }

    private fun pump() {
        scope.launch {
            mutex.withLock {
                val limit = config().concurrency.coerceIn(1, 8)
                val runningCount = _tasks.value.count { it.state == TaskState.RUNNING }
                val slots = limit - runningCount
                if (slots <= 0) return@withLock
                // 队列按创建先后执行（列表是新在前，所以从尾部取）
                val next = _tasks.value.filter { it.state == TaskState.QUEUED }.sortedBy { it.id }.take(slots)
                next.forEach { task ->
                    mutate(task.id) { it.copy(state = TaskState.RUNNING) }
                    running[task.id] = scope.launch { run(task) }
                }
                if (_tasks.value.any { it.active }) startService()
            }
        }
    }

    private suspend fun run(task: DownloadTask) {
        val work = File(workRoot, task.id.toString()).apply { mkdirs() }
        try {
            val (file, mime) = when (val source = task.request.source) {
                is DownloadSource.Direct -> fetchDirect(task, source, work)
                is DownloadSource.YtDlp -> {
                    val executor = ytDlpExecutor ?: throw IOException("yt-dlp 还没准备好")
                    val out = executor.download(source, work) { d, t -> progress(task.id, d, t) }
                    out to MediaSaver.mimeForExtension(out.extension)
                }
                is DownloadSource.Ugoira -> {
                    val executor = ugoiraExecutor ?: throw IOException("转换器还没准备好")
                    val out = executor.convert(source, work) { p -> progress(task.id, (p * 1000).toLong(), 1000) }
                    out to "image/gif"
                }
                is DownloadSource.Sticker -> {
                    val executor = stickerExecutor ?: throw IOException("贴纸转换器还没准备好")
                    executor.convert(source, work) { p -> progress(task.id, (p * 1000).toLong(), 1000) }
                }
                is DownloadSource.Local -> throw IOException("本机生成的文件，不能重新下载")
            }
            val kind = task.request.kind.let { k ->
                // 类型和文件不符时，按真实类型落到对应位置
                when {
                    mime.startsWith("image/") -> MediaKind.IMAGE
                    mime.startsWith("video/") -> MediaKind.VIDEO
                    mime.startsWith("audio/") -> MediaKind.AUDIO
                    else -> if (k == MediaKind.IMAGE || k == MediaKind.VIDEO) MediaKind.OTHER else k
                }
            }
            val ext = file.extension.ifBlank { MediaSaver.extensionFor(mime) ?: "bin" }
            val base = MediaSaver.sanitize(task.request.fileName.substringBeforeLast('.').ifBlank { task.request.fileName })
            val cfg = config()
            val tree = when (kind) {
                MediaKind.IMAGE -> cfg.imageTree
                MediaKind.VIDEO, MediaKind.AUDIO -> cfg.videoTree
                MediaKind.OTHER -> cfg.imageTree
            }
            currentCoroutineContext().ensureActive()
            val saved = saver.saveFile(file, "$base.$ext", mime, kind, tree, task.request.subDir)
            saved.fallbackReason?.let { _notices.value = it }
            mutate(task.id) { it.copy(state = TaskState.SUCCEEDED, savedTo = saved.displayPath, savedUri = saved.uri.toString(), savedMime = mime, error = null) }
        } catch (e: CancellationException) {
            mutate(task.id) { if (it.state == TaskState.RUNNING) it.copy(state = TaskState.CANCELLED) else it }
        } catch (e: Throwable) {
            app.shitu.core.DevLog.error("下载", task.request.group + " · " + task.request.fileName, e)
            mutate(task.id) { it.copy(state = TaskState.FAILED, error = e.friendlyMessage()) }
        } finally {
            work.deleteRecursively()
            running.remove(task.id)
            persist()
            pump()
        }
    }

    private suspend fun fetchDirect(task: DownloadTask, source: DownloadSource.Direct, work: File): Pair<File, String> {
        val urls = (listOf(source.url) + source.mirrors).distinct()
        var last: Throwable? = null
        for (url in urls) {
            try {
                return fetchOne(task, url, source.headers, work)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IOException("没有可用地址")
    }

    private suspend fun fetchOne(task: DownloadTask, url: String, headers: Map<String, String>, work: File): Pair<File, String> {
        val request = Request.Builder().url(url)
            .header("User-Agent", Http.DESKTOP_UA)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        Http.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val mime = (body.contentType()?.toString() ?: "").substringBefore(';').ifBlank {
                MediaSaver.mimeForExtension(url.substringBefore('?').substringAfterLast('.', ""))
            }
            if (mime.startsWith("text/html") || mime == "application/json") {
                throw IOException("服务器返回的是网页而不是文件")
            }
            val total = body.contentLength()
            val urlExt = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
                .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            val ext = MediaSaver.extensionFor(mime)?.takeIf { mime != "application/octet-stream" } ?: urlExt ?: "bin"
            val out = File(work, "data.$ext")
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastTick = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastTick > 200_000_000L) {
                            lastTick = now
                            progress(task.id, done, total)
                        }
                    }
                    progress(task.id, done, total)
                }
            }
            val finalMime = if (mime == "application/octet-stream") MediaSaver.mimeForExtension(ext) else mime
            return out to finalMime
        }
    }

    private fun progress(id: Long, downloaded: Long, total: Long) {
        mutate(id, persist = false) { it.copy(downloaded = downloaded, total = total) }
    }

    private fun mutate(id: Long, persist: Boolean = true, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
        if (persist) persist()
    }

    private val persistLock = Mutex()
    private fun persist() {
        val snapshot = _tasks.value.take(500)
        scope.launch {
            persistLock.withLock {
                runCatching {
                    val tmp = File(store.parentFile, "downloads.json.tmp")
                    tmp.writeText(Http.json.encodeToString(ListSerializer(DownloadTask.serializer()), snapshot))
                    tmp.renameTo(store)
                }
            }
        }
    }

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
