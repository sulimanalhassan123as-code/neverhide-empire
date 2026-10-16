package com.neverhide.empire.tools.gamepad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Injects touches into OTHER apps (the DLS game under our overlay pad).
 * Accessibility is the only non-root way to do this; the user enables it
 * once in Settings → Accessibility → Neverhide GamePad.
 */
class GamePadAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GamePadAccessibilityService? = null
        fun ready(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Quick tap at (x,y). */
    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y); lineTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 40))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    /**
     * Press-and-drag: touches down at the game's joystick position and
     * moves toward (dx,dy) direction, holding for [durationMs].
     * Used for the run-circle: down at center, drag to direction, release.
     */
    fun joyDrag(cx: Float, cy: Float, dx: Float, dy: Float, durationMs: Long) {
        val p = Path().apply { moveTo(cx, cy); lineTo(cx + dx, cy + dy) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }
}
