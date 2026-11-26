package app.shitu.ui.workspace

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import app.shitu.ui.LocalContainer
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.KSerializer

/**
 * 读取并自动保存某个工具的状态。返回 null 表示还在从磁盘读取。
 * 每次修改立即记到内存、300ms 后写盘（原子写）；无论从「工具」页还是「最近」页进来，
 * 拿到的都是同一份最新状态，进程被杀也能恢复到最后一次输入。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun <T> rememberToolState(id: String, serializer: KSerializer<T>, default: () -> T): MutableState<T>? {
    val store = LocalContainer.current.toolStateStore
    val loaded by produceState(store.peek<T>(id), id) { value = store.load(id, serializer) ?: default() }
    val initial = loaded ?: return null
    val state = remember(id) { mutableStateOf<T>(initial) }
    LaunchedEffect(id) {
        snapshotFlow { state.value }.collect { store.remember(id, it) }
    }
    LaunchedEffect(id) {
        snapshotFlow { state.value }.debounce(300).collect { store.saveAsync(id, serializer, it) }
    }
    // 离开页面时立即写一次，防止 debounce 里还没落盘的最后一次修改丢失
    DisposableEffect(id) { onDispose { store.saveAsync(id, serializer, state.value) } }
    return state
}

private val scrollMemory = HashMap<String, Pair<Int, Int>>()

/** 工具页的滚动位置：切走再回来停在原处。 */
@Composable
fun rememberToolListState(id: String): LazyListState {
    val saved = scrollMemory[id]
    val state = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    DisposableEffect(id) {
        onDispose { scrollMemory[id] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    return state
}
