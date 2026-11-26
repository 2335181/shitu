package app.shitu.feature.search

import app.shitu.core.Http
import app.shitu.core.getText
import app.shitu.feature.bili.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 各图站的独立适配：识别帖子链接、SauceNAO 字段名、公开 API 补全信息。
 * Gelbooru 的 API 需要账号 Key、Sankaku 的 API 需要登录，这两个只给链接与相似度。
 */
internal enum class Booru(
    val engine: Engine,
    val sauceField: String,
    private val pattern: Regex,
    private val postUrl: (String) -> String,
) {
    DANBOORU(Engine.DANBOORU, "danbooru_id", Regex("danbooru\\.donmai\\.us/(?:posts|post/show)/(\\d+)"), { "https://danbooru.donmai.us/posts/$it" }),
    GELBOORU(Engine.GELBOORU, "gelbooru_id", Regex("gelbooru\\.com/index\\.php\\?.*?\\b(?:id|md5)=(\\w+)"), {
        if (it.all(Char::isDigit)) "https://gelbooru.com/index.php?page=post&s=view&id=$it" else "https://gelbooru.com/index.php?page=post&s=list&md5=$it"
    }),
    YANDERE(Engine.YANDERE, "yandere_id", Regex("yande\\.re/post/show/(\\d+)"), { "https://yande.re/post/show/$it" }),
    KONACHAN(Engine.KONACHAN, "konachan_id", Regex("konachan\\.(?:com|net)/post/show/(\\d+)"), { "https://konachan.com/post/show/$it" }),
    SANKAKU(Engine.SANKAKU, "sankaku_id", Regex("sankakucomplex\\.com/(?:[a-z]{2}/)?posts?/(?:show/)?(\\w+)"), { "https://chan.sankakucomplex.com/posts/$it" }),
    ;

    fun idFrom(url: String): String? = pattern.find(url.replace("&amp;", "&"))?.groupValues?.get(1)

    suspend fun hit(id: String, similarity: Float, thumb: String, gelAuth: String = ""): SearchHit {
        val base = SearchHit(title = if (id.all(Char::isDigit)) "${engine.label} #$id" else engine.label, url = postUrl(id), thumbnail = thumb, similarity = similarity)
        if (this == GELBOORU) return runCatching { gelbooru(id, base, gelAuth) }.getOrDefault(base)
        val n = id.toLongOrNull() ?: return base
        return runCatching { enrich(n, base) }.getOrDefault(base)
    }

    private suspend fun enrich(id: Long, base: SearchHit): SearchHit = when (this) {
        // Danbooru 拒绝浏览器 UA（会给 Cloudflare 验证页），用应用自己的 UA
        DANBOORU -> {
            val o = Http.json.parseToJsonElement(
                Http.client.getText("https://danbooru.donmai.us/posts/$id.json", mapOf("User-Agent" to "shitu/0.4 (Android)")),
            ) as JsonObject
            val artist = o.str("tag_string_artist").orEmpty().tags(2)
            val chars = o.str("tag_string_character").orEmpty().tags(2)
            val copy = o.str("tag_string_copyright").orEmpty().tags(1)
            base.copy(
                title = listOf(chars, copy).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { base.title },
                thumbnail = o.str("preview_file_url") ?: base.thumbnail,
                detail = listOfNotNull(
                    artist.takeIf { it.isNotBlank() }?.let { "画师 $it" },
                    size(o), rating(o.str("rating")),
                ).joinToString(" · "),
            )
        }
        YANDERE, KONACHAN -> {
            val host = if (this == YANDERE) "yande.re" else "konachan.com"
            val arr = Http.json.parseToJsonElement(Http.client.getText("https://$host/post.json?tags=id:$id")) as JsonArray
            val o = arr.firstOrNull() as? JsonObject ?: return base
            base.copy(
                title = o.str("tags").orEmpty().tags(3).ifBlank { base.title },
                thumbnail = o.str("preview_url") ?: base.thumbnail,
                detail = listOfNotNull(o.str("author")?.let { "上传 $it" }, size(o), rating(o.str("rating"))).joinToString(" · "),
            )
        }
        GELBOORU, SANKAKU -> base
    }

    /** Gelbooru 的 API 需要 api_key + user_id（账号设置页最下方 API Access Credentials）。没填就只给链接。 */
    private suspend fun gelbooru(id: String, base: SearchHit, auth: String): SearchHit {
        val cred = auth.trim().trimStart('&').takeIf { it.contains("api_key=") && it.contains("user_id=") } ?: return base
        val q = if (id.all(Char::isDigit)) "id=$id" else "tags=md5:$id"
        val root = Http.json.parseToJsonElement(
            Http.client.getText("https://gelbooru.com/index.php?page=dapi&s=post&q=index&json=1&limit=1&$q&$cred"),
        ) as JsonObject
        val o = (root["post"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return base
        return base.copy(
            title = o.str("tags").orEmpty().tags(3).ifBlank { base.title },
            url = "https://gelbooru.com/index.php?page=post&s=view&id=" + (o.str("id") ?: id),
            thumbnail = o.str("preview_url") ?: base.thumbnail,
            detail = listOfNotNull(o.str("owner")?.let { "上传 $it" }, size(o), rating(o.str("rating")?.take(1))).joinToString(" · "),
        )
    }

    private fun size(o: JsonObject): String? {
        val w = (o.str("image_width") ?: o.str("width"))?.toIntOrNull() ?: return null
        val h = (o.str("image_height") ?: o.str("height"))?.toIntOrNull() ?: return null
        return "原图 ${w}×$h"
    }

    private fun rating(r: String?) = when (r) {
        "g", "s" -> if (this == DANBOORU && r == "s") "敏感" else "全年龄"
        "q" -> "存疑"
        "e" -> "限制级"
        else -> null
    }

    private fun String.tags(n: Int) = split(' ').filter { it.isNotBlank() }.take(n).joinToString(" ") { it.replace('_', ' ') }
}
