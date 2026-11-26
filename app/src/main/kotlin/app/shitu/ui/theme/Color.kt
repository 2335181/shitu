package app.shitu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 全应用唯一允许出现颜色字面量的文件。
 *
 * 方向：「纸与墨」。米白纸面上铺几团很淡的天蓝、薄荷、蜜桃色雾，正文是近黑的墨色，
 * 强调只用一种天蓝（斜体拉丁字与少量关键词），主按钮是墨色胶囊。
 */

// 纸面雾色（浅色）
private val MistSky = Color(0xFFD6E6F4)
private val MistMint = Color(0xFFD3ECE2)
private val MistPeach = Color(0xFFF3DDCB)
private val Paper = Color(0xFFF7F5F1)

// 夜色雾（深色）
private val NightSky = Color(0xFF1A2836)
private val NightMint = Color(0xFF14302A)
private val NightPeach = Color(0xFF35251B)
private val Night = Color(0xFF11100E)

private val Ink = Color(0xFF16140F)
private val InkSoft = Color(0xFF2B2823)
private val Stone = Color(0xFF7C776F)
private val StoneLight = Color(0xFFA8A29A)
private val Hairline = Color(0xFFE6E1DA)
private val HairlineDark = Color(0xFF2E2C28)
private val Chalk = Color(0xFFF4F0E9)
private val Sky = Color(0xFF5FA6DA)
private val SkyDeep = Color(0xFF2F7DB8)
private val SkyNight = Color(0xFF8CC4EC)
private val Blush = Color(0xFFF4ECE7)
private val BlushNight = Color(0xFF2C2622)
private val Moss = Color(0xFF3E8A64)
private val MossNight = Color(0xFF7CCBA2)
private val Ember = Color(0xFFC2412D)
private val EmberNight = Color(0xFFFF9C8A)

/** 主题之外的少量品牌色：背景雾、强调色、成功色。 */
@Immutable
data class ShituPalette(
    val mistA: Color,
    val mistB: Color,
    val mistC: Color,
    val accent: Color,
    val success: Color,
    val isDark: Boolean,
)

val LocalShituPalette = staticCompositionLocalOf {
    ShituPalette(MistSky, MistMint, MistPeach, Sky, Moss, isDark = false)
}

internal val PaperPalette = ShituPalette(MistSky, MistMint, MistPeach, Sky, Moss, isDark = false)
internal val NightPalette = ShituPalette(NightSky, NightMint, NightPeach, SkyNight, MossNight, isDark = true)

/** 跟随壁纸时：雾色取自动态配色的容器色，强调色取 primary。 */
internal fun dynamicPalette(scheme: ColorScheme, dark: Boolean) = ShituPalette(
    mistA = scheme.primaryContainer,
    mistB = scheme.tertiaryContainer,
    mistC = scheme.secondaryContainer,
    accent = scheme.primary,
    success = if (dark) MossNight else Moss,
    isDark = dark,
)

internal val PaperColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Chalk,
    primaryContainer = Blush,
    onPrimaryContainer = Ink,
    inversePrimary = SkyNight,
    secondary = SkyDeep,
    onSecondary = Color.White,
    secondaryContainer = MistSky,
    onSecondaryContainer = Ink,
    tertiary = Moss,
    onTertiary = Color.White,
    tertiaryContainer = MistMint,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Blush,
    onSurfaceVariant = Stone,
    surfaceTint = Paper,
    inverseSurface = Ink,
    inverseOnSurface = Chalk,
    error = Ember,
    onError = Color.White,
    errorContainer = Color(0xFFFBE3DE),
    onErrorContainer = Color(0xFF5C1408),
    outline = StoneLight,
    outlineVariant = Hairline,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE9E5DF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF8),
    surfaceContainer = Color(0xFFF2EFEA),
    surfaceContainerHigh = Color(0xFFECE8E2),
    surfaceContainerHighest = Color(0xFFE5E0D9),
)

internal val NightColorScheme = darkColorScheme(
    primary = Chalk,
    onPrimary = Ink,
    primaryContainer = BlushNight,
    onPrimaryContainer = Chalk,
    inversePrimary = SkyDeep,
    secondary = SkyNight,
    onSecondary = Ink,
    secondaryContainer = NightSky,
    onSecondaryContainer = Chalk,
    tertiary = MossNight,
    onTertiary = Ink,
    tertiaryContainer = NightMint,
    onTertiaryContainer = Chalk,
    background = Night,
    onBackground = Chalk,
    surface = Night,
    onSurface = Chalk,
    surfaceVariant = BlushNight,
    onSurfaceVariant = StoneLight,
    surfaceTint = Night,
    inverseSurface = Chalk,
    inverseOnSurface = Ink,
    error = EmberNight,
    onError = Ink,
    errorContainer = Color(0xFF4A1A12),
    onErrorContainer = Color(0xFFFFDAD3),
    outline = Stone,
    outlineVariant = HairlineDark,
    scrim = Color.Black,
    surfaceBright = InkSoft,
    surfaceDim = Night,
    surfaceContainerLowest = Color(0xFF0B0A09),
    surfaceContainerLow = Color(0xFF181715),
    surfaceContainer = Color(0xFF1D1C19),
    surfaceContainerHigh = Color(0xFF24221F),
    surfaceContainerHighest = Color(0xFF2C2A26),
)
