/*
 * B站收藏集与装扮素材解析。
 *
 * 素材字段与白名单规则参考 hiyufan/shizhen（MIT，commit a893b6e）
 * src/qiuyuantu/parser/bilibili_assets.py，已用 Kotlin 重写并扩展到收藏集的
 * 兑换奖励、表情、头像框、勋章等分类。接口路径来自公开网页实测。
 */
package app.shitu.feature.bili

import android.net.Uri
import app.shitu.core.Http
import app.shitu.core.UserFacingException
import app.shitu.core.getText
import app.shitu.download.MediaKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class AssetCategory(val label: String) {
    CARD_IMAGE("卡片图片"),
    CARD_VIDEO("卡片视频"),
    REWARD("兑换奖励"),
    EMOJI("静态表情"),
    EMOJI_ANIMATED("动态表情"),
    PENDANT("头像框"),
    MEDAL("勋章"),
    COVER("封面"),
    BACKGROUND("装扮背景"),
    ANIMATION("装扮动画"),
    OTHER("其他"),
}

@Serializable
data class AssetVariant(
    val url: String,
    val mirrors: List<String> = emptyList(),
)

@Serializable
data class BiliAsset(
    val key: String,
    val name: String,
    val category: AssetCategory,
    val preview: String,
    /** 无水印版本（服务器原始素材）。 */
    val clean: AssetVariant?,
    /** 有水印版本（服务器提供的下载版）。 */
    val marked: AssetVariant? = null,
    val isVideo: Boolean = false,
) {
    val kind: MediaKind
        get() = when {
            isVideo -> MediaKind.VIDEO
            category == AssetCategory.ANIMATION -> MediaKind.OTHER
            else -> MediaKind.IMAGE
        }
}

@Serializable
data class Lottery(val id: Long, val name: String, val image: String = "")

@Serializable
data class BiliAssetPack(
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
    val actId: Long? = null,
    val suitId: Long? = null,
    val lotteries: List<Lottery> = emptyList(),
    val lotteryId: Long? = null,
    val assets: List<BiliAsset> = emptyList(),
)

@Serializable
data class CatalogEntry(
    val id: Long,
    val name: String,
    val cover: String = "",
    val isSuit: Boolean = false,
    val subtitle: String = "",
    val lotteryId: Long? = null,
)

/** 用户输入可能是：活动名、act_id、装扮 ID、详情页链接。 */
sealed interface BiliInput {
    data class Collection(val actId: Long, val lotteryId: Long?) : BiliInput
    data class Suit(val id: Long) : BiliInput
    data class Keyword(val text: String) : BiliInput
}

object BiliGarbApi {
    private const val API = "https://api.bilibili.com"
    private val headers = mapOf("Referer" to "https://www.bilibili.com/", "Origin" to "https://www.bilibili.com")
    private val URL_IN_TEXT = Regex("https?://[\\w.-]+(?:/[\\w\\-./?%&=+#~:@!,;]*)?")

    fun parseInput(raw: String, preferSuit: Boolean): BiliInput {
        val text = raw.trim()
        URL_IN_TEXT.find(text)?.value?.let { link ->
            val uri = Uri.parse(link)
            val path = uri.path.orEmpty().trimEnd('/')
            val act = uri.getQueryParameter("act_id")?.toLongOrNull()
            if (act != null) return BiliInput.Collection(act, uri.getQueryParameter("lottery_id")?.toLongOrNull())
            if (path.endsWith("/suit/detail")) {
                uri.getQueryParameter("id")?.toLongOrNull()?.let { return BiliInput.Suit(it) }
            }
        }
        text.toLongOrNull()?.let { return if (preferSuit) BiliInput.Suit(it) else BiliInput.Collection(it, null) }
        return BiliInput.Keyword(text)
    }

    private suspend fun get(path: String): JsonObject {
        val body = Http.client.getText(API + path, headers)
        val root = Http.json.parseToJsonElement(body).jsonObject
        val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
        if (code != 0) {
            val msg = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            throw UserFacingException(if (code == -412) "请求被 B站拦截（412），稍后再试" else "B站返回错误：$msg（$code）")
        }
        return root["data"] as? JsonObject ?: throw UserFacingException("B站没有返回内容，可能 ID 不存在")
    }

