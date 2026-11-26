package app.shitu.feature.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 可以登录的站点。cookieDomains 用于从 WebView 导出 Cookie，loginCheck 是登录后才会有的 Cookie 名。 */
enum class Site(
    val label: String,
    val loginUrl: String,
    val cookieDomains: List<String>,
    val loginCheck: List<String>,
    val note: String,
) {
    BILIBILI("哔哩哔哩", "https://passport.bilibili.com/h5-app/passport/login", listOf("bilibili.com"), listOf("SESSDATA"), "高画质视频、会员内容"),
    YOUTUBE("YouTube", "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.youtube.com%2F", listOf("youtube.com", "google.com"), listOf("SAPISID", "__Secure-3PSID"), "年龄限制、会员视频"),
    X("X / Twitter", "https://x.com/i/flow/login", listOf("x.com", "twitter.com"), listOf("auth_token"), "敏感内容、受保护推文"),
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/", listOf("instagram.com"), listOf("sessionid"), "几乎所有帖子都要登录"),
    DOUYIN("抖音", "https://www.douyin.com/", listOf("douyin.com"), listOf("sessionid", "sessionid_ss"), "图文笔记、限制分享的作品"),
    XIAOHONGSHU("小红书", "https://www.xiaohongshu.com/explore", listOf("xiaohongshu.com"), listOf("web_session"), "过期链接、要求登录的笔记"),
    TIKTOK("TikTok", "https://www.tiktok.com/login", listOf("tiktok.com"), listOf("sessionid"), "地区限制内容"),
    WEIBO("微博", "https://passport.weibo.cn/signin/login", listOf("weibo.com", "weibo.cn"), listOf("SUB"), "需要登录的微博"),
    PIXIV("Pixiv", "https://accounts.pixiv.net/login?return_to=https%3A%2F%2Fwww.pixiv.net%2F", listOf("pixiv.net"), listOf("PHPSESSID"), "R-18 作品、动图原图"),
    ;

    fun matches(host: String): Boolean = cookieDomains.any { host == it || host.endsWith(".$it") }
}

@Serializable
data class SiteCookies(
    /** Netscape cookies.txt 内容，给 yt-dlp。 */
    val netscape: String,
    /** 请求头形式，给自带解析器。 */
    val header: String,
    val savedAt: Long,
    val count: Int,
)

data class SiteStatus(val site: Site, val loggedIn: Boolean, val savedAt: Long?, val count: Int)

/**
 * Cookie 保险箱：用 Android Keystore 的 AES-GCM 密钥加密，整体存一个文件。
 * 只在需要时解密：yt-dlp 运行时写到私有临时文件，用完删除。
 */
