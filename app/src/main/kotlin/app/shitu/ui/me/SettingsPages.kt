package app.shitu.ui.me

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.BuildConfig
import app.shitu.core.Clipboard
import app.shitu.core.formatBytes
import app.shitu.core.openUrl
import app.shitu.core.toast
import app.shitu.feature.search.Engine
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Gap
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.OutlinePill
import app.shitu.ui.design.PageTopBar
import app.shitu.ui.design.PaperCard
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.design.SettingRow
import app.shitu.ui.design.SoftButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- 下载与存储 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoragePage(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.collectAsStateWithLifecycle()
    val repo = container.settingsRepository
    var cacheSize by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }

    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    fun adopt(uri: Uri, old: String?) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        old?.takeIf { it != uri.toString() }
            ?.let { prev -> runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(prev), flags) } }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        adopt(uri, settings.imageFolderUri.takeIf { it != settings.videoFolderUri })
        scope.launch { repo.setImageFolder(uri.toString()) }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        adopt(uri, settings.videoFolderUri.takeIf { it != settings.imageFolderUri })
        scope.launch { repo.setVideoFolder(uri.toString()) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { PageTopBar("下载与存储", onBack = { nav.pop() }) }
        item {
            Gap(8.dp)
            EditorialTitle("存到*哪里*，\n你说了算。", style = MaterialTheme.typography.displaySmall)
            Gap(10.dp)
            Lede("默认统一存到 Download/拾图 下：图片进「图片」、视频进「视频」，相册里也能看到。也可以选任意文件夹，比如网盘同步目录。")
            Gap(20.dp)
        }
        item {
            FolderCard("图片保存位置", settings.imageFolderUri, "默认 · Download/拾图/图片", onPick = { imagePicker.launch(null) }, onReset = { scope.launch { repo.setImageFolder(null) } })
            Gap(12.dp)
            FolderCard("视频与音频保存位置", settings.videoFolderUri, "默认 · Download/拾图/视频（音频在 Download/拾图/音频）", onPick = { videoPicker.launch(null) }, onReset = { scope.launch { repo.setVideoFolder(null) } })
            Gap(12.dp)
            PermissionCard()
        }
        item {
            SectionHeader("同时下载")
            Text("${settings.downloadConcurrency} 个", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.downloadConcurrency.toFloat(),
                onValueChange = { scope.launch { repo.setConcurrency(it.toInt()) } },
                valueRange = 1f..8f, steps = 6,
            )
            SectionHeader("缓存")
            SettingRow("临时文件", subtitle = if (cacheSize < 0) "计算中…" else formatBytes(cacheSize), trailing = {
                SoftButton("清理", {
                    scope.launch {
                        withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                        cacheSize = 0
                        context.toast("已清理")
                    }
                }, compact = true)
            })
        }
    }
}

@Composable
private fun FolderCard(title: String, uri: String?, default: String, onPick: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    val valid = remember(uri) {
        uri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.let { d -> d.exists() && d.canWrite() } }.getOrNull() }
    }
    val name = remember(uri) { uri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() } }
    MistCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Gap(6.dp)
        Text(
            when {
                uri == null -> default
                valid == false -> "「${name ?: "所选文件夹"}」已不可用，会自动存到默认位置"
                else -> "自定义 · ${name ?: uri}"
            },
            color = if (valid == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("选择文件夹", onPick, compact = true)
            if (uri != null) SoftButton("恢复默认", onReset, compact = true)
        }
    }
}

// ---------------- 识图引擎 ----------------

