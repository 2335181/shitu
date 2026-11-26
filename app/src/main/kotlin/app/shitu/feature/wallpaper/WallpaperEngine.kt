/*
 * Wallpaper Engine 的 PKG 打包格式与 TEX 贴图格式。
 * 格式说明参考 notscuffed/RePKG（MIT），用 Kotlin 重新实现：PKG 解包、TEX 容器 v1–v4、
 * LZ4 块解压、DXT1/3/5 与 RGBA8888 / RG88 / R8 解码。
 */
package app.shitu.feature.wallpaper

import android.graphics.Bitmap
import app.shitu.core.UserFacingException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PkgEntry(val path: String, val offset: Long, val size: Int)

data class Pkg(val file: File, val version: String, val dataStart: Long, val entries: List<PkgEntry>)

/** TEX 解码结果：要么是位图，要么是原样的已编码文件（PNG/JPG/GIF/MP4）。 */
sealed interface TexOutput {
    data class Image(val bitmap: Bitmap) : TexOutput
    data class Encoded(val bytes: ByteArray, val ext: String) : TexOutput
}

object WallpaperEngine {

    fun readPkg(file: File): Pkg = RandomAccessFile(file, "r").use { raf ->
        fun i32(): Int { val b = ByteArray(4); raf.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int }
        fun str(max: Int): String {
            val n = i32()
            if (n < 0 || n > max) throw UserFacingException("不是 Wallpaper Engine 的 PKG 文件")
            val b = ByteArray(n); raf.readFully(b); return b.decodeToString()
        }
        val magic = str(32)
        if (!magic.startsWith("PKGV")) throw UserFacingException("不是 Wallpaper Engine 的 PKG 文件（文件头 $magic）")
        val count = i32()
        if (count !in 0..100_000) throw UserFacingException("PKG 文件损坏")
        val entries = (0 until count).map { PkgEntry(str(1024), i32().toLong() and 0xFFFFFFFFL, i32()) }
        Pkg(file, magic, raf.filePointer, entries)
    }

    fun readEntry(pkg: Pkg, e: PkgEntry): ByteArray = RandomAccessFile(pkg.file, "r").use { raf ->
        raf.seek(pkg.dataStart + e.offset)
        ByteArray(e.size).also { raf.readFully(it) }
    }

    // ---------------- TEX ----------------

    private class Reader(val buf: ByteBuffer) {
        fun i32() = buf.int
        fun cstr(max: Int = 64): String {
            val sb = StringBuilder()
            while (buf.hasRemaining()) { val c = buf.get(); if (c.toInt() == 0) break; sb.append(c.toInt().toChar()); if (sb.length > max) break }
            return sb.toString()
        }
        fun bytes(n: Int): ByteArray { if (n < 0 || n > buf.remaining()) throw UserFacingException("TEX 文件损坏"); return ByteArray(n).also { buf.get(it) } }
    }

