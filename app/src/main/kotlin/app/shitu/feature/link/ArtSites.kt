/*
 * 插画站解析：Pixiv（ajax 接口）与 Pinterest（PinResource 接口）。
 * 接口路径来自公开网页与 yt-dlp 的 Pinterest 提取器（Unlicense）实测。
 */
package app.shitu.feature.link

import android.net.Uri
import app.shitu.core.Http
import app.shitu.core.UserFacingException
import app.shitu.core.await
import app.shitu.feature.bili.arr
import app.shitu.feature.bili.obj
import app.shitu.feature.bili.str
import app.shitu.feature.bili.strOrNull
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

@Serializable
data class UgoiraFrame(val file: String, val delay: Int)

@Serializable
data class UgoiraInfo(val illustId: String, val title: String, val zipUrl: String, val frames: List<UgoiraFrame>, val preview: String = "")

object Pixiv {
    const val REFERER = "https://www.pixiv.net/"
    private val headers = mapOf("Referer" to REFERER)

    fun illustId(text: String): String? {
        Regex("pixiv\\.net/(?:[a-z]{2}/)?artworks/(\\d+)").find(text)?.let { return it.groupValues[1] }
        Regex("illust_id=(\\d+)").find(text)?.let { return it.groupValues[1] }
        Regex("pixiv\\.net/i/(\\d+)").find(text)?.let { return it.groupValues[1] }
        return text.trim().takeIf { it.length in 5..12 && it.all(Char::isDigit) }
    }

    private suspend fun api(path: String, cookie: String?): JsonObject {
        val req = Request.Builder().url("https://www.pixiv.net/ajax$path")
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Referer", REFERER)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        val body = Http.client.newCall(req).await().use { it.body.string() }
        val root = runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw UserFacingException("Pixiv 没有返回数据，检查网络是否能访问 pixiv.net")
        if (root.str("error") == "true") {
            val msg = root.str("message").orEmpty()
            throw UserFacingException(
                if (cookie.isNullOrBlank()) "Pixiv：${msg.ifBlank { "作品不存在或需要登录" }}。R-18 作品先在「我的 → 账号」里登录 Pixiv"
                else "Pixiv：${msg.ifBlank { "作品不存在或已删除" }}",
            )
        }
        return root
    }

    suspend fun ugoira(id: String, cookie: String?): UgoiraInfo {
        val info = api("/illust/$id", cookie).obj("body") ?: throw UserFacingException("Pixiv 作品信息为空")
        if (info.str("illustType") != "2") throw UserFacingException("这个作品不是动图（Ugoira）")
        val meta = api("/illust/$id/ugoira_meta", cookie).obj("body") ?: throw UserFacingException("没拿到动图帧信息")
        val frames = meta.arr("frames").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            UgoiraFrame(o.str("file") ?: return@mapNotNull null, o.str("delay")?.toIntOrNull() ?: 100)
        }
        val zip = meta.str("originalSrc") ?: meta.str("src") ?: throw UserFacingException("没拿到动图帧包地址")
        return UgoiraInfo(id, info.str("illustTitle").orEmpty(), zip, frames, info.obj("urls")?.str("small").orEmpty())
    }

    suspend fun parse(url: String, cookie: String?): LinkResult {
        val id = illustId(url) ?: throw UserFacingException("没认出 Pixiv 作品 ID，贴 pixiv.net/artworks/数字 这样的链接")
        val info = api("/illust/$id", cookie).obj("body") ?: throw UserFacingException("Pixiv 作品信息为空")
        val title = info.str("illustTitle").orEmpty()
        val author = info.str("userName").orEmpty()
        val type = info.str("illustType")
        val media = mutableListOf<LinkMedia>()
        val cover = info.obj("urls")?.let { it.str("regular") ?: it.str("small") }.orEmpty()
        if (type == "2") {
            val u = ugoira(id, cookie)
            media += LinkMedia(
                LinkMediaType.UGOIRA, u.zipUrl, cover, "动图 → GIF（${u.frames.size} 帧）", headers,
                ugoira = u,
            )
            media += LinkMedia(LinkMediaType.IMAGE, u.zipUrl, cover, "原始帧包 ZIP", headers)
        } else {
            val pages = api("/illust/$id/pages", cookie).arr("body")
            pages.forEachIndexed { i, el ->
                val urls = (el as? JsonObject)?.obj("urls") ?: return@forEachIndexed
                val original = urls.str("original") ?: return@forEachIndexed
                media += LinkMedia(LinkMediaType.IMAGE, original, urls.str("small") ?: urls.str("regular").orEmpty(), "第 ${i + 1} 页", headers)
            }
        }
        if (media.isEmpty()) throw UserFacingException("这个作品里没有可下载的图片")
        val tags = buildList {
            if (info.str("xRestrict")?.let { it != "0" } == true) add("R-18")
            add(when (type) { "1" -> "漫画"; "2" -> "动图"; else -> "插画" })
        }
        return LinkResult("Pixiv", title.ifBlank { "Pixiv $id" }, author, cover, media, "https://www.pixiv.net/artworks/$id", note = tags.joinToString(" "))
    }
}

