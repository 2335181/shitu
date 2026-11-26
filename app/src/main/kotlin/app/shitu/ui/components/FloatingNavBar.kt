package app.shitu.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shitu.navigation.ShituTab
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/** 悬浮毛玻璃胶囊导航栏：选中项是墨色胶囊。 */
@Composable
fun FloatingNavBar(
    selectedTab: ShituTab,
    onTabSelected: (ShituTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    workspaceBadgeCount: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = NavBarHorizontalMargin, end = NavBarHorizontalMargin, bottom = NavBarBottomMargin),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavBarHeight)
                .shadow(18.dp, CircleShape, ambientColor = scheme.scrim, spotColor = scheme.scrim)
                .clip(CircleShape)
                .hazeBlur(input = HazeInput.Sources(hazeState), style = HazeMaterials.regular(scheme.surfaceContainerLowest))
                .border(1.dp, scheme.outlineVariant, CircleShape)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ShituTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = tab == selectedTab,
                    badge = if (tab == ShituTab.WORKSPACE) workspaceBadgeCount else 0,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(tab: ShituTab, selected: Boolean, badge: Int, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val spec = MaterialTheme.motionScheme.fastEffectsSpec<androidx.compose.ui.graphics.Color>()
    val bg by animateColorAsState(if (selected) scheme.primary else scheme.primary.copy(alpha = 0f), spec, label = "navbg")
    val fg by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, spec, label = "navfg")
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .background(bg)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
                Icon(tab.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(tab.labelRes),
                color = fg,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
