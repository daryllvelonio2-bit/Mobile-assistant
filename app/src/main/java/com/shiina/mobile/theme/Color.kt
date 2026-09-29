package com.shiina.mobile.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// SHIINA DESIGN SYSTEM — Color Palette
// Sophisticated indigo/iris primary with warm amber accents
// Dark-first, OLED-friendly, accessible contrast ratios
// ============================================================

// --- Brand Core ---
val ShiinaPrimary = Color(0xFF6366F1)       // Indigo 500 - primary brand
val ShiinaPrimaryVariant = Color(0xFF4F46E5) // Indigo 600 - pressed states
val ShiinaPrimaryLight = Color(0xFF818CF8)   // Indigo 400 - hover/glow
val ShiinaPrimaryDark = Color(0xFF3730A3)    // Indigo 700 - emphasis

val ShiinaSecondary = Color(0xFFF59E0B)      // Amber 500 - warm accent
val ShiinaSecondaryVariant = Color(0xFFD97706) // Amber 600
val ShiinaSecondaryLight = Color(0xFFFBBF24)  // Amber 400

val ShiinaTertiary = Color(0xFF0D9488)       // Teal 600 - success/calm
val ShiinaTertiaryLight = Color(0xFF5EEAD4)  // Teal 300

// --- Semantic Light Scheme ---
val PrimaryLight = ShiinaPrimary
val OnPrimaryLight = Color.White
val PrimaryContainerLight = Color(0xFFEEF0FF)  // Indigo 50
val OnPrimaryContainerLight = ShiinaPrimaryDark

val SecondaryLight = ShiinaSecondary
val OnSecondaryLight = Color.White
val SecondaryContainerLight = Color(0xFFFFF8E1) // Amber 50
val OnSecondaryContainerLight = Color(0xFF78350F)

val TertiaryLight = ShiinaTertiary
val OnTertiaryLight = Color.White
val TertiaryContainerLight = Color(0xFFCCFBF1)  // Teal 50
val OnTertiaryContainerLight = Color(0xFF134E4A)

val ErrorLight = Color(0xFFDC2626)           // Red 600
val OnErrorLight = Color.White
val ErrorContainerLight = Color(0xFFFEF2F2)
val OnErrorContainerLight = Color(0xFF7F1D1D)

val BackgroundLight = Color(0xFFFAFAFA)      // Near-white, not pure
val OnBackgroundLight = Color(0xFF0F172A)    // Slate 950
val SurfaceLight = Color.White
val OnSurfaceLight = Color(0xFF0F172A)
val SurfaceVariantLight = Color(0xFFF1F5F9)  // Slate 100
val OnSurfaceVariantLight = Color(0xFF475569) // Slate 600

val OutlineLight = Color(0xFFE2E8F0)         // Slate 200
val OutlineVariantLight = Color(0xFFF1F5F9)  // Slate 100
val ShadowLight = Color(0xFF0F172A)          // Slate 950 @ 8%

val InverseSurfaceLight = Color(0xFF1E293B)
val InverseOnSurfaceLight = Color(0xFFF8FAFC)
val InversePrimaryLight = ShiinaPrimaryLight

// --- Semantic Dark Scheme (OLED-friendly) ---
val PrimaryDark = ShiinaPrimaryLight
val OnPrimaryDark = ShiinaPrimaryDark
val PrimaryContainerDark = ShiinaPrimaryVariant
val OnPrimaryContainerDark = Color(0xFFEEF0FF)

val SecondaryDark = ShiinaSecondaryLight
val OnSecondaryDark = Color(0xFF451A03)
val SecondaryContainerDark = Color(0xFF78350F)
val OnSecondaryContainerDark = Color(0xFFFFF8E1)

val TertiaryDark = ShiinaTertiaryLight
val OnTertiaryDark = Color(0xFF042F2E)
val TertiaryContainerDark = Color(0xFF134E4A)
val OnTertiaryContainerDark = Color(0xFFCCFBF1)

val ErrorDark = Color(0xFFF87171)            // Red 400
val OnErrorDark = Color(0xFF450A0A)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFEF2F2)

val BackgroundDark = Color(0xFF030712)       // Slate 950 - OLED true black base
val OnBackgroundDark = Color(0xFFF8FAFC)     // Slate 50
val SurfaceDark = Color(0xFF0B1220)          // Slightly elevated
val OnSurfaceDark = Color(0xFFF8FAFC)
val SurfaceVariantDark = Color(0xFF1E293B)   // Slate 800
val OnSurfaceVariantDark = Color(0xFF94A3B8) // Slate 400

val OutlineDark = Color(0xFF334155)          // Slate 700
val OutlineVariantDark = Color(0xFF1E293B)   // Slate 800
val ShadowDark = Color.Black

val InverseSurfaceDark = Color(0xFFF1F5F9)
val InverseOnSurfaceDark = Color(0xFF0F172A)
val InversePrimaryDark = ShiinaPrimary

// --- Mood/State Colors (used by avatar & overlays) ---
object MoodColors {
    val Calm = Color(0xFF93C5FD)      // Blue 300
    val Candid = Color(0xFFFDE047)    // Yellow 300
    val Firm = Color(0xFFF87171)      // Red 400
    val Warm = Color(0xFF86EFAC)      // Green 300
    val Pouty = Color(0xFFE879F9)     // Pink 300
    val Excited = Color(0xFFFDE68A)   // Amber 200
    val Melancholy = Color(0xFFB0BEC5) // Blue-grey 300
    val Sulky = Color(0xFFD8B4FE)     // Purple 300
    val Validating = Color(0xFF67E8F9) // Cyan 300
    val Sleepy = Color(0xFFC4B5FD)    // Violet 300
}

// --- Gradient Definitions ---
val PrimaryGradient = listOf(
    ShiinaPrimary to 0f,
    ShiinaPrimaryLight to 1f
)

val SurfaceGradient = listOf(
    Color(0xFF0B1220) to 0f,
    Color(0xFF030712) to 1f
)

val MoodGradient = mapOf(
    "calm" to listOf(Color(0xFF93C5FD) to 0f, Color(0xFF60A5FA) to 1f),
    "candid" to listOf(Color(0xFFFDE047) to 0f, Color(0xFFFACC15) to 1f),
    "firm" to listOf(Color(0xFFF87171) to 0f, Color(0xFFEF4444) to 1f),
    "warm" to listOf(Color(0xFF86EFAC) to 0f, Color(0xFF4ADE80) to 1f),
    "pouty" to listOf(Color(0xFFE879F9) to 0f, Color(0xFFF0ABFC) to 1f),
    "excited" to listOf(Color(0xFFFDE68A) to 0f, Color(0xFFFBBF24) to 1f),
    "melancholy" to listOf(Color(0xFFB0BEC5) to 0f, Color(0xFF94A3B8) to 1f),
    "sulky" to listOf(Color(0xFFD8B4FE) to 0f, Color(0xFFC084FC) to 1f),
    "validating" to listOf(Color(0xFF67E8F9) to 0f, Color(0xFF22D3EE) to 1f),
    "sleepy" to listOf(Color(0xFFC4B5FD) to 0f, Color(0xFFA78BFA) to 1f),
)