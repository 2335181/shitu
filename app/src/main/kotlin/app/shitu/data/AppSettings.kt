package app.shitu.data

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ColorSource {
    /** Material You：取自壁纸。 */
    DYNAMIC,

    /** 固定的「纸与墨」品牌配色。 */
    BRAND,
}

enum class YtDlpChannel(val label: String) { STABLE("稳定版"), NIGHTLY("每日构建") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.BRAND,
    val developerOptionsEnabled: Boolean = false,
    val imageFolderUri: String? = null,
    val videoFolderUri: String? = null,
    val downloadConcurrency: Int = 3,
    val clipboardDetect: Boolean = true,
    /** 识图引擎：启用的 ID，按顺序。 */
    val searchEngines: List<String> = DEFAULT_ENGINES,
    val sauceNaoKey: String = "",
    /** Gelbooru 账号页给的「&api_key=…&user_id=…」整段。 */
    val gelbooruAuth: String = "",
    val imageMaxSide: Int = 2048,
    val ytDlpChannel: YtDlpChannel = YtDlpChannel.STABLE,
    /** Telegram 机器人 Token，只用于读取贴纸包（Bot API getStickerSet）。 */
    val telegramBotToken: String = "",
) {
    companion object {
        val DEFAULT_ENGINES = listOf("google", "yandex", "bing", "saucenao", "tracemoe", "iqdb", "ascii2d", "baidu", "sogou", "tineye", "danbooru", "yandere", "konachan", "gelbooru", "sankaku")
    }
}
