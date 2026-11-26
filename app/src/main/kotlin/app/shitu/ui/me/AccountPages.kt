package app.shitu.ui.me

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.LinearProgressIndicator
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.core.friendlyMessage
import app.shitu.core.toast
import app.shitu.feature.account.BiliQrLogin
import app.shitu.feature.account.Site
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.Page
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.PageTopBar
import app.shitu.ui.design.PaperCard
import app.shitu.ui.design.SoftButton
import app.shitu.ui.theme.LocalShituPalette
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun AccountsPage(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val status by container.cookieVault.status.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val sites = container.cookieVault.importNetscape(text)
        context.toast(if (sites.isEmpty()) "文件里没有可识别站点的 Cookie" else "已导入：" + sites.joinToString("、") { it.label })
    }
    val fmt = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { PageTopBar("账号与登录", onBack = { nav.pop() }) }
        item {
            Gap(8.dp)
            EditorialTitle("登录一次，\n*yt-dlp* 一直记得。", style = MaterialTheme.typography.displaySmall)
            Gap(10.dp)
            Lede("在内置浏览器里登录对应网站，拾图把 Cookie 用系统密钥库加密存起来。解析和 yt-dlp 下载时自动带上，只发给对应网站。")
            Gap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("导入 cookies.txt", { importer.launch(arrayOf("text/plain", "*/*")) }, compact = true)
                SoftButton("全部退出", { container.cookieVault.logoutAll(); context.toast("已清除所有登录") }, compact = true)
            }
            Gap(20.dp)
        }
        items(status, key = { it.site.name }) { s ->
            MistCard(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.site.label, style = MaterialTheme.typography.titleLarge)
                        Gap(2.dp)
                        Text(s.site.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.loggedIn) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(LocalShituPalette.current.success),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Check, "已登录", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp)) }
                    }
                }
                Gap(12.dp)
                if (s.loggedIn) {
                    DotTags(listOf("已登录", "${s.count} 个 Cookie", s.savedAt?.let { "保存于 " + fmt.format(Date(it)) }.orEmpty()).filter { it.isNotBlank() })
                    if (s.savedAt != null && System.currentTimeMillis() - s.savedAt > 30L * 86400_000) {
                        Gap(4.dp)
                        Text("登录超过 30 天了，如果解析提示要登录，重新登录一次。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Gap(12.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("重新登录", { nav.push(if (s.site == Site.BILIBILI) Page.BiliLogin else Page.WebLogin(s.site)) }, compact = true)
                        SoftButton("退出", { container.cookieVault.logout(s.site); context.toast("已退出 ${s.site.label}") }, compact = true)
                    }
                } else {
                    SoftButton("登录", { nav.push(if (s.site == Site.BILIBILI) Page.BiliLogin else Page.WebLogin(s.site)) }, compact = true)
                }
            }
        }
    }
}

/** 通用 WebView 登录：用户在网页里登录，点「完成」时导出 Cookie。 */
@Composable
fun WebLoginPage(site: Site, padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PageTopBar("登录 ${site.label}", onBack = { nav.pop() }) }
            SoftButton("完成", {
                if (container.cookieVault.captureFromWebView(site)) {
                    context.toast("${site.label} 已登录"); nav.pop()
                } else context.toast("还没检测到登录，登录成功后再点完成")
            }, compact = true)
            Spacer(Modifier.width(8.dp))
        }
        if (site == Site.INSTAGRAM) {
            Text(
                "部分手机上这个页面会显示空白。遇到的话，在电脑浏览器登录 Instagram，用扩展导出 cookies.txt，再到「账号与登录 → 导入 cookies.txt」导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        LoginWebView(site.loginUrl, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun LoginWebView(url: String, modifier: Modifier, onPage: (String) -> Unit = {}) {
    var progress by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    WebView.setWebContentsDebuggingEnabled(app.shitu.BuildConfig.DEBUG)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // 去掉 WebView 标记（"; wv" 与 "Version/4.0"），否则 Instagram、Google 会给空白页或拒绝登录
                    settings.userAgentString = cleanUserAgent(settings.userAgentString)
                    // 不发送 X-Requested-With: app.shitu，Instagram / Google 看到这个头会拒绝或白屏
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { failure = null }
                        override fun onPageFinished(view: WebView, u: String) = onPage(u)
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) failure = "页面打不开（${error.description}）。这个网站在国内需要代理，确认手机网络能访问后点重试。"
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame && response.statusCode >= 400) failure = "网站返回 HTTP ${response.statusCode}，稍后重试。"
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme.orEmpty()
                            // intent:// 之类交给系统，避免白屏
                            return scheme != "http" && scheme != "https"
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        failure?.let { msg ->
            PaperCard(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("没加载出来", style = MaterialTheme.typography.titleLarge)
                Gap(6.dp)
                Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(12.dp)
                SoftButton("重试", { failure = null; webView?.reload() }, compact = true)
            }
        }
    }
}

