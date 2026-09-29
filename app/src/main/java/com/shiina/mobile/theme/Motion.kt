package com.shiina.mobile.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

// ============================================================
// SHIINA DESIGN SYSTEM — Motion & Animation
// Meaningful, performant, respectful of reduced motion
// ============================================================

object ShiinaMotion {
    // Duration tokens (ms)
    const val DurationInstant = 0
    const val DurationFast = 100
    const val DurationMedium = 200
    const val DurationMediumSlow = 300
    const val DurationSlow = 400
    const val DurationSlowest = 500
    const val DurationPageTransition = 350

    // Easing curves
    val EaseOut: Easing = CubicBezierEasing(0.0f, 0f, 0.2f, 1f)
    val EaseIn: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val EaseOutExpo: Easing = CubicBezierEasing(0.19f, 1f, 0.22f, 1f) // Material expressive
    val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f) // Overshoot
    val Linear: Easing = LinearEasing

    // Spring specs
    val SpringGentle: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )
    val SpringSnappy: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
    val SpringStiff: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    // Tween specs
    val TweenFast: TweenSpec<Float> = tween(DurationFast, easing = EaseOut)
    val TweenMedium: TweenSpec<Float> = tween(DurationMedium, easing = EaseOut)
    val TweenMediumSlow: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOut)
    val TweenSlow: TweenSpec<Float> = tween(DurationSlow, easing = EaseOut)
    val TweenPage: TweenSpec<Float> = tween(DurationPageTransition, easing = EaseOutExpo)

    // Color animation specs (animateColorAsState needs AnimationSpec<Color>)
    val TweenFastColor: TweenSpec<androidx.compose.ui.graphics.Color> =
        tween(DurationFast, easing = EaseOut)
    val TweenMediumColor: TweenSpec<androidx.compose.ui.graphics.Color> =
        tween(DurationMedium, easing = EaseOut)
    val TweenSlowColor: TweenSpec<androidx.compose.ui.graphics.Color> =
        tween(DurationSlow, easing = EaseOut)

    // Stagger for lists
    const val StaggerDelay = 50 // ms per item
    const val StaggerDuration = DurationMedium

    // Specific animation specs for components
    object Components {
        // Navigation bar item
        val NavItemSelect: TweenSpec<Float> = tween(DurationMedium, easing = EaseOut)
        val NavItemIndicator: TweenSpec<Float> = tween(DurationMedium, easing = EaseOutExpo)

        // Card hover/press
        val CardPress: TweenSpec<Float> = tween(DurationFast, easing = EaseOut)
        val CardHover: TweenSpec<Float> = tween(DurationMedium, easing = EaseOut)

        // Button
        val ButtonPress: TweenSpec<Float> = tween(DurationInstant)
        val ButtonRipple: TweenSpec<Float> = tween(DurationMedium, easing = EaseOut)

        // FAB
        val FabExpand: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOutExpo)
        val FabCollapse: TweenSpec<Float> = tween(DurationMedium, easing = EaseInOut)

        // Bottom sheet / modal
        val SheetExpand: TweenSpec<Float> = tween(DurationPageTransition, easing = EaseOutExpo)
        val SheetCollapse: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseInOut)

        // Dialog
        val DialogEnter: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOutExpo)
        val DialogExit: TweenSpec<Float> = tween(DurationMedium, easing = EaseInOut)

        // Toast / snackbar
        val SnackbarSlide: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOutExpo)
        val SnackbarFade: TweenSpec<Float> = tween(DurationMedium, easing = EaseOut)

        // Avatar / character
        val AvatarPop: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOutBack)
        val AvatarMoodChange: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseInOut)
        val AvatarBlink: TweenSpec<Float> = tween(130, easing = EaseInOut)
        val AvatarWander: TweenSpec<Float> = tween(4000, easing = EaseInOut)

        // Bubble / overlay
        val BubbleEnter: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOutExpo)
        val BubbleExit: TweenSpec<Float> = tween(DurationMedium, easing = EaseInOut)
        val BubbleShimmer: TweenSpec<Float> = tween(700, easing = LinearEasing)

        // Chat
        val MessageEnter: TweenSpec<Float> = tween(DurationMedium, easing = EaseOutExpo)
        val MessageExit: TweenSpec<Float> = tween(DurationFast, easing = EaseInOut)
        val TypingIndicator: TweenSpec<Float> = tween(1400, easing = LinearEasing)
        val ScrollToBottom: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOutExpo)

        // Onboarding
        val OnboardingSlide: TweenSpec<Float> = tween(DurationPageTransition, easing = EaseOutExpo)
        val OnboardingFade: TweenSpec<Float> = tween(DurationMedium, easing = EaseOut)
        val OnboardingStepIndicator: TweenSpec<Float> = tween(DurationMedium, easing = EaseOutExpo)

        // Settings toggles
        val SwitchToggle: TweenSpec<Float> = tween(DurationFast, easing = EaseOut)
        val RadioSelect: TweenSpec<Float> = tween(DurationFast, easing = EaseOutExpo)

        // Progress / loading
        val ProgressCircular: TweenSpec<Float> = tween(1000, easing = LinearEasing)
        val ProgressLinear: TweenSpec<Float> = tween(DurationSlow, easing = EaseInOut)
        val SkeletonShimmer: TweenSpec<Float> = tween(1500, easing = LinearEasing)

        // Pull to refresh
        val PullRefresh: TweenSpec<Float> = tween(DurationMediumSlow, easing = EaseOutExpo)

        // Toggle / checkbox
        val CheckboxToggle: TweenSpec<Float> = tween(DurationFast, easing = EaseOutExpo)
    }

    // Reduced motion fallbacks (when accessibility enabled)
    object Reduced {
        val TweenFast: TweenSpec<Float> = tween(0)
        val TweenMedium: TweenSpec<Float> = tween(0)
        val TweenSlow: TweenSpec<Float> = tween(0)
    }
}
