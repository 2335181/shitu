package app.shitu.feature.convert

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import app.shitu.core.UserFacingException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安卓动态照片（Google Motion Photo v1，兼容旧版 MicroVideo 字段）：
 * 一张 JPEG，XMP 里声明视频长度，MP4 直接拼在 JPEG 后面。
 * Google 相册、小米 / OPPO / vivo / 三星等相册都按这个格式识别为「实况 / 动态照片」。
 */
class MotionPhoto(private val context: Context) {
    private val ffmpeg = FfmpegRunner(context)

    /**
     * 视频 → 动态照片。截取 [start, start+length]，转成 H.264 MP4（长边不超过 1440），
     * 封面取 [coverAt] 秒处（相对截取段）。
     */
    suspend fun fromVideo(
        input: File,
        workDir: File,
        start: Double,
        length: Double,
        coverAt: Double,
        keepAudio: Boolean,
        onProgress: (Float) -> Unit,
    ): File {
        val mp4 = File(workDir, "motion.mp4")
        ffmpeg.run(
            buildList {
                addAll(listOf("-ss", "%.3f".format(java.util.Locale.US, start), "-t", "%.3f".format(java.util.Locale.US, length), "-i", input.absolutePath))
                addAll(listOf("-vf", "scale='if(gt(iw,ih),min(1440,iw),-2)':'if(gt(iw,ih),-2,min(1440,ih))':flags=lanczos,format=yuv420p"))
                addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-profile:v", "high"))
                if (keepAudio) addAll(listOf("-c:a", "aac", "-b:a", "128k")) else add("-an")
                addAll(listOf("-movflags", "+faststart", mp4.absolutePath))
            },
        ) { t -> onProgress((t / length).toFloat().coerceIn(0f, 0.9f)) }
        val jpeg = withContext(Dispatchers.IO) { coverJpeg(mp4, coverAt) }
        val out = File(workDir, "MVIMG_${System.currentTimeMillis()}.jpg")
        withContext(Dispatchers.IO) { write(jpeg, mp4, (coverAt * 1_000_000).toLong(), out) }
        onProgress(1f)
        return out
    }

    private fun coverJpeg(video: File, at: Double): ByteArray {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(video.absolutePath)
            val frame = mmr.getFrameAtTime((at * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                ?: mmr.frameAtTime ?: throw UserFacingException("取不到封面帧")
            return ByteArrayOutputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
        } finally {
            mmr.release()
        }
    }

    private fun write(jpeg: ByteArray, mp4: File, presentationUs: Long, out: File) {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "封面不是 JPEG" }
        val videoLen = mp4.length()
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Shitu">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about=""
 xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
 xmlns:Container="http://ns.google.com/photos/1.0/container/"
 xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
 GCamera:MotionPhoto="1"
 GCamera:MotionPhotoVersion="1"
 GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
 GCamera:MicroVideo="1"
 GCamera:MicroVideoVersion="1"
 GCamera:MicroVideoOffset="$videoLen"
 GCamera:MicroVideoPresentationTimestampUs="$presentationUs">
<Container:Directory>
<rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLen" Item:Padding="0"/></rdf:li>
</rdf:Seq>
</Container:Directory>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>""".toByteArray()
        val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
        val segLen = 2 + ns.size + xmp.size
        require(segLen <= 0xFFFF) { "XMP 太长" }
        out.outputStream().buffered().use { o ->
            o.write(jpeg, 0, 2) // SOI
            o.write(0xFF); o.write(0xE1)
            o.write(segLen shr 8); o.write(segLen and 0xFF)
            o.write(ns); o.write(xmp)
            o.write(jpeg, 2, jpeg.size - 2)
            mp4.inputStream().use { it.copyTo(o) }
        }
    }

    /** 动态照片 → 拆出里面的 MP4。找不到时抛出。 */
    suspend fun extractVideo(input: File, out: File): File = withContext(Dispatchers.IO) {
        val len = input.length()
        val head = RandomAccessFile(input, "r").use { raf ->
            val n = minOf(len, 256 * 1024).toInt()
            ByteArray(n).also { raf.readFully(it) }
        }.decodeToString()
        val fromXmp = Regex("Item:Semantic=\"MotionPhoto\"[^>]*?Item:Length=\"(\\d+)\"").find(head)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("Item:Length=\"(\\d+)\"[^>]*?Item:Semantic=\"MotionPhoto\"").find(head)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("MicroVideoOffset=\"(\\d+)\"").find(head)?.groupValues?.get(1)?.toLongOrNull()
        val offset = fromXmp?.takeIf { it in 1 until len }?.let { len - it } ?: findFtyp(input)
            ?: throw UserFacingException("这张图里没有视频，不是动态照片")
        RandomAccessFile(input, "r").use { raf ->
            raf.seek(offset)
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = raf.read(buf); if (n < 0) break; o.write(buf, 0, n) }
            }
        }
        out
    }

    /** 兜底：从前往后找第一个 MP4 的 ftyp 盒（JPEG 之后紧跟的就是视频）。 */
    private fun findFtyp(file: File): Long? {
        val bytes = file.readBytes()
        val sig = "ftyp".toByteArray()
        for (i in 8 until bytes.size - 8) {
            if (bytes[i] == sig[0] && bytes[i + 1] == sig[1] && bytes[i + 2] == sig[2] && bytes[i + 3] == sig[3] &&
                bytes[i - 6] == 0xFF.toByte() && bytes[i - 5] == 0xD9.toByte()
            ) return (i - 4).toLong()
        }
        for (i in 8 until bytes.size - 8) {
            if (bytes[i] == sig[0] && bytes[i + 1] == sig[1] && bytes[i + 2] == sig[2] && bytes[i + 3] == sig[3]) return (i - 4).toLong()
        }
        return null
    }
}
