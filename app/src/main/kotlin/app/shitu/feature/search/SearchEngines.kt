package app.shitu.feature.search

import android.net.Uri
import app.shitu.core.Http
import app.shitu.core.UserFacingException
import app.shitu.core.DevLog
import app.shitu.core.await
import app.shitu.core.friendlyMessage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement
import app.shitu.feature.bili.arr
import app.shitu.feature.bili.obj
import app.shitu.feature.bili.str
import app.shitu.feature.bili.strOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

enum class EngineType { API, WEB }

enum class Engine(val id: String, val label: String, val type: EngineType, val blurb: String) {
    SAUCENAO("saucenao", "SauceNAO", EngineType.API, "插画、漫画、Pixiv 出处"),
    TRACEMOE("tracemoe", "trace.moe", EngineType.API, "动画截图找番剧和集数"),
    IQDB("iqdb", "IQDB", EngineType.API, "二次元图站：Danbooru、Gelbooru 等"),
    GOOGLE("google", "Google Lens", EngineType.WEB, "最全的通用识图"),
    YANDEX("yandex", "Yandex", EngineType.WEB, "找相似图、大图很准"),
    BING("bing", "Bing", EngineType.WEB, "通用识图"),
    ASCII2D("ascii2d", "ascii2d", EngineType.WEB, "二次元插画，色彩与特征检索"),
    BAIDU("baidu", "百度识图", EngineType.WEB, "中文网络来源"),
    SOGOU("sogou", "搜狗识图", EngineType.WEB, "中文网络来源"),
    TINEYE("tineye", "TinEye", EngineType.WEB, "找最早出处与改动版本"),
    DANBOORU("danbooru", "Danbooru", EngineType.API, "图站：标签齐全，带作者与角色"),
    GELBOORU("gelbooru", "Gelbooru", EngineType.API, "图站：收录量大"),
    YANDERE("yandere", "Yande.re", EngineType.API, "图站：高清扫图、原画"),
    KONACHAN("konachan", "Konachan", EngineType.API, "图站：壁纸向插画"),
    SANKAKU("sankaku", "Sankaku", EngineType.API, "图站：Sankaku Channel"),
    ;

    /** 不需要代理、在中国大陆网络下也能直接用的引擎。 */
    val worksInMainland: Boolean get() = this in setOf(SAUCENAO, TRACEMOE, IQDB, BAIDU, DANBOORU, GELBOORU, YANDERE, KONACHAN, SANKAKU)


    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class SearchHit(
    val title: String,
    val url: String,
    val thumbnail: String = "",
    /** 0..100 */
    val similarity: Float? = null,
    val detail: String = "",
    /** 同一结果在其他站点的链接（IQDB 一行里常同时列出 Danbooru、Gelbooru…）。 */
    val alt: List<String> = emptyList(),
)

sealed interface EngineResult {
    data object Loading : EngineResult
    data class Hits(val hits: List<SearchHit>) : EngineResult
    data class Web(val url: String) : EngineResult
    data class Failed(val message: String, val fallbackUrl: String? = null) : EngineResult
}

/**
 * 各识图引擎的适配器。接口型返回结果列表；网页型返回要打开的结果页地址。
 * 网页型里只有 Google Lens 与 Yandex 支持直接上传；其余引擎用 Yandex 返回的图片公开地址做「按网址搜图」。
 */
class SearchEngines(private val sauceNaoKey: () -> String, private val gelbooruAuth: () -> String = { "" }) {
    private val jpeg = "image/jpeg".toMediaType()

    /** 给按网址搜图的引擎用：上传一次 Yandex 拿到公开图片地址，多引擎复用。 */
    @Volatile private var hostedFor: Int = 0
    @Volatile private var hosted: Pair<String, String>? = null

