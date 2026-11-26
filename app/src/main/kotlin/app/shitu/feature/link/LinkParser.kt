/*
 * 链接解析。
 *
 * 抖音（slidesinfo 接口、playwm→play 换无水印地址）与小红书（__INITIAL_STATE__、
 * ci.xiaohongshu.com 原图、实况 stream.h264）的思路参考 hiyufan/shizhen（MIT，commit a893b6e）
 * src/qiuyuantu/parser/douyin.py 与 redbook.py，用 Kotlin 重写。
 * 其他平台交给 yt-dlp（YtDlpManager），网页兜底取 og:image / og:video。
 */
package app.shitu.feature.link

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
import org.jsoup.Jsoup

@Serializable
enum class LinkMediaType { VIDEO, IMAGE, LIVE, AUDIO, UGOIRA }

@Serializable
data class LinkMedia(
    val type: LinkMediaType,
    val url: String,
    val preview: String = "",
    val label: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** 实况图里配对的视频。 */
    val liveVideo: String? = null,
    /** true 表示这条要交给 yt-dlp 下载（url 是原始页面链接）。 */
    val viaYtDlp: Boolean = false,
    val ytFormat: String? = null,
    /** Pixiv 动图：下载时把帧包合成 GIF。 */
    val ugoira: UgoiraInfo? = null,
)

@Serializable
data class LinkResult(
    val platform: String,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val media: List<LinkMedia>,
    val sourceUrl: String,
    val note: String = "",
)

/** 由 yt-dlp 模块实现：读取视频信息（不下载）。 */
fun interface YtDlpInfoProvider {
    suspend fun info(url: String): LinkResult
}

object LinkParser {
    private val URL_RE = Regex("https?://[\\w.-]+(?::\\d+)?(?:/[\\w\\-./?%&=+#~:@!,;*'()]*)?")

    fun extractUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', '，', '。', ')', '）')

    fun platformOf(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host.endsWith("douyin.com") || host.endsWith("iesdouyin.com") -> "抖音"
            host.endsWith("xiaohongshu.com") || host.endsWith("xhslink.com") -> "小红书"
            host.endsWith("kuaishou.com") || host.endsWith("chenzhongtech.com") -> "快手"
            host.endsWith("bilibili.com") || host.endsWith("b23.tv") -> "哔哩哔哩"
            host.endsWith("weibo.com") || host.endsWith("weibo.cn") -> "微博"
            host.endsWith("youtube.com") || host.endsWith("youtu.be") -> "YouTube"
            host.endsWith("x.com") || host.endsWith("twitter.com") -> "X"
            host.endsWith("instagram.com") -> "Instagram"
            host.endsWith("tiktok.com") -> "TikTok"
            host.endsWith("threads.net") || host.endsWith("threads.com") -> "Threads"
            host == "mp.weixin.qq.com" -> "公众号"
            host == "t.me" -> "Telegram"
            host.endsWith("pixiv.net") -> "Pixiv"
            host.endsWith("pinterest.com") || host.contains(".pinterest.") || host == "pin.it" -> "Pinterest"
            else -> host.removePrefix("www.").ifBlank { "网页" }
        }
    }

    suspend fun parse(
        text: String,
        ytDlp: YtDlpInfoProvider?,
        cookieFor: (String) -> String?,
    ): LinkResult {
        val url = extractUrl(text) ?: throw UserFacingException("没找到链接，把整段分享文字贴进来就行")
        val platform = platformOf(url)
        return when (platform) {
            "抖音" -> Douyin.parse(url, cookieFor("douyin.com"))
            "小红书" -> Xhs.parse(url, cookieFor("xiaohongshu.com"))
            "公众号" -> OpenGraph.parse(url, platform, articleImages = true)
            "Pixiv" -> Pixiv.parse(url, cookieFor("pixiv.net"))
            "Pinterest" -> runCatching { Pinterest.parse(url) }.getOrElse { e ->
                ytDlp?.let { runCatching { it.info(url) }.getOrNull() } ?: throw e
            }
            else -> {
                val viaYt = ytDlp?.let { runCatching { it.info(url) } }
                viaYt?.getOrNull() ?: runCatching { OpenGraph.parse(url, platform) }.getOrElse {
                    throw viaYt?.exceptionOrNull() ?: it
                }
            }
        }
    }

    internal suspend fun resolveRedirect(url: String, ua: String = Http.MOBILE_UA): String {
        var current = url
        repeat(5) {
            val req = Request.Builder().url(current).header("User-Agent", ua).build()
            val next = Http.noRedirect.newCall(req).await().use { r ->
                if (r.isRedirect) r.header("Location") else null
            } ?: return current
            current = URI(current).resolve(next).toString()
        }
        return current
    }

    internal suspend fun fetchHtml(url: String, ua: String, cookie: String? = null): Pair<String, String> {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw UserFacingException("页面打不开（HTTP ${r.code}）")
            return r.request.url.toString() to r.body.string()
        }
    }
}

