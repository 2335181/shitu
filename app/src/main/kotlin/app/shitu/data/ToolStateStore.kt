package app.shitu.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the state of one tool as JSON in `filesDir/tool_state/<toolId>.json`.
 *
 * Writes go to a temporary sibling file first and are then renamed over the target, so a process
 * death can never leave a half-written state file behind.
 */
class ToolStateStore(
    filesDir: File,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) {
    private val stateDir = File(filesDir, STATE_DIRECTORY)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * 进程内的最新状态。页面重新进入时先用它，不再依赖磁盘写入的先后，
     * 避免「刚离开就从工具页点回来」时读到旧文件。
     */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(id: String): T? = memory[id] as T?

    fun <T> remember(id: String, value: T) {
        if (value != null) memory[id] = value
    }

    suspend fun <T> load(id: String, serializer: KSerializer<T>): T? = withContext(ioDispatcher) {
        @Suppress("UNCHECKED_CAST")
        (memory[id] as T?)?.let { return@withContext it }
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrNull()?.also { memory[id] = it as Any }
    }

    suspend fun <T> save(id: String, serializer: KSerializer<T>, value: T) {
        withContext(ioDispatcher + NonCancellable) {
            runCatching { writeAtomically(id, serializer, value) }
        }
    }

    /**
     * Queues a save on a store-owned single-threaded scope. UI can leave the screen immediately
     * after the last keystroke without losing it.
     */
    fun <T> saveAsync(id: String, serializer: KSerializer<T>, value: T) {
        remember(id, value)
        writeScope.launch {
            runCatching { writeAtomically(id, serializer, value) }
        }
    }

    private fun <T> writeAtomically(id: String, serializer: KSerializer<T>, value: T) {
        if (!stateDir.exists() && !stateDir.mkdirs()) return

        val target = fileFor(id)
        val temporary = File(stateDir, "${target.name}.tmp")
        val bytes = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }

        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun fileFor(id: String): File {
        val safeId = id.replace(UNSAFE_FILE_NAME_CHARACTERS, "_")
        return File(stateDir, "$safeId.json")
    }

    private companion object {
        const val STATE_DIRECTORY = "tool_state"
        val UNSAFE_FILE_NAME_CHARACTERS = Regex("[^A-Za-z0-9._-]")
    }
}
