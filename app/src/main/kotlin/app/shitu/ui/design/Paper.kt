package app.shitu.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import app.shitu.ui.theme.AccentFamily
import app.shitu.ui.theme.EyebrowStyle
import app.shitu.ui.theme.LocalShituPalette

/**
 * 纸面背景：米白底 + 三团很淡的彩雾。雾是静止的，避免和毛玻璃导航栏一起每帧重绘。
 */
@Composable
fun PaperBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalShituPalette.current
    val base = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h)
                drawRect(
                    Brush.radialGradient(
                        listOf(palette.mistA, Color.Transparent),
                        center = Offset(w * 0.1f, h * 0.06f),
                        radius = r * 0.75f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(palette.mistB, Color.Transparent),
                        center = Offset(w * 1.0f, h * 0.55f),
                        radius = r * 0.6f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(palette.mistC, Color.Transparent),
                        center = Offset(w * 0.0f, h * 1.0f),
                        radius = r * 0.55f,
                    ),
                )
            },
    ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() } }
}

/**
 * 标题标记：`*词*` 包住的部分用强调色；如果是拉丁字母，再换成 Fraunces 斜体。
 * 例：`常用的都在，\n其它交给 *yt-dlp*。`
 */
@Composable
fun accentText(source: String): AnnotatedString {
    val accent = LocalShituPalette.current.accent
    return buildAnnotatedString {
        val parts = source.split('*')
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                val latin = part.all { it.code < 0x2E80 }
                val style = if (latin) {
                    SpanStyle(color = accent, fontFamily = AccentFamily, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)
                } else {
                    SpanStyle(color = accent)
                }
                withStyle(style) { append(part) }
            } else {
                append(part)
            }
        }
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text = text,
        style = EyebrowStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        textAlign = textAlign,
    )
}

/** 大号社论式标题，支持 `*强调*` 标记。 */
@Composable
fun EditorialTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    textAlign: TextAlign? = null,
) {
    Text(
        text = accentText(text),
        style = style,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
        textAlign = textAlign,
    )
}

@Composable
fun Lede(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        textAlign = textAlign,
    )
}

/** 主操作：墨色胶囊按钮，整屏只放一个。 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = CircleShape,
        color = if (enabled) scheme.primary else scheme.surfaceContainerHighest,
        contentColor = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = modifier.defaultMinSize(minHeight = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = scheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** 次要操作：浅色胶囊（参考「粘贴」按钮）。 */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = scheme.primaryContainer,
        contentColor = if (enabled) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 14.dp else 20.dp,
                vertical = if (compact) 8.dp else 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** 描边胶囊（参考顶栏「本地视频」）。 */
@Composable
fun OutlinePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) scheme.secondary else scheme.outline),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 白纸卡片：输入区与重点内容。 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.extraLarge)
            .padding(padding),
    ) { CompositionLocalProvider(LocalContentColor provides scheme.onSurface) { content() } }
}

/** 雾面卡片：列表条目（参考「抖音 / 小红书」卡片），半透明叠在彩雾上。 */
@Composable
fun MistCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 22.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .clip(shape)
            .background(scheme.surfaceContainerLow.copy(alpha = 0.62f))
            .border(1.dp, scheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
    ) { CompositionLocalProvider(LocalContentColor provides scheme.onSurface) { content() } }
}

/** 「视频 · 图集 · 实况」式的点分标签。 */
@Composable
fun DotTags(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Text(
        text = tags.joinToString("  ·  "),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** 子页面顶栏：返回 + 衬线标题，透明底，跟随内容。 */
@Composable
fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        actions()
    }
}

/** 状态栏底色：内容上滚时不和时间、电量叠在一起。 */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(top + 12.dp)
            .background(Brush.verticalGradient(listOf(bg, bg, bg.copy(alpha = 0f)))),
    )
}

/** 小圆角状态徽标。 */
@Composable
fun StatusBadge(text: String, color: Color, onColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = onColor)
    }
}

/** 列表里一行设置项。 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))
