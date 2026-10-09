package com.example.babymotionalert

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches for the camera app's "Video will stop in 5 seconds" dialog and
 * presses "Continue" automatically. Also tells the detection service to
 * ignore the screen for a few seconds so the dialog doesn't trigger a
 * false motion alarm.
 *
 * Must be enabled manually: Settings -> Accessibility -> Baby Motion Alert.
 */
class ReconnectClickerService : AccessibilityService() {

    private var lastClick = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        // Is the reconnect dialog on screen?
        val dialogNodes = try {
            root.findAccessibilityNodeInfosByText("Video will stop")
        } catch (_: Exception) { null }
        if (dialogNodes.isNullOrEmpty()) return

        // Dialog present: ignore screen changes for a moment (it flashes in and out)
        MotionDetectionService.suppressUntil = now + 8000L

        if (now - lastClick < 2000L) return // debounce repeated events

        val continueNodes = try {
            root.findAccessibilityNodeInfosByText("Continue")
        } catch (_: Exception) { null } ?: return

        for (n in continueNodes) {
            if (n.text?.toString()?.equals("Continue", ignoreCase = true) != true) continue
            var node: AccessibilityNodeInfo? = n
            while (node != null && !node.isClickable) node = node.parent
            if (node != null && node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastClick = now
                break
            }
        }
    }

    override fun onInterrupt() {}
}
