/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.utils.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.crea.reader.R

/**
 * Setup the Compose app-wide theme.
 *
 * The colors are CREA's own (res/values/colors.xml and its night variant), the same ones the XML
 * screens use, so Compose panels match the rest of the app instead of the device's wallpaper colors.
 */
@Composable
fun AppTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = creaColorScheme(if (useDarkTheme) darkColorScheme() else lightColorScheme())
    MaterialTheme(
        colorScheme = colors,
        typography = CreaTypography,
        content = content
    )
}

/** [base] with every brand role replaced by the CREA color of the current light/dark configuration. */
@Composable
private fun creaColorScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = colorResource(R.color.crea_primary),
    onPrimary = colorResource(R.color.crea_on_primary),
    primaryContainer = colorResource(R.color.crea_primary_container),
    onPrimaryContainer = colorResource(R.color.crea_on_primary_container),
    secondary = colorResource(R.color.crea_secondary),
    onSecondary = colorResource(R.color.crea_on_secondary),
    secondaryContainer = colorResource(R.color.crea_secondary_container),
    onSecondaryContainer = colorResource(R.color.crea_on_secondary_container),
    tertiary = colorResource(R.color.crea_tertiary),
    onTertiary = colorResource(R.color.crea_on_tertiary),
    tertiaryContainer = colorResource(R.color.crea_tertiary_container),
    onTertiaryContainer = colorResource(R.color.crea_on_tertiary_container),
    background = colorResource(R.color.crea_surface),
    onBackground = colorResource(R.color.crea_on_surface),
    surface = colorResource(R.color.crea_surface),
    onSurface = colorResource(R.color.crea_on_surface),
    surfaceVariant = colorResource(R.color.crea_surface_variant),
    onSurfaceVariant = colorResource(R.color.crea_on_surface_variant),
    surfaceTint = colorResource(R.color.crea_primary),
    surfaceContainerLowest = colorResource(R.color.crea_surface_container_lowest),
    surfaceContainerLow = colorResource(R.color.crea_surface_container_low),
    surfaceContainer = colorResource(R.color.crea_surface_container),
    surfaceContainerHigh = colorResource(R.color.crea_surface_container_high),
    surfaceContainerHighest = colorResource(R.color.crea_surface_container_highest),
    outline = colorResource(R.color.crea_outline),
    outlineVariant = colorResource(R.color.crea_outline_variant),
    error = colorResource(R.color.crea_error),
    onError = colorResource(R.color.crea_on_error),
)

/**
 * Montserrat, the font of creanicaragua.org. Medium is the regular weight, as on the website, so text
 * asking for Normal (400) gets Medium too.
 */
private val Montserrat = FontFamily(
    Font(R.font.montserrat_medium, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
)

/** Material's type scale in Montserrat: bold headings and semi-bold titles, like the website. */
private val CreaTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Montserrat, fontWeight = FontWeight.Bold),
        displayMedium = displayMedium.copy(fontFamily = Montserrat, fontWeight = FontWeight.Bold),
        displaySmall = displaySmall.copy(fontFamily = Montserrat, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontFamily = Montserrat, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = Montserrat, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = Montserrat, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = Montserrat),
        bodyMedium = bodyMedium.copy(fontFamily = Montserrat),
        bodySmall = bodySmall.copy(fontFamily = Montserrat),
        labelLarge = labelLarge.copy(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold),
    )
}

