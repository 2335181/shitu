package app.shitu.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 把下载结果写到用户可见的位置：
 * - 设置了自定义文件夹（SAF 树 URI）时写到那里；
 * - 否则写 MediaStore：Download/拾图/图片、视频、音频、其他。
 * 自定义文件夹失效（被删或权限收回）时回退默认，并把 fallbackReason 带回给调用方提示。
 */
class MediaSaver(private val context: Context, private val record: Boolean = true) {

    data class Saved(val uri: Uri, val displayPath: String, val fallbackReason: String? = null)

    fun save(
        displayName: String,
        mime: String,
        kind: MediaKind,
        customTree: String?,
        subDir: String? = null,
        write: (OutputStream) -> Unit,
    ): Saved {
        val name = sanitize(displayName)
        val sub = subDir?.split('/')?.map { sanitize(it) }?.filter { it.isNotBlank() }.orEmpty()
        var fallback: String? = null
        if (!customTree.isNullOrBlank()) {
            val result = runCatching { saveToTree(customTree.toUri(), sub, name, mime, write) }
            result.getOrNull()?.let { if (record) onSaved?.invoke(it, name, mime, kind); return it }
            fallback = "自定义文件夹不可用，已改存到默认位置"
        }
        return saveToMediaStore(name, mime, kind, sub, write).copy(fallbackReason = fallback).also {
            // 工具里直接保存的文件（转 GIF、实况、壁纸提取…）也记进下载队列的「已完成」，方便点开查看
            if (record) onSaved?.invoke(it, name, mime, kind)
        }
    }

    fun saveFile(file: File, displayName: String, mime: String, kind: MediaKind, customTree: String?, subDir: String? = null): Saved =
        save(displayName, mime, kind, customTree, subDir) { out -> file.inputStream().use { it.copyTo(out) } }

    fun saveStream(input: InputStream, displayName: String, mime: String, kind: MediaKind, customTree: String?): Saved =
        save(displayName, mime, kind, customTree) { out -> input.copyTo(out) }

    private fun saveToTree(tree: Uri, sub: List<String>, name: String, mime: String, write: (OutputStream) -> Unit): Saved {
        var dir = DocumentFile.fromTreeUri(context, tree)
            ?: error("tree unavailable")
        check(dir.exists() && dir.canWrite()) { "tree not writable" }
        sub.forEach { part -> dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part) ?: error("mkdir failed") }
        val base = name.substringBeforeLast('.')
        val file = dir.createFile(mime, base) ?: error("create failed")
        try {
            context.contentResolver.openOutputStream(file.uri)!!.use(write)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        val folder = dir.name ?: "自定义文件夹"
        return Saved(file.uri, "$folder/${file.name ?: name}")
    }

    private fun saveToMediaStore(name: String, mime: String, kind: MediaKind, sub: List<String>, write: (OutputStream) -> Unit): Saved {
        val resolver = context.contentResolver
        // 统一放在 Download/拾图/ 下按类型分文件夹；相册照样能扫到里面的图片和视频
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = (listOf(Environment.DIRECTORY_DOWNLOADS, FOLDER, subFolder(kind)) + sub).joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("无法写入相册")
        try {
            resolver.openOutputStream(uri)!!.use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return Saved(uri, "$relative/$name")
    }

    companion object {
        /** 由 AppContainer 接到 DownloadManager.record。 */
        @Volatile var onSaved: ((Saved, String, String, MediaKind) -> Unit)? = null
        const val FOLDER = "拾图"

        fun subFolder(kind: MediaKind) = when (kind) {
            MediaKind.IMAGE -> "图片"
            MediaKind.VIDEO -> "视频"
            MediaKind.AUDIO -> "音频"
            MediaKind.OTHER -> "其他"
        }

        /** 给界面显示用的默认位置。 */
        fun defaultPath(kind: MediaKind) = "Download/$FOLDER/${subFolder(kind)}"
        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun sanitize(name: String): String =
            name.replace(ILLEGAL, "_").trim().trim('.').take(120).ifBlank { "file" }

        fun extensionFor(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/heic" -> "heic"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "application/zip" -> "zip"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';').trim())
        }

        fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/ogg"
            "bin", "svga" -> "application/octet-stream"
            "zip" -> "application/zip"
            "json" -> "application/json"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        }
    }
}
