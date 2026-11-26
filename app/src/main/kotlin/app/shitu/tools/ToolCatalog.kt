package app.shitu.tools

/**
 * 工具目录。implemented = false 的条目在工具页里排在后面，以紧凑列表展示。
 * 链接解析类工具都打开同一个「链接解析」页面，只是预设平台不同。
 */
data class ToolInfo(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val implemented: Boolean = false,
    /** 打开哪个工作台页面；默认等于 id。 */
    val screen: String = id,
    val featured: Boolean = false,
)

data class ToolCategory(val title: String, val tools: List<ToolInfo>)

object ToolCatalog {
    const val LINK_PARSE = "link_parse"
    const val BILI_COLLECTION = "bili_collection"
    const val BILI_SUIT = "bili_suit"
    const val VIDEO_GIF = "video_gif"
    const val MUSIC_COVER = "music_cover"
    const val DOUYIN_LIVE = "douyin_live"
    const val LIVE_PHOTO = "live_photo"
    const val UGOIRA = "pixiv_ugoira"
    const val TG_STICKER = "tg_sticker"
    const val WALLPAPER_ENGINE = "we_pkg"
    const val ANIM_EMOJI = "anim_emoji"

    private const val C_LINK = "链接解析"
    private const val C_BILI = "B站素材"
    private const val C_LIVE = "直播"
    private const val C_MUSIC = "音乐封面"
    private const val C_GAME = "游戏素材"
    private const val C_ART = "插画"
    private const val C_STICKER = "贴纸表情"
    private const val C_FILE = "文件提取"
    private const val C_ANIM = "动画转换"
    private const val C_CONVERT = "转换"

