package app.shitu.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { p ->
            AppSettings(
                themeMode = enumOr(p[THEME_MODE], ThemeMode.SYSTEM),
                colorSource = enumOr(p[COLOR_SOURCE], ColorSource.BRAND),
                developerOptionsEnabled = p[DEVELOPER_OPTIONS] ?: false,
                imageFolderUri = p[IMAGE_FOLDER],
                videoFolderUri = p[VIDEO_FOLDER],
                downloadConcurrency = p[CONCURRENCY] ?: 3,
                clipboardDetect = p[CLIPBOARD] ?: true,
                searchEngines = p[ENGINES]?.split(',')?.filter { it.isNotBlank() }?.let { saved ->
                    // v0.4 新增图站引擎：老用户自动追加到末尾
                    if ("danbooru" in saved || p[ENGINES_V4] == true) saved else saved + BOORU_IDS
                } ?: AppSettings.DEFAULT_ENGINES,
                sauceNaoKey = p[SAUCENAO_KEY].orEmpty(),
                gelbooruAuth = p[GELBOORU_AUTH].orEmpty(),
                imageMaxSide = p[IMAGE_MAX_SIDE] ?: 2048,
                ytDlpChannel = enumOr(p[YTDLP_CHANNEL], YtDlpChannel.STABLE),
                telegramBotToken = p[TG_TOKEN].orEmpty(),
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[THEME_MODE] = mode.name }
    suspend fun setColorSource(source: ColorSource) = edit { it[COLOR_SOURCE] = source.name }
    suspend fun setDeveloperOptionsEnabled(enabled: Boolean) = edit { it[DEVELOPER_OPTIONS] = enabled }
    suspend fun setImageFolder(uri: String?) = edit { if (uri == null) it.remove(IMAGE_FOLDER) else it[IMAGE_FOLDER] = uri }
    suspend fun setVideoFolder(uri: String?) = edit { if (uri == null) it.remove(VIDEO_FOLDER) else it[VIDEO_FOLDER] = uri }
    suspend fun setConcurrency(value: Int) = edit { it[CONCURRENCY] = value.coerceIn(1, 8) }
    suspend fun setClipboardDetect(value: Boolean) = edit { it[CLIPBOARD] = value }
    suspend fun setSearchEngines(ids: List<String>) = edit { it[ENGINES] = ids.joinToString(","); it[ENGINES_V4] = true }
    suspend fun setGelbooruAuth(v: String) = edit { it[GELBOORU_AUTH] = v.trim() }
    suspend fun setSauceNaoKey(key: String) = edit { it[SAUCENAO_KEY] = key.trim() }
    suspend fun setImageMaxSide(value: Int) = edit { it[IMAGE_MAX_SIDE] = value }
    suspend fun setYtDlpChannel(channel: YtDlpChannel) = edit { it[YTDLP_CHANNEL] = channel.name }
    suspend fun setTelegramBotToken(token: String) = edit { it[TG_TOKEN] = token.trim() }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: default

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_SOURCE = stringPreferencesKey("color_source_v2")
        val DEVELOPER_OPTIONS = booleanPreferencesKey("developer_options_enabled")
        val IMAGE_FOLDER = stringPreferencesKey("image_folder_uri")
        val VIDEO_FOLDER = stringPreferencesKey("video_folder_uri")
        val CONCURRENCY = intPreferencesKey("download_concurrency")
        val CLIPBOARD = booleanPreferencesKey("clipboard_detect")
        val ENGINES = stringPreferencesKey("search_engines")
        val ENGINES_V4 = booleanPreferencesKey("search_engines_v4")
        val BOORU_IDS = listOf("danbooru", "yandere", "konachan", "gelbooru", "sankaku")
        val SAUCENAO_KEY = stringPreferencesKey("saucenao_key")
        val GELBOORU_AUTH = stringPreferencesKey("gelbooru_auth")
        val IMAGE_MAX_SIDE = intPreferencesKey("image_max_side")
        val YTDLP_CHANNEL = stringPreferencesKey("ytdlp_channel")
        val TG_TOKEN = stringPreferencesKey("telegram_bot_token")
    }
}