    fun decodeTex(data: ByteArray): TexOutput {
        val r = Reader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))
        val texv = r.cstr(); val texi = r.cstr()
        if (!texv.startsWith("TEXV") || !texi.startsWith("TEXI")) throw UserFacingException("不是 TEX 贴图")
        val format = r.i32(); r.i32() /* flags */
        r.i32(); r.i32() // texture w/h（2 的幂）
        val imageW = r.i32(); val imageH = r.i32()
        r.i32()
        val container = r.cstr()
        val version = container.removePrefix("TEXB").toIntOrNull() ?: throw UserFacingException("未知的 TEX 容器 $container")
        val imageCount = r.i32()
        var fif = -1
        if (version >= 3) fif = r.i32()
        var mp4 = false
        if (version >= 4) mp4 = r.i32() == 1
        if (imageCount <= 0) throw UserFacingException("TEX 里没有图像")
        val mipCount = r.i32()
        if (mipCount <= 0) throw UserFacingException("TEX 里没有图像")

        // 只取第一张图的第一层（最大分辨率）
        val w: Int; val h: Int; var lz4 = false; var decompressed = 0
        when {
            version == 1 -> { w = r.i32(); h = r.i32() }
            version >= 4 && peekV4(r) -> {
                r.i32(); r.i32(); r.cstr(1 shl 16); r.i32()
                w = r.i32(); h = r.i32(); lz4 = r.i32() == 1; decompressed = r.i32()
            }
            else -> { w = r.i32(); h = r.i32(); lz4 = r.i32() == 1; decompressed = r.i32() }
        }
        var bytes = r.bytes(r.i32())
        if (lz4) bytes = lz4Block(bytes, decompressed)

        if (mp4) return TexOutput.Encoded(bytes, "mp4")
        if (fif != -1) return TexOutput.Encoded(bytes, sniffExt(bytes) ?: FIF_EXT[fif] ?: "bin")

        val pixels = when (format) {
            0 -> rgba8888(bytes, w, h)
            4 -> dxt(bytes, w, h, 5)
            6 -> dxt(bytes, w, h, 3)
            7 -> dxt(bytes, w, h, 1)
            8 -> rg88(bytes, w, h)
            9 -> r8(bytes, w, h)
            else -> throw UserFacingException("暂不支持的贴图格式 $format")
        }
        val full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val cw = imageW.coerceIn(1, w); val ch = imageH.coerceIn(1, h)
        return TexOutput.Image(if (cw == w && ch == h) full else Bitmap.createBitmap(full, 0, 0, cw, ch))
    }

    private fun peekV4(r: Reader): Boolean {
        val p = r.buf.position()
        val ok = r.buf.remaining() >= 8 && r.buf.getInt(p) == 1 && r.buf.getInt(p + 4) == 2
        return ok
    }

    private val FIF_EXT = mapOf(0 to "bmp", 1 to "ico", 2 to "jpg", 13 to "png", 25 to "gif", 17 to "tga", 18 to "tif", 35 to "webp")

    private fun sniffExt(b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
        b.size > 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        b.size > 8 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp" -> "mp4"
        else -> null
    }

    /** LZ4 块格式解压（无帧头）。 */
    fun lz4Block(src: ByteArray, size: Int): ByteArray {
        val dst = ByteArray(size)
        var s = 0; var d = 0
        while (s < src.size) {
            val token = src[s++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) { while (true) { val b = src[s++].toInt() and 0xFF; lit += b; if (b != 255) break } }
            System.arraycopy(src, s, dst, d, lit); s += lit; d += lit
            if (s >= src.size) break
            val off = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8); s += 2
            var len = token and 15
            if (len == 15) { while (true) { val b = src[s++].toInt() and 0xFF; len += b; if (b != 255) break } }
            len += 4
            var from = d - off
            if (off <= 0 || from < 0) throw UserFacingException("LZ4 数据损坏")
            repeat(len) { dst[d++] = dst[from++] }
        }
        return dst
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun rgba8888(b: ByteArray, w: Int, h: Int) = IntArray(w * h) { i ->
        val p = i * 4
        if (p + 3 >= b.size) 0 else argb(b[p + 3].toInt() and 0xFF, b[p].toInt() and 0xFF, b[p + 1].toInt() and 0xFF, b[p + 2].toInt() and 0xFF)
    }

    private fun rg88(b: ByteArray, w: Int, h: Int) = IntArray(w * h) { i ->
        val p = i * 2
        if (p + 1 >= b.size) 0 else { val l = b[p].toInt() and 0xFF; argb(b[p + 1].toInt() and 0xFF, l, l, l) }
    }

    private fun r8(b: ByteArray, w: Int, h: Int) = IntArray(w * h) { i ->
        if (i >= b.size) 0 else { val l = b[i].toInt() and 0xFF; argb(255, l, l, l) }
    }

    /** DXT1 / DXT3 / DXT5（BC1/2/3）解码。 */
    private fun dxt(src: ByteArray, w: Int, h: Int, kind: Int): IntArray {
        val out = IntArray(w * h)
        val blockSize = if (kind == 1) 8 else 16
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        val colors = IntArray(4)
        val alphas = IntArray(16)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var p = (by * bw + bx) * blockSize
            if (p + blockSize > src.size) continue
            when (kind) {
                3 -> {
                    for (i in 0 until 8) {
                        val v = src[p + i].toInt() and 0xFF
                        alphas[i * 2] = (v and 0x0F) * 17; alphas[i * 2 + 1] = (v ushr 4) * 17
                    }
                    p += 8
                }
                5 -> {
                    val a0 = src[p].toInt() and 0xFF; val a1 = src[p + 1].toInt() and 0xFF
                    var bits = 0L
                    for (i in 0 until 6) bits = bits or ((src[p + 2 + i].toLong() and 0xFF) shl (8 * i))
                    val table = IntArray(8)
                    table[0] = a0; table[1] = a1
                    if (a0 > a1) for (i in 1..6) table[i + 1] = ((7 - i) * a0 + i * a1) / 7
                    else { for (i in 1..4) table[i + 1] = ((5 - i) * a0 + i * a1) / 5; table[6] = 0; table[7] = 255 }
                    for (i in 0 until 16) alphas[i] = table[((bits ushr (3 * i)) and 7).toInt()]
                    p += 8
                }
                else -> alphas.fill(255)
            }
            val c0 = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
            val c1 = (src[p + 2].toInt() and 0xFF) or ((src[p + 3].toInt() and 0xFF) shl 8)
            fun rgb(c: Int): IntArray = intArrayOf(((c ushr 11) and 31) * 255 / 31, ((c ushr 5) and 63) * 255 / 63, (c and 31) * 255 / 31)
            val r0 = rgb(c0); val r1 = rgb(c1)
            colors[0] = argb(255, r0[0], r0[1], r0[2]); colors[1] = argb(255, r1[0], r1[1], r1[2])
            if (c0 > c1 || kind != 1) {
                colors[2] = argb(255, (2 * r0[0] + r1[0]) / 3, (2 * r0[1] + r1[1]) / 3, (2 * r0[2] + r1[2]) / 3)
                colors[3] = argb(255, (r0[0] + 2 * r1[0]) / 3, (r0[1] + 2 * r1[1]) / 3, (r0[2] + 2 * r1[2]) / 3)
            } else {
                colors[2] = argb(255, (r0[0] + r1[0]) / 2, (r0[1] + r1[1]) / 2, (r0[2] + r1[2]) / 2)
                colors[3] = 0
            }
            val idx = (src[p + 4].toLong() and 0xFF) or ((src[p + 5].toLong() and 0xFF) shl 8) or
                ((src[p + 6].toLong() and 0xFF) shl 16) or ((src[p + 7].toLong() and 0xFF) shl 24)
            for (py in 0 until 4) for (px in 0 until 4) {
                val x = bx * 4 + px; val y = by * 4 + py
                if (x >= w || y >= h) continue
                val i = py * 4 + px
                val c = colors[((idx ushr (2 * i)) and 3).toInt()]
                out[y * w + x] = if (kind == 1) c else (c and 0x00FFFFFF) or (alphas[i] shl 24)
            }
        }
        return out
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
}
