package app.shitu.ui.workspace.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shitu.core.friendlyMessage
import app.shitu.core.toast
import app.shitu.download.DownloadRequest
import app.shitu.download.DownloadSource
import app.shitu.feature.bili.AssetCategory
import app.shitu.feature.bili.BiliAsset
import app.shitu.feature.bili.BiliAssetPack
import app.shitu.feature.bili.BiliCatalog
import app.shitu.feature.bili.BiliGarbApi
import app.shitu.feature.bili.BiliInput
import app.shitu.feature.bili.CatalogEntry
import app.shitu.tools.ToolCatalog
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Gap
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.OutlinePill
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.workspace.rememberToolListState
import app.shitu.ui.workspace.rememberToolState
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class BiliToolState(
    val query: String = "",
    val candidates: List<CatalogEntry> = emptyList(),
    val pack: BiliAssetPack? = null,
    val watermark: Boolean = false,
    val category: AssetCategory? = null,
    val selected: Set<String> = emptySet(),
)

private const val REFERER = "https://www.bilibili.com/"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BiliAssetTool(padding: PaddingValues, header: LazyListScope.() -> Unit, suit: Boolean) {
    val id = if (suit) ToolCatalog.BILI_SUIT else ToolCatalog.BILI_COLLECTION
    val holder = rememberToolState(id, BiliToolState.serializer()) { BiliToolState() } ?: return
    var st by holder
    val context = LocalContext.current
    val container = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState(st.query)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<List<CatalogEntry>?>(null) }


    LaunchedEffect(suit) {
        if (!suit && recent == null) recent = runCatching { BiliGarbApi.recent() }.getOrDefault(emptyList())
    }

    fun openPack(block: suspend () -> BiliAssetPack) {
        scope.launch {
            loading = true; error = null
            runCatching { block() }
                .onSuccess { st = st.copy(pack = it, selected = emptySet(), category = null, candidates = emptyList()) }
                .onFailure { error = it.friendlyMessage() }
            loading = false
        }
    }

    fun openEntry(e: CatalogEntry) = openPack { if (e.isSuit) BiliGarbApi.suit(e.id) else BiliGarbApi.collection(e.id, e.lotteryId) }

    fun submit() {
        when (val parsed = BiliGarbApi.parseInput(input.text.toString(), suit)) {
            is BiliInput.Collection -> openPack { BiliGarbApi.collection(parsed.actId, parsed.lotteryId) }
            is BiliInput.Suit -> openPack { BiliGarbApi.suit(parsed.id) }
            is BiliInput.Keyword -> scope.launch {
                loading = true; error = null
                val keyword = parsed.text.replaceFirst("收藏集", "").trim().ifBlank { parsed.text }
                val r = runCatching { BiliGarbApi.search(keyword) }
                val remote = r.getOrDefault(emptyList())
                val merged = if (suit) remote.filter { it.isSuit } else BiliCatalog.rank(context, parsed.text, remote)
                st = st.copy(candidates = merged, pack = null)
                if (merged.isEmpty()) error = r.exceptionOrNull()?.friendlyMessage() ?: "没找到「${parsed.text}」，换个关键词，或直接贴详情页链接"
                loading = false
            }
        }
    }

    LaunchedEffect(nav.pendingLink) {
        nav.pendingLink?.let { input.setTextAndPlaceCursorAtEnd(it); nav.pendingLink = null; submit() }
    }
    LaunchedEffect(Unit) { snapshotFlow { input.text.toString() }.collect { st = st.copy(query = it) } }

    val pack = st.pack
    val visible = pack?.assets.orEmpty().filter { st.category == null || it.category == st.category }

    if (pack != null) {
    RegisterSelection(
                    count = visible.count { it.key in st.selected },
                    total = visible.size,
                    onToggleAll = {
                        val keys = visible.map { it.key }.toSet()
                        st = st.copy(selected = if (keys.all { it in st.selected }) st.selected - keys else st.selected + keys)
                    },
                    onDownload = {
                        val chosen = visible.filter { it.key in st.selected }
                        val reqs = chosen.mapNotNull { a -> a.toRequest(pack, st.watermark) }
                        val n = container.downloadManager.enqueue(reqs)
                        val missing = chosen.size - reqs.size
                        context.toast(buildString {
                            append("已加入下载队列 $n 个")
                            if (missing > 0) append("，$missing 个没有${if (st.watermark) "有水印" else "无水印"}版本已跳过")
                        })
                        st = st.copy(selected = st.selected - chosen.map { it.key }.toSet())
                    },
                )
    }
    LazyColumn(Modifier.fillMaxSize(), state = rememberToolListState(id), contentPadding = padding) {
        header()
        item {
            EditorialTitle(
                if (suit) "装扮，\n整套*拿走*。" else "收藏集，\n卡片一张*不落*。",
                style = MaterialTheme.typography.displaySmall,
            )
            Gap(12.dp)
            Lede(
                if (suit) "输入装扮名、装扮 ID，或者贴装扮详情页链接。背景、表情、头像框、动画都会列出来。"
                else "输入收藏集名称、act_id，或者贴详情页链接。卡片图、卡片视频、表情、头像框、勋章分类列出。",
            )
            Gap(22.dp)
            InputCard(
                state = input,
                placeholder = if (suit) "装扮名 / ID / 链接…" else "收藏集名称 / act_id / 链接…",
                action = if (suit) "查找装扮" else "查找收藏集",
                onAction = ::submit,
                loading = loading,
            )
            error?.let {
                Gap(12.dp)
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (pack == null && st.candidates.isNotEmpty()) {
            item { SectionHeader("找到 ${st.candidates.size} 个") }
            items(st.candidates, key = { (if (it.isSuit) "s" else "a") + it.id }) { e ->
                EntryRow(e) { openEntry(e) }
                Gap(10.dp)
            }
        }

        if (pack == null && st.candidates.isEmpty() && !suit) {
            item { SectionHeader("近期活动") }
            val list = recent
            if (list == null) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                items(list, key = { "r" + it.id }) { e ->
                    EntryRow(e) { openEntry(e) }
                    Gap(10.dp)
                }
            }
        }

        if (pack != null) {
            item { PackHeader(pack, holder, onLottery = { lid -> openPack { BiliGarbApi.collection(pack.actId!!, lid) } }) }
            val rows = visible.chunked(3)
            items(rows.size, key = { "row$it" }) { i ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows[i].forEach { a ->
                        val variant = if (st.watermark) a.marked else a.clean
                        MediaTile(
                            url = BiliGarbApi.thumb(a.preview),
                            label = a.name,
                            selected = a.key in st.selected,
                            isVideo = a.isVideo,
                            referer = REFERER,
                            ratio = if (a.category == AssetCategory.CARD_IMAGE || a.category == AssetCategory.CARD_VIDEO) 0.68f else 1f,
                            badge = if (variant == null) "无此版本" else null,
                            onClick = {
                                st = st.copy(selected = if (a.key in st.selected) st.selected - a.key else st.selected + a.key)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rows[i].size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item {
                Gap(4.dp)
                SelectionCount(visible.count { it.key in st.selected }, visible.size)
                Gap(10.dp)
                Text(
                    "「无水印 / 有水印」是 B站服务器本来就提供的两个版本，拾图不做去水印处理，也不承诺分辨率。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun BiliAsset.toRequest(pack: BiliAssetPack, watermark: Boolean): DownloadRequest? {
    val v = (if (watermark) marked else clean) ?: return null
    return DownloadRequest(
        source = DownloadSource.Direct(v.url, v.mirrors, mapOf("Referer" to REFERER)),
        fileName = "${pack.title}_${category.label}_$name${if (watermark) "_水印" else ""}",
        kind = kind,
        group = "${pack.title} · ${category.label}",
        thumbnail = preview,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackHeader(pack: BiliAssetPack, holder: MutableState<BiliToolState>, onLottery: (Long) -> Unit) {
    var st by holder
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Gap(26.dp)
        MistCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    BiliGarbApi.thumb(pack.cover), null,
                    modifier = Modifier.size(76.dp).clip(MaterialTheme.shapes.medium).background(scheme.surfaceContainer),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(pack.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (pack.subtitle.isNotBlank()) Text(pack.subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Gap(4.dp)
                    DotTags(listOfNotNull(pack.actId?.let { "act_id $it" }, pack.suitId?.let { "ID $it" }, "${pack.assets.size} 个素材"))
                }
            }
        }
        if (pack.lotteries.size > 1) {
            Gap(16.dp)
            Text("卡池", style = MaterialTheme.typography.labelLarge, color = scheme.outline)
            Gap(8.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pack.lotteries.forEach { l ->
                    OutlinePill(l.name, onClick = { if (l.id != pack.lotteryId) onLottery(l.id) }, selected = l.id == pack.lotteryId)
                }
            }
        }
        Gap(16.dp)
        // 水印切换：两段式胶囊
        Row(
            Modifier.clip(CircleShape).background(scheme.surfaceContainerHigh).padding(4.dp),
        ) {
            listOf(false to "无水印", true to "有水印").forEach { (wm, label) ->
                val on = st.watermark == wm
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (on) scheme.primary else scheme.surfaceContainerHigh)
                        .clickable { st = st.copy(watermark = wm) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
        Gap(14.dp)
        val counts = pack.assets.groupingBy { it.category }.eachCount()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePill("全部 ${pack.assets.size}", onClick = { st = st.copy(category = null) }, selected = st.category == null)
            AssetCategory.entries.filter { it in counts }.forEach { c ->
                OutlinePill("${c.label} ${counts[c]}", onClick = { st = st.copy(category = c) }, selected = st.category == c)
            }
        }
        Gap(16.dp)
    }
}

@Composable
private fun EntryRow(e: CatalogEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MistCard(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(scheme.surfaceContainer)) {
                if (e.cover.isNotBlank()) AsyncImage(BiliGarbApi.thumb(e.cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(if (e.isSuit) "装扮" else "收藏集", e.subtitle).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}
