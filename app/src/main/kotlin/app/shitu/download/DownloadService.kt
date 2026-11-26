package app.shitu.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.shitu.MainActivity
import app.shitu.R
import app.shitu.ShituApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 用户主动发起下载时运行的前台服务（dataSync），队列空了就自己停。 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build("准备下载…", 0, 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val manager = (application as ShituApp).container.downloadManager
        watcher?.cancel()
        watcher = scope.launch {
            manager.tasks.collectLatest { tasks ->
                val active = tasks.filter { it.active }
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val running = active.filter { it.state == TaskState.RUNNING }
                val known = running.filter { it.total > 0 }
                val percent = if (known.isNotEmpty()) {
                    (known.sumOf { it.downloaded } * 100 / known.sumOf { it.total }).toInt()
                } else 0
                val text = "进行中 ${running.size} · 排队 ${active.size - running.size}"
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, build(text, percent, 100, known.isEmpty()))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, progress: Int, max: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("拾图正在下载")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 1001
    }
}
