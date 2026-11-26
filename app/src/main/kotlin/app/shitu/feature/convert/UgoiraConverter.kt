package app.shitu.feature.convert

import android.content.Context
import app.shitu.core.Http
import app.shitu.core.UserFacingException
import app.shitu.core.await
import app.shitu.download.DownloadSource
import app.shitu.download.UgoiraExecutor
import app.shitu.feature.bili.arr
import app.shitu.feature.bili.obj
import app.shitu.feature.bili.str
import app.shitu.feature.link.UgoiraFrame
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * Pixiv 动图（Ugoira）帧包 → GIF。
 * 帧包是一组 JPG/PNG，每帧延时不同；用 ffmpeg concat 按真实延时排时间轴，
 * 再用 palettegen + paletteuse 一次编码，颜色干净、节奏和原作一致。
 */
class UgoiraConverter(private val context: Context) : UgoiraExecutor {
    private val ffmpeg = FfmpegRunner(context)

    override suspend fun convert(source: DownloadSource.Ugoira, workDir: File, onProgress: (Float) -> Unit): File {
        val zip = File(workDir, "frames.zip")
        download(source.zipUrl, source.headers, zip) { onProgress(it * 0.4f) }
        return framesToGif(zip, source.frames, 100, workDir) { onProgress(0.4f + it * 0.6f) }
    }

    private suspend fun download(url: String, headers: Map<String, String>, out: File, onProgress: (Float) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", Http.DESKTOP_UA)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw UserFacingException("帧包下载失败（HTTP ${r.code}）")
            val total = r.body.contentLength()
            withContext(Dispatchers.IO) {
                r.body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
        }
    }

    /**
     * 帧包转 GIF。frames 为空时：先找包里的 animation.json（PixivUtil、Pixiv Toolkit 导出的格式），
     * 再不行就按文件名顺序、每帧 [defaultDelay] 毫秒。
     */
    suspend fun framesToGif(
        zip: File,
        frames: List<UgoiraFrame>?,
        defaultDelay: Int,
        workDir: File,
        maxWidth: Int? = null,
        onProgress: (Float) -> Unit,
    ): File {
        val dir = File(workDir, "frames").apply { deleteRecursively(); mkdirs() }
        var json: String? = null
        withContext(Dispatchers.IO) {
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/')
                    if (name.isBlank() || name.startsWith(".")) continue
                    if (name.endsWith(".json", true)) { json = zin.readBytes().decodeToString(); continue }
                    if (!name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") || it.endsWith(".gif") }) continue
                    File(dir, name).outputStream().use { zin.copyTo(it) }
                }
            }
        }
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        if (files.isEmpty()) throw UserFacingException("压缩包里没有图片帧")
        val plan: List<UgoiraFrame> = frames?.takeIf { it.isNotEmpty() }
            ?: json?.let { parseFramesJson(it) }?.takeIf { it.isNotEmpty() }
            ?: files.map { UgoiraFrame(it.name, defaultDelay) }
        val present = plan.filter { File(dir, it.file).exists() }
        if (present.isEmpty()) throw UserFacingException("帧列表和压缩包里的文件对不上")

        val list = File(workDir, "frames.txt")
        list.writeText(buildString {
            appendLine("ffconcat version 1.0")
            present.forEach { f ->
                appendLine("file '${File(dir, f.file).absolutePath}'")
                appendLine("duration %.3f".format(java.util.Locale.US, f.delay.coerceAtLeast(20) / 1000.0))
            }
            // concat 的最后一帧需要再写一次，时长才会生效
            appendLine("file '${File(dir, present.last().file).absolutePath}'")
        })
        val totalSec = present.sumOf { it.delay.coerceAtLeast(20) } / 1000.0
        val out = File(workDir, "ugoira.gif")
        val scale = maxWidth?.let { "scale='min($it,iw)':-2:flags=lanczos," }.orEmpty()
        ffmpeg.run(
            listOf(
                "-f", "concat", "-safe", "0", "-i", list.absolutePath,
                "-fps_mode", "vfr",
                "-vf", "${scale}split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a",
                "-loop", "0", out.absolutePath,
            ),
        ) { t -> onProgress((t / totalSec).toFloat().coerceIn(0f, 1f)) }
        onProgress(1f)
        return out
    }

    private fun parseFramesJson(text: String): List<UgoiraFrame>? = runCatching {
        val root = Http.json.parseToJsonElement(text).jsonObject
        val holder = root.obj("body") ?: root.obj("ugokuIllustData") ?: root
        holder.arr("frames").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            UgoiraFrame(o.str("file") ?: return@mapNotNull null, o.str("delay")?.toIntOrNull() ?: 100)
        }
    }.getOrNull()
}