    suspend fun run(engine: Engine, image: ByteArray): EngineResult = try {
        when (engine) {
            Engine.SAUCENAO -> sauceNao(image)
            Engine.TRACEMOE -> traceMoe(image)
            Engine.IQDB -> iqdb(image)
            Engine.GOOGLE -> EngineResult.Web(googleLens(image))
            // 注意：Google Lens 必须让浏览器自己去上传（uploadbyurl），否则结果页绑定的是拾图的匿名会话，
            // 用户浏览器里登录了 Google 账号时会显示「图片未找到 / 未与账号关联」。
            Engine.YANDEX -> EngineResult.Web(yandex(image).first)
            Engine.BING -> EngineResult.Web("https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:" + Uri.encode(hostedUrl(image)))
            Engine.ASCII2D -> EngineResult.Web("https://ascii2d.net/search/url/" + Uri.encode(hostedUrl(image)))
            Engine.BAIDU -> EngineResult.Web(baidu(image))
            Engine.SOGOU -> EngineResult.Web("https://pic.sogou.com/ris?flag=1&query=" + Uri.encode(hostedUrl(image)))
            Engine.TINEYE -> EngineResult.Web("https://tineye.com/search?url=" + Uri.encode(hostedUrl(image)))
            Engine.DANBOORU, Engine.GELBOORU, Engine.YANDERE, Engine.KONACHAN, Engine.SANKAKU ->
                booru(Booru.entries.first { it.engine == engine }, image)
        }
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        DevLog.error("识图·${engine.label}", "搜索失败", e)
        val msg = when (e) {
            is UserFacingException -> e.message.orEmpty()
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException, is javax.net.ssl.SSLException ->
                "连不上 ${engine.label}" + if (engine.worksInMainland) "（${e.friendlyMessage()}）" else "：中国大陆网络需要开代理才能用这个引擎"
            else -> e.friendlyMessage()
        }
        EngineResult.Failed(msg, fallbackUrl = HOME[engine])
    }

    /** 解析 JSON；收到网页（被墙、跳转验证页、限流页）时给出能看懂的提示，并把开头写进开发者日志。 */
    private fun parse(body: String, who: String): JsonObject {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) {
            DevLog.warn("识图·$who", "期望 JSON，收到：" + t.take(300).replace(Regex("\\s+"), " "))
            throw UserFacingException("$who 返回的是网页而不是数据：当前网络连不上它（中国大陆需要代理），或被要求人机验证")
        }
        return Http.json.parseToJsonElement(t).let { it as? JsonObject ?: JsonObject(mapOf("items" to it)) }
    }

