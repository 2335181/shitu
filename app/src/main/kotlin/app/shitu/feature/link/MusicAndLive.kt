/*
 * 音乐封面与抖音直播流。
 *
 * 网易云公开详情接口、抖音直播进房接口（webcast/room/web/enter + ttwid 访客标识）
 * 参考 hiyufan/shizhen（MIT，commit a893b6e）src/qiuyuantu/parser/music.py 与 douyin_live.py，
 * 用 Kotlin 重写。Apple Music 走页面 og:image 并改写为原始尺寸。
 */
package app.shitu.feature.link

import android.net.Uri
import app.shitu.core.Http
import app.shitu.core.UserFacingException
import app.shitu.core.await
import app.shitu.core.getText
import app.shitu.feature.bili.arr
import app.shitu.feature.bili.obj
import app.shitu.feature.bili.str
import app.shitu.feature.bili.strOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import org.jsoup.Jsoup

object MusicCover {
    suspend fun parse(text: String): LinkResult {
        var url = LinkParser.extractUrl(text) ?: throw UserFacingException("没找到链接")
        val host0 = Uri.parse(url).host.orEmpty()
        if (host0 == "163cn.tv" || host0.endsWith("c.y.qq.com") || host0 == "url.cn") url = LinkParser.resolveRedirect(url, Http.DESKTOP_UA)
        val host = Uri.parse(url).host.orEmpty()
        return when {
            host.endsWith("music.163.com") -> netease(url)
            host.endsWith("y.qq.com") -> qq(url)
            host == "music.apple.com" -> apple(url)
            else -> throw UserFacingException("目前支持网易云、QQ 音乐、Apple Music 的歌曲 / 专辑 / 歌单链接")
        }
    }

    private suspend fun netease(url: String): LinkResult {
        val uri = Uri.parse(url)
        val target = uri.fragment?.takeIf { it.startsWith("/") }?.let { Uri.parse("https://x$it") } ?: uri
        val m = Regex("/(?:m/)?(song|album|playlist)(?:/(\\d+))?/?").find(target.path.orEmpty())
            ?: throw UserFacingException("请用网易云的歌曲、专辑或歌单链接")
        val kind = m.groupValues[1]
        val id = m.groupValues[2].ifBlank { target.getQueryParameter("id").orEmpty() }
        if (id.isBlank() || !id.all(Char::isDigit)) throw UserFacingException("链接里没有作品 ID")
        val path = when (kind) {
            "song" -> "/api/song/detail?ids=%5B$id%5D"
            "album" -> "/api/v1/album/$id"
            else -> "/api/v6/playlist/detail?id=$id&n=0"
        }
        val root = Http.json.parseToJsonElement(
            Http.client.getText("https://music.163.com$path", mapOf("Referer" to "https://music.163.com/")),
        ).jsonObject
        if (root.str("code") != "200") throw UserFacingException("网易云没有返回作品信息")
        val detail = if (kind == "song") root.arr("songs").firstOrNull() as? JsonObject else root.obj(kind)
        detail ?: throw UserFacingException("作品不存在或不公开")
        val cover = when (kind) {
            "song" -> detail.obj("album")?.str("picUrl")
            "playlist" -> detail.str("coverImgUrl")
            else -> detail.str("picUrl")
        }?.substringBefore('?')?.replace("http://", "https://") ?: throw UserFacingException("没有封面")
        val artist = (detail.arr("artists").mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(" / "))
            .ifBlank { detail.obj("creator")?.str("nickname").orEmpty() }
        return LinkResult("网易云音乐", detail.str("name").orEmpty(), artist, cover, listOf(LinkMedia(LinkMediaType.IMAGE, cover, "$cover?param=400y400", "原图封面")), url)
    }