/** 把系统 WebView 的 UA 改成同版本的 Chrome 手机版。 */
internal fun cleanUserAgent(ua: String): String =
    ua.replace("; wv)", ")").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")

/** B站：扫码（原生）+ 短信（官方 H5 页面里完成极验与短信）。 */
@Composable
fun BiliLoginPage(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        PageTopBar("登录哔哩哔哩", onBack = { nav.pop() }, modifier = Modifier.padding(horizontal = 8.dp))
        Row(
            Modifier.padding(horizontal = 24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
        ) {
            listOf("扫码登录", "短信登录").forEachIndexed { i, label ->
                val on = tab == i
                Text(
                    label,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { tab = i }.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        Gap(12.dp)
        if (tab == 0) {
            QrLogin(padding) { cookies ->
                container.cookieVault.putCookies(Site.BILIBILI, cookies)
                context.toast("B站登录成功，已同步给 yt-dlp")
                nav.pop()
            }
        } else {
            Text(
                "在下面的官方页面里输入手机号，完成人机验证并填短信验证码，登录成功后点「完成」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Gap(8.dp)
            Box(Modifier.weight(1f)) {
                LoginWebView("https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F", Modifier.fillMaxSize())
            }
            InkButton("完成", {
                if (container.cookieVault.captureFromWebView(Site.BILIBILI)) { context.toast("B站登录成功"); nav.pop() }
                else context.toast("还没检测到登录")
            }, Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

@Composable
private fun QrLogin(padding: PaddingValues, onSuccess: (Map<String, String>) -> Unit) {
    val context = LocalContext.current
    var qr by remember { mutableStateOf<BiliQrLogin.Qr?>(null) }
    var status by remember { mutableStateOf("正在申请二维码…") }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        qr = null
        status = "正在申请二维码…"
        val q = runCatching { BiliQrLogin.generate() }.getOrElse { status = it.friendlyMessage(); return@LaunchedEffect }
        qr = q
        status = "用 B站 App 扫一扫"
        while (true) {
            delay(2000)
            when (val p = runCatching { BiliQrLogin.poll(q.key) }.getOrNull()) {
                is BiliQrLogin.Poll.Success -> { onSuccess(p.cookies); return@LaunchedEffect }
                BiliQrLogin.Poll.Scanned -> status = "已扫码，在手机上点确认"
                BiliQrLogin.Poll.Expired -> { status = "二维码过期了"; qr = null; return@LaunchedEffect }
                else -> Unit
            }
        }
    }
    val bitmap = remember(qr) { qr?.url?.let { qrBitmap(it, 720) } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = padding.calculateBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PaperCard(Modifier.fillMaxWidth(), padding = PaddingValues(24.dp)) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), "登录二维码", Modifier.size(240.dp))
                } else if (status.startsWith("正在")) {
                    CircularProgressIndicator()
                } else {
                    InkButton("刷新二维码", { round++ })
                }
            }
            Gap(12.dp)
            Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Gap(16.dp)
        Lede("二维码要用另一台设备上的 B站 App 扫。只有这一台手机的话，点下面按钮用本机 B站 App 打开确认页。")
        Gap(12.dp)
        qr?.let { q ->
            SoftButton("用本机 B站 App 打开", {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q.url)).setPackage("tv.danmaku.bili")) }
                    .onFailure { context.toast("没装 B站 App") }
            })
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF16140F.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
