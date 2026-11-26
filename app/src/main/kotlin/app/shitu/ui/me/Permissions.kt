package app.shitu.ui.me

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.shitu.ui.design.Gap
import app.shitu.ui.design.PaperCard
import app.shitu.ui.design.SoftButton
import app.shitu.ui.theme.LocalShituPalette

object StoragePermissions {
    val media: Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMedia(context: Context) = media.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ||
        (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)

    fun hasAllFiles() = Environment.isExternalStorageManager()

    fun allFilesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
}

/** 「下载与存储」里的权限卡：相册（媒体）权限 + 所有文件访问。 */
@Composable
fun PermissionCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val media = remember(tick) { StoragePermissions.hasMedia(context) }
    val all = remember(tick) { StoragePermissions.hasAllFiles() }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    PaperCard(Modifier.fillMaxWidth()) {
        Text("权限", style = MaterialTheme.typography.titleLarge)
        Gap(12.dp)
        PermRow(
            "图片、视频和音频",
            "读取相册：识图选图，打开重装前保存的文件。",
            granted = media,
        ) { request.launch(StoragePermissions.media) }
        Gap(14.dp)
        PermRow(
            "所有文件访问",
            "可选。读取 Wallpaper Engine 工坊目录等任意文件夹；在系统设置里打开。",
            granted = all,
        ) {
            runCatching { context.startActivity(StoragePermissions.allFilesIntent(context)) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) Text("已授权", style = MaterialTheme.typography.labelLarge, color = LocalShituPalette.current.success)
        else SoftButton("授权", onGrant, compact = true)
    }
}
