package com.raza.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.raza.ai.core.NavKind
import com.raza.ai.core.Swipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional helper the user switches on in Android Settings. It does nothing on its own: it reacts only to
 * calls from ActionExecutor, and the manifest config limits the screens it can see to WhatsApp, YouTube and TikTok.
 * It never reads or stores screen content beyond finding the one button it was asked to press.
 * Methods that wait must be called from a background thread, never the main thread.
 */
class RazaAccessibilityService : AccessibilityService() {
    private val worker = HandlerThread("raza-a11y").apply { start() }
    private val handler = Handler(worker.looper)

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun global(kind: NavKind): Boolean = when (kind) {
        NavKind.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        NavKind.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        NavKind.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    fun screenSize(): Pair<Int, Int> = resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** True only if Android reports that the gesture finished (not cancelled). Blocks up to ~4 s. */
    fun swipe(s: Swipe): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "swipe() must not run on the main thread" }
        val path = Path().apply { moveTo(s.x1, s.y1); lineTo(s.x2, s.y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, s.durationMs)).build()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok.set(true); done.countDown() }
                override fun onCancelled(g: GestureDescription?) { done.countDown() }
            },
            handler,
        )
        if (!accepted) return false
        done.await(4, TimeUnit.SECONDS)
        return ok.get()
    }

    /** First node in the active window matching [match], polling until [timeoutMs]. */
    fun waitForNode(timeoutMs: Long, packages: Set<String>, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "waitForNode() must not run on the main thread" }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() in packages) {
                find(root, match)?.let { return it }
            }
            Thread.sleep(300)
        }
        return null
    }

    /** Presses the node, or its nearest clickable parent. True only if Android accepted the click. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            hops++
        }
        return false
    }

    private fun find(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val n = queue.removeFirst()
            visited++
            if (match(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: RazaAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
