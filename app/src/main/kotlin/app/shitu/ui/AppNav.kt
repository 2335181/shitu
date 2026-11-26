package app.shitu.ui

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shitu.feature.account.Site
import app.shitu.navigation.ShituTab

sealed interface Page {
    data object Downloads : Page
    data object Accounts : Page
    data object BiliLogin : Page
    data class WebLogin(val site: Site) : Page
    data object Engines : Page
    data object Storage : Page
    data object Licenses : Page
    data object Sponsor : Page
    data object Developer : Page
}

class SelectionActions(
    val count: Int,
    val total: Int,
    val onToggleAll: () -> Unit,
    val onDownload: () -> Unit,
)

/** 全应用导航状态：当前标签 + 叠在标签上的二级页面栈 + 外部分享进来的内容。 */
@Stable
class AppNav {
    var tab by mutableStateOf(ShituTab.SEARCH)
    val pages = mutableStateListOf<Page>()

    /** 分享进来的图片，识图页取走后清空。 */
    var sharedImage by mutableStateOf<Uri?>(null)

    /** 分享或剪贴板识别到的链接文字，链接解析工具取走后清空。 */
    var pendingLink by mutableStateOf<String?>(null)

    /** 当前工具页登记的批量选择操作，由导航栏上方的悬浮按钮显示。 */
    var selection by mutableStateOf<SelectionActions?>(null)

    val top: Page? get() = pages.lastOrNull()

    fun push(page: Page) {
        pages += page
    }

    fun pop(): Boolean {
        if (pages.isEmpty()) return false
        pages.removeAt(pages.lastIndex)
        return true
    }
}
