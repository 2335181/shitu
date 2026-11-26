package app.shitu.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.data.WorkspaceState
import app.shitu.navigation.ShituTab
import app.shitu.tools.ToolCatalog
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.Page
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Eyebrow
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.design.Lede
import app.shitu.ui.design.OutlinePill
import app.shitu.ui.design.PaperCard
import app.shitu.ui.workspace.tools.BiliAssetTool
import app.shitu.ui.workspace.tools.DouyinLiveTool
import app.shitu.ui.workspace.tools.LinkParseTool
import app.shitu.ui.workspace.tools.MusicCoverTool
import app.shitu.ui.workspace.tools.VideoGifTool
import kotlinx.coroutines.launch

/** 最近（原工作台）：顶部是最近工具切换，下面是当前工具本身。 */
@Composable
fun WorkspaceScreen(padding: PaddingValues) {
    val container = LocalContainer.current
    val state by container.workspaceRepository.state.collectAsStateWithLifecycle(WorkspaceState(currentToolId = "…"))
    if (state.currentToolId == "…") return
    val header: LazyListScope.() -> Unit = { item(key = "ws-header") { WorkspaceHeader(state) } }
    val tool = ToolCatalog.find(state.currentToolId)
    key(tool?.screen) {
        when (tool?.screen) {
            ToolCatalog.LINK_PARSE -> LinkParseTool(padding, header, state.platformHint)
            ToolCatalog.BILI_COLLECTION -> BiliAssetTool(padding, header, suit = false)
            ToolCatalog.BILI_SUIT -> BiliAssetTool(padding, header, suit = true)
            ToolCatalog.VIDEO_GIF -> VideoGifTool(padding, header)
            ToolCatalog.MUSIC_COVER -> MusicCoverTool(padding, header)
            ToolCatalog.DOUYIN_LIVE -> DouyinLiveTool(padding, header)
            ToolCatalog.UGOIRA -> app.shitu.ui.workspace.tools.UgoiraTool(padding, header)
            ToolCatalog.LIVE_PHOTO -> app.shitu.ui.workspace.tools.LivePhotoTool(padding, header)
            ToolCatalog.TG_STICKER -> app.shitu.ui.workspace.tools.TgStickerTool(padding, header)
            ToolCatalog.ANIM_EMOJI -> app.shitu.ui.workspace.tools.AnimEmojiTool(padding, header)
            ToolCatalog.WALLPAPER_ENGINE -> app.shitu.ui.workspace.tools.WallpaperEngineTool(padding, header)
            else -> EmptyWorkspace(padding)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceHeader(state: WorkspaceState) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val tasks by container.downloadManager.tasks.collectAsStateWithLifecycle()
    val active = tasks.count { it.active }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("最近 · 上次用到这里", Modifier.weight(1f))
            OutlinePill(
                if (active > 0) "下载 $active" else "下载",
                onClick = { nav.push(Page.Downloads) },
                icon = Icons.Rounded.Download,
                selected = active > 0,
            )
        }
        val recent = state.recentToolIds.mapNotNull(ToolCatalog::find).filter { it.implemented }
        if (recent.size > 1) {
            Gap(14.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { t ->
                    OutlinePill(t.title, onClick = { scope.launch { container.workspaceRepository.open(t.id) } }, selected = t.id == state.currentToolId)
                }
            }
        }
        Gap(18.dp)
    }
}

@Composable
private fun EmptyWorkspace(padding: PaddingValues) {
    val nav = LocalNav.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Gap(8.dp)
            Eyebrow("最近")
            Gap(14.dp)
            EditorialTitle("还没开始，\n先挑个*工具*。", style = MaterialTheme.typography.displaySmall)
            Gap(14.dp)
            Lede("这里会一直留着你上次用的工具、输入和结果，关掉应用再回来也还在。")
            Gap(24.dp)
            PaperCard(Modifier.fillMaxWidth()) {
                Text("最常用的三个", style = MaterialTheme.typography.titleLarge)
                Gap(6.dp)
                Text("万能链接解析、B站收藏集、装扮下载", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(16.dp)
                InkButton("去工具页", onClick = { nav.tab = ShituTab.TOOLS }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
