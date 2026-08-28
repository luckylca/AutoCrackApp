package com.luckylca.autocrack.debug

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Debug-only UI driver that acts only through the production accessibility node tree. */
class DebugUiAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var evidence: File

    override fun onServiceConnected() {
        super.onServiceConnected()
        evidence = File(filesDir, "debug-validation/formal-ui-accessibility.txt")
        evidence.parentFile?.mkdirs()
        evidence.writeText("service_connected\n", Charsets.UTF_8)

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (launchIntent == null) {
            evidence.appendText("launch_intent_missing\n", Charsets.UTF_8)
            disableSelf()
            return
        }
        startActivity(launchIntent)
        evidence.appendText("main_activity_requested\n", Charsets.UTF_8)

        handler.postDelayed({
            val clicked = clickExactText("工具")
            evidence.appendText("tools_clicked=$clicked\n", Charsets.UTF_8)
        }, 1_500L)
        handler.postDelayed({
            captureVisibleTexts("after_tools")
            disableSelf()
        }, 3_000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        if (::evidence.isInitialized) evidence.appendText("service_interrupted\n", Charsets.UTF_8)
    }

    private fun clickExactText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = root.findAccessibilityNodeInfosByText(text)
        val node = candidates.firstOrNull { it.text?.toString() == text } ?: return false
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isClickable) return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = candidate.parent
        }
        return false
    }

    private fun captureVisibleTexts(label: String) {
        val root = rootInActiveWindow
        if (root == null) {
            evidence.appendText("$label=<no_root>\n", Charsets.UTF_8)
            return
        }
        val texts = ArrayList<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || texts.size >= 160) return
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(texts::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let { texts += "desc:$it" }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> visit(child, depth + 1) }
            }
        }
        visit(root, 0)
        evidence.appendText("$label=${texts.joinToString(" | ")}\n", Charsets.UTF_8)
    }
}