@Composable
fun EnginesPage(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.collectAsStateWithLifecycle()
    val repo = container.settingsRepository
    val enabled = settings.searchEngines.mapNotNull(Engine::byId)
    val disabled = Engine.entries.filter { it !in enabled }
    val key = rememberTextFieldState(settings.sauceNaoKey)
    LaunchedEffect(Unit) { snapshotFlow { key.text.toString() }.collect { repo.setSauceNaoKey(it) } }
    val gel = rememberTextFieldState(settings.gelbooruAuth)
    LaunchedEffect(Unit) { snapshotFlow { gel.text.toString() }.collect { repo.setGelbooruAuth(it) } }
    val dan = rememberTextFieldState(settings.danbooruAuth)
    LaunchedEffect(Unit) { snapshotFlow { dan.text.toString() }.collect { repo.setDanbooruAuth(it) } }

    fun save(list: List<Engine>) = scope.launch { repo.setSearchEngines(list.map { it.id }) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { PageTopBar("识图引擎", onBack = { nav.pop() }) }
        item {
            Gap(8.dp)
            EditorialTitle("引擎排个*顺序*。", style = MaterialTheme.typography.displaySmall)
            Gap(10.dp)
            Lede("勾选的引擎会出现在识图页，按这里的顺序排列。")
            Gap(16.dp)
        }
        items(enabled, key = { it.id }) { e ->
            val i = enabled.indexOf(e)
            EngineRow(e, true, onToggle = { save(enabled - e) },
                onUp = if (i > 0) ({ save(enabled.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < enabled.lastIndex) ({ save(enabled.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null)
        }
        items(disabled, key = { it.id }) { e -> EngineRow(e, false, onToggle = { save(enabled + e) }) }
        item {
            SectionHeader("SauceNAO API Key")
            PaperCard(Modifier.fillMaxWidth()) {
                Text("SauceNAO 要 API Key 才能在应用内出结果。免费注册后在账户页复制。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(12.dp)
                OutlinedSecureTextField(state = key, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
                Gap(10.dp)
                SoftButton("去获取 Key", { context.openUrl("https://saucenao.com/user.php?page=search-api") }, compact = true)
            }
            SectionHeader("图站账号（可选）")
            PaperCard(Modifier.fillMaxWidth()) {
                Text("图站引擎先用 IQDB / SauceNAO 找到帖子，再查各站公开接口补标签和原图尺寸。账号都是选填。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(12.dp)
                OutlinedSecureTextField(state = dan, label = { Text("Danbooru：用户名:API Key") }, modifier = Modifier.fillMaxWidth())
                Gap(6.dp)
                Text("填了之后 Danbooru 引擎会额外用 Danbooru 自己的以图搜图，收录更全。API Key 在 Danbooru 个人资料页 → API Key 里生成，勾选 iqdb_queries 权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Gap(10.dp)
                SoftButton("去 Danbooru 生成 Key", { context.openUrl("https://danbooru.donmai.us/profile") }, compact = true)
                Gap(16.dp)
                OutlinedSecureTextField(state = gel, label = { Text("Gelbooru：&api_key=…&user_id=…") }, modifier = Modifier.fillMaxWidth())
                Gap(6.dp)
                Text("Gelbooru 的接口要账号凭证，不填也能搜到链接，只是不显示标签。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Gap(10.dp)
                SoftButton("去 Gelbooru 账号页复制", { context.openUrl("https://gelbooru.com/index.php?page=account&s=options") }, compact = true)
                Gap(10.dp)
                Text("Sankaku 没有公开的第三方接口（登录要走它 App 的私有协议），这里只给帖子链接和相似度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            SectionHeader("上传尺寸")
            Text("长边压到 ${settings.imageMaxSide} 像素再上传", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = settings.imageMaxSide.toFloat(),
                onValueChange = { scope.launch { repo.setImageMaxSide((it / 256).toInt() * 256) } },
                valueRange = 1024f..4096f, steps = 11,
            )
        }
    }
}

@Composable
private fun EngineRow(e: Engine, on: Boolean, onToggle: () -> Unit, onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(e.label, style = MaterialTheme.typography.titleMedium)
            Text(e.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (on) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Rounded.ArrowUpward, "上移") }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Rounded.ArrowDownward, "下移") }
        }
    }
}

// ---------------- 许可、赞助、开发者 ----------------

@Composable
fun LicensesPage(padding: PaddingValues) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val libs = listOf(
        "youtubedl-android" to "GPL-3.0 · 内置 Python、yt-dlp、FFmpeg",
        "yt-dlp" to "Unlicense",
        "FFmpeg" to "LGPL / GPL",
        "求原图 hiyufan/shizhen" to "MIT · 抖音、小红书、B站素材、音乐封面、抖音直播解析思路移植",
        "renmu123/biliAPI" to "GPL-3.0 · B站扫码登录接口",
        "Jetpack Compose / AndroidX / Media3" to "Apache-2.0",
        "Haze" to "Apache-2.0 · 毛玻璃",
        "Coil" to "Apache-2.0",
        "OkHttp" to "Apache-2.0",
        "kotlinx.serialization / coroutines" to "Apache-2.0",
        "ZXing" to "Apache-2.0 · 二维码",
        "jsoup" to "MIT",
        "lottie-android" to "Apache-2.0 · TGS 贴纸渲染",
        "notscuffed/RePKG" to "MIT · Wallpaper Engine PKG / TEX 格式参考",
        "Noto Animated Emoji" to "Apache-2.0 · 动态 Emoji 在线素材",
        "思源宋体 Noto Serif SC" to "SIL OFL 1.1 · 子集化",
        "Fraunces" to "SIL OFL 1.1 · 子集化",
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { PageTopBar("开源许可与声明", onBack = { nav.pop() }) }
        item {
            Gap(8.dp)
            EditorialTitle("拾图是\n*自由软件*。", style = MaterialTheme.typography.displaySmall)
            Gap(10.dp)
            Lede("源代码以 GPL-3.0 发布。你可以自由使用、修改、再发布，前提是同样以 GPL-3.0 开源。")
            Gap(16.dp)
            PaperCard(Modifier.fillMaxWidth()) {
                Text("免责声明", style = MaterialTheme.typography.titleLarge)
                Gap(6.dp)
                Text(
                    "拾图只下载各平台公开返回的素材，不解锁付费权益，也不绕过访问控制。素材版权归原作者与平台所有，仅供个人学习与收藏，请勿用于商业用途。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionHeader("用到的开源项目")
        }
        items(libs) { (name, lic) -> SettingRow(name, subtitle = lic) }
        if (BuildConfig.GITHUB_REPO.isNotBlank()) {
            item { Gap(12.dp); SoftButton("查看源代码", { context.openUrl("https://github.com/${BuildConfig.GITHUB_REPO}") }) }
        }
    }
}

@Composable
fun SponsorPage(padding: PaddingValues) {
    val nav = LocalNav.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { PageTopBar("赞助", onBack = { nav.pop() }) }
        item {
            Gap(8.dp)
            EditorialTitle("谢谢你\n愿意*支持*。", style = MaterialTheme.typography.displaySmall)
            Gap(10.dp)
            Lede("拾图免费、无广告、不收集数据。如果它帮到了你，可以请作者喝杯咖啡，或者在 GitHub 上点个 Star。")
            Gap(20.dp)
            PaperCard(Modifier.fillMaxWidth()) {
                Text("收款码还没放上来", style = MaterialTheme.typography.titleLarge)
                Gap(6.dp)
                Text("发布前把收款码图片放到 res/drawable/sponsor_qr.png 即可在这里显示。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperPage(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by container.downloadManager.tasks.collectAsStateWithLifecycle()
    val yt by container.ytDlp.state.collectAsStateWithLifecycle()
    val info = remember(tasks, yt) {
        buildString {
            appendLine("拾图 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("设备 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("yt-dlp $yt")
            appendLine("最近失败：")
            tasks.filter { it.error != null }.take(10).forEach { appendLine("· ${it.request.group} — ${it.error}") }
        }
    }
    val log by app.shitu.core.DevLog.entries.collectAsStateWithLifecycle()
    var onlyErrors by remember { mutableStateOf(true) }
    val shownLog = log.filter { !onlyErrors || it.level != app.shitu.core.DevLog.Level.NET }.asReversed()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item { PageTopBar("开发者选项", onBack = { nav.pop() }) }
        item {
            PaperCard(Modifier.fillMaxWidth()) {
                Text("调试信息（不含 Cookie）", style = MaterialTheme.typography.titleLarge)
                Gap(8.dp)
                Text(info, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                Gap(12.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("复制", { Clipboard.copy(context, info + "\n\n" + app.shitu.core.DevLog.format()); context.toast("已复制（含日志）") }, compact = true)
                    OutlinePill("关闭开发者选项", onClick = {
                        scope.launch { container.settingsRepository.setDeveloperOptionsEnabled(false) }
                        nav.pop()
                    })
                }
            }
        }
        item {
            SectionHeader("运行日志")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(if (onlyErrors) "显示全部请求" else "只看错误", { onlyErrors = !onlyErrors }, compact = true)
                SoftButton("清空", { app.shitu.core.DevLog.clear() }, compact = true)
            }
            Gap(8.dp)
            if (shownLog.isEmpty()) Text("暂无记录。识图失败、下载出错和网络请求会记在这里（不含 Cookie、Key 和网址参数）。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shownLog.size) { i ->
            val e = shownLog[i]
            val color = when (e.level) {
                app.shitu.core.DevLog.Level.ERROR -> MaterialTheme.colorScheme.error
                app.shitu.core.DevLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(e.time)) + "  [" + e.tag + "] " + e.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = color,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
    }
}