    private suspend fun qq(url: String): LinkResult {
        // 页面里的 __INITIAL_DATA__ 带专辑 mid；封面地址可按 mid 拼出 800 像素版
        val (_, html) = LinkParser.fetchHtml(url, Http.DESKTOP_UA)
        val mid = Regex("\"albumMid\"\\s*:\\s*\"(\\w+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"album\"\\s*:\\s*\\{[^}]*?\"mid\"\\s*:\\s*\"(\\w+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("/albumDetail/(\\w+)").find(url)?.groupValues?.get(1)
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: doc.title()
        val cover = if (mid != null) "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg"
        else doc.selectFirst("meta[property=og:image]")?.attr("content")?.replace(Regex("R\\d+x\\d+"), "R800x800")
            ?: throw UserFacingException("没找到 QQ 音乐封面")
        return LinkResult("QQ 音乐", title, "", cover, listOf(LinkMedia(LinkMediaType.IMAGE, cover, cover, "800×800 封面")), url)
    }

    private suspend fun apple(url: String): LinkResult {
        val (_, html) = LinkParser.fetchHtml(url, Http.DESKTOP_UA)
        val doc = Jsoup.parse(html)
        val og = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: throw UserFacingException("Apple Music 页面没有封面")
        // mzstatic 地址末段是尺寸模板，换成很大的数取原图
        val full = og.replace(Regex("/\\d+x\\d+[a-z]*(-\\d+)?\\.(jpg|png|webp)$"), "/100000x100000-999.jpg")
        val media = mutableListOf(LinkMedia(LinkMediaType.IMAGE, full, og, "原图封面"))
        Regex("\"video\"\\s*:\\s*\"(https://[^\"]+\\.m3u8)\"").findAll(html).map { it.groupValues[1] }.distinct().take(2).forEachIndexed { i, v ->
            media += LinkMedia(LinkMediaType.VIDEO, v, og, "动态封面 ${i + 1}", viaYtDlp = true)
        }
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return LinkResult("Apple Music", title, "", og, media, url)
    }
}

@Serializable
data class LiveStream(val label: String, val flv: String, val hls: String)

@Serializable
data class LiveRoom(val title: String, val anchor: String, val cover: String, val streams: List<LiveStream>)

object DouyinLive {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private var ttwid: String? = null

    suspend fun parse(text: String): LiveRoom {
        var url = LinkParser.extractUrl(text) ?: text.trim().takeIf { it.matches(Regex("[\\w.-]+")) }?.let { "https://live.douyin.com/$it" }
            ?: throw UserFacingException("贴直播间链接或房间号")
        if (Uri.parse(url).host == "v.douyin.com") url = LinkParser.resolveRedirect(url)
        val path = Uri.parse(url).path.orEmpty()
        Regex("/(?:webcast/reflow|share/live)/(\\d+)").find(path)?.let { return byRoomId(it.groupValues[1]) }
        val rid = Regex("/(?:live/)?([\\w.-]+)/?$").find(path)?.groupValues?.get(1)
            ?: throw UserFacingException("这不是直播间链接")
        return byWebRid(rid)
    }

    private suspend fun guest(fresh: Boolean): String {
        if (!fresh) ttwid?.let { return it }
        val req = Request.Builder().url("https://live.douyin.com/").head().header("User-Agent", UA).build()
        val id = Http.noRedirect.newCall(req).await().use { r ->
            r.headers("Set-Cookie").firstNotNullOfOrNull { Regex("ttwid=([^;]+)").find(it)?.groupValues?.get(1) }
        } ?: throw UserFacingException("抖音没有发放访客身份")
        ttwid = id
        return id
    }

    private suspend fun byWebRid(rid: String): LiveRoom {
        val params = "aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN&browser_language=zh-CN" +
            "&browser_platform=Win32&browser_name=Chrome&browser_version=130.0.0.0&web_rid=" + Uri.encode(rid)
        var body = ""
        for (fresh in listOf(false, true)) {
            val req = Request.Builder().url("https://live.douyin.com/webcast/room/web/enter/?$params")
                .header("User-Agent", UA).header("Referer", "https://live.douyin.com/$rid")
                .header("Cookie", "ttwid=${guest(fresh)}").build()
            body = Http.client.newCall(req).await().use { it.body.string() }
            if (body.isNotBlank()) break
        }
        if (body.isBlank()) throw UserFacingException("抖音直播接口没有返回数据")
        val data = Http.json.parseToJsonElement(body).jsonObject.obj("data") ?: throw UserFacingException("没有找到这个直播间")
        val room = data.arr("data").firstOrNull() as? JsonObject ?: throw UserFacingException("没有找到这个直播间")
        return build(room, data.obj("user")?.str("nickname").orEmpty())
    }

