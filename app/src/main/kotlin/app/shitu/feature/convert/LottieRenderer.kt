package app.shitu.feature.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import app.shitu.core.UserFacingException

/**
 * Lottie / TGS（gzip 压缩的 Lottie）逐帧渲染成 PNG，再交给 ffmpeg 合成 GIF。
 * 用 lottie-android（Apache-2.0）在后台线程直接画到 Bitmap 上，不需要界面。
 */
class LottieRenderer(private val context: Context) {
    private val ffmpeg = FfmpegRunner(context)

    suspend fun toGif(input: File, workDir: File, size: Int = 512, fps: Int = 30, onProgress: (Float) -> Unit = {}): File {
        val json = withContext(Dispatchers.IO) {
            val bytes = input.readBytes()
            val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            if (gz) GZIPInputStream(bytes.inputStream()).use { it.readBytes().decodeToString() } else bytes.decodeToString()
        }
        val result = withContext(Dispatchers.IO) { LottieCompositionFactory.fromJsonStringSync(json, input.name) }
        val comp = result.value ?: throw UserFacingException("动画文件解析失败：${result.exception?.message.orEmpty()}")
        val frames = File(workDir, "lottie").apply { deleteRecursively(); mkdirs() }
        val drawable = LottieDrawable().apply { setComposition(comp) }
        val bounds = comp.bounds
        val scale = size.toFloat() / maxOf(bounds.width(), bounds.height()).coerceAtLeast(1)
        val w = (bounds.width() * scale).toInt().coerceAtLeast(2) / 2 * 2
        val h = (bounds.height() * scale).toInt().coerceAtLeast(2) / 2 * 2
        drawable.setBounds(0, 0, w, h)
        val durationSec = comp.duration / 1000f
        val count = (durationSec * fps).toInt().coerceIn(1, 600)
        withContext(Dispatchers.Default) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                bmp.eraseColor(Color.TRANSPARENT)
                drawable.progress = if (count == 1) 0f else i.toFloat() / (count - 1)
                drawable.draw(canvas)
                File(frames, "%05d.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                onProgress(i.toFloat() / count * 0.7f)
            }
            bmp.recycle()
        }
        val out = File(workDir, input.nameWithoutExtension + ".gif")
        ffmpeg.run(
            listOf(
                "-framerate", fps.toString(), "-i", File(frames, "%05d.png").absolutePath,
                "-vf", "split[a][b];[a]palettegen=reserve_transparent=1:stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a:alpha_threshold=128",
                "-gifflags", "-offsetting", "-loop", "0", out.absolutePath,
            ),
        ) { t -> onProgress(0.7f + (t / durationSec).toFloat().coerceIn(0f, 1f) * 0.3f) }
        frames.deleteRecursively()
        return out
    }
}
