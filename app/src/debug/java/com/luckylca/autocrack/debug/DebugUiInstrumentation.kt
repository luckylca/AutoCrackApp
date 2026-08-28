package com.luckylca.autocrack.debug

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.luckylca.autocrack.MainActivity
import java.io.File

/** Debug-only real UI input probe. It clicks the production MainActivity via Instrumentation. */
class DebugUiInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        runCatching {
            val evidence = File(targetContext.filesDir, "debug-validation/formal-ui-instrumentation.txt")
            evidence.parentFile?.mkdirs()
            evidence.writeText("instrumentation_started\n", Charsets.UTF_8)

            val intent = Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(targetContext, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            targetContext.startActivity(intent)
            evidence.appendText("activity_start_requested\n", Charsets.UTF_8)
            SystemClock.sleep(1_500)

            // Current production UI dump places the top "工具" tab at [230,135]-[428,260].
            evidence.appendText("before_tools_tap\n", Charsets.UTF_8)
            tap(329f, 197f)
            evidence.appendText("after_tools_tap\n", Charsets.UTF_8)
            SystemClock.sleep(1_500)

            result.putString("status", "clicked_tools")
        }.onFailure { error ->
            result.putString("status", "failed")
            result.putString("error", error.stackTraceToString().take(4000))
        }
        finish(Activity.RESULT_OK, result)
    }

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            },
        )
        val upTime = SystemClock.uptimeMillis()
        sendPointerSync(
            MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            },
        )
    }
}