    private fun multipart(field: String, image: ByteArray, extra: Map<String, String> = emptyMap()) =
        MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            extra.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(field, "image.jpg", image.toRequestBody(jpeg))
        }.build()

    private suspend fun post(url: String, body: okhttp3.RequestBody, noRedirect: Boolean = false): okhttp3.Response {
        val req = Request.Builder().url(url).post(body).header("User-Agent", Http.DESKTOP_UA).build()
        return (if (noRedirect) Http.noRedirect else Http.client).newCall(req).await()
    }

    private suspend fun sauceNao(image: ByteArray): EngineResult {
        val key = sauceNaoKey()
        if (key.isBlank()) {
            return EngineResult.Failed("没有填 SauceNAO API Key，去「我的 → 识图引擎」填写", "https://saucenao.com/user.php?page=search-api")
        }
        val hits = sauceRaw(image, key).take(8).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val h = o.obj("header") ?: return@mapNotNull null
            val d = o.obj("data")
            val link = d?.arr("ext_urls")?.firstOrNull()?.strOrNull() ?: d?.str("source")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            SearchHit(
                title = d?.str("title") ?: d?.str("eng_name") ?: d?.str("source") ?: h.str("index_name").orEmpty(),
                url = link,
                thumbnail = h.str("thumbnail").orEmpty(),
                similarity = h.str("similarity")?.toFloatOrNull(),
                detail = listOfNotNull(
                    d?.str("member_name") ?: d?.str("creator")?.removeSurrounding("[\"", "\"]"),
                    h.str("index_name")?.substringAfter(": ")?.substringBefore(" - "),
                ).joinToString(" · "),
            )
        }
        return EngineResult.Hits(hits)
    }

    /** SauceNAO 原始结果；同一张图只请求一次，SauceNAO 与各图站引擎共用（免费额度 30 秒 4 次）。 */
    private suspend fun sauceRaw(image: ByteArray, key: String): List<JsonElement> = shared("sauce", image) {
        val body = post("https://saucenao.com/search.php?output_type=2&numres=16&db=999&api_key=" + Uri.encode(key), multipart("file", image))
            .use { r -> if (r.code == 429) throw UserFacingException("SauceNAO 额度用完了，稍后再试"); r.body.string() }
        parse(body, "SauceNAO").arr("results")
    }

    private suspend fun traceMoe(image: ByteArray): EngineResult {
        val req = Request.Builder().url("https://api.trace.moe/search?anilistInfo&cutBorders")
            .post(image.toRequestBody(jpeg)).build()
        val body = Http.client.newCall(req).await().use { it.body.string() }
        val root = parse(body, "trace.moe")
        root.str("error")?.takeIf { it.isNotBlank() }?.let { throw UserFacingException(it) }
        val hits = root.arr("result").take(6).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val ani = o.obj("anilist")
            val t = ani?.obj("title")
            val name = t?.str("chinese") ?: t?.str("native") ?: t?.str("romaji") ?: o.str("filename").orEmpty()
            val from = o.str("from")?.toDoubleOrNull() ?: 0.0
            val ep = o.str("episode")
            SearchHit(
                title = name,
                url = "https://anilist.co/anime/" + (ani?.str("id") ?: ""),
                thumbnail = o.str("image").orEmpty(),
                similarity = o.str("similarity")?.toFloatOrNull()?.times(100),
                detail = listOfNotNull(ep?.let { "第 $it 集" }, "%d:%02d".format((from / 60).toInt(), (from % 60).toInt())).joinToString(" · "),
            )
        }
        return EngineResult.Hits(hits)
    }

    private suspend fun iqdb(image: ByteArray): EngineResult = EngineResult.Hits(iqdbHits(image))

    private suspend fun iqdbHits(image: ByteArray): List<SearchHit> = shared("iqdb", image) {
        // iqdb 现在有 Cookie 校验（首次访问 307 种 iqdb_bcc 再跳转），高峰期还会排队几十秒
        if (iqdbCookies.isEmpty()) runCatching {
            iqdbClient.newCall(Request.Builder().url("https://iqdb.org/").header("User-Agent", Http.DESKTOP_UA).build()).await().close()
        }
        val html = iqdbClient.newCall(
            Request.Builder().url("https://iqdb.org/").post(multipart("file", image)).header("User-Agent", Http.DESKTOP_UA).build(),
        ).await().use { it.body.string() }
        if (html.contains("ckatt=1") && !html.contains("similarity")) {
            iqdbCookies.clear()
            throw UserFacingException("IQDB 要求重新校验，再搜一次")
        }
        val doc = Jsoup.parse(html, "https://iqdb.org/")
        val hits = doc.select("#pages > div").drop(1).mapNotNull { div ->
            val rows = div.select("tr")
            if (rows.firstOrNull()?.text()?.contains("No relevant", true) == true) return@mapNotNull null
            val a = div.selectFirst("td.image a") ?: return@mapNotNull null
            val img = div.selectFirst("td.image img")
            val sim = rows.lastOrNull()?.text()?.let { Regex("(\\d+)% similarity").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
            val href = a.absUrl("href").ifBlank { "https:" + a.attr("href") }
            SearchHit(
                title = rows.getOrNull(2)?.text()?.ifBlank { null } ?: href.substringAfter("//").substringBefore('/'),
                url = href,
                thumbnail = img?.absUrl("src").orEmpty(),
                similarity = sim,
                detail = rows.getOrNull(3)?.text().orEmpty(),
                alt = div.select("a[href]").map { it.absUrl("href").ifBlank { "https:" + it.attr("href") } }.filter { it != href }.distinct(),
            )
        }
        if (hits.isEmpty() && !html.contains("No relevant") && !html.contains("Your image")) {
            DevLog.warn("识图·IQDB", "页面结构不对：" + html.take(300).replace(Regex("\\s+"), " "))
            throw UserFacingException("IQDB 返回的页面不对，可能被限流，稍后再试")
        }
        hits
    }

    private suspend fun googleLens(image: ByteArray): String {
        val hostedUrl = runCatching { hostedUrl(image) }.getOrNull()
        if (hostedUrl != null) {
            return "https://lens.google.com/uploadbyurl?url=" + Uri.encode(hostedUrl) + "&hl=zh-CN"
        }
        // 中转失败时退回直接上传（结果页在登录了 Google 的浏览器里可能打不开）
        return post("https://lens.google.com/v3/upload?hl=zh-CN", multipart("encoded_image", image), noRedirect = true).use { r ->
            r.header("Location") ?: throw UserFacingException("Google Lens 上传失败（${r.code}）")
        }
    }

    private suspend fun yandex(image: ByteArray): Pair<String, String> {
        val hash = image.contentHashCode()
        hosted?.takeIf { hostedFor == hash }?.let { return it }
        val url = "https://yandex.com/images/search?rpt=imageview&format=json&request=" +
            Uri.encode("{\"blocks\":[{\"block\":\"b-page_type_search-by-image__link\"}]}")
        val body = post(url, multipart("upfile", image)).use { it.body.string() }
        val params = runCatching { parse(body, "Yandex") }.getOrElse {
            throw UserFacingException("这个引擎要先把图片中转到 Yandex，但当前网络连不上 Yandex（中国大陆需要代理，或被要求验证）。百度识图、IQDB、SauceNAO、trace.moe 和各图站引擎不受影响")
        }.arr("blocks").firstOrNull()
            ?.let { (it as JsonObject).obj("params") } ?: throw UserFacingException("Yandex 上传失败")
        val cbir = params.str("cbirId") ?: throw UserFacingException("Yandex 上传失败")
        val result = "https://yandex.com/images/search?rpt=imageview&cbir_id=" + Uri.encode(cbir) to params.str("originalImageUrl").orEmpty()
        hosted = result
        hostedFor = hash
        return result
    }

    private suspend fun hostedUrl(image: ByteArray): String =
        yandex(image).second.ifBlank { throw UserFacingException("图片中转失败") }

    /** 百度识图自带上传接口，国内直连，不依赖 Yandex 中转。 */
    private suspend fun baidu(image: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "image.jpg", image.toRequestBody(jpeg))
            .addFormDataPart("tn", "pc")
            .addFormDataPart("from", "pc")
            .addFormDataPart("image_source", "PC_UPLOAD_SEARCH_FILE")
            .addFormDataPart("range", "{\"page_from\": \"searchIndex\"}")
            .build()
        val req = Request.Builder().url("https://graph.baidu.com/upload?uptime=" + System.currentTimeMillis()).post(body)
            .header("User-Agent", Http.DESKTOP_UA)
            .header("Referer", "https://graph.baidu.com/pcpage/index?tpl_from=pc")
            .header("Acs-Token", "1")
            .build()
        val text = Http.client.newCall(req).await().use { it.body.string() }
        val url = runCatching { parse(text, "百度识图").obj("data")?.str("url") }.getOrNull()
        if (!url.isNullOrBlank()) return url
        DevLog.warn("识图·百度识图", "上传接口没返回地址：" + text.take(200))
        // 退回按网址搜图
        return "https://graph.baidu.com/details?isfromtusoupc=1&tn=pc&carousel=0&image=" + Uri.encode(hostedUrl(image))
    }

    // ---------- 图站（Booru）适配器 ----------
    // 各图站本身不开放以图搜图（Danbooru 的 IQDB 接口要登录），这里用 IQDB 多站检索 + SauceNAO 结果做召回，
    // 再按帖子编号调用各站公开 API 补全标签、原图尺寸和预览图。同一张图的 IQDB / SauceNAO 只请求一次。

    private suspend fun booru(site: Booru, image: ByteArray): EngineResult = kotlinx.coroutines.coroutineScope {
        val key = sauceNaoKey()
        val fromIqdb = async { runCatching { iqdbHits(image) } }
        val fromSauce = async { if (key.isBlank()) Result.success(emptyList()) else runCatching { sauceRaw(image, key) } }
        val iq = fromIqdb.await()
        val sn = fromSauce.await()
        val found = linkedMapOf<String, Pair<Float, String>>() // id -> (相似度, 缩略图)
        iq.getOrNull().orEmpty().forEach { h ->
            val id = (listOf(h.url) + h.alt).firstNotNullOfOrNull { site.idFrom(it) } ?: return@forEach
            val sim = h.similarity ?: 0f
            if ((found[id]?.first ?: -1f) < sim) found[id] = sim to h.thumbnail
        }
        sn.getOrNull().orEmpty().forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val d = o.obj("data") ?: return@forEach
            val id = d.str(site.sauceField)
                ?: d.arr("ext_urls").firstNotNullOfOrNull { site.idFrom(it.strOrNull().orEmpty()) } ?: return@forEach
            val sim = o.obj("header")?.str("similarity")?.toFloatOrNull() ?: 0f
            if (sim < 55f) return@forEach
            if ((found[id]?.first ?: -1f) < sim) found[id] = sim to o.obj("header")?.str("thumbnail").orEmpty()
        }
        if (found.isEmpty()) {
            val err = iq.exceptionOrNull() ?: sn.exceptionOrNull()
            if (err != null && iq.isFailure && (key.isBlank() || sn.isFailure)) throw err
            return@coroutineScope EngineResult.Hits(emptyList())
        }
        val hits = found.entries.sortedByDescending { it.value.first }.take(8).map { (id, v) ->
            async { site.hit(id, v.first, v.second, gelbooruAuth()) }
        }.map { it.await() }
        EngineResult.Hits(hits)
    }

    private val iqdbCookies = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Cookie>()
    private val iqdbClient = Http.client.newBuilder()
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
        .cookieJar(object : okhttp3.CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                cookies.forEach { c -> iqdbCookies.removeAll { it.name == c.name }; iqdbCookies += c }
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = iqdbCookies.filter { it.matches(url) }
        })
        .build()

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memo = ConcurrentHashMap<String, Deferred<Any?>>()

    /** 同一张图、同一个上游只请求一次，多个引擎共享结果；失败的不缓存。 */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(name: String, image: ByteArray, block: suspend () -> T): T {
        if (memo.size > 24) memo.clear()
        val k = name + ":" + image.size + ":" + image.contentHashCode()
        val d = memo.getOrPut(k) { bg.async { block() } }
        return try {
            d.await() as T
        } catch (e: Throwable) {
            if (e !is kotlinx.coroutines.CancellationException) memo.remove(k, d)
            throw e
        }
    }


    companion object {
        val HOME = mapOf(
            Engine.SAUCENAO to "https://saucenao.com/",
            Engine.TRACEMOE to "https://trace.moe/",
            Engine.IQDB to "https://iqdb.org/",
            Engine.GOOGLE to "https://lens.google.com/",
            Engine.YANDEX to "https://yandex.com/images/",
            Engine.BING to "https://www.bing.com/visualsearch",
            Engine.ASCII2D to "https://ascii2d.net/",
            Engine.BAIDU to "https://graph.baidu.com/pcpage/index?tpl_from=pc",
            Engine.SOGOU to "https://pic.sogou.com/",
            Engine.TINEYE to "https://tineye.com/",
            Engine.DANBOORU to "https://danbooru.donmai.us/",
            Engine.GELBOORU to "https://gelbooru.com/",
            Engine.YANDERE to "https://yande.re/",
            Engine.KONACHAN to "https://konachan.com/",
            Engine.SANKAKU to "https://chan.sankakucomplex.com/",
        )
    }
}