    private suspend fun byRoomId(id: String): LiveRoom {
        val body = Http.client.getText("https://webcast.amemv.com/webcast/room/reflow/info/?type_id=0&live_id=1&app_id=1128&room_id=$id")
        val room = Http.json.parseToJsonElement(body).jsonObject.obj("data")?.obj("room") ?: throw UserFacingException("没有找到这个直播间")
        return build(room, room.obj("owner")?.str("nickname").orEmpty())
    }

    private fun build(room: JsonObject, anchor: String): LiveRoom {
        if (room.str("status") != "2") throw UserFacingException(if (anchor.isNotBlank()) "$anchor 现在没在直播" else "主播现在没在直播")
        val su = room.obj("stream_url") ?: throw UserFacingException("这个直播间没有提供画面")
        val streams = mutableListOf<LiveStream>()
        val pull = su.obj("live_core_sdk_data")?.obj("pull_data")
        val qualities = pull?.str("stream_data")?.let { runCatching { Http.json.parseToJsonElement(it).jsonObject.obj("data") }.getOrNull() }
        val names = pull?.obj("options")?.arr("qualities").orEmpty().mapNotNull { it as? JsonObject }
            .associate { (it.str("sdk_key") ?: "") to (it.str("name") ?: "") }
        val levels = pull?.obj("options")?.arr("qualities").orEmpty().mapNotNull { it as? JsonObject }
            .associate { (it.str("sdk_key") ?: "") to (it.str("level")?.toIntOrNull() ?: 0) }
        qualities?.entries?.sortedByDescending { levels[it.key] ?: 0 }?.forEach { (key, v) ->
            if (key == "ao") return@forEach
            val main = (v as? JsonObject)?.obj("main") ?: return@forEach
            val flv = main.str("flv").orEmpty().replace("http://", "https://")
            val hls = main.str("hls").orEmpty().replace("http://", "https://")
            if (flv.isNotBlank() || hls.isNotBlank()) streams += LiveStream(names[key]?.ifBlank { null } ?: key, flv, hls)
        }
        if (streams.isEmpty()) {
            val flv = su.obj("flv_pull_url"); val hls = su.obj("hls_pull_url_map")
            (flv?.keys.orEmpty() + hls?.keys.orEmpty()).distinct().forEach { k ->
                streams += LiveStream(k, flv?.str(k).orEmpty(), hls?.str(k).orEmpty())
            }
        }
        if (streams.isEmpty()) throw UserFacingException("这个直播间没有向访客提供画面")
        val cover = room.obj("cover")?.arr("url_list")?.firstOrNull()?.strOrNull().orEmpty()
        return LiveRoom(room.str("title").orEmpty().ifBlank { "$anchor 的直播间" }, anchor, cover, streams)
    }
}

/**
 * B站直播流：room_init 把短号换成真实房间号 → get_info 取标题封面 → getRoomPlayInfo 按清晰度逐个取 FLV / HLS 地址。
 * 未登录时 B站只给到「超清」左右，登录后（账号页登录 B站）可拿原画。地址带时效签名，几小时内有效。
 */
object BiliLive {
    private const val REFERER = "https://live.bilibili.com/"

    fun matches(text: String) = text.contains("live.bilibili.com") || (text.contains("b23.tv") && text.contains("直播"))

