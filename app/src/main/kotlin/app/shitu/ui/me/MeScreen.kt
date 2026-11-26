package app.shitu.ui.me

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.BuildConfig
import app.shitu.core.Http
import app.shitu.core.friendlyMessage
import app.shitu.core.getText
import app.shitu.core.openUrl
import app.shitu.core.toast
import app.shitu.data.ColorSource
import app.shitu.data.ThemeMode
import app.shitu.data.YtDlpChannel
import app.shitu.feature.bili.str
import app.shitu.feature.ytdlp.YtDlpManager
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.Page
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Eyebrow
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.OutlinePill
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.design.SettingRow
import app.shitu.ui.theme.LocalShituPalette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeScreen(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.collectAsStateWithLifecycle()
    val accounts by container.cookieVault.status.collectAsStateWithLifecycle()
    val yt by container.ytDlp.state.collectAsStateWithLifecycle()
    var updating by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    var sponsor by remember { mutableStateOf(false) }
    val repo = container.settingsRepository
    val logged = accounts.filter { it.loggedIn }

    if (sponsor) SponsorSheet(onDismiss = { sponsor = false })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            ProfileHeader()
            Gap(28.dp)
        }

        item {
            MistCard(Modifier.fillMaxWidth(), onClick = { nav.push(Page.Accounts) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("账号与登录", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = LocalShituPalette.current.accent)
                }
                Gap(6.dp)
                Text(
                    if (logged.isEmpty()) "还没登录任何网站。B站支持扫码和短信登录，其他网站在内置浏览器里登录一次即可。"
                    else "已登录：" + logged.joinToString("、") { it.site.label },
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12.dp)
                DotTags(listOf("B站扫码", "短信", "YouTube", "X", "Instagram", "抖音", "小红书"))
            }
            Gap(14.dp)
        }

        item {
            MistCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("yt-dlp", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text(
                        when (val s = yt) {
                            YtDlpManager.State.Initializing -> "初始化中…"
                            is YtDlpManager.State.Ready -> s.version
                            is YtDlpManager.State.Updating -> "更新中…"
                            is YtDlpManager.State.Failed -> "不可用"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Gap(6.dp)
                Text("网站改版后解析失败，多半更新一下就好。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YtDlpChannel.entries.forEach { ch ->
                        OutlinePill(ch.label, onClick = { scope.launch { repo.setYtDlpChannel(ch) } }, selected = settings.ytDlpChannel == ch)
                    }
                }
                Gap(14.dp)
                InkButton(
                    if (updating) "正在更新…" else "更新 yt-dlp",
                    {
                        scope.launch {
                            updating = true
                            runCatching { container.ytDlp.update(settings.ytDlpChannel) }
                                .onSuccess { context.toast(it) }
                                .onFailure { context.toast(it.friendlyMessage()) }
                            updating = false
                        }
                    },
                    Modifier.fillMaxWidth(),
                    loading = updating,
                    enabled = yt is YtDlpManager.State.Ready,
                )
            }
        }

        item {
            SectionHeader("外观")
            Text("主题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            Gap(8.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (m, l) ->
                    OutlinePill(l, onClick = { scope.launch { repo.setThemeMode(m) } }, selected = settings.themeMode == m)
                }
            }
            Gap(14.dp)
            Text("配色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            Gap(8.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ColorSource.BRAND to "纸与墨", ColorSource.DYNAMIC to "跟随壁纸取色").forEach { (c, l) ->
                    OutlinePill(l, onClick = { scope.launch { repo.setColorSource(c) } }, selected = settings.colorSource == c)
                }
            }
        }

        item {
            SectionHeader("设置")
            Column {
                SettingRow("下载与存储", subtitle = folderSummary(settings.imageFolderUri, settings.videoFolderUri) + " · 并发 ${settings.downloadConcurrency}", icon = Icons.Rounded.Folder, onClick = { nav.push(Page.Storage) })
                SettingRow("识图引擎", subtitle = "${settings.searchEngines.size} 个启用" + if (settings.sauceNaoKey.isBlank()) " · SauceNAO 未填 Key" else "", icon = Icons.Rounded.ImageSearch, onClick = { nav.push(Page.Engines) })
                SettingRow(
                    "回到应用时识别剪贴板链接",
                    subtitle = "系统会提示「拾图读取了剪贴板」",
                    icon = Icons.Rounded.ContentPaste,
                    trailing = { Switch(settings.clipboardDetect, { v -> scope.launch { repo.setClipboardDetect(v) } }) },
                )
                SettingRow("检查更新", subtitle = "当前 ${BuildConfig.VERSION_NAME}", icon = Icons.Rounded.SystemUpdate, onClick = {
                    scope.launch { checkUpdate(context) }
                })
                SettingRow("开源许可与声明", subtitle = "GPL-3.0 · 求原图 MIT · 第三方库", icon = Icons.Rounded.Gavel, onClick = { nav.push(Page.Licenses) })
                SettingRow("赞助", subtitle = "觉得好用，请作者喝杯咖啡", icon = Icons.Rounded.Favorite, onClick = { sponsor = true })
                if (settings.developerOptionsEnabled) {
                    SettingRow("开发者选项", subtitle = "网络日志、调试信息", icon = Icons.Rounded.Code, onClick = { nav.push(Page.Developer) })
                }
                SettingRow("版本", subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = Icons.Rounded.Info, onClick = {
                    if (settings.developerOptionsEnabled) return@SettingRow
                    taps++
                    when {
                        taps >= 7 -> { scope.launch { repo.setDeveloperOptionsEnabled(true) }; context.toast("开发者选项已开启") }
                        taps >= 4 -> context.toast("再点 ${7 - taps} 次开启开发者选项")
                    }
                })
            }
        }
    }
}

private fun folderSummary(image: String?, video: String?) = when {
    image == null && video == null -> "默认：Download/拾图"
    else -> "自定义文件夹"
}

private suspend fun checkUpdate(context: android.content.Context) {
    val repo = BuildConfig.GITHUB_REPO
    if (repo.isBlank()) {
        context.toast("这个版本还没有配置发布仓库")
        return
    }
    runCatching {
        val body = Http.client.getText("https://api.github.com/repos/$repo/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
        Http.json.parseToJsonElement(body).jsonObject
    }.onSuccess { o ->
        val tag = o.str("tag_name").orEmpty().removePrefix("v")
        if (tag.isNotBlank() && tag != BuildConfig.VERSION_NAME) {
            context.toast("有新版本 $tag")
            context.openUrl(o.str("html_url") ?: "https://github.com/$repo/releases")
        } else context.toast("已经是最新版")
    }.onFailure { context.toast(it.friendlyMessage()) }
}

@Suppress("unused")
private fun shareText(context: android.content.Context, text: String) =
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
