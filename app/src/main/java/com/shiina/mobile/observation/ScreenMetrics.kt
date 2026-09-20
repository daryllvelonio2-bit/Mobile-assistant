package com.shiina.mobile.observation

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.shiina.mobile.debug.AppDebugServer

/**
 * Screen resolution learner and coordinate translator.
 * Automatically learns physical screen dimensions from WindowManager real metrics and captured screenshots.
 * Dynamically accounts for device orientation (portrait vs landscape) so AI taps and vision coordinates
 * are never inverted when apps rotate to fullscreen or return to portrait.
 * Translates normalized (0..1000) coordinates to exact physical screen pixels.
 */
class ScreenMetrics(private val context: Context) {

    private val prefs = context.getSharedPreferences("shiina_screen_metrics", Context.MODE_PRIVATE)

    // Base physical dimensions: baseShort = smaller dimension, baseLong = larger dimension
    @Volatile private var baseShort: Int = prefs.getInt("base_short", 0)
    @Volatile private var baseLong: Int = prefs.getInt("base_long", 0)

    init {
        // Backward compatibility migration from screen_width/screen_height
        if (baseShort <= 0 || baseLong <= 0) {
            val oldW = prefs.getInt("screen_width", 0)
            val oldH = prefs.getInt("screen_height", 0)
            if (oldW > 0 && oldH > 0) {
                baseShort = minOf(oldW, oldH)
                baseLong = maxOf(oldW, oldH)
            }
        }
        refresh()
    }

    /**
     * Discovers or refreshes the real physical display resolution using WindowManager real metrics.
     */
    fun refresh(): Pair<Int, Int> {
        val (w, h) = detectRealMetrics()
        if (w > 0 && h > 0) {
            val shortSide = minOf(w, h)
            val longSide = maxOf(w, h)
            if (shortSide != baseShort || longSide != baseLong) {
                baseShort = shortSide
                baseLong = longSide
                prefs.edit()
                    .putInt("base_short", shortSide)
                    .putInt("base_long", longSide)
                    .apply()
                AppDebugServer.log("SCREEN_METRICS", "Learned physical screen dimensions: ${shortSide}x${longSide}")
            }
        }
        return getCurrentDimensions()
    }

    /**
     * Updates learned physical dimensions from an actual captured screenshot bitmap.
     * The screenshot is the true ground truth of what the AI observes.
     */
    fun updateFromBitmap(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w > 0 && h > 0) {
            val shortSide = minOf(w, h)
            val longSide = maxOf(w, h)
            if (shortSide != baseShort || longSide != baseLong) {
                baseShort = shortSide
                baseLong = longSide
                prefs.edit()
                    .putInt("base_short", shortSide)
                    .putInt("base_long", longSide)
                    .apply()
                AppDebugServer.log("SCREEN_METRICS", "Updated physical screen dimensions from screenshot: ${shortSide}x${longSide}")
            }
        }
    }

    /**
     * Checks if device is currently in landscape orientation.
     */
    fun isLandscape(): Boolean {
        val orientation = context.resources.configuration.orientation
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return true
        if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) return false

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: android.view.Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.rotation
            }
            return rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270
        }
        return false
    }

    /**
     * Returns the active width and height based on the current orientation.
     */
    fun getCurrentDimensions(): Pair<Int, Int> {
        val shortSide = if (baseShort > 0) baseShort else 1080
        val longSide = if (baseLong > 0) baseLong else 2400
        return if (isLandscape()) {
            longSide to shortSide
        } else {
            shortSide to longSide
        }
    }

    val width: Int get() = getCurrentDimensions().first
    val height: Int get() = getCurrentDimensions().second

    val isReady: Boolean get() = baseShort > 0 && baseLong > 0

    private fun detectRealMetrics(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            }
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return dm.widthPixels to dm.heightPixels
            }
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * Normalizes (x, y) coordinates to exact screen pixels according to the current screen dimensions.
     * If coordinates are in 0..1000 scale (standard AI vision output), maps proportionally to (0..width, 0..height).
     * If coordinates already exceed 1000, treats them as absolute pixels.
     * Clamps the result within current screen bounds.
     */
    fun toPixels(x: Float, y: Float): Pair<Float, Float> {
        if (!isReady) refresh()
        val (w, h) = getCurrentDimensions()
        val wf = w.toFloat()
        val hf = h.toFloat()

        val isNormalized = (x in 0f..1000f && y in 0f..1000f) && (wf > 1000f || hf > 1000f)
        val pixelX = if (isNormalized) (x / 1000f) * wf else x
        val pixelY = if (isNormalized) (y / 1000f) * hf else y

        return pixelX.coerceIn(0f, wf) to pixelY.coerceIn(0f, hf)
    }

    override fun toString(): String {
        val (w, h) = getCurrentDimensions()
        return "${w}x${h}"
    }
}
