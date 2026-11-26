package app.shitu.core

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Images {
    /** 解码为软件位图（可裁剪），长边先限制在 4096 防止 OOM。 */
    suspend fun decode(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val max = maxOf(w, h)
            if (max > 4096) {
                val s = 4096f / max
                decoder.setTargetSize((w * s).toInt(), (h * s).toInt())
            }
        }
    }

    /** 按 0..1 的相对矩形裁剪，再把长边压到 maxSide，输出 JPEG。 */
    suspend fun prepare(bitmap: Bitmap, crop: android.graphics.RectF?, maxSide: Int): ByteArray = withContext(Dispatchers.Default) {
        var bmp = bitmap
        if (crop != null && (crop.left > 0.001f || crop.top > 0.001f || crop.right < 0.999f || crop.bottom < 0.999f)) {
            val r = Rect(
                (crop.left * bmp.width).toInt().coerceIn(0, bmp.width - 1),
                (crop.top * bmp.height).toInt().coerceIn(0, bmp.height - 1),
                (crop.right * bmp.width).toInt().coerceIn(1, bmp.width),
                (crop.bottom * bmp.height).toInt().coerceIn(1, bmp.height),
            )
            bmp = Bitmap.createBitmap(bmp, r.left, r.top, (r.width()).coerceAtLeast(1), (r.height()).coerceAtLeast(1))
        }
        val long = maxOf(bmp.width, bmp.height)
        if (long > maxSide) {
            val s = maxSide.toFloat() / long
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        }
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }
}

object Clipboard {
    fun text(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    fun imageUri(context: Context): Uri? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return null
        val desc = clip.description
        if (!(0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }) return null
        return clip.getItemAt(0).uri
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("拾图", text))
    }
}

fun Context.openUrl(url: String) {
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url.toUri())
    }.recoverCatching {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast("没有可以打开链接的浏览器") }
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    else -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
}