    suspend fun search(keyword: String): List<CatalogEntry> {
        val data = get("/x/garb/v2/mall/home/search?key_word=" + Uri.encode(keyword))
        return data.arr("list").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val props = o.obj("properties")
            val type = props?.str("type")
            val name = o.str("name") ?: return@mapNotNull null
            if (type == "dlc_act") {
                val act = props.str("dlc_act_id")?.toLongOrNull() ?: return@mapNotNull null
                CatalogEntry(
                    id = act, name = name, cover = https(props.str("image_cover")),
                    subtitle = o.str("total_count_desc").orEmpty(),
                    lotteryId = props.str("dlc_lottery_id")?.toLongOrNull(),
                )
            } else {
                val id = o.long("item_id") ?: return@mapNotNull null
                CatalogEntry(
                    id = id, name = name, isSuit = true,
                    cover = https(props?.str("image_cover")),
                    subtitle = o.str("sale_count_desc").orEmpty(),
                )
            }
        }
    }

    suspend fun recent(): List<CatalogEntry> {
        val data = get("/x/vas/dlc_act/act/list?scene=1&site=0")
        return data.arr("list").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            CatalogEntry(
                id = o.long("act_id") ?: return@mapNotNull null,
                name = o.str("act_name").orEmpty(),
                cover = https(o.str("act_pic")),
                subtitle = listOfNotNull(o.str("tag")?.takeIf { it.isNotBlank() }, o.str("act_desc")?.takeIf { it.isNotBlank() }).joinToString(" · "),
                lotteryId = o.long("lottery_id"),
            )
        }
    }

    suspend fun collection(actId: Long, lotteryId: Long?): BiliAssetPack = coroutineScope {
        val basicJob = async { get("/x/vas/dlc_act/act/basic?act_id=$actId") }
        val landingJob = async { runCatching { get("/x/vas/user/dlc/right/landing?act_id=$actId") }.getOrNull() }
        val basic = basicJob.await()
        val lotteries = basic.arr("lottery_list").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Lottery(o.long("lottery_id") ?: return@mapNotNull null, o.str("lottery_name").orEmpty(), o.str("lottery_image").orEmpty())
        }
        val chosen = lotteryId?.takeIf { id -> lotteries.isEmpty() || lotteries.any { it.id == id } }
            ?: lotteries.firstOrNull()?.id
            ?: throw UserFacingException("这个收藏集没有卡池")
        val detail = get("/x/vas/dlc_act/lottery_home_detail?act_id=$actId&lottery_id=$chosen")
        val returnedId = detail.long("lottery_id")
        if (returnedId != null && returnedId != chosen) throw UserFacingException("返回的卡池与请求不一致")

        val out = AssetCollector()
        // 卡片：图片与视频，各有无水印 / 有水印两版
        detail.arr("item_list").forEach { el ->
            val card = (el as? JsonObject)?.obj("card_info") ?: return@forEach
            out.card(card)
        }
        // 兑换奖励
        val collect = detail.obj("collect_list")
        (collect?.arr("collect_infos").orEmpty() + collect?.arr("collect_chain").orEmpty()).forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o.str("redeem_item_name").orEmpty().ifBlank { o.str("redeem_text").orEmpty() }
            val typeName = o.str("redeem_item_type_name").orEmpty()
            val cat = when {
                name.contains("勋章") -> AssetCategory.MEDAL
                name.contains("头像框") || typeName.contains("头像挂件") -> AssetCategory.PENDANT
                else -> AssetCategory.REWARD
            }
            out.image(name, cat, o.str("redeem_item_image"), marked = o.str("redeem_item_image_download"))
            out.image("$name · 详情图", AssetCategory.REWARD, o.str("redeem_detail_image"))
            o.obj("card_item")?.obj("card_type_info")?.let { out.card(it, AssetCategory.REWARD) }
        }
        // 勋章各等级
        basic.str("collector_medal_info")?.let { raw ->
            runCatching { Http.json.parseToJsonElement(raw).jsonArray }.getOrNull()?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                out.image("收藏家勋章 LV${o.str("level").orEmpty()}", AssetCategory.MEDAL, o.str("image"))
            }
        }
        // 表情、头像框、装扮（权益落地页）
        landingJob.await()?.obj("items")?.let { items -> out.landing(items) }
        if (out.legacyEmojiIds.isNotEmpty()) legacyEmojis(out)
        // 封面与开卡动画
        out.image("活动封面", AssetCategory.COVER, basic.str("act_y_img"))
        out.image("方形封面", AssetCategory.COVER, basic.str("act_square_img"))
        lotteries.forEach { out.image("${it.name} · 卡池封面", AssetCategory.COVER, it.image) }
        basic.obj("animation_info")?.let { anim ->
            listOf("animation_draw_url" to "开卡动画", "animation_entry_url" to "入场动画").forEach { (key, label) ->
                anim.obj(key)?.entries?.forEach { (rank, v) ->
                    out.video("$label · $rank", AssetCategory.OTHER, listOfNotNull(v.strOrNull()), emptyList(), "")
                }
            }
        }

        BiliAssetPack(
            title = basic.str("act_title").orEmpty().ifBlank { detail.str("name").orEmpty() },
            subtitle = lotteries.firstOrNull { it.id == chosen }?.name.orEmpty(),
            cover = basic.str("act_y_img").orEmpty(),
            actId = actId,
            lotteries = lotteries,
            lotteryId = chosen,
            assets = out.result(),
        )
    }

    private val emojiCache = java.util.concurrent.ConcurrentHashMap<Long, JsonObject>()

    /** 老式表情包：按 item_id 逐个取详情（并发 6 个，结果缓存在进程内）。 */
    private suspend fun legacyEmojis(out: AssetCollector) = coroutineScope {
        val ids = out.legacyEmojiIds.distinct().take(300)
        val details = ids.chunked(6).flatMap { chunk ->
            chunk.map { id ->
                async {
                    emojiCache[id] ?: runCatching { get("/x/garb/v2/mall/suit/detail?item_id=$id") }.getOrNull()?.also { emojiCache[id] = it }
                }
            }.awaitAll()
        }
        details.filterNotNull().forEach { d ->
            val raw = d.str("name").orEmpty()
            val name = Regex("_(.*?)]").find(raw)?.groupValues?.get(1) ?: raw
            val p = d.obj("properties")
            out.emoji(name, p?.str("image"), p?.str("image_gif"), p?.str("image_webp"))
        }
    }

    suspend fun suit(id: Long): BiliAssetPack {
        val data = get("/x/garb/v2/mall/suit/detail?item_id=$id&part=suit")
        val returned = data.long("item_id")
        if (returned != null && returned != id) throw UserFacingException("返回的装扮与请求不一致")
        val out = AssetCollector()
        val name = data.str("name").orEmpty()
        out.properties(name, data.obj("properties"), AssetCategory.COVER)
        data.obj("suit_items")?.forEach { (group, arr) ->
            val cat = when (group) {
                "emoji_package" -> AssetCategory.EMOJI
                "pendant" -> AssetCategory.PENDANT
                "card", "card_bg" -> AssetCategory.REWARD
                "skin", "space_bg", "loading" -> AssetCategory.BACKGROUND
                "thumbup", "play_icon" -> AssetCategory.ANIMATION
                else -> AssetCategory.OTHER
            }
            (arr as? JsonArray)?.forEach { el ->
                val row = el as? JsonObject ?: return@forEach
                val rowName = row.str("name").orEmpty()
                out.properties(rowName, row.obj("properties"), cat)
                row.arr("items").forEach { child ->
                    val c = child as? JsonObject ?: return@forEach
                    out.properties(c.str("name").orEmpty().trim('[', ']'), c.obj("properties"), AssetCategory.EMOJI)
                }
            }
        }
        return BiliAssetPack(
            title = name,
            subtitle = data.str("group_name")?.takeIf { it != name }.orEmpty(),
            cover = data.obj("properties")?.str("image_cover").orEmpty(),
            suitId = id,
            assets = out.result(),
        )
    }

    // ---------------- 素材收集 ----------------

    private class AssetCollector {
        private val map = LinkedHashMap<String, BiliAsset>()
        val legacyEmojiIds = mutableListOf<Long>()

        fun result() = map.values.toList()

        /** 一个表情：静态图 + 动态版（GIF 优先，没有就用动态 WebP）。 */
        fun emoji(name: String, static: String?, gif: String?, webp: String?) {
            val label = name.trim('[', ']').ifBlank { "表情" }
            val animated = gif?.takeIf { it.isNotBlank() } ?: webp?.takeIf { it.isNotBlank() }
            if (animated != null) {
                val clean = safe(animated)
                if (clean != null && !map.containsKey(clean)) {
                    map[clean] = BiliAsset(
                        key = clean, name = label, category = AssetCategory.EMOJI_ANIMATED,
                        // 预览用动图本身，列表里就能看到它在动
                        preview = clean,
                        clean = AssetVariant(clean, listOfNotNull(safe(webp)?.takeIf { it != clean })),
                    )
                }
            }
            image(label, AssetCategory.EMOJI, static)
        }

        fun image(name: String, cat: AssetCategory, url: String?, marked: String? = null) {
            val clean = safe(url) ?: return
            if (map.containsKey(clean)) return
            map[clean] = BiliAsset(
                key = clean, name = name.ifBlank { cat.label }, category = cat, preview = clean,
                clean = AssetVariant(clean),
                marked = safe(marked)?.let { AssetVariant(it) },
            )
        }

        fun video(name: String, cat: AssetCategory, clean: List<String>, marked: List<String>, preview: String) {
            val c = clean.mapNotNull { safe(it) }
            val m = marked.mapNotNull { safe(it) }
            if (c.isEmpty() && m.isEmpty()) return
            val key = (c.firstOrNull() ?: m.first()).substringBefore('?')
            if (map.containsKey(key)) return
            map[key] = BiliAsset(
                key = key, name = name, category = cat, preview = preview, isVideo = true,
                clean = c.firstOrNull()?.let { AssetVariant(it, c.drop(1)) },
                marked = m.firstOrNull()?.let { AssetVariant(it, m.drop(1)) },
            )
        }

        fun card(card: JsonObject, imageCat: AssetCategory = AssetCategory.CARD_IMAGE) {
            val name = card.str("card_name").orEmpty()
            val img = card.str("card_img")
            image(name, imageCat, img, marked = card.str("card_img_download"))
            video(
                name,
                if (imageCat == AssetCategory.CARD_IMAGE) AssetCategory.CARD_VIDEO else imageCat,
                card.arr("video_list").mapNotNull { it.strOrNull() },
                card.arr("video_list_download").mapNotNull { it.strOrNull() },
                safe(img).orEmpty(),
            )
        }

        fun landing(items: JsonObject) {
            items.forEach { (group, value) ->
                val list = (value as? JsonObject)?.arr("items").orEmpty()
                list.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val props = o.obj("garb_properties")
                    when (group) {
                        "emoji_package" -> {
                            image("表情包封面", AssetCategory.EMOJI, props?.str("image") ?: o.str("image_url"))
                            val inline = props?.str("item_emoji_list")?.let { raw ->
                                runCatching { Http.json.parseToJsonElement(raw).jsonArray }.getOrNull()
                            }
                            inline?.forEach { e ->
                                val eo = e as? JsonObject ?: return@forEach
                                emoji(eo.str("name").orEmpty(), eo.str("image"), eo.str("image_gif") ?: eo.str("gif"), eo.str("image_webp"))
                            }
                            // 老式表情包只给 item_ids，要逐个查 suit/detail（见 7.1.7 的 detail-emote-request.js）
                            if (inline.isNullOrEmpty()) {
                                props?.str("item_ids")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.let { legacyEmojiIds += it }
                            }
                        }
                        "pendant" -> {
                            image("头像框", AssetCategory.PENDANT, props?.str("image") ?: o.str("image_url"))
                            image("头像框 · 预览", AssetCategory.PENDANT, props?.str("garb_avatar"))
                        }
                        "act_card", "act_card_bg" -> {
                            image("勋章", AssetCategory.MEDAL, o.str("image_url"))
                            image("评论背景", AssetCategory.MEDAL, o.obj("card_bg")?.str("img"))
                        }
                        "skin" -> properties("主题装扮", props, AssetCategory.BACKGROUND)
                        "act_lottery_card" -> {
                            val info = o.obj("card_info")
                            val ci = info?.obj("splash")?.obj("resource")?.obj("full_size")?.str("thumb")
                            image("${info?.str("name").orEmpty()} · 开屏", AssetCategory.BACKGROUND, ci)
                        }
                        else -> image(o.str("reward_cond").orEmpty().ifBlank { "奖励" }, AssetCategory.REWARD, o.str("image_url"))
                    }
                }
            }
        }

        /** 装扮属性里所有素材地址，按字段归类。 */
        fun properties(name: String, props: JsonObject?, fallback: AssetCategory) {
            props ?: return
            props.forEach { (key, value) ->
                val url = value.strOrNull() ?: return@forEach
                if (!url.startsWith("http") && !url.startsWith("//")) return@forEach
                val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                if (ext !in ALLOWED_EXT) return@forEach
                val label = PROPERTY_LABELS[key] ?: key
                val full = if (name.isBlank()) label else "$name · $label"
                when {
                    ext == "mp4" || ext == "webm" -> video(full, AssetCategory.BACKGROUND, listOf(url), emptyList(), safe(props.str("image_preview") ?: props.str("image_cover")).orEmpty())
                    ext == "bin" || ext == "svga" || ext == "json" || key.contains("ani") -> {
                        val clean = safe(url) ?: return@forEach
                        map.putIfAbsent(clean, BiliAsset(
                            key = clean, name = full, category = AssetCategory.ANIMATION,
                            preview = safe(props.str("image_preview") ?: props.str("static_icon_image")).orEmpty(),
                            clean = AssetVariant(clean),
                        ))
                    }
                    ext == "zip" -> Unit
                    key.contains("gif") || ext == "gif" -> image(full, AssetCategory.EMOJI_ANIMATED.takeIf { fallback == AssetCategory.EMOJI } ?: fallback, url)
                    key.startsWith("head_") || key.startsWith("image1") || key.startsWith("image2") || key.contains("loading") -> image(full, AssetCategory.BACKGROUND, url)
                    key == "image_cover" || key == "fan_share_image" -> image(full, AssetCategory.COVER, url)
                    else -> image(full, fallback, url)
                }
            }
        }

        companion object {
            val ALLOWED_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "webm", "svga", "bin", "json")
            val PROPERTY_LABELS = mapOf(
                "head_myself_mp4_bg" to "动态背景",
                "head_myself_bg" to "个人页背景",
                "head_myself_squared_bg" to "方形背景",
                "head_bg" to "顶部背景",
                "head_tab_bg" to "标签背景",
                "tail_bg" to "底栏背景",
                "image" to "图片",
                "image_cover" to "封面",
                "image_preview" to "预览图",
                "image_preview_small" to "小预览",
                "image_enhance" to "大图",
                "image_ani" to "动画",
                "image_ani_cut" to "裁切动画",
                "image_gif" to "动态图",
                "image1_landscape" to "空间背景1 横",
                "image1_portrait" to "空间背景1 竖",
                "image2_landscape" to "空间背景2 横",
                "image2_portrait" to "空间背景2 竖",
                "fan_no_image" to "粉丝编号底图",
                "loading_url" to "加载动画",
                "loading_frame_url" to "加载帧",
                "drag_icon" to "拖动图标动画",
                "icon" to "播放图标动画",
                "squared_image" to "方形图",
                "static_icon_image" to "静态图标",
                "garb_avatar" to "头像预览",
                "fan_share_image" to "分享图",
            )
        }
    }

    /** 只允许 B站素材域名；返回规范化的 https 地址。 */
    fun safe(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var url = if (value.startsWith("//")) "https:$value" else value
        // 去掉 B站图片处理后缀（如 @0-0-1079-1），取原图
        if (url.contains("hdslb.com/") && url.contains('@')) url = url.substringBefore('@')
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (uri.userInfo != null || uri.port != -1) return null
        val ok = SAFE_DOMAINS.any { host == it || host.endsWith(".$it") } ||
            (host.endsWith(".akamaized.net") && host.startsWith("upos-"))
        return if (ok) url.replaceFirst("http://", "https://") else null
    }

    private fun https(u: String?) = u.orEmpty().replaceFirst("http://", "https://").let { if (it.startsWith("//")) "https:$it" else it }

    /** 列表缩略图：hdslb 图片加处理后缀，省流量。 */
    fun thumb(url: String): String {
        if (!url.contains("hdslb.com/")) return url
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (ext in setOf("png", "jpg", "jpeg", "webp")) "$url@300w.webp" else url
    }

    private val SAFE_DOMAINS = listOf("hdslb.com", "bilivideo.com", "bilivideo.cn")
}

// ---------------- JSON 小工具 ----------------

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
internal fun JsonObject.str(key: String): String? = this[key].strOrNull()
internal fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()
internal fun JsonElement?.strOrNull(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}