    suspend fun parse(text: String, cookie: String? = null): LiveRoom {
        var url = LinkParser.extractUrl(text) ?: text.trim().takeIf { it.all(Char::isDigit) }?.let { "https://live.bilibili.com/$it" }
            ?: throw UserFacingException("贴 B站直播间链接或房间号")
        if (Uri.parse(url).host?.endsWith("b23.tv") == true) url = LinkParser.resolveRedirect(url)
        val short = Regex("live\\.bilibili\\.com/(?:h5/)?(\\d+)").find(url)?.groupValues?.get(1)
            ?: throw UserFacingException("这不是 B站直播间链接")
        val headers = buildMap { put("Referer", REFERER); put("User-Agent", Http.DESKTOP_UA); cookie?.let { put("Cookie", it) } }
        val init = Http.json.parseToJsonElement(Http.client.getText("https://api.live.bilibili.com/room/v1/Room/room_init?id=$short", headers)).jsonObject
        val initData = init.obj("data") ?: throw UserFacingException("没有找到这个直播间")
        val roomId = initData.str("room_id") ?: throw UserFacingException("没有找到这个直播间")
        val info = Http.json.parseToJsonElement(Http.client.getText("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId", headers)).jsonObject.obj("data")
        val anchor = info?.str("uid")?.let { uid ->
            runCatching {
                Http.json.parseToJsonElement(Http.client.getText("https://api.live.bilibili.com/live_user/v1/Master/info?uid=$uid", headers))
                    .jsonObject.obj("data")?.obj("info")?.str("uname")
            }.getOrNull()
        }.orEmpty()
        if (initData.str("live_status") != "1") throw UserFacingException(if (anchor.isNotBlank()) "$anchor 现在没在直播" else "主播现在没在直播")

        suspend fun play(qn: Int): JsonObject? = Http.json.parseToJsonElement(
            Http.client.getText(
                "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId&protocol=0,1&format=0,1,2&codec=0&qn=$qn&platform=web&ptype=8",
                headers,
            ),
        ).jsonObject.obj("data")?.obj("playurl_info")?.obj("playurl")

        val first = play(10000) ?: throw UserFacingException("这个直播间没有向访客提供画面")
        val names = first.arr("g_qn_desc").mapNotNull { it as? JsonObject }.associate { (it.str("qn")?.toIntOrNull() ?: 0) to it.str("desc").orEmpty() }

        fun pick(p: JsonObject, qn: Int, proto: String, fmt: String): String = p.arr("stream").mapNotNull { it as? JsonObject }
            .filter { it.str("protocol_name") == proto }
            .flatMap { it.arr("format").mapNotNull { f -> f as? JsonObject } }
            .filter { it.str("format_name") == fmt }
            .flatMap { it.arr("codec").mapNotNull { c -> c as? JsonObject } }
            .firstOrNull { it.str("codec_name") == "avc" && it.str("current_qn")?.toIntOrNull() == qn }
            ?.let { c ->
                val u = c.arr("url_info").firstOrNull() as? JsonObject ?: return@let null
                u.str("host").orEmpty() + c.str("base_url").orEmpty() + u.str("extra").orEmpty()
            }.orEmpty()

        val accept = first.arr("stream").mapNotNull { it as? JsonObject }
            .flatMap { it.arr("format").mapNotNull { f -> f as? JsonObject } }
            .flatMap { it.arr("codec").mapNotNull { c -> c as? JsonObject } }
            .flatMap { c -> c.arr("accept_qn").mapNotNull { it.strOrNull()?.toIntOrNull() } }
            .distinct().sortedDescending()
        val streams = mutableListOf<LiveStream>()
        for (qn in accept) {
            val p = if (qn == 10000) first else runCatching { play(qn) }.getOrNull() ?: continue
            val flv = pick(p, qn, "http_stream", "flv")
            val hls = pick(p, qn, "http_hls", "ts").ifBlank { pick(p, qn, "http_hls", "fmp4") }
            if (flv.isNotBlank() || hls.isNotBlank()) streams += LiveStream(names[qn] ?: "qn$qn", flv, hls)
        }
        if (streams.isEmpty()) throw UserFacingException("这个直播间没有向访客提供画面")
        return LiveRoom(
            info?.str("title").orEmpty().ifBlank { "$anchor 的直播间" }, anchor,
            info?.str("user_cover").orEmpty().ifBlank { info?.str("keyframe").orEmpty() }, streams,
        )
    }
}
