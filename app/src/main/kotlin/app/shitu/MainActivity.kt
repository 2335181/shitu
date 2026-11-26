package app.shitu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import app.shitu.navigation.ShituTab
import app.shitu.tools.ToolCatalog
import app.shitu.ui.AppNav
import app.shitu.ui.ShituRoot
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val nav = AppNav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ShituApp).container
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ShituRoot(container = container, nav = nav) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        val container = (application as ShituApp).container
        when {
            type.startsWith("image/") -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
                nav.sharedImage = uri
                nav.pages.clear()
                nav.tab = ShituTab.SEARCH
            }
            type.startsWith("text/") -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val target = if (text.contains("bilibili.com/h5/mall")) {
                    if (text.contains("suit")) ToolCatalog.BILI_SUIT else ToolCatalog.BILI_COLLECTION
                } else ToolCatalog.LINK_PARSE
                runBlocking { container.workspaceRepository.open(target) }
                nav.pendingLink = text
                nav.pages.clear()
                nav.tab = ShituTab.WORKSPACE
            }
        }
    }
}
