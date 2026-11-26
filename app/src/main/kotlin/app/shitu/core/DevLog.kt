package app.shitu.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 开发者日志：内存里保留最近 300 条网络请求与错误，「我的 → 开发者选项」查看。
 * 只记方法、域名和路径，不记查询参数、请求头与 Cookie（避免 API Key / Token 外泄）。
 */
object DevLog {
    enum class Level { NET, WARN, ERROR }
    data class Entry(val time: Long, val level: Level, val tag: String, val message: String)

    private const val MAX = 300
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun net(message: String) = add(Level.NET, "HTTP", message)
    fun warn(tag: String, message: String) = add(Level.WARN, tag, message)
    fun error(tag: String, message: String, e: Throwable? = null) =
        add(Level.ERROR, tag, if (e == null) message else "$message\n${e.javaClass.simpleName}: ${e.message?.take(600)}")

    fun clear() { _entries.value = emptyList() }

    private fun add(level: Level, tag: String, message: String) {
        if (level != Level.NET) android.util.Log.w("ShituDev", "[$tag] $message")
        _entries.update { (it + Entry(System.currentTimeMillis(), level, tag, message)).takeLast(MAX) }
    }

    fun format(list: List<Entry> = entries.value): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        return list.joinToString("\n") { "${fmt.format(Date(it.time))} ${it.level} [${it.tag}] ${it.message}" }
    }

    /** OkHttp 拦截器：记录每个请求的结果与耗时。 */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val where = req.method + " " + req.url.host + req.url.encodedPath.take(80)
        val start = System.nanoTime()
        val res: Response = try {
            chain.proceed(req)
        } catch (e: Exception) {
            add(Level.ERROR, "HTTP", "$where 失败：${e.javaClass.simpleName} ${e.message?.take(200).orEmpty()}")
            throw e
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val type = res.header("Content-Type").orEmpty().substringBefore(';')
        add(if (res.code >= 400) Level.WARN else Level.NET, "HTTP", "$where → ${res.code} $type ${ms}ms")
        res
    }
}
