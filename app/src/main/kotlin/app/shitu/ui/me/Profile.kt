package app.shitu.ui.me

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.shitu.R
import app.shitu.core.friendlyMessage
import app.shitu.core.openUrl
import app.shitu.core.toast
import app.shitu.download.MediaKind
import app.shitu.download.MediaSaver
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.theme.LocalShituPalette
import app.shitu.ui.theme.SerifFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val REPO_URL = "https://github.com/2335181/shitu"
const val BILIBILI_URL = "https://space.bilibili.com/481490124"

/** 「我的」页顶部：头像、名字、开源仓库与 B站主页。 */
@Composable
fun ProfileHeader() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(112.dp)
                .border(BorderStroke(3.dp, scheme.surfaceContainerLowest), CircleShape)
                .padding(3.dp)
                .border(BorderStroke(1.dp, scheme.outlineVariant), CircleShape),
        ) {
            Image(
                painterResource(R.drawable.me_avatar), "头像",
                modifier = Modifier.size(112.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
        Gap(16.dp)
        Text(
            "小眠",
            fontFamily = SerifFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            letterSpacing = 0.12.em,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Gap(4.dp)
        Text("拾图作者", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Gap(16.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LinkIcon(R.drawable.ic_github, "开源仓库", tint = scheme.onSurface) { context.openUrl(REPO_URL) }
            LinkIcon(R.drawable.ic_bilibili, "B站主页", tint = Color(0xFFFB7299)) { context.openUrl(BILIBILI_URL) }
        }
    }
}

@Composable
private fun LinkIcon(@DrawableRes icon: Int, label: String, tint: Color, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), label, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

private enum class PayChannel(val label: String, @DrawableRes val qr: Int, val action: String, val brand: Color) {
    WECHAT("微信", R.drawable.sponsor_wechat, "保存二维码并打开微信", Color(0xFF07C160)),
    ALIPAY("支付宝", R.drawable.sponsor_alipay, "保存二维码并打开支付宝", Color(0xFF1677FF)),
}

/** 赞助：半屏弹层，微信 / 支付宝手风琴展开收款码。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorSheet(onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var open by remember { mutableStateOf(PayChannel.WECHAT) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "支持开发者",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(6.dp)
            Text(
                "拾图免费、无广告、不收集数据。觉得好用，可以请作者喝杯咖啡。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(18.dp)
            PayChannel.entries.forEach { ch ->
                PayCard(ch, expanded = open == ch, onToggle = { open = ch })
                Gap(12.dp)
            }
        }
    }
}

@Composable
private fun PayCard(ch: PayChannel, expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chevron")
    var saving by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.extraLarge),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(ch.brand))
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Text(ch.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.rotate(rotation), tint = scheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(ch.qr), "${ch.label}收款码",
                    modifier = Modifier.width(220.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.FillWidth,
                )
                Gap(16.dp)
                InkButton(
                    if (saving) "正在保存…" else ch.action,
                    {
                        scope.launch {
                            saving = true
                            runCatching { saveQr(context, ch) }
                                .onSuccess {
                                    context.toast("收款码已保存到 Download/拾图/图片，在扫一扫里从相册选它")
                                    openPayApp(context, ch)
                                }
                                .onFailure { context.toast(it.friendlyMessage()) }
                            saving = false
                        }
                    },
                    Modifier.fillMaxWidth(),
                    loading = saving,
                )
            }
        }
    }
}

private suspend fun saveQr(context: Context, ch: PayChannel) = withContext(Dispatchers.IO) {
    val bmp = BitmapFactory.decodeResource(context.resources, ch.qr)
    MediaSaver(context).save("拾图赞助_${ch.label}.jpg", "image/jpeg", MediaKind.IMAGE, null) { out ->
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
    }
}

private fun openPayApp(context: Context, ch: PayChannel) {
    val intent = when (ch) {
        // 直接打开微信扫一扫
        PayChannel.WECHAT -> Intent().apply {
            component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
            putExtra("LauncherUI.From.Scaner.Shortcut", true)
            action = Intent.ACTION_VIEW
        }
        PayChannel.ALIPAY -> Intent(Intent.ACTION_VIEW, Uri.parse("alipayqr://platformapi/startapp?saId=10000007"))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        val pkg = if (ch == PayChannel.WECHAT) "com.tencent.mm" else "com.eg.android.AlipayGphone"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) context.startActivity(launch) else context.toast("没装${ch.label}")
    }
}
