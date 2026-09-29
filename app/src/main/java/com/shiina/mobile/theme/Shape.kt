package com.shiina.mobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ============================================================
// SHIINA DESIGN SYSTEM — Shape System
// Consistent border radius scale for cohesive feel
// ============================================================

object ShiinaShapes {
    // Extra small - chips, badges, small pills
    val ExtraSmall = RoundedCornerShape(4.dp)

    // Small - buttons, text fields, chips
    val Small = RoundedCornerShape(8.dp)

    // Medium - cards, dialogs, sheets
    val Medium = RoundedCornerShape(12.dp)

    // Large - modal bottom sheets, major cards
    val Large = RoundedCornerShape(16.dp)

    // Extra large - full screen modals, hero sections
    val ExtraLarge = RoundedCornerShape(24.dp)

    // Full - pills, FABs, avatar masks
    val Full = RoundedCornerShape(999.dp)

    // Asymmetric - chat bubbles, directional elements
    val BubbleUser = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 4.dp,
    )

    val BubbleAssistant = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 4.dp,
        bottomEnd = 20.dp,
    )

    val BubbleSystem = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp,
    )

    // Overlay bubble
    val OverlayAvatar = RoundedCornerShape(50.dp)
    val OverlayBubble = RoundedCornerShape(24.dp)

    // Onboarding
    val OnboardingCard = RoundedCornerShape(20.dp)
    val OnboardingStepIndicator = RoundedCornerShape(8.dp)

    // Settings / list items
    val ListItem = RoundedCornerShape(12.dp)
    val SectionHeader = RoundedCornerShape(0.dp)
}

// Expose for Material3 shape mapping
val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = ShiinaShapes.ExtraSmall,
    small = ShiinaShapes.Small,
    medium = ShiinaShapes.Medium,
    large = ShiinaShapes.Large,
    extraLarge = ShiinaShapes.ExtraLarge,
)