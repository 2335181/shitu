package app.shitu.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The single source of truth for the workspace: the currently open tool plus the five most recent
 * tools, newest first and de-duplicated.
 */
class WorkspaceRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val state: Flow<WorkspaceState> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            WorkspaceState(
                currentToolId = preferences[CURRENT_TOOL_ID]?.takeIf { it.isNotBlank() },
                recentToolIds = preferences[RECENT_TOOL_IDS].decodeRecentToolIds(),
                platformHint = preferences[PLATFORM_HINT],
            )
        }

    suspend fun open(toolId: String) {
        dataStore.edit { preferences ->
            preferences[CURRENT_TOOL_ID] = toolId
            val recent = preferences[RECENT_TOOL_IDS].decodeRecentToolIds()
            val updated = (listOf(toolId) + recent.filterNot { it == toolId }).take(MAX_RECENT_TOOLS)
            preferences[RECENT_TOOL_IDS] =
                json.encodeToString(ListSerializer(String.serializer()), updated)
        }
    }

    /** 从「工具」页点某个平台进入链接解析时，记下平台名做输入提示。 */
    suspend fun setPlatformHint(name: String?) {
        dataStore.edit { if (name == null) it.remove(PLATFORM_HINT) else it[PLATFORM_HINT] = name }
    }

    private fun String?.decodeRecentToolIds(): List<String> {
        if (this.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), this)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_RECENT_TOOLS = 5
        val CURRENT_TOOL_ID = stringPreferencesKey("workspace_current_tool_id")
        val RECENT_TOOL_IDS = stringPreferencesKey("workspace_recent_tool_ids")
        val PLATFORM_HINT = stringPreferencesKey("workspace_platform_hint")
    }
}

data class WorkspaceState(
    val currentToolId: String? = null,
    val recentToolIds: List<String> = emptyList(),
    val platformHint: String? = null,
)
