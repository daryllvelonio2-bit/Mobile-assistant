package com.shiina.mobile.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// ============================================================
// SHIINA DESIGN SYSTEM — Complete Theme Composition
// Light + Dark schemes with dynamic color support (Android 12+)
// ============================================================

// --- Light Color Scheme ---
private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = InversePrimaryLight,
    surfaceTint = PrimaryLight,
)

// --- Dark Color Scheme (OLED-friendly) ---
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = InversePrimaryDark,
    surfaceTint = PrimaryDark,
)

/**
 * Main theme composable - use this as the root of your Compose hierarchy.
 *
 * @param darkTheme Force dark/light mode (defaults to system)
 * @param dynamicColor Enable Material You dynamic color (Android 12+)
 * @param content Composable content
 */
@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/**
 * Convenience accessors for the current theme values.
 * Use these instead of MaterialTheme.xxx for Shiina-specific tokens.
 */
object ShiinaTheme {
    // Colors
    val primary: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.primary
    val onPrimary: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onPrimary
    val primaryContainer: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer

    val secondary: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.secondary
    val onSecondary: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onSecondary
    val secondaryContainer: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer

    val tertiary: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.tertiary
    val onTertiary: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onTertiary
    val tertiaryContainer: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
    val onTertiaryContainer: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onTertiaryContainer

    val error: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.error
    val onError: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onError

    val background: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.background
    val onBackground: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onBackground

    val surface: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.surface
    val onSurface: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    val surfaceVariant: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    val outline: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.outline
    val outlineVariant: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.outlineVariant

    val inverseSurface: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.inverseSurface
    val inverseOnSurface: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.inverseOnSurface
    val inversePrimary: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.inversePrimary

    val surfaceTint: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.surfaceTint
    val typography: androidx.compose.material3.Typography
        @Composable get() = MaterialTheme.typography

    // Shapes
    val shapes: androidx.compose.material3.Shapes
        @Composable get() = MaterialTheme.shapes

    // Motion
    val motion = ShiinaMotion

    // Custom Shiina colors (mood, gradients, etc.)
    val moodColors = MoodColors
}