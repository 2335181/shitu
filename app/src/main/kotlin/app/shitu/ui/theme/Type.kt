package app.shitu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.shitu.R

/** 标题用的中文衬线（思源宋体子集，900 与 700 两档）。 */
val SerifFamily = FontFamily(
    Font(R.font.noto_serif_sc_black, FontWeight.Black),
    Font(R.font.noto_serif_sc_black, FontWeight.ExtraBold),
    Font(R.font.noto_serif_sc_bold, FontWeight.Bold),
    Font(R.font.noto_serif_sc_bold, FontWeight.SemiBold),
)

/** 拉丁强调字（Fraunces Italic），只用于标题里的英文关键词。 */
val AccentFamily = FontFamily(
    Font(R.font.fraunces_italic, FontWeight.Bold, FontStyle.Italic),
)

private val base = Typography()

internal val ShituTypography = Typography(
    displayLarge = base.displayLarge.copy(
        fontFamily = SerifFamily, fontWeight = FontWeight.Black,
        fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = (-0.01).em,
    ),
    displayMedium = base.displayMedium.copy(
        fontFamily = SerifFamily, fontWeight = FontWeight.Black,
        fontSize = 42.sp, lineHeight = 50.sp, letterSpacing = (-0.01).em,
    ),
    displaySmall = base.displaySmall.copy(
        fontFamily = SerifFamily, fontWeight = FontWeight.Black,
        fontSize = 34.sp, lineHeight = 42.sp,
    ),
    headlineLarge = base.headlineLarge.copy(
        fontFamily = SerifFamily, fontWeight = FontWeight.Black, lineHeight = 40.sp,
    ),
    headlineMedium = base.headlineMedium.copy(
        fontFamily = SerifFamily, fontWeight = FontWeight.Black, lineHeight = 36.sp,
    ),
    headlineSmall = base.headlineSmall.copy(
        fontFamily = SerifFamily, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
    ),
    titleLarge = base.titleLarge.copy(
        fontFamily = SerifFamily, fontWeight = FontWeight.Bold, lineHeight = 28.sp,
    ),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** 页眉小字：字距拉开的分类标签，如「视频 · 图片 · GIF」。 */
val EyebrowStyle = TextStyle(
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.18.em,
    lineHeight = 18.sp,
)
