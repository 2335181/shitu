package app.shitu.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shitu.navigation.ShituTab
import app.shitu.tools.ToolCatalog
import app.shitu.tools.ToolInfo
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Eyebrow
import app.shitu.ui.design.Gap
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.OutlinePill
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.theme.LocalShituPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val open: (ToolInfo) -> Unit = { tool ->
        scope.launch {
            container.workspaceRepository.open(tool.screen)
            container.workspaceRepository.setPlatformHint(
                if (tool.screen == ToolCatalog.LINK_PARSE && tool.id != ToolCatalog.LINK_PARSE) tool.title else null,
            )
            nav.tab = ShituTab.WORKSPACE
        }
    }
    val featured = ToolCatalog.tools.filter { it.featured }
    val platforms = ToolCatalog.tools.filter { it.screen == ToolCatalog.LINK_PARSE && it.id != ToolCatalog.LINK_PARSE }
    val others = ToolCatalog.tools.filter { it.implemented && !it.featured && it.screen != ToolCatalog.LINK_PARSE }
    val soon = ToolCatalog.tools.filter { !it.implemented }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Gap(8.dp)
            Eyebrow("链接解析 · B站素材 · 转换")
            Gap(14.dp)
            EditorialTitle("常用的都在，\n其它交给 *yt-dlp*。", style = MaterialTheme.typography.displaySmall)
            Gap(14.dp)
            Lede("把 App 里「复制链接」得到的整段文字贴进来就行，网址会自动认出来。")
            Gap(24.dp)
        }
        items(featured, key = { it.id }) { tool ->
            FeaturedCard(tool) { open(tool) }
            Gap(14.dp)
        }
        item { SectionHeader("更多工具") }
        items(others, key = { it.id }) { tool ->
            FeaturedCard(tool, compact = true) { open(tool) }
            Gap(12.dp)
        }
        item {
            SectionHeader("万能解析支持的平台")
            Text(
                "这些都走「万能链接解析」，点一个直接打开；没列出的网站也可以试试，交给 yt-dlp。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(14.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                platforms.forEach { OutlinePill(it.title, onClick = { open(it) }) }
            }
        }
        if (soon.isNotEmpty()) item {
            SectionHeader("即将推出")
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                soon.groupBy { it.category }.forEach { (cat, list) ->
                    Column {
                        Text(cat, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                        Text(
                            list.joinToString("、") { it.title },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(tool: ToolInfo, compact: Boolean = false, onClick: () -> Unit) {
    MistCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tool.title,
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward, null,
                tint = LocalShituPalette.current.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Gap(8.dp)
        Text(
            tool.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Gap(14.dp)
        DotTags(tool.tags)
    }
}
