package com.shiina.mobile.observation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.view.WindowManager
import com.shiina.mobile.debug.AppDebugServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One-shot consent holder: filled by MainActivity, consumed by ObservationService. */
object CaptureConsent {
    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile var data: Intent? = null
}

/**
 * Single-shot screen capture behind the user's Settings toggle.
 * No loops here: callers (CaptureWatcher) own rate limits. Frames land in
 * app-private storage only and old ones are pruned.
 */
class ScreenshotTaker(
    private val context: Context,
    private val screenMetrics: ScreenMetrics? = null,
) {

    private val lock = Mutex()

    @Volatile private var projection: MediaProjection? = null
    val ready: Boolean get() = projection != null ||
            (com.shiina.mobile.action.ShiinaAccessibilityService.isEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)

    fun setProjection(mp: MediaProjection) {
        release()
        projection = mp
        AppDebugServer.log("CAPTURE", "Projection ready (consent granted)")
    }

    fun release() {
        runCatching { projection?.stop() }
        projection = null
    }

    suspend fun capture(reason: String): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            val mp = projection
            if (mp != null) {
                val file = runCatching { grabFrame(mp, reason) }.getOrNull()
                if (file != null) return@withContext file
            }
            // Fallback: capture directly via AccessibilityService (Android 11+) without MediaProjection consent
            val a11y = com.shiina.mobile.action.ShiinaAccessibilityService.instance
            if (a11y != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bitmap = runCatching { a11y.captureScreenshot() }.getOrNull()
                if (bitmap != null) {
                    screenMetrics?.updateFromBitmap(bitmap)
                    val dir = File(context.filesDir, "captures").apply { mkdirs() }
                    val out = File(dir, "cap_${System.currentTimeMillis()}_$reason.jpg")
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    bitmap.recycle()
                    prune(dir)
                    AppDebugServer.log("CAPTURE", "Saved ${out.name} ($reason via Accessibility)")
                    return@withContext out
                }
            }
            AppDebugServer.log("CAPTURE", "Capture skipped ($reason): no consent or accessibility screenshot available")
            null
        }
    }

    private fun grabFrame(mp: MediaProjection, reason: String): File? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val width = dm.widthPixels
        val height = dm.heightPixels
        if (width <= 0 || height <= 0) return null
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = mp.createVirtualDisplay(
            "shiina-capture", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null,
        )
        try {
            var img: android.media.Image? = null
            repeat(20) {
                if (img == null) {
                    img = reader.acquireLatestImage()
                    if (img == null) Thread.sleep(100)
                }
            }
            val image = img ?: return null
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
                )
                padded.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                screenMetrics?.updateFromBitmap(cropped)
                val dir = File(context.filesDir, "captures").apply { mkdirs() }
                val out = File(dir, "cap_${System.currentTimeMillis()}_$reason.jpg")
                out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                cropped.recycle()
                prune(dir)
                AppDebugServer.log("CAPTURE", "Saved ${out.name} ($reason)")
                return out
            } finally {
                image.close()
            }
        } finally {
            runCatching { display.release() }
            runCatching { reader.close() }
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        files.dropLast(20).forEach { runCatching { it.delete() } }
    }

    /** Newest capture within [maxAgeMs], or null (used for on-demand vision). */
    fun latestCapture(maxAgeMs: Long = 30L * 60L * 1_000L): File? {
        val dir = File(context.filesDir, "captures")
        val now = System.currentTimeMillis()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") && now - it.lastModified() <= maxAgeMs }
            ?.maxByOrNull { it.lastModified() }
    }
}