    val tools: List<ToolInfo> = listOf(
        ToolInfo(LINK_PARSE, C_LINK, "万能链接解析", "整段分享文字贴进来，自动识别平台；没有专用解析器的交给 yt-dlp。", listOf("视频", "图集", "音频"), implemented = true, featured = true),
        ToolInfo(BILI_COLLECTION, C_BILI, "收藏集下载", "按名称、act_id 或链接找收藏集，卡片图、卡片视频、表情、头像框一次拿全。", listOf("卡片", "视频", "表情", "头像框"), implemented = true, featured = true),
        ToolInfo(BILI_SUIT, C_BILI, "装扮下载", "完整装扮的背景、表情、头像框、点赞动画、加载动画。", listOf("背景", "表情", "SVGA"), implemented = true, featured = true),

        ToolInfo("douyin", C_LINK, "抖音", "无水印视频、图集原图、实况图、背景音乐。", listOf("视频", "图集", "实况", "音乐"), implemented = true, screen = LINK_PARSE),
        ToolInfo("xiaohongshu", C_LINK, "小红书", "笔记原图按原始分辨率取，视频无水印。", listOf("原图", "视频", "实况"), implemented = true, screen = LINK_PARSE),
        ToolInfo("kuaishou", C_LINK, "快手", "无水印视频与图集，贴分享链接即可。", listOf("视频", "图集"), implemented = true, screen = LINK_PARSE),
        ToolInfo("bilibili", C_LINK, "哔哩哔哩", "视频、封面、动态图片。", listOf("视频", "封面", "动态"), implemented = true, screen = LINK_PARSE),
        ToolInfo("weibo", C_LINK, "微博", "微博原图、视频。", listOf("原图", "视频"), implemented = true, screen = LINK_PARSE),
        ToolInfo("youtube", C_LINK, "YouTube", "视频、音频、封面，交给 yt-dlp。", listOf("视频", "音频"), implemented = true, screen = LINK_PARSE),
        ToolInfo("x", C_LINK, "X / Twitter", "推文视频与原图。", listOf("视频", "原图"), implemented = true, screen = LINK_PARSE),
        ToolInfo("instagram", C_LINK, "Instagram", "帖子、Reels；需要登录的内容先在「账号」里登录。", listOf("视频", "图片"), implemented = true, screen = LINK_PARSE),
        ToolInfo("tiktok", C_LINK, "TikTok", "无水印视频。", listOf("视频"), implemented = true, screen = LINK_PARSE),
        ToolInfo("threads", C_LINK, "Threads", "帖子图片与视频。", listOf("图片", "视频"), implemented = true, screen = LINK_PARSE),
        ToolInfo("wechat", C_LINK, "公众号文章", "文章里的全部图片与封面。", listOf("图片", "封面"), implemented = true, screen = LINK_PARSE),
        ToolInfo("telegram", C_LINK, "Telegram 公开帖", "公开频道帖子的图片与视频。", listOf("图片", "视频"), implemented = true, screen = LINK_PARSE),
        ToolInfo("pixiv", C_ART, "Pixiv", "插画、漫画全部分页原图；动图 Ugoira 直接转 GIF。R-18 作品先在账号里登录 Pixiv。", listOf("原图", "多页", "动图"), implemented = true, screen = LINK_PARSE),
        ToolInfo("pinterest", C_ART, "Pinterest", "Pin 原图（originals）、视频、多页 Idea Pin。", listOf("原图", "视频"), implemented = true, screen = LINK_PARSE),

        ToolInfo(UGOIRA, C_ART, "Pixiv 动图转 GIF", "贴作品链接自动取帧包，或者选本地下载好的 ugoira 帧包（zip），按原始帧延时合成 GIF。", listOf("Ugoira", "ZIP", "GIF"), implemented = true),
        ToolInfo(LIVE_PHOTO, C_CONVERT, "转实况照片", "视频转安卓动态照片（Motion Photo，相册里长按会动），也能把动态照片拆回视频。", listOf("动态照片", "实况", "拆分"), implemented = true),
        ToolInfo(TG_STICKER, C_STICKER, "Telegram 贴纸", "贴纸包整包下载：静态 WebP 转 PNG，动态 TGS 与视频贴纸转 GIF。", listOf("贴纸", "TGS", "GIF"), implemented = true),
        ToolInfo(ANIM_EMOJI, C_STICKER, "动态 Emoji", "Google Noto 动态表情库，搜索后下载 GIF / WebP / Lottie，附公开素材库地址。", listOf("GIF", "WebP", "Lottie"), implemented = true),
        ToolInfo(WALLPAPER_ENGINE, C_FILE, "Wallpaper Engine 提取", "选 scene.pkg 解包，TEX 贴图转 PNG，视频与音频原样导出。", listOf("PKG", "TEX", "PNG"), implemented = true),

        ToolInfo(DOUYIN_LIVE, C_LIVE, "直播流", "抖音、B站直播间的 FLV / HLS 地址，只给地址不录制。", listOf("FLV", "HLS"), implemented = true),

        ToolInfo(MUSIC_COVER, C_MUSIC, "音乐封面", "网易云、QQ 音乐、Apple Music 的高清专辑封面。", listOf("网易云", "QQ 音乐", "Apple Music"), implemented = true),

        ToolInfo(VIDEO_GIF, C_CONVERT, "视频转 GIF", "本地视频截一段转成高质量 GIF，可调帧率与宽度。", listOf("GIF", "调色板"), implemented = true),

        ToolInfo("steam", C_GAME, "Steam 点数商店", "动态头像、头像框、个人资料背景。", listOf("头像", "背景")),
        ToolInfo("hoyoplay", C_GAME, "HoYoPlay 壁纸", "启动器背景视频与壁纸。", listOf("壁纸", "视频")),
        ToolInfo("wuwa", C_GAME, "鸣潮壁纸", "官方壁纸原图。", listOf("壁纸")),
        ToolInfo("miyoushe", C_ART, "米游社", "帖子原图。", listOf("原图")),
        ToolInfo("line_sticker", C_STICKER, "LINE 贴纸", "静态与动态贴纸。", listOf("贴纸", "APNG")),
        ToolInfo("discord_emoji", C_STICKER, "Discord 表情", "服务器表情与贴纸。", listOf("表情")),
        ToolInfo("twitch_emotes", C_STICKER, "7TV / BTTV / FFZ", "直播间表情。", listOf("表情")),
        ToolInfo("svga", C_ANIM, "SVGA / PAG / VAP", "动画文件渲染为视频或 GIF（含 B站 .bin）。", listOf("SVGA", "PAG", "VAP")),
    )

    private val byId = tools.associateBy { it.id }

    fun find(id: String?): ToolInfo? = id?.let(byId::get)

    fun categories(): List<ToolCategory> =
        tools.groupBy { it.category }.map { (k, v) -> ToolCategory(k, v) }
}
