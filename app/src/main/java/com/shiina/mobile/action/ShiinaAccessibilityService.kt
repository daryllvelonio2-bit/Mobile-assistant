package com.shiina.mobile.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shiina.mobile.debug.AppDebugServer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class UiElement(
    val id: Int,
    val text: String,
    val desc: String,
    val type: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
)

/**
 * Accessibility Service for Shiina Mobile.
 * Enables autonomous screen interactions (tapping coordinates, clicking elements by text or ID,
 * scrolling/swiping, entering text, and global navigation) without requiring root or external cables.
 */
class ShiinaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ShiinaAccessibilityService? = null
        val isEnabled: Boolean get() = instance != null
    }

    private val cachedElements = java.util.concurrent.ConcurrentHashMap<Int, UiElement>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        serviceInfo = info
        AppDebugServer.log("ACCESSIBILITY", "ShiinaAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active event stream available for window/focus monitoring
    }

    override fun onInterrupt() {
        AppDebugServer.log("ACCESSIBILITY", "ShiinaAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        AppDebugServer.log("ACCESSIBILITY", "ShiinaAccessibilityService destroyed")
    }

    /**
     * Dumps all interactive and labeled UI elements from the active window hierarchy.
     * Assigns sequential element IDs (1..N) and caches their exact screen bounds and centers.
     */
    fun dumpInteractiveElements(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UiElement>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !node.isVisibleToUser) {
                return
            }
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) {
                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i))
                }
                return
            }

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isCheckable = node.isCheckable
            val isFocusable = node.isFocusable

            val label = if (text.isNotBlank()) text else desc
            val hasLabel = label.isNotBlank()
            val isInteractive = isClickable || isEditable || isCheckable || isFocusable

            if ((hasLabel || isInteractive) && rect.width() >= 12 && rect.height() >= 12) {
                val type = node.className?.toString()?.substringAfterLast(".") ?: "View"
                val elem = UiElement(
                    id = 0,
                    text = text,
                    desc = desc,
                    type = type,
                    centerX = rect.centerX(),
                    centerY = rect.centerY(),
                    bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                    isClickable = isClickable,
                    isEditable = isEditable,
                )
                elements.add(elem)
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        // Deduplicate elements with identical bounds and labels
        val seen = HashSet<String>()
        val deduplicated = mutableListOf<UiElement>()
        var nextId = 1
        for (elem in elements) {
            val key = "${elem.bounds}:${elem.text}:${elem.desc}"
            if (seen.add(key)) {
                deduplicated.add(elem.copy(id = nextId++))
            }
        }

        cachedElements.clear()
        deduplicated.forEach { cachedElements[it.id] = it }
        return deduplicated
    }

    /**
     * Clicks a cached element by its ID.
     * Tries text click first, then falls back to physical tap gesture at element center.
     */
    suspend fun clickElementById(id: Int): Boolean {
        val elem = cachedElements[id] ?: return false
        if (elem.text.isNotBlank()) {
            if (clickNodeByText(elem.text)) return true
        }
        return tap(elem.centerX.toFloat(), elem.centerY.toFloat())
    }

    /**
     * Dispatches a tap gesture at the specified screen coordinates (x, y).
     */
    suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                cont.resume(false)
            }
        }, null)
        if (!dispatched) {
            cont.resume(false)
        }
    }

    /**
     * Dispatches a swipe gesture from (startX, startY) to (endX, endY).
     */
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                cont.resume(false)
            }
        }, null)
        if (!dispatched) {
            cont.resume(false)
        }
    }

    /**
     * Searches the active window hierarchy for an element matching the given text and clicks it.
     */
    fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                if (curr.isClickable) {
                    val clicked = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                curr = curr.parent
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        return false
    }

    /**
     * Clicks an element by text, with fallback to physical tap gesture on the element's center.
     */
    suspend fun clickText(text: String): Boolean {
        if (clickNodeByText(text)) return true
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val tapped = tap(rect.centerX().toFloat(), rect.centerY().toFloat())
                if (tapped) return true
            }
        }
        return false
    }

    /**
     * Inputs text into the currently focused input field.
     */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Performs a global navigation action (back, home, recents, notifications).
     */
    fun pressGlobal(action: String): Boolean {
        return when (action.lowercase().trim()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }
}
