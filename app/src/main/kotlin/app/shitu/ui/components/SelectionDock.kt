package app.shitu.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.shitu.ui.SelectionActions
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/** 导航栏上方右侧的两个圆形悬浮按钮：全选 / 下载（长按进入下载队列）。材质与导航栏相同。 */
@Composable
fun SelectionDock(actions: SelectionActions, hazeState: HazeState, onOpenQueue: () -> Unit, modifier: Modifier = Modifier) {
    val allSelected = actions.total > 0 && actions.count == actions.total
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(end = NavBarHorizontalMargin, bottom = NavBarBottomMargin + NavBarHeight + 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        GlassCircle(
            icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
            label = if (allSelected) "取消全选" else "全选",
            hazeState = hazeState,
            onClick = actions.onToggleAll,
        )
        GlassCircle(
            icon = Icons.Rounded.Download,
            label = "下载选中的 ${actions.count} 项，长按打开下载队列",
            hazeState = hazeState,
            badge = actions.count,
            primary = actions.count > 0,
            onClick = actions.onDownload,
            onLongClick = onOpenQueue,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassCircle(
    icon: ImageVector,
    label: String,
    hazeState: HazeState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    badge: Int = 0,
    primary: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    BadgedBox(badge = { if (badge > 0) Badge(containerColor = scheme.primary, contentColor = scheme.onPrimary) { Text(badge.toString()) } }) {
        Box(
            Modifier
                .size(58.dp)
                .shadow(14.dp, CircleShape, ambientColor = scheme.scrim, spotColor = scheme.scrim)
                .clip(CircleShape)
                .hazeBlur(input = HazeInput.Sources(hazeState), style = HazeMaterials.regular(scheme.surfaceContainerLowest))
                .border(1.dp, scheme.outlineVariant, CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClick = onLongClick?.let { f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } },
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (primary) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
    }
}
