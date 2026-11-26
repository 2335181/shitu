package app.shitu

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.shitu.data.AppSettings
import app.shitu.data.SettingsRepository
import app.shitu.data.ToolStateStore
import app.shitu.data.WorkspaceRepository
import app.shitu.download.DownloadConfig
import app.shitu.download.DownloadManager
import app.shitu.feature.account.CookieVault
import app.shitu.feature.ytdlp.YtDlpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 手写依赖容器。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { appContext.preferencesDataStoreFile(DATA_STORE_NAME) },
    )

    val settingsRepository = SettingsRepository(dataStore)
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    val workspaceRepository = WorkspaceRepository(dataStore)
    val toolStateStore = ToolStateStore(appContext.filesDir)

    val cookieVault = CookieVault(appContext)
    val ytDlp = YtDlpManager(appContext, cookieVault)

    val telegramStickers = app.shitu.feature.sticker.TelegramStickers(appContext) { settings.value.telegramBotToken }

    val downloadManager = DownloadManager(appContext) {
        val s = settings.value
        DownloadConfig(s.downloadConcurrency, s.imageFolderUri, s.videoFolderUri)
    }.also {
        it.ytDlpExecutor = ytDlp
        it.ugoiraExecutor = app.shitu.feature.convert.UgoiraConverter(appContext)
        it.stickerExecutor = telegramStickers
        app.shitu.download.MediaSaver.onSaved = { saved, name, mime, kind -> it.record(saved, name, mime, kind) }
    }

    val searchSession by lazy {
        app.shitu.ui.search.SearchSession(app.shitu.feature.search.SearchEngines({ settings.value.sauceNaoKey }, { settings.value.gelbooruAuth }, { settings.value.danbooruAuth }))
    }

    private companion object {
        const val DATA_STORE_NAME = "shitu_preferences"
    }
}
