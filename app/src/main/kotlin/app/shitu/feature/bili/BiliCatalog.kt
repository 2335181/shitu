package app.shitu.feature.bili

import android.content.Context
import app.shitu.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 内置收藏集目录（1,836 条，act_id + 名称）。名称里 `$$` 后面是别名（UP 主、团体名）：参与匹配，不显示。
 *
 * 排序规则与「手机收藏集图片下载器 7.1.7」一致：
 * 关键词逐字计分（目录条目每个命中字 +1、商城接口条目 +1.2、英文字母 +0.5），
 * 完整关键词每出现一次 +100；按 act_id 合并后取前 30。
 * 所以搜「瑞娅」时，名字或别名里带「瑞娅」的收藏集排在最前，带「瑞」「娅」单字的也会列出来。
 */
object BiliCatalog {
    @Serializable
    private data class Raw(val id: Long, val name: String)

    private data class Entry(val id: Long, val name: String, val haystack: String)

    private val lock = Mutex()
    private var entries: List<Entry>? = null

    private suspend fun load(context: Context): List<Entry> = lock.withLock {
        entries ?: withContext(Dispatchers.IO) {
            val text = context.assets.open("bili_catalog.json").bufferedReader().use { it.readText() }
            Http.json.decodeFromString(ListSerializer(Raw.serializer()), text).map {
                Entry(it.id, it.name.substringBefore("$$"), it.name.lowercase())
            }
        }.also { entries = it }
    }

    /** 合并内置目录与商城搜索结果并排序。remote 里的装扮条目会被忽略。 */
    suspend fun rank(context: Context, keyword: String, remote: List<CatalogEntry>, limit: Int = 30): List<CatalogEntry> {
        val name = keyword.trim().replaceFirst("收藏集", "").lowercase()
        if (name.isBlank()) return emptyList()
        val chars = name.filterNot { it.isWhitespace() }.toList()
        val remoteById = remote.filter { !it.isSuit }.associateBy { it.id }

        val scored = LinkedHashMap<Long, Pair<Double, CatalogEntry>>()
        // 目录倒序遍历：同分时新活动在前（与 7.1.7 一致）
        load(context).asReversed().forEach { e ->
            val w = weight(e.haystack, chars, 1.0) + occurrences(e.haystack, name) * 100
            if (w > 0) {
                val api = remoteById[e.id]
                scored[e.id] = w to CatalogEntry(
                    id = e.id, name = e.name,
                    cover = api?.cover.orEmpty(),
                    subtitle = api?.subtitle?.takeIf { it.isNotBlank() } ?: "act_id ${e.id}",
                    lotteryId = api?.lotteryId,
                )
            }
        }
        remoteById.values.forEach { r ->
            if (r.id in scored) return@forEach
            val lower = r.name.lowercase()
            val w = weight(lower, chars, 1.2) + occurrences(lower, name) * 100
            if (w > 0) scored[r.id] = w to r
        }
        return scored.values.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    private fun weight(text: String, chars: List<Char>, base: Double): Double =
        chars.sumOf { c -> if (text.indexOf(c) >= 0) (if (c in 'a'..'z') 0.5 else base) else 0.0 }

    private fun occurrences(text: String, word: String): Int {
        if (word.isEmpty()) return 0
        var count = 0
        var i = text.indexOf(word)
        while (i >= 0) { count++; i = text.indexOf(word, i + word.length) }
        return count
    }
}
