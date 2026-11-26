package app.shitu.ui.workspace.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shitu.core.friendlyMessage
import app.shitu.core.toast
import app.shitu.download.DownloadRequest
import app.shitu.download.DownloadSource
import app.shitu.download.MediaKind
import app.shitu.feature.link.LinkMedia
import app.shitu.feature.link.LinkMediaType
import app.shitu.feature.link.LinkParser
import app.shitu.feature.link.LinkResult
import app.shitu.tools.ToolCatalog
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.Page
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Gap
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.design.SoftButton
import app.shitu.ui.workspace.rememberToolState
import java.net.URI
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class LinkToolState(
    val input: String = "",
    val result: LinkResult? = null,
    val selected: Set<Int> = emptySet(),
)

@Composable
fun LinkParseTool(padding: PaddingValues, header: LazyListScope.() -> Unit, platformHint: String?) {
    val holder = rememberToolState(ToolCatalog.LINK_PARSE, LinkToolState.serializer()) { LinkToolState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState(st.input)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun parse() {
        scope.launch {
            loading = true; error = null
            runCatching {
                LinkParser.parse(input.text.toString(), container.ytDlp) { domain -> container.cookieVault.headerFor(domain) }
            }.onSuccess { r ->
                st = st.copy(result = r, selected = r.media.indices.filter { r.media[it].type != LinkMediaType.AUDIO }.take(1).toSet())
            }.onFailure { error = it.friendlyMessage() }
            loading = false
        }
    }

    LaunchedEffect(nav.pendingLink) {
        nav.pendingLink?.let {
            input.setTextAndPlaceCursorAtEnd(it); nav.pendingLink = null
            parse()
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { input.text.toString() }.collect { st = st.copy(input = it) } }

    val result = st.result
    if (result != null) {
    RegisterSelection(
                    count = st.selected.size,
                    total = result.media.size,
                    onToggleAll = {
                        st = st.copy(selected = if (st.selected.size == result.media.size) emptySet() else result.media.indices.toSet())
                    },
                    onDownload = {
                        val reqs = st.selected.sorted().flatMap { result.media[it].toRequests(result) }
                        val n = container.downloadManager.enqueue(reqs)
                        context.toast("已加入下载队列 $n 个")
                    },
                )
    }
    LazyColumn(Modifier.fillMaxSize(), state = app.shitu.ui.workspace.rememberToolListState(ToolCatalog.LINK_PARSE), contentPadding = padding) {
        header()
        item {
            EditorialTitle(
                if (platformHint != null) "${platformHint}，\n贴进来就*解析*。" else "把链接定格成\n*原图*与视频。",
                style = MaterialTheme.typography.displaySmall,
            )
            Gap(12.dp)
            Lede("抖音、小红书、快手、B站、Pixiv、Pinterest、YouTube、X 里复制的整段分享文字直接贴进来就行。视频没有水印，图片是原图。")
            Gap(22.dp)
            InputCard(input, "粘贴分享链接…", "解析", ::parse, loading)
            error?.let { msg ->
                Gap(12.dp)
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (msg.contains("登录")) {
                    Gap(8.dp)
                    SoftButton("去登录", { nav.push(Page.Accounts) }, compact = true)
                }
            }
        }
        if (result != null) {
            item {
                Gap(26.dp)
                MistCard(Modifier.fillMaxWidth()) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Gap(8.dp)
                    DotTags(listOf(result.platform, result.author, result.note, "${result.media.size} 项").filter { it.isNotBlank() })
                }
                SectionHeader("可以下载的")
            }
            val rows = result.media.indices.toList().chunked(2)
            items(rows.size, key = { "m$it" }) { i ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows[i].forEach { idx ->
                        val m = result.media[idx]
                        MediaTile(
                            url = m.preview.ifBlank { result.cover },
                            label = m.label.ifBlank { typeLabel(m) },
                            selected = idx in st.selected,
                            isVideo = m.type == LinkMediaType.VIDEO,
                            referer = m.headers["Referer"],
                            badge = typeLabel(m),
                            ratio = 0.8f,
                            onClick = { st = st.copy(selected = if (idx in st.selected) st.selected - idx else st.selected + idx) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rows[i].size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                SelectionCount(st.selected.size, result.media.size)
                Gap(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "需要登录的内容，先去账号里登录对应站点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    SoftButton("下载队列", { nav.push(Page.Downloads) }, icon = Icons.Rounded.Download, compact = true)
                }
            }
        } else {
            item {
                SectionHeader("能认出的平台")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        Triple("抖音", "无水印视频、图集原图、实况图、背景音乐。", listOf("视频", "图集", "实况", "音乐")),
                        Triple("小红书", "笔记原图按原始分辨率取，视频无水印，实况图整篇打包。", listOf("原图", "视频", "实况")),
                        Triple("YouTube · X · B站 · 快手", "交给内置 yt-dlp，挑清晰度或只要音频。", listOf("视频", "音频", "封面")),
                    ).forEach { (t, d, tags) ->
                        MistCard(Modifier.fillMaxWidth()) {
                            Text(t, style = MaterialTheme.typography.headlineSmall)
                            Gap(6.dp)
                            Text(d, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Gap(12.dp)
                            DotTags(tags)
                        }
                    }
                }
            }
        }
    }
}

private fun typeLabel(m: LinkMedia) = when (m.type) {
    LinkMediaType.VIDEO -> "视频"
    LinkMediaType.IMAGE -> "图片"
    LinkMediaType.LIVE -> "实况"
    LinkMediaType.AUDIO -> "音频"
    LinkMediaType.UGOIRA -> "动图"
}

private fun LinkMedia.toRequests(r: LinkResult): List<DownloadRequest> {
    val base = r.title.take(40).ifBlank { r.platform } + "_" + label
    if (viaYtDlp) {
        return listOf(
            DownloadRequest(
                DownloadSource.YtDlp(url, ytFormat, audioOnly = type == LinkMediaType.AUDIO),
                base, if (type == LinkMediaType.AUDIO) MediaKind.AUDIO else MediaKind.VIDEO, "${r.platform} · ${r.title.take(20)}", preview,
            ),
        )
    }
    if (type == LinkMediaType.UGOIRA && ugoira != null) {
        return listOf(
            DownloadRequest(
                DownloadSource.Ugoira(url, ugoira.frames, headers),
                base, MediaKind.IMAGE, "${r.platform} · ${r.title.take(20)}", preview,
            ),
        )
    }
    val kind = when (type) {
        LinkMediaType.VIDEO -> MediaKind.VIDEO
        LinkMediaType.AUDIO -> MediaKind.AUDIO
        else -> MediaKind.IMAGE
    }
    val main = DownloadRequest(DownloadSource.Direct(url, headers = headers), base, kind, "${r.platform} · ${r.title.take(20)}", preview)
    val live = liveVideo?.let {
        DownloadRequest(DownloadSource.Direct(it, headers = headers), base + "_实况", MediaKind.VIDEO, main.group, preview)
    }
    return listOfNotNull(main, live)
}

@Suppress("unused")
private fun host(u: String) = runCatching { URI(u).host }.getOrNull()
