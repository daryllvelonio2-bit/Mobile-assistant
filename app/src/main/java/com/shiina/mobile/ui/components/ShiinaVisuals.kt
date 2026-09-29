package com.shiina.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Animated mood orb — breathing gradient sphere that reacts to Shiina's mood.
 * Pure Canvas, no images, GPU-cheap. Used as the companion's presence proxy.
 */
@Composable
fun MoodOrb(
    mood: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 160.dp,
    busy: Boolean = false,
) {
    val colors = moodGradientFor(mood)
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )
    val pulse by transition.animateFloat(
        initialValue = if (busy) 0.35f else 0f,
        targetValue = if (busy) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)

            // Outer glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.first().copy(alpha = 0.35f), Color.Transparent),
                    center = c,
                    radius = r * 1.15f,
                ),
                radius = r * 1.15f,
                center = c,
            )

            // Main sphere with breathing scale
            drawCircle(
                brush = Brush.linearGradient(
                    colors = colors,
                    start = Offset(c.x - r, c.y - r),
                    end = Offset(c.x + r, c.y + r),
                ),
                radius = r * 0.86f * breath,
                center = c,
            )

            // Specular highlight orbiting
            val angle = shimmer * 2f * Math.PI.toFloat()
            val hx = c.x + kotlin.math.cos(angle) * r * 0.4f
            val hy = c.y + kotlin.math.sin(angle) * r * 0.4f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(hx, hy),
                    radius = r * 0.42f,
                ),
                radius = r * 0.42f,
                center = Offset(hx, hy),
            )

            // Busy ring
            if (busy) {
                drawArc(
                    color = Color.White.copy(alpha = 0.25f * pulse + 0.15f),
                    startAngle = shimmer * 360f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 0.98f, c.y - r * 0.98f),
                    size = androidx.compose.ui.geometry.Size(r * 1.96f, r * 1.96f),
                    style = Stroke(width = r * 0.06f, cap = StrokeCap.Round),
                )
            }
        }
        MoodFaceBadge(mood = mood, size = size * 0.30f)
    }
}

/** Small expressive face rendered inside the orb. */
@Composable
private fun MoodFaceBadge(mood: String, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val ink = Color.White.copy(alpha = 0.92f)
        val eyeR = w * 0.075f
        drawOval(
            color = ink,
            topLeft = Offset(w * 0.30f - eyeR, h * 0.36f - eyeR),
            size = androidx.compose.ui.geometry.Size(eyeR * 2, eyeR * 2),
        )
        drawOval(
            color = ink,
            topLeft = Offset(w * 0.70f - eyeR, h * 0.36f - eyeR),
            size = androidx.compose.ui.geometry.Size(eyeR * 2, eyeR * 2),
        )
        val stroke = Stroke(width = w * 0.05f, cap = StrokeCap.Round)
        when (mood.lowercase()) {
            "excited" -> drawOval(
                color = ink,
                topLeft = Offset(w * 0.36f, h * 0.58f),
                size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.20f),
            )
            "pouty", "sulky" -> drawArc(
                color = ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(w * 0.36f, h * 0.60f),
                size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.14f),
                style = stroke,
            )
            "firm" -> drawLine(
                color = ink,
                start = Offset(w * 0.38f, h * 0.68f),
                end = Offset(w * 0.62f, h * 0.68f),
                strokeWidth = w * 0.05f,
                cap = StrokeCap.Round,
            )
            "melancholy" -> drawArc(
                color = ink, startAngle = 210f, sweepAngle = 120f, useCenter = false,
                topLeft = Offset(w * 0.34f, h * 0.62f),
                size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.16f),
                style = stroke,
            )
            else -> drawArc(
                color = ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(w * 0.32f, h * 0.52f),
                size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.22f),
                style = stroke,
            )
        }
    }
}

/** Three-dot typing indicator for chat. */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    dotSize: androidx.compose.ui.unit.Dp = 7.dp,
) {
    val dot = if (color == Color.Unspecified) {
        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    } else color
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 180, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dot.copy(alpha = alpha)),
            )
        }
    }
}

/** Skeleton shimmer placeholder block. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    baseColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.5f),
                        baseColor.copy(alpha = 0.85f),
                        baseColor.copy(alpha = 0.5f),
                    ),
                    start = Offset(shimmer * 600f - 300f, 0f),
                    end = Offset(shimmer * 600f, 300f),
                ),
            ),
    )
}

/** Circular progress ring with track. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 5.dp,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    trackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
) {
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val sw = strokeWidth.toPx()
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(sw / 2, sw / 2),
            size = androidx.compose.ui.geometry.Size(r * 2 - sw, r * 2 - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(sw / 2, sw / 2),
            size = androidx.compose.ui.geometry.Size(r * 2 - sw, r * 2 - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

/** Simple animated check draw-in for success states. */
@Composable
fun AnimatedCheck(
    modifier: Modifier = Modifier,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    size: androidx.compose.ui.unit.Dp = 72.dp,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = progress.value
        if (p > 0.5f) {
            val local = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val path = Path().apply {
                moveTo(w * 0.26f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.70f)
                lineTo(w * 0.44f + (w * 0.30f) * local, h * 0.70f - (h * 0.34f) * local)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = w * 0.09f, cap = StrokeCap.Round),
            )
        }
    }
}

/** Multi-stop vertical gradient page background. */
@Composable
fun ShiinaGradientBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        androidx.compose.material3.MaterialTheme.colorScheme.surface,
        androidx.compose.material3.MaterialTheme.colorScheme.background,
    ),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors)),
    ) {
        content()
    }
}

// --- internal mapping helpers ---

internal fun moodGradientFor(mood: String): List<Color> = when (mood.lowercase()) {
    "pouty", "sulky" -> listOf(Color(0xFFE879F9), Color(0xFFA855F7))
    "candid" -> listOf(Color(0xFFFDE047), Color(0xFFF59E0B))
    "firm", "firm_warning" -> listOf(Color(0xFFFB7185), Color(0xFFDC2626))
    "warm", "validating" -> listOf(Color(0xFF6EE7B7), Color(0xFF10B981))
    "excited" -> listOf(Color(0xFFFDE68A), Color(0xFFFB923C))
    "melancholy" -> listOf(Color(0xFF94A3B8), Color(0xFF475569))
    "sleepy" -> listOf(Color(0xFFC4B5FD), Color(0xFF7C3AED))
    else -> listOf(Color(0xFF93C5FD), Color(0xFF6366F1))
}
