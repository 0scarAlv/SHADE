package com.shade.panel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.shade.panel.R

// Manrope ships as a single variable font (OFL, see android/FONT_LICENSE_Manrope_OFL.txt);
// each weight below is the same file with a different wght axis value.
@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: FontWeight) = Font(
    R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Manrope = FontFamily(
    manrope(FontWeight.ExtraLight),
    manrope(FontWeight.Light),
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

private val base = Typography()

private fun TextStyle.manrope(weight: FontWeight, letterSpacing: Float? = null) = copy(
    fontFamily = Manrope,
    fontWeight = weight,
    letterSpacing = letterSpacing?.em ?: this.letterSpacing,
)

val Typography = Typography(
    displayLarge = base.displayLarge.manrope(FontWeight.Light, -0.02f),
    displayMedium = base.displayMedium.manrope(FontWeight.Light, -0.02f),
    displaySmall = base.displaySmall.manrope(FontWeight.Normal, -0.01f),
    headlineLarge = base.headlineLarge.manrope(FontWeight.Bold, -0.02f),
    headlineMedium = base.headlineMedium.manrope(FontWeight.Bold, -0.02f),
    headlineSmall = base.headlineSmall.manrope(FontWeight.Bold, -0.01f),
    titleLarge = base.titleLarge.manrope(FontWeight.Bold, -0.01f),
    titleMedium = base.titleMedium.manrope(FontWeight.SemiBold),
    titleSmall = base.titleSmall.manrope(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.manrope(FontWeight.Medium),
    bodyMedium = base.bodyMedium.manrope(FontWeight.Medium),
    bodySmall = base.bodySmall.manrope(FontWeight.Medium),
    labelLarge = base.labelLarge.manrope(FontWeight.SemiBold),
    labelMedium = base.labelMedium.manrope(FontWeight.SemiBold, 0.02f),
    labelSmall = base.labelSmall.manrope(FontWeight.SemiBold, 0.04f).copy(fontSize = 11.sp),
)
