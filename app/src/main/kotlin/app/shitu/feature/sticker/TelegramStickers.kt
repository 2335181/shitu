package app.shitu.feature.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.shitu.core.Http
import app.shitu.core.UserFacingException
import app.shitu.core.await
import app.shitu.download.DownloadSource
import app.shitu.download.StickerExecutor
import app.shitu.feature.bili.arr
import app.shitu.feature.bili.obj
import app.shitu.feature.bili.str
import app.shitu.feature.convert.FfmpegRunner
import app.shitu.feature.convert.LottieRenderer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

@Serializable
enum class StickerFormat(val label: String) { STATIC("静态"), ANIMATED("TGS 动画"), VIDEO("视频贴纸") }

@Serializable
data class TgSticker(
    val fileId: String,
    val emoji: String = "",
    val format: StickerFormat,
    val thumbFileId: String? = null,
)

@Serializable
data class TgStickerSet(val name: String, val title: String, val stickers: List<TgSticker>, val isEmoji: Boolean = false)

/**
 * Telegram 贴纸：用 Bot API 读贴纸包（Telegram 不提供匿名读取贴纸包的公开接口，所以需要一个机器人 Token）。
 * Token 只发给 api.telegram.org，不写进下载记录。
 */
class TelegramStickers(private val context: Context, private val token: () -> String) : StickerExecutor {
    private val ffmpeg = FfmpegRunner(context)
    private val lottie = LottieRenderer(context)

    fun setName(text: String): String? {
        Regex("t\\.me/(?:addstickers|addemoji)/([A-Za-z0-9_]+)").find(text)?.let { return it.groupValues[1] }
        return text.trim().takeIf { it.matches(Regex("[A-Za-z0-9_]{2,64}")) }
    }

    private suspend fun call(method: String, query: String): JsonObject {
        val t = token().trim()
        if (t.isBlank()) throw UserFacingException("先填 Telegram 机器人 Token（在 @BotFather 里 /newbot 免费创建）")
        val req = Request.Builder().url("https://api.telegram.org/bot$t/$method?$query").build()
        val body = Http.client.newCall(req).await().use { it.body.string() }
        val root = runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw UserFacingException("Telegram 没有返回数据，确认手机能访问 api.telegram.org")
        if (root.str("ok") != "true") {
            val desc = root.str("description").orEmpty()
            throw UserFacingException(
                when {
                    desc.contains("Unauthorized", true) -> "Token 不对，去 @BotFather 重新复制"
                    desc.contains("STICKERSET_INVALID", true) -> "没有这个贴纸包，检查链接"
                    else -> "Telegram：$desc"
                },
            )
        }
        return root
    }

    suspend fun load(name: String): TgStickerSet {
        val r = call("getStickerSet", "name=" + android.net.Uri.encode(name)).obj("result")
            ?: throw UserFacingException("贴纸包为空")
        val list = r.arr("stickers").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            TgSticker(
                fileId = o.str("file_id") ?: return@mapNotNull null,
                emoji = o.str("emoji").orEmpty(),
                format = when {
                    o.str("is_video") == "true" -> StickerFormat.VIDEO
                    o.str("is_animated") == "true" -> StickerFormat.ANIMATED
                    else -> StickerFormat.STATIC
                },
                thumbFileId = (o.obj("thumbnail") ?: o.obj("thumb"))?.str("file_id"),
            )
        }
        return TgStickerSet(r.str("name") ?: name, r.str("title") ?: name, list, r.str("sticker_type") == "custom_emoji")
    }

    /** 贴纸文件的临时下载地址（含 Token，只在内存里用，不落盘）。 */
    suspend fun fileUrl(fileId: String): String {
        val path = call("getFile", "file_id=" + android.net.Uri.encode(fileId)).obj("result")?.str("file_path")
            ?: throw UserFacingException("文件已过期")
        return "https://api.telegram.org/file/bot${token().trim()}/$path"
    }

    private suspend fun fetch(fileId: String, out: File) {
        val req = Request.Builder().url(fileUrl(fileId)).build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw UserFacingException("贴纸下载失败（HTTP ${r.code}）")
            withContext(Dispatchers.IO) { r.body.byteStream().use { i -> out.outputStream().use { i.copyTo(it) } } }
        }
    }

    override suspend fun convert(source: DownloadSource.Sticker, workDir: File, onProgress: (Float) -> Unit): Pair<File, String> {
        val format = StickerFormat.valueOf(source.format)
        val ext = when (format) { StickerFormat.STATIC -> "webp"; StickerFormat.ANIMATED -> "tgs"; StickerFormat.VIDEO -> "webm" }
        val raw = File(workDir, "sticker.$ext")
        fetch(source.fileId, raw)
        onProgress(0.2f)
        if (source.keepOriginal) {
            return raw to when (format) { StickerFormat.STATIC -> "image/webp"; StickerFormat.ANIMATED -> "application/x-tgsticker"; StickerFormat.VIDEO -> "video/webm" }
        }
        return when (format) {
            StickerFormat.STATIC -> withContext(Dispatchers.IO) {
                val bmp = BitmapFactory.decodeFile(raw.absolutePath) ?: throw UserFacingException("贴纸图片解码失败")
                val png = File(workDir, "sticker.png")
                png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                png to "image/png"
            }
            StickerFormat.ANIMATED -> lottie.toGif(raw, workDir) { onProgress(0.2f + it * 0.8f) } to "image/gif"
            StickerFormat.VIDEO -> {
                val gif = File(workDir, "sticker.gif")
                // 视频贴纸是带透明通道的 VP9，必须用 libvpx 解码才保得住透明
                ffmpeg.run(
                    listOf(
                        "-c:v", "libvpx-vp9", "-i", raw.absolutePath,
                        "-vf", "split[a][b];[a]palettegen=reserve_transparent=1:stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a:alpha_threshold=128",
                        "-gifflags", "-offsetting", "-loop", "0", gif.absolutePath,
                    ),
                ) { t -> onProgress(0.2f + (t / 3.0).toFloat().coerceIn(0f, 0.8f)) }
                gif to "image/gif"
            }
        }
    }
}
