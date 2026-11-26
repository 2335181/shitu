package app.shitu.ui.workspace.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shitu.core.Clipboard
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.design.PaperCard
import app.shitu.ui.design.SoftButton
import app.shitu.ui.theme.LocalShituPalette
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade

/** 参考稿的输入卡：白纸、无边框输入、右上「粘贴」、底部墨色主按钮。 */
@Composable
fun InputCard(
    state: TextFieldState,
    placeholder: String,
    action: String,
    onAction: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    extra: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    PaperCard(modifier.fillMaxWidth(), padding = PaddingValues(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f).padding(top = 10.dp, end = 8.dp)) {
                BasicTextField(
                    state = state,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(LocalShituPalette.current.accent),
                    lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(3, 6),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) 28.dp else 84.dp),
                    decorator = { inner ->
                        if (state.text.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.outline)
                        }
                        inner()
                    },
                )
            }
            if (state.text.isEmpty()) {
                SoftButton("粘贴", {
                    Clipboard.text(context)?.let { state.setTextAndPlaceCursorAtEnd(it.trim()) }
                })
            } else {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(scheme.primaryContainer).clickable { state.clearText() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "清空", Modifier.size(18.dp)) }
            }
        }
        extra?.let { Gap(8.dp); it() }
        Gap(14.dp)
        InkButton(action, onAction, Modifier.fillMaxWidth().padding(end = 8.dp), enabled = state.text.isNotBlank(), loading = loading)
    }
}

/** 一张素材缩略图：可多选，视频带播放角标。 */
@Composable
fun MediaTile(
    url: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    referer: String? = null,
    ratio: Float = 1f,
    badge: String? = null,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val accent = LocalShituPalette.current.accent
    val tileShape = MaterialTheme.shapes.medium
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                // 动图（AnimatedImageDrawable）会无视普通 clip 画出边界，用独立图层裁剪
                .graphicsLayer { shape = tileShape; clip = true }
                .background(scheme.surfaceContainer)
                .then(if (selected) Modifier.border(3.dp, accent, MaterialTheme.shapes.medium) else Modifier.border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium))
                .clickable(onClick = onClick),
        ) {
            if (url.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).crossfade(true).apply {
                        if (referer != null) httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
                    }.build(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
            }
            if (isVideo) {
                Icon(
                    Icons.Rounded.PlayCircle, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            }
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.inverseOnSurface,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .clip(CircleShape).background(scheme.inverseSurface).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle, null, tint = accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.White),
                )
            }
        }
        Gap(6.dp)
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
    }
}

/**
 * 批量选择：全选 / 下载按钮悬浮在导航栏上方（由 ShituRoot 绘制）。
 * 必须在 LazyColumn 外面调用，否则列表项滚出屏幕就会注销。长按下载按钮进入下载队列。
 */
@Composable
fun RegisterSelection(count: Int, total: Int, onToggleAll: () -> Unit, onDownload: () -> Unit) {
    val nav = app.shitu.ui.LocalNav.current
    val actions = app.shitu.ui.SelectionActions(count, total, onToggleAll, onDownload)
    androidx.compose.runtime.SideEffect { nav.selection = actions }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { nav.selection = null } }
}

@Composable
fun SelectionCount(count: Int, total: Int, modifier: Modifier = Modifier) {
    Text(
        "已选 $count / $total · 右下角按钮全选或下载，长按下载进入队列",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