object Pinterest {
    suspend fun parse(url: String): LinkResult {
        val final = if (URI(url).host.orEmpty().endsWith("pin.it")) LinkParser.resolveRedirect(url, Http.DESKTOP_UA) else url
        val path = URI(final).path.orEmpty()
        val seg = Regex("/pin/([^/]+)").find(path)?.groupValues?.get(1)
            ?: throw UserFacingException("这不是 Pin 的链接，打开单个 Pin 后再复制链接")
        val id = seg.substringAfterLast("--").takeIf { it.all(Char::isDigit) } ?: seg.filter(Char::isDigit)
        if (id.isBlank()) throw UserFacingException("没认出 Pin 的 ID")
        val data = "{\"options\":{\"id\":\"$id\",\"field_set_key\":\"unauth_react_main_pin\"}}"
        val req = Request.Builder()
            .url("https://www.pinterest.com/resource/PinResource/get/?data=" + Uri.encode(data))
            .header("User-Agent", Http.DESKTOP_UA)
            .header("X-Pinterest-PWS-Handler", "www/[username].js")
            .header("Accept", "application/json")
            .build()
        val body = Http.client.newCall(req).await().use { it.body.string() }
        val pin = runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?.obj("resource_response")?.obj("data")
            ?: throw UserFacingException("Pinterest 没返回这个 Pin，可能已删除或需要登录")
        val media = mutableListOf<LinkMedia>()
        val orig = pin.obj("images")?.obj("orig")?.str("url")
        val preview = pin.obj("images")?.let { it.obj("474x") ?: it.obj("236x") }?.str("url") ?: orig.orEmpty()
        bestVideo(pin.obj("videos"))?.let { media += LinkMedia(LinkMediaType.VIDEO, it, preview, "视频") }
        // 多页 Idea Pin
        pin.obj("story_pin_data")?.arr("pages")?.forEachIndexed { i, page ->
            (page as? JsonObject)?.arr("blocks")?.forEach { b ->
                val block = b as? JsonObject ?: return@forEach
                bestVideo(block.obj("video"))?.let { v ->
                    media += LinkMedia(LinkMediaType.VIDEO, v, preview, "第 ${i + 1} 页 · 视频")
                }
                val img = block.obj("image")?.obj("images")
                (img?.obj("originals") ?: img?.obj("orig"))?.str("url")?.let { u ->
                    media += LinkMedia(LinkMediaType.IMAGE, u, img?.obj("736x")?.str("url") ?: u, "第 ${i + 1} 页")
                }
            }
        }
        // 轮播
        pin.obj("carousel_data")?.arr("carousel_slots")?.forEachIndexed { i, s ->
            val imgs = (s as? JsonObject)?.obj("images")
            val u = (imgs?.obj("orig") ?: imgs?.obj("originals") ?: imgs?.obj("736x"))?.str("url")
            if (u != null) media += LinkMedia(LinkMediaType.IMAGE, u.replace("/736x/", "/originals/"), imgs?.obj("236x")?.str("url") ?: u, "第 ${i + 1} 张")
        }
        if (orig != null && media.none { it.type == LinkMediaType.IMAGE }) {
            media += LinkMedia(LinkMediaType.IMAGE, orig, preview, "原图")
        }
        if (media.isEmpty()) throw UserFacingException("这个 Pin 里没有可下载的图片或视频")
        val title = pin.str("title")?.takeIf { it.isNotBlank() } ?: pin.str("grid_title")?.takeIf { it.isNotBlank() }
            ?: pin.str("description")?.take(60)?.takeIf { it.isNotBlank() } ?: "Pinterest Pin"
        val author = pin.obj("origin_pinner")?.str("full_name") ?: pin.obj("pinner")?.str("full_name") ?: pin.obj("native_creator")?.str("full_name")
        return LinkResult("Pinterest", title, author.orEmpty(), preview, media.distinctBy { it.url }, "https://www.pinterest.com/pin/$id/")
    }

    private fun bestVideo(videos: JsonObject?): String? {
        val list = videos?.obj("video_list") ?: return null
        val order = listOf("V_EXP7", "V_720P", "V_EXP6", "V_EXP5", "V_EXP4", "V_480P", "V_EXP3", "V_360P")
        order.forEach { k -> list.obj(k)?.str("url")?.takeIf { it.endsWith(".mp4") || it.contains(".mp4?") }?.let { return it } }
        return list.values.mapNotNull { (it as? JsonObject)?.str("url") }.firstOrNull { it.contains(".mp4") }
    }

    @Suppress("unused")
    private fun JsonObject.firstUrl() = values.firstNotNullOfOrNull { it.strOrNull() }
}
