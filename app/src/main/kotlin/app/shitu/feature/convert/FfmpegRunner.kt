package app.shitu.feature.convert

import android.content.Context
import app.shitu.core.UserFacingException
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 直接运行 youtubedl-android 自带的 ffmpeg 可执行文件（nativeLibraryDir/libffmpeg.so），
 * 依赖库在 noBackupFilesDir/youtubedl-android/packages/{ffmpeg,python}/usr/lib。
 */
class FfmpegRunner(private val context: Context) {

    private fun ensure() {
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
    }

    /** 运行 ffmpeg；onTime 回调当前已处理的秒数。 */
    suspend fun run(args: List<String>, onTime: (Double) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        ensure()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pkgs = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val ld = listOf("ffmpeg", "python", "aria2c").map { File(pkgs, "$it/usr/lib").absolutePath }.joinToString(":")
        val pb = ProcessBuilder(listOf(bin.absolutePath, "-hide_banner", "-y") + args)
            .redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ld
        val job = coroutineContext[kotlinx.coroutines.Job]
        val process = pb.start()
        val tail = ArrayDeque<String>()
        val time = Regex("time=(\\d+):(\\d+):(\\d+\\.?\\d*)")
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (job?.isActive == false) { process.destroy(); return@forEach }
                    time.find(line)?.let { m ->
                        val (h, mi, s) = m.destructured
                        onTime(h.toDouble() * 3600 + mi.toDouble() * 60 + s.toDouble())
                    }
                    tail.addLast(line); if (tail.size > 12) tail.removeFirst()
                }
            }
            val code = process.waitFor()
            if (code != 0) throw UserFacingException("转换失败：" + (tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg 退出码 $code"))
        } finally {
            process.destroy()
        }
    }

    /** 两遍法转 GIF：先生成调色板，再用它抖动编码，画质明显好于直接转。 */
    suspend fun videoToGif(
        input: File,
        output: File,
        start: Double,
        duration: Double,
        fps: Int,
        width: Int,
        onProgress: (Float) -> Unit,
    ) {
        val palette = File(output.parentFile, "palette.png")
        val scale = "fps=$fps,scale=$width:-1:flags=lanczos"
        val range = listOf("-ss", "%.2f".format(start), "-t", "%.2f".format(duration))
        run(range + listOf("-i", input.absolutePath, "-vf", "$scale,palettegen=stats_mode=diff", palette.absolutePath)) {
            onProgress((it / duration * 0.3).toFloat().coerceIn(0f, 0.3f))
        }
        run(
            range + listOf(
                "-i", input.absolutePath, "-i", palette.absolutePath,
                "-lavfi", "$scale[x];[x][1:v]paletteuse=dither=sierra2_4a",
                "-loop", "0", output.absolutePath,
            ),
        ) { onProgress((0.3 + it / duration * 0.7).toFloat().coerceIn(0.3f, 1f)) }
        palette.delete()
    }

    /** 读时长（秒）。 */
    fun duration(input: File): Double {
        var out = 0.0
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(input.absolutePath)
            out = (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000
        } finally {
            mmr.release()
        }
        return out
    }
}
