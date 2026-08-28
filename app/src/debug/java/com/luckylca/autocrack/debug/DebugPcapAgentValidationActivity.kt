package com.luckylca.autocrack.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentPcapToolExecutor
import com.luckylca.autocrack.agent.AgentToolSession
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.apk.PackageRepository
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only real-device proof for rootfs pcap install/self-test and production Agent JSON dispatch. */
class DebugPcapAgentValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validate() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
                    .put("exception", error::class.java.name)
            }
            val output = File(filesDir, REPORT_PATH)
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun validate(): JSONObject {
        val targetPackage = intent.getStringExtra("package_name")?.trim().orEmpty().ifBlank { DEFAULT_TARGET_PACKAGE }
        val packageFile = File(filesDir, TOOLPACK_INPUT_PATH)
        require(packageFile.isFile && packageFile.length() > 0L) { "Rootfs pcap analysis toolpack file is missing" }

        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val detector = RootDetector(runner)
        val root = detector.inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val host = RootShellRuntimeEngine(layout, requireNotNull(root.suPath))
        val chroot = ChrootRuntimeEngine(layout, host)
        val installer = ToolpackPackageInstaller(applicationContext, layout)

        val install = installer.install(Uri.fromFile(packageFile))
        check(install.manifest.id == AgentPcapToolExecutor.TOOLPACK_ID) { "Unexpected toolpack id: ${install.manifest.id}" }
        check(install.manifest.version == AgentPcapToolExecutor.TOOLPACK_VERSION) { "Unexpected toolpack version: ${install.manifest.version}" }
        val installed = installer.listInstalled().single {
            it.manifest.id == AgentPcapToolExecutor.TOOLPACK_ID &&
                it.manifest.version == AgentPcapToolExecutor.TOOLPACK_VERSION
        }
        val selfTest = installer.runSelfTests(installed, chroot)
        check(selfTest.passed) { "Rootfs pcap analysis self-test failed" }

        val workspace = layout.createRuntimeWorkspace()
        val captureRoot = File(workspace, "network-captures/${targetPackage.replace('.', '_')}").canonicalFile
        check(captureRoot.mkdirs() || captureRoot.isDirectory) { "Unable to create pcap validation directory" }
        val pcap = File(captureRoot, "validation-empty.pcap").canonicalFile
        pcap.writeBytes(emptyEthernetPcap())
        val metadata = File(captureRoot, "tcpdump-session.json").canonicalFile
        metadata.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("packageName", targetPackage)
                .put("pcapPath", pcap.path)
                .put("vpnUsed", false)
                .put("surfingConfigTouched", false)
                .put("httpsDecrypted", false)
                .toString(2),
            Charsets.UTF_8,
        )

        val extraction = PackageRepository(applicationContext, runner).extractPackage(root, targetPackage)
        val session = AgentToolSessionFactory(applicationContext, runner, detector).create(
            extraction = extraction,
            allowDynamicTools = false,
            knownRootStatus = root,
        )
        val toolNames = session.tools.map { it.name }.sorted()
        check(AgentPcapToolExecutor.PCAP_TOOL_NAMES.all(toolNames::contains)) {
            "Production Agent session is missing pcap tools: $toolNames"
        }

        val calls = JSONObject()
        try {
            AgentPcapToolExecutor.PCAP_TOOL_NAMES.forEach { name ->
                val result = call(session, name)
                check(result.optString("runtimeTarget") == "android_rootfs_only") { "$name escaped runtime target" }
                calls.put(name, result)
            }
        } finally {
            session.closeSafely()
        }
        val info = calls.getJSONObject(AgentPcapToolExecutor.TOOL_INFO)
        check(info.optInt("packetCount", -1) == 0) { "Deterministic validation pcap should contain zero packets" }
        check(info.getJSONObject("global").optInt("versionMajor") == 2)
        check(info.getJSONObject("global").optInt("versionMinor") == 4)

        return JSONObject()
            .put("success", true)
            .put("toolpackId", install.manifest.id)
            .put("toolpackVersion", install.manifest.version)
            .put("payloadBytes", install.payloadBytes)
            .put("selfTestPassed", selfTest.passed)
            .put("targetPackage", targetPackage)
            .put("pcapBytes", pcap.length())
            .put("productionToolCount", toolNames.size)
            .put("pcapToolNames", JSONArray(AgentPcapToolExecutor.PCAP_TOOL_NAMES))
            .put("agentResults", calls)
            .put(
                "selfTests",
                JSONArray(selfTest.results.map { result ->
                    JSONObject()
                        .put("id", result.test.id)
                        .put("passed", result.passed)
                        .put("exitCode", result.commandResult.exitCode ?: JSONObject.NULL)
                        .put("failure", result.failure ?: JSONObject.NULL)
                }),
            )
    }

    private suspend fun call(session: AgentToolSession, name: String): JSONObject {
        val args = if (name == AgentPcapToolExecutor.TOOL_INFO) JSONObject() else JSONObject().put("max_records", 8)
        val result = JSONObject(session.dispatch(name, args))
        check(result.optBoolean("ok")) { "$name returned ok=false: $result" }
        return result
    }

    private fun emptyEthernetPcap(): ByteArray = ByteBuffer.allocate(24)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0xa1b2c3d4.toInt())
        .putShort(2)
        .putShort(4)
        .putInt(0)
        .putInt(0)
        .putInt(65_535)
        .putInt(1)
        .array()

    private companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.example.myapplication"
        const val TOOLPACK_INPUT_PATH = "debug-validation/rootfs-pcap-analysis-toolpack.zip"
        const val REPORT_PATH = "debug-validation/pcap-agent-report.json"
    }
}
