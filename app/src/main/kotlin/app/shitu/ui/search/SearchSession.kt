package app.shitu.ui.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shitu.core.Images
import app.shitu.core.friendlyMessage
import app.shitu.feature.search.Engine
import app.shitu.feature.search.EngineResult
import app.shitu.feature.search.SearchEngines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 识图会话：活在应用进程里，切换标签不丢结果。 */
@Stable
class SearchSession(private val engines: SearchEngines) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    var uri by mutableStateOf<Uri?>(null)
        private set
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var crop by mutableStateOf(RectF(0f, 0f, 1f, 1f))
    var cropping by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    var searching by mutableStateOf(false)
        private set
    val results = mutableStateMapOf<Engine, EngineResult>()
    var resultOrder by mutableStateOf<List<Engine>>(emptyList())
        private set

    fun load(context: Context, newUri: Uri) {
        job?.cancel()
        uri = newUri
        loadError = null
        results.clear()
        resultOrder = emptyList()
        crop = RectF(0f, 0f, 1f, 1f)
        cropping = false
        scope.launch {
            bitmap = null
            bitmap = runCatching { Images.decode(context, newUri) }
                .onFailure { loadError = "这张图打不开：${it.friendlyMessage()}" }
                .getOrNull()
        }
    }

    fun clear() {
        job?.cancel()
        uri = null
        bitmap = null
        loadError = null
        results.clear()
        resultOrder = emptyList()
        searching = false
        cropping = false
    }

    fun search(selected: List<Engine>, maxSide: Int) {
        val bmp = bitmap ?: return
        job?.cancel()
        results.clear()
        resultOrder = selected
        selected.forEach { results[it] = EngineResult.Loading }
        searching = true
        cropping = false
        job = scope.launch {
            val bytes = Images.prepare(bmp, crop, maxSide)
            val jobs = selected.map { engine ->
                launch {
                    val r = withContext(Dispatchers.IO) { engines.run(engine, bytes) }
                    results[engine] = r
                }
            }
            jobs.forEach { it.join() }
            searching = false
        }
    }
}
