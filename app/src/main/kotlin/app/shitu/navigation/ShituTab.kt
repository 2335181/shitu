package app.shitu.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.vector.ImageVector
import app.shitu.R

enum class ShituTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    SEARCH(R.string.tab_search, Icons.Rounded.Search),
    TOOLS(R.string.tab_tools, Icons.Rounded.Apps),
    WORKSPACE(R.string.tab_workspace, Icons.Rounded.Dashboard),
    ME(R.string.tab_me, Icons.Rounded.Person),
}
