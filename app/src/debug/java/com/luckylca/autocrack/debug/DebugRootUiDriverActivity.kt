package com.luckylca.autocrack.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.luckylca.autocrack.MainActivity
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Debug-only driver: uses AutoCrack's own granted Root to send real input to production UI. */
class DebugRootUiDriverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { driveToolsProbe() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
            }
            val file = File(filesDir, "debug-validation/formal-ui-root-driver.json")
            file.parentFile?.mkdirs()
            file.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun driveToolsProbe(): JSONObject {
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner).inspect()
        require(root.isRootGranted) { root.diagnostic ?: "Root is not granted" }
        val suPath = requireNotNull(root.suPath) { "Root granted but su path missing" }

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        delay(1_500L)

        val tapX = intent.getIntExtra("tap_x", 329)
        val tapY = intent.getIntExtra("tap_y", 197)
        val tap = runner.run(
            command = listOf(suPath, "-c", "/system/bin/input tap $tapX $tapY"),
            label = "Debug-only production UI tools tap",
            timeoutMillis = 5_000L,
        )
        delay(1_500L)

        return JSONObject()
            .put("success", tap.succeeded)
            .put("rootProvider", root.provider.name)
            .put("suPath", suPath)
            .put("tapExitCode", tap.exitCode ?: JSONObject.NULL)
            .put("tapStdout", tap.stdout)
            .put("tapStderr", tap.stderr)
            .put("tapFailure", tap.failure ?: JSONObject.NULL)
            .put("tapX", tapX)
            .put("tapY", tapY)
    }
}
