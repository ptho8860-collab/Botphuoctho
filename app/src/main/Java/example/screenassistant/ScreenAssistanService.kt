package com.example.screenassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenAssistantService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshotHash: Int = 0

    companion object {
        private const val TAG = "ScreenAssistant"
        var instance: ScreenAssistantService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService đã kết nối.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val texts = collectScreenTexts()
        if (texts.isEmpty()) return

        val currentHash = texts.hashCode()
        if (currentHash != lastSnapshotHash) {
            lastSnapshotHash = currentHash
            Log.d(TAG, "Nội dung màn hình (${event.packageName}): ${texts.take(15)}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService bị gián đoạn.")
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                startActivity(intent)
                true
            } else {
                Log.w(TAG, "Không tìm thấy app với package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi mở app $packageName", e)
            false
        }
    }

    fun collectScreenTexts(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<String>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()

                if (!text.isNullOrEmpty()) result.add(text)
                if (!desc.isNullOrEmpty() && desc != text) result.add(desc)

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    traverse(child)
                    child?.recycle()
                }
            } finally {
                if (node != root) {
                    node.recycle()
                }
            }
        }

        try {
            traverse(root)
        } finally {
            root.recycle()
        }

        return result.distinct()
    }

    fun findCenterOfText(keyword: String, ignoreCase: Boolean = true): Pair<Float, Float>? {
        val root = rootInActiveWindow ?: return null
        var targetCenter: Pair<Float, Float>? = null

        fun search(node: AccessibilityNodeInfo?) {
            if (node == null || targetCenter != null) return
            try {
                val combined = listOfNotNull(
                    node.text?.toString(),
                    node.contentDescription?.toString()
                ).joinToString(" ")

                if (combined.isNotBlank() && combined.contains(keyword, ignoreCase = ignoreCase)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        targetCenter = Pair(bounds.exactCenterX(), bounds.exactCenterY())
                        return
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    search(child)
                    child?.recycle()
                    if (targetCenter != null) break
                }
            } finally {
                if (node != root) {
                    node.recycle()
                }
            }
        }

        try {
            search(root)
        } finally {
            root.recycle()
        }

        return targetCenter
    }

    fun recognizeTextInNormalizedRegion(
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
        onResult: (String) -> Unit
    ) {
        val dm = resources.displayMetrics
        val left = (dm.widthPixels * leftRatio.coerceIn(0f, 1f)).toInt()
        val top = (dm.heightPixels * topRatio.coerceIn(0f, 1f)).toInt()
        val right = (dm.widthPixels * rightRatio.coerceIn(0f, 1f)).toInt()
        val bottom = (dm.heightPixels * bottomRatio.coerceIn(0f, 1f)).toInt()

        if (right <= left || bottom <= top) {
            onResult("")
            return
        }

        onResult("")
    }

    fun tap(
        x: Float,
        y: Float,
        durationMs: Long = 60L,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }, handler)
    }

    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 350L,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }, handler)
    }

    fun swipeUpNextVideo(onDone: ((Boolean) -> Unit)? = null) {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels * 0.5f
        val startY = dm.heightPixels * 0.78f
        val endY = dm.heightPixels * 0.25f
        swipe(centerX, startY, centerX, endY, durationMs = 320L, onDone = onDone)
    }
}
