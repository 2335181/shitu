package app.shitu.download

import kotlinx.serialization.Serializable

enum class MediaKind { IMAGE, VIDEO, AUDIO, OTHER }

enum class TaskState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

@Serializable
sealed interface DownloadSource {
    /** 普通 HTTP 下载。mirrors 是同一文件的 CDN 备份，失败时依次换。 */
    @Serializable
    data class Direct(
        val url: String,
        val mirrors: List<String> = emptyList(),
        val headers: Map<String, String> = emptyMap(),
    ) : DownloadSource

    /** 交给 yt-dlp 下载。format 为空时用默认最佳格式。 */
    @Serializable
    data class YtDlp(
        val url: String,
        val format: String? = null,
        val audioOnly: Boolean = false,
    ) : DownloadSource

    /** Pixiv 动图：下载帧包，按每帧延时合成 GIF。 */
    @Serializable
    data class Ugoira(
        val zipUrl: String,
        val frames: List<app.shitu.feature.link.UgoiraFrame>,
        val headers: Map<String, String> = emptyMap(),
    ) : DownloadSource

    /** Telegram 贴纸：运行时用设置里的 Token 换下载地址，Token 不进下载记录。 */
    @Serializable
    data class Sticker(
        val fileId: String,
        /** StickerFormat 的名字 */
        val format: String,
        val keepOriginal: Boolean = false,
    ) : DownloadSource

    /** 本机工具生成的文件，只是一条记录，不能重试。 */
    @Serializable
    data class Local(val uri: String) : DownloadSource
}

@Serializable
data class DownloadRequest(
    val source: DownloadSource,
    /** 不含扩展名也可以，保存时会按 Content-Type 补。 */
    val fileName: String,
    val kind: MediaKind,
    /** 来源说明，如「初音未来live集 · 卡片」。 */
    val group: String = "",
    val thumbnail: String? = null,
    /** 默认文件夹下再建的子目录，例如贴纸包名。 */
    val subDir: String? = null,
)

@Serializable
data class DownloadTask(
    val id: Long,
    val request: DownloadRequest,
    val state: TaskState = TaskState.QUEUED,
    val downloaded: Long = 0,
    val total: Long = -1,
    val error: String? = null,
    val savedTo: String? = null,
    /** 保存后的 content:// 地址与类型，下载页点一下用默认应用打开。 */
    val savedUri: String? = null,
    val savedMime: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val active get() = state == TaskState.QUEUED || state == TaskState.RUNNING
    val progress: Float? get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
}