class CookieVault(private val context: Context) {
    private val file = File(context.filesDir, "cookies.bin")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), SiteCookies.serializer())
    private var cache: MutableMap<String, SiteCookies> = load()

    private val _status = MutableStateFlow(statusSnapshot())
    val status: StateFlow<List<SiteStatus>> = _status.asStateFlow()

    fun get(site: Site): SiteCookies? = cache[site.name]

    /** 按 host 找对应站点的请求头 Cookie。 */
    fun headerFor(host: String): String? = Site.entries.firstOrNull { it.matches(host) }?.let { cache[it.name]?.header }

    /** WebView 登录完成后调用：从 CookieManager 导出该站点的 Cookie。返回是否检测到登录。 */
    fun captureFromWebView(site: Site): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val lines = mutableListOf<String>()
        val headerParts = linkedMapOf<String, String>()
        site.cookieDomains.forEach { domain ->
            listOf("https://$domain", "https://www.$domain", "https://m.$domain").forEach { url ->
                cm.getCookie(url)?.split(';')?.forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && headerParts[name] == null) {
                        headerParts[name] = value
                        lines += netscapeLine(".$domain", name, value)
                    }
                }
            }
        }
        val loggedIn = site.loginCheck.any { it in headerParts }
        if (!loggedIn) return false
        put(site, buildNetscape(lines), headerParts.entries.joinToString("; ") { "${it.key}=${it.value}" }, headerParts.size)
        return true
    }

    /** 直接保存一组 Cookie（例如 B站扫码登录拿到的）。 */
    fun putCookies(site: Site, cookies: Map<String, String>) {
        val domain = site.cookieDomains.first()
        val lines = cookies.map { (k, v) -> netscapeLine(".$domain", k, v) }
        put(site, buildNetscape(lines), cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }, cookies.size)
        // 同步进 WebView，方便之后打开网页时也是登录状态
        val cm = CookieManager.getInstance()
        cookies.forEach { (k, v) -> cm.setCookie("https://www.$domain", "$k=$v; Domain=.$domain; Path=/") }
        cm.flush()
    }

    /** 导入 cookies.txt（Netscape 格式）。按域名分配到各站点，返回导入的站点。 */
    fun importNetscape(text: String): List<Site> {
        val bySite = mutableMapOf<Site, MutableList<List<String>>>()
        text.lineSequence().forEach { raw ->
            val line = raw.removePrefix("#HttpOnly_")
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val cols = line.split('\t')
            if (cols.size < 7) return@forEach
            val host = cols[0].trimStart('.')
            val site = Site.entries.firstOrNull { it.matches(host) } ?: return@forEach
            bySite.getOrPut(site) { mutableListOf() } += cols
        }
        bySite.forEach { (site, rows) ->
            val lines = rows.map { it.joinToString("\t") }
            val header = rows.associate { it[5] to it[6] }
            put(site, buildNetscape(lines), header.entries.joinToString("; ") { "${it.key}=${it.value}" }, rows.size)
        }
        return bySite.keys.toList()
    }

    fun logout(site: Site) {
        cache.remove(site.name)
        persist()
        val cm = CookieManager.getInstance()
        site.cookieDomains.forEach { domain ->
            listOf("https://$domain", "https://www.$domain", "https://m.$domain").forEach { url ->
                cm.getCookie(url)?.split(';')?.forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    cm.setCookie(url, "$name=; Max-Age=0; Domain=.$domain; Path=/")
                }
            }
        }
        cm.flush()
    }

    fun logoutAll() {
        cache.clear()
        persist()
        CookieManager.getInstance().removeAllCookies(null)
    }

    /** 把所有站点合并成一个临时 cookies.txt，用完由调用方删除。 */
    fun writeTempNetscape(dir: File): File? {
        if (cache.isEmpty()) return null
        val body = cache.values.joinToString("\n") { it.netscape.lineSequence().filterNot { l -> l.startsWith("#") }.joinToString("\n") }
        val f = File(dir, "cookies-${System.nanoTime()}.txt")
        f.writeText("# Netscape HTTP Cookie File\n$body\n")
        return f
    }

    private fun put(site: Site, netscape: String, header: String, count: Int) {
        cache[site.name] = SiteCookies(netscape, header, System.currentTimeMillis(), count)
        persist()
    }

    private fun statusSnapshot() = Site.entries.map { s ->
        val c = cache[s.name]
        SiteStatus(s, c != null, c?.savedAt, c?.count ?: 0)
    }

    private fun persist() {
        runCatching {
            val plain = json.encodeToString(serializer, cache).toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val enc = cipher.doFinal(plain)
            val tmp = File(file.parentFile, "cookies.bin.tmp")
            tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + enc)
            tmp.renameTo(file)
        }
        _status.value = statusSnapshot()
    }

    private fun load(): MutableMap<String, SiteCookies> = runCatching {
        val bytes = file.readBytes()
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes.copyOfRange(1 + ivLen, bytes.size))
        json.decodeFromString(serializer, String(plain)).toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "shitu_cookie_vault"
        // 2038 年后过期；WebView 不暴露真实过期时间，统一给一个远期值，由站点自己判失效
        const val FAR_FUTURE = 2145916800L

        fun netscapeLine(domain: String, name: String, value: String) =
            listOf(domain, "TRUE", "/", "TRUE", FAR_FUTURE.toString(), name, value).joinToString("\t")

        fun buildNetscape(lines: List<String>) = "# Netscape HTTP Cookie File\n" + lines.joinToString("\n")
    }
}