// ---------------- 抖音 ----------------

private object Douyin {
    private val HOSTS = listOf("www.douyin.com", "www.iesdouyin.com")

    suspend fun parse(url: String, cookie: String?): LinkResult {
        val final = if (URI(url).host == "v.douyin.com") LinkParser.resolveRedirect(url) else url
        if (final.contains("live.douyin.com") || final.contains("webcast")) {
            throw UserFacingException("这是直播间链接，请用「抖音直播流」工具")
        }
        val id = videoId(final) ?: throw UserFacingException("这不是单条作品的链接，打开作品后再复制分享链接")
        val isNote = final.contains("/note/") || final.contains("/slides/") || final.contains("is_slides=1")
        val queries = listOf("aweme_ids=%5B$id%5D", "aweme_ids=%5B$id%5D&request_source=200").let { if (isNote) it.reversed() else it }
        var filtered = false
        for (q in queries) {
            for (host in HOSTS) {
                val body = runCatching {
                    val req = Request.Builder().url("https://$host/web/api/v2/aweme/slidesinfo/?$q")
                        .header("User-Agent", Http.DESKTOP_UA)
                        .header("Referer", "https://www.douyin.com/")
                        .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                        .build()
                    Http.client.newCall(req).await().use { it.body.string() }
                }.getOrNull() ?: continue
                val root = runCatching { Http.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
                val aweme = root.arr("aweme_details").firstOrNull() as? JsonObject
                if (aweme != null) return build(aweme, url)
                if (root.arr("filter_list").isNotEmpty()) { filtered = true; break }
            }
        }
        throw UserFacingException(
            if (filtered && cookie.isNullOrBlank()) "抖音不给匿名访问这条内容，先在「我的 → 账号」里登录抖音再试"
            else if (filtered) "抖音限制了这条内容的分享" else "抖音没有返回数据，稍后再试",
        )
    }

    private fun videoId(url: String): String? {
        val uri = URI(url)
        val q = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        (q["modal_id"] ?: q["aweme_id"])?.takeIf { it.all(Char::isDigit) }?.let { return it }
        return uri.path.orEmpty().split('/').lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun urls(o: JsonObject?, key: String = "url_list") = o?.arr(key)?.mapNotNull { it.strOrNull() }.orEmpty()

    private fun build(aweme: JsonObject, source: String): LinkResult {
        val desc = aweme.str("desc").orEmpty()
        val author = aweme.obj("author")?.str("nickname").orEmpty()
        val video = aweme.obj("video")
        val cover = urls(video?.obj("cover")).firstOrNull().orEmpty()
        val headers = mapOf("Referer" to "https://www.douyin.com/")
        val media = mutableListOf<LinkMedia>()
        val images = aweme.arr("images")
        if (images.isNotEmpty()) {
            images.forEachIndexed { i, el ->
                val img = el as? JsonObject ?: return@forEachIndexed
                val list = urls(img)
                val best = list.firstOrNull { !it.contains(".webp") } ?: list.firstOrNull() ?: return@forEachIndexed
                val live = urls(img.obj("video")?.obj("play_addr")).firstOrNull()
                media += LinkMedia(
                    type = if (live != null) LinkMediaType.LIVE else LinkMediaType.IMAGE,
                    url = best, preview = best, label = "第 ${i + 1} 张", headers = headers, liveVideo = live,
                )
            }
        } else {
            val play = video?.obj("play_addr_h264") ?: video?.obj("play_addr")
            val u = urls(play).firstOrNull()?.replace("playwm", "play")
            if (u != null) media += LinkMedia(LinkMediaType.VIDEO, u, cover, "无水印视频", headers)
            if (cover.isNotEmpty()) media += LinkMedia(LinkMediaType.IMAGE, cover, cover, "封面", headers)
        }
        urls(aweme.obj("music")?.obj("play_url")).firstOrNull()?.let {
            media += LinkMedia(LinkMediaType.AUDIO, it, cover, "背景音乐", headers)
        }
        if (media.isEmpty()) throw UserFacingException("这条作品里没有可下载的内容")
        return LinkResult("抖音", desc.ifBlank { "抖音作品" }, author, cover, media, source)
    }
}

// ---------------- 小红书 ----------------

private object Xhs {
    private val STATE = Regex("window\\.__INITIAL_STATE__\\s*=\\s*(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    suspend fun parse(url: String, cookie: String?): LinkResult {
        val (final, html) = LinkParser.fetchHtml(url, Http.DESKTOP_UA, cookie)
        val raw = STATE.find(html)?.groupValues?.get(1)?.trim()
            ?: throw UserFacingException(if (final.contains("login") || final.contains("captcha")) "小红书要求登录，先在「我的 → 账号」里登录小红书" else "小红书页面结构变了，没找到笔记数据")
        val state = Http.json.parseToJsonElement(raw.replace(Regex("\\bundefined\\b"), "null")).jsonObject
        val noteRoot = state.obj("note")
        val id = noteRoot?.str("currentNoteId")
        val data = id?.let { noteRoot.obj("noteDetailMap")?.obj(it)?.obj("note") }
            ?: state.obj("noteData")?.obj("data")?.obj("noteData")
            ?: throw UserFacingException("链接可能已过期，重新在 App 里复制分享链接；若仍失败，先登录小红书")
        val media = mutableListOf<LinkMedia>()
        val headers = mapOf("Referer" to "https://www.xiaohongshu.com/")
        val stream = data.obj("video")?.obj("media")?.obj("stream")
        val h264 = stream?.arr("h264").orEmpty().mapNotNull { it as? JsonObject }
            .sortedByDescending { minOf(it.str("width")?.toIntOrNull() ?: 0, it.str("height")?.toIntOrNull() ?: 0) }
        val images = data.arr("imageList").mapNotNull { it as? JsonObject }
        val cover = images.firstOrNull()?.let { it.str("urlDefault") ?: it.str("url") }.orEmpty()
        val best = h264.firstOrNull()?.str("masterUrl")
        if (best != null) {
            media += LinkMedia(LinkMediaType.VIDEO, best, cover, "无水印视频", headers)
        } else {
            images.forEachIndexed { i, item ->
                val page = item.str("urlDefault") ?: item.str("url") ?: return@forEachIndexed
                val key = item.str("fileId") ?: imageKey(page)
                val original = if (!key.isNullOrBlank()) "https://ci.xiaohongshu.com/$key" else page
                val live = item.obj("stream")?.arr("h264")?.firstOrNull()?.let { (it as? JsonObject)?.str("masterUrl") }
                    ?.takeIf { item.str("livePhoto") == "true" }
                media += LinkMedia(
                    if (live != null) LinkMediaType.LIVE else LinkMediaType.IMAGE,
                    original, page, "第 ${i + 1} 张", headers, liveVideo = live,
                )
            }
        }
        if (media.isEmpty()) throw UserFacingException("这篇笔记里没有可下载的内容")
        val user = data.obj("user")
        return LinkResult(
            "小红书",
            data.str("title").orEmpty().ifBlank { data.str("desc").orEmpty().take(60) }.ifBlank { "小红书笔记" },
            user?.str("nickname") ?: user?.str("nickName").orEmpty(),
            cover, media, url,
        )
    }

    private fun imageKey(page: String): String? {
        val parts = runCatching { URI(page).path }.getOrNull()?.split('/')?.filter { it.isNotEmpty() } ?: return null
        if (parts.size < 3) return null
        return parts.drop(2).joinToString("/").substringBefore('!')
    }
}

// ---------------- 网页兜底 ----------------

private object OpenGraph {
    suspend fun parse(url: String, platform: String, articleImages: Boolean = false): LinkResult {
        val (final, html) = LinkParser.fetchHtml(url, Http.MOBILE_UA)
        val doc = Jsoup.parse(html, final)
        fun meta(p: String) = doc.selectFirst("meta[property=$p], meta[name=$p]")?.attr("content")?.takeIf { it.isNotBlank() }
        val title = meta("og:title") ?: doc.title()
        val media = mutableListOf<LinkMedia>()
        meta("og:video")?.let { media += LinkMedia(LinkMediaType.VIDEO, it, meta("og:image").orEmpty(), "视频") }
        val cover = meta("og:image") ?: meta("twitter:image")
        cover?.let { media += LinkMedia(LinkMediaType.IMAGE, it, it, "封面") }
        if (articleImages) {
            // 公众号文章：正文图片在 data-src
            doc.select("#js_content img[data-src], img[data-src]").mapNotNull { it.attr("data-src").takeIf { s -> s.startsWith("http") } }
                .distinct().forEachIndexed { i, src ->
                    media += LinkMedia(LinkMediaType.IMAGE, src, src, "图 ${i + 1}", mapOf("Referer" to "https://mp.weixin.qq.com/"))
                }
            Regex("var\\s+msg_cdn_url\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let {
                media.add(0, LinkMedia(LinkMediaType.IMAGE, it, it, "文章封面"))
            }
        }
        if (media.isEmpty()) throw UserFacingException("这个页面里没找到能下载的图片或视频")
        return LinkResult(platform, title.ifBlank { platform }, "", cover.orEmpty(), media.distinctBy { it.url }, url)
    }
}
