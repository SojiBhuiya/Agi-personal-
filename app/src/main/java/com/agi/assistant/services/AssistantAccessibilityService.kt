package com.agi.assistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The assistant's "hands and eyes". It is only active after the user enables
 * it in Android Settings > Accessibility, and everything it does goes
 * through the public AccessibilityService API:
 *  - global actions (back, home, recents, notifications, lock, screenshot)
 *  - reading the current window tree (for read_screen / tap_text)
 *  - clicking nodes by their text / content description
 *  - scrolling (node action first, gesture fallback)
 *  - setting text into a focused editable field
 */
class AssistantAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not record or log window content. Events are only used to keep
        // the current package name for context.
        event?.packageName?.let { currentPackage = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Global actions ------------------------------------------------------

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    // ---- Screen reading ------------------------------------------------------

    data class ScreenNode(val text: String, val className: String, val clickable: Boolean, val editable: Boolean, val bounds: Rect)

    /** Flattens the active window into a list of meaningful nodes. */
    fun snapshot(maxNodes: Int = 120): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<ScreenNode>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= maxNodes || depth > 40) return
            val text = (n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() })
            if (text != null && n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                out += ScreenNode(text.trim(), n.className?.toString()?.substringAfterLast('.') ?: "", n.isClickable, n.isEditable, r)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    fun screenText(limit: Int = 4000): String {
        val sb = StringBuilder()
        snapshot().forEach { n ->
            val tag = when { n.editable -> "[input] "; n.clickable -> "[button] "; else -> "" }
            sb.append(tag).append(n.text).append('\n')
            if (sb.length > limit) return sb.toString() + "…"
        }
        return sb.toString().trim()
    }

    // ---- Tapping -------------------------------------------------------------

    /** Clicks the first node whose text/description matches [query] (case insensitive, partial). */
    fun clickByText(query: String): String? {
        val root = rootInActiveWindow ?: return null
        val q = query.lowercase(Locale.ROOT)
        val direct = root.findAccessibilityNodeInfosByText(query).orEmpty()
        val candidates = ArrayList<AccessibilityNodeInfo>(direct)
        if (candidates.isEmpty()) collect(root) { n ->
            val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
            t.lowercase(Locale.ROOT).contains(q)
        }.let(candidates::addAll)
        for (n in candidates) {
            var target: AccessibilityNodeInfo? = n
            var hops = 0
            while (target != null && !target.isClickable && hops++ < 5) target = target.parent
            if (target != null && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return n.text?.toString() ?: n.contentDescription?.toString() ?: query
            }
        }
        // Gesture fallback: tap the centre of the first visible match.
        candidates.firstOrNull { it.isVisibleToUser }?.let { n ->
            val r = Rect(); n.getBoundsInScreen(r)
            if (tap(r.exactCenterX(), r.exactCenterY())) return n.text?.toString() ?: query
        }
        return null
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(gesture, null, null)
    }

    // ---- Scrolling -----------------------------------------------------------

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow
        val forward = direction == "down" || direction == "right"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (root != null) {
            val scrollables = collect(root) { it.isScrollable && it.isVisibleToUser }
            for (n in scrollables) if (n.performAction(action)) return true
        }
        // Gesture fallback
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val (sx, sy, ex, ey) = when (direction) {
            "up" -> listOf(w / 2, h * 0.3f, w / 2, h * 0.75f)
            "left" -> listOf(w * 0.8f, h / 2, w * 0.2f, h / 2)
            "right" -> listOf(w * 0.2f, h / 2, w * 0.8f, h / 2)
            else -> listOf(w / 2, h * 0.75f, w / 2, h * 0.3f)
        }
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return dispatchGesture(gesture, null, null)
    }

    // ---- Typing --------------------------------------------------------------

    fun typeText(text: String, append: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: collect(root) { it.isEditable && it.isVisibleToUser }.firstOrNull()
            ?: return false
        if (!focused.isEditable) return false
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (append) focused.text?.toString().orEmpty() else ""
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---- Screenshot (API 30+) -------------------------------------------------

    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = result.hardwareBuffer.let { hb ->
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false).also { hb.close() }
                    }
                    cont.resume(bmp)
                }
                override fun onFailure(errorCode: Int) { cont.resume(null) }
            })
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private fun collect(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size > 200) return
            if (pred(n)) out += n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    companion object {
        private const val TAG = "AssistantA11y"
        @Volatile var instance: AssistantAccessibilityService? = null
            private set
        @Volatile var currentPackage: String? = null
            private set
        val isRunning get() = instance != null
    }
}
