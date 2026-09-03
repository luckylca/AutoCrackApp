package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRuntimeToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedSharedRuntimeToolpacks() {
        val manifests = listOf(
            manifest(UI_INSPECT_MANIFEST),
            manifest(RUNTIME_INSPECT_MANIFEST),
            manifest(MEMORY_DUMP_MANIFEST),
            manifest(RUNTIME_CONTROL_MANIFEST),
        )

        assertEquals(4, manifests.size)
        manifests.forEach { manifest ->
            assertEquals(2, manifest.schemaVersion)
            assertEquals(">=1.0.0", manifest.requires.runtime)
            assertTrue(manifest.requires.commands.contains("android-shell"))
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsModifiedSharedRuntimeRequirements() {
        val manifest = manifest(UI_INSPECT_MANIFEST)
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest.copy(
                    requires = manifest.requires.copy(
                        capabilities = manifest.requires.capabilities.dropLast(1),
                    ),
                ),
            )
        }
    }

    private fun manifest(text: String): ToolpackPackageManifest =
        ToolpackPackageManifest.parse(text.trimIndent())

    private val UI_INSPECT_MANIFEST="""
{
  "schemaVersion": 2,
  "id": "ui-inspect",
  "title": "AutoCrack UI Inspect",
  "version": "ui-inspect-1.0.0",
  "description": "Inspect live Android windows, View trees, listeners, images and runtime-only View mutations.",
  "architecture": "all",
  "payloadEntry": "payload.zip",
  "payloadSha256": "00f14a9e48546435d7cf1794fb25b5cd11645488d6d8f348b6037c5b61017bbd",
  "payloadSizeBytes": 12892,
  "requiredPaths": [
    "bin/ui-inspect",
    "libexec/ui_inspect_cli.py",
    "libexec/autocrack_runtime_client.py",
    "README.md",
    "VERSION"
  ],
  "commands": [
    {
      "name": "ui-inspect",
      "relativePath": "bin/ui-inspect",
      "description": "Inspect live Android windows, View trees, listeners, images and runtime-only View mutations."
    }
  ],
  "selfTests": [
    {
      "id": "ui-inspect-help",
      "title": "AutoCrack UI Inspect CLI surface",
      "command": "/opt/autocrack/toolpacks/active/ui-inspect/bin/ui-inspect --help",
      "expectedExitCodes": [
        0
      ],
      "outputContains": [
        "windows",
        "listeners",
        "compose-tree"
      ]
    }
  ],
  "sources": [
    {
      "name": "ui-inspect-cli",
      "version": "1.0.0",
      "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/ui-inspect",
      "sha256": "154c49926a344787b4a62f2731c80f457ed11b9d1d9e3a47a9a67dfc456b4421"
    }
  ],
  "requires": {
    "runtime": ">=1.0.0",
    "capabilities": [
      "ui.windows",
      "ui.tree",
      "ui.at",
      "ui.find",
      "ui.props",
      "ui.parent",
      "ui.children",
      "ui.siblings",
      "ui.listeners",
      "ui.stack",
      "ui.image",
      "ui.action",
      "ui.compose.status",
      "ui.compose.tree",
      "object.describe"
    ],
    "commands": [
      "android-shell"
    ],
    "optionalCapabilities": [
      "ui.compose.tree"
    ]
  }
}
    """

    private val RUNTIME_INSPECT_MANIFEST="""
{
  "schemaVersion": 2,
  "id": "runtime-inspect",
  "title": "AutoCrack Runtime Inspect",
  "version": "runtime-inspect-1.0.0",
  "description": "Inspect target process, Activity, ClassLoader, class metadata and object handles.",
  "architecture": "all",
  "payloadEntry": "payload.zip",
  "payloadSha256": "6ddcf859c1d30749ad1ff1d65d850b1c16bf6b5a485e320d85a343b16884789f",
  "payloadSizeBytes": 10926,
  "requiredPaths": [
    "bin/runtime-inspect",
    "libexec/runtime_inspect_cli.py",
    "libexec/autocrack_runtime_client.py",
    "README.md",
    "VERSION"
  ],
  "commands": [
    {
      "name": "runtime-inspect",
      "relativePath": "bin/runtime-inspect",
      "description": "Inspect target process, Activity, ClassLoader, class metadata and object handles."
    }
  ],
  "selfTests": [
    {
      "id": "runtime-inspect-help",
      "title": "AutoCrack Runtime Inspect CLI surface",
      "command": "/opt/autocrack/toolpacks/active/runtime-inspect/bin/runtime-inspect --help",
      "expectedExitCodes": [
        0
      ],
      "outputContains": [
        "doctor",
        "class-search",
        "object-dump",
        "activities"
      ]
    }
  ],
  "sources": [
    {
      "name": "runtime-inspect-cli",
      "version": "1.0.0",
      "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/runtime-inspect",
      "sha256": "25de19b43a015c6def3c7b6c507ce61e3694697225e2bcd45836e071e438991c"
    }
  ],
  "requires": {
    "runtime": ">=1.0.0",
    "capabilities": [
      "runtime.process",
      "runtime.doctor",
      "runtime.activities",
      "runtime.declared_activities",
      "runtime.classloaders",
      "runtime.class.search",
      "runtime.class.describe",
      "object.describe",
      "object.fields",
      "object.dump",
      "object.release"
    ],
    "commands": [
      "android-shell"
    ],
    "optionalCapabilities": [
      "object.pin",
      "object.clear_session"
    ]
  }
}
    """

    private val MEMORY_DUMP_MANIFEST="""
{
  "schemaVersion": 2,
  "id": "memory-dump",
  "title": "AutoCrack Memory Dump",
  "version": "memory-dump-1.0.0",
  "description": "Dump bounded maps, memory ranges, modules, SO segments, Dex, runtime assets and XML.",
  "architecture": "all",
  "payloadEntry": "payload.zip",
  "payloadSha256": "c646d89896b977de617926f541ad8df88f5c552799c6bab5cd441d590954658b",
  "payloadSizeBytes": 44993,
  "requiredPaths": [
    "bin/memory-dump",
    "libexec/memory_dump_cli.py",
    "libexec/autocrack_runtime_client.py",
    "README.md",
    "VERSION"
  ],
  "commands": [
    {
      "name": "memory-dump",
      "relativePath": "bin/memory-dump",
      "description": "Dump bounded maps, memory ranges, modules, SO segments, Dex, runtime assets and XML."
    }
  ],
  "selfTests": [
    {
      "id": "memory-dump-help",
      "title": "AutoCrack Memory Dump CLI surface",
      "command": "/opt/autocrack/toolpacks/active/memory-dump/bin/memory-dump --help",
      "expectedExitCodes": [
        0
      ],
      "outputContains": [
        "maps",
        "dex-art-dump",
        "dex-art-export",
        "dex-dump",
        "assets-list"
      ]
    }
  ],
  "sources": [
    {
      "name": "memory-dump-cli",
      "version": "1.0.0",
      "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/memory-dump",
      "sha256": "6da8bcead05edd0f3facc89a82b6c2a2f817a4cf53ba5e3aac5a03ee2274efcf"
    }
  ],
  "requires": {
    "runtime": ">=1.0.0",
    "capabilities": [
      "memory.maps",
      "memory.modules",
      "memory.native.modules",
      "memory.read",
      "memory.native.probe",
      "memory.dladdr",
      "memory.module.dump",
      "memory.module.file_dump",
      "memory.elf.info",
      "memory.elf.symbols",
      "memory.elf.relocations",
      "memory.elf.dynamic",
      "memory.dex.list",
      "memory.dex.art_probe",
      "memory.dex.art_pointer_probe",
      "memory.dex.art_dump",
      "memory.dex.art_export.open",
      "memory.dex.art_export.chunk",
      "memory.dex.art_export.close",
      "memory.dex.info",
      "memory.dex.apk_index",
      "memory.dex.strings",
      "memory.dex.classes",
      "memory.dex.fields",
      "memory.dex.methods",
      "memory.dex.class_data",
      "memory.dex.scan",
      "memory.dex.dump",
      "memory.assets.list",
      "memory.assets.pull",
      "memory.xml.pull",
      "memory.xml.block_probe",
      "memory.xml.binary",
      "memory.xml.axml_decode",
      "memory.xml.axml_text",
      "memory.apk.entries",
      "memory.apk.pull"
    ],
    "commands": [
      "android-shell"
    ],
    "optionalCapabilities": [
      "memory.xml.block_probe",
      "memory.xml.binary",
      "memory.dex.art_pointer"
    ]
  }
}
    """

    private val RUNTIME_CONTROL_MANIFEST="""
{
  "schemaVersion": 2,
  "id": "runtime-control",
  "title": "AutoCrack Runtime Control",
  "version": "runtime-control-1.0.0",
  "description": "Start activities, kill processes, inject SOs, disable FLAG_SECURE and control WebView debugging/eval.",
  "architecture": "all",
  "payloadEntry": "payload.zip",
  "payloadSha256": "8ee12531fbe11a668df6ef343741b78fe4aeb6f0ce6e58a6ff659834dd2b11aa",
  "payloadSizeBytes": 19285,
  "requiredPaths": [
    "bin/runtime-control",
    "libexec/runtime_control_cli.py",
    "libexec/autocrack_runtime_client.py",
    "README.md",
    "VERSION"
  ],
  "commands": [
    {
      "name": "runtime-control",
      "relativePath": "bin/runtime-control",
      "description": "Start activities, kill processes, inject SOs, disable FLAG_SECURE and control WebView debugging/eval."
    }
  ],
  "selfTests": [
    {
      "id": "runtime-control-help",
      "title": "AutoCrack Runtime Control CLI surface",
      "command": "/opt/autocrack/toolpacks/active/runtime-control/bin/runtime-control --help",
      "expectedExitCodes": [
        0
      ],
      "outputContains": [
        "webview-debug",
        "webview-devtools-sockets",
        "secure-diagnose",
        "secure-disable",
        "so-diagnose",
        "activity-start"
      ]
    }
  ],
  "sources": [
    {
      "name": "runtime-control-cli",
      "version": "1.0.0",
      "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/runtime-control",
      "sha256": "48f2b7cf5bbb4a86f4255280d8881a0ea1efc39a6346fc9fe5171d5240fbae12"
    }
  ],
  "requires": {
    "runtime": ">=1.0.0",
    "capabilities": [
      "control.activity.start",
      "control.process.kill",
      "control.so.inject",
      "control.so.diagnose",
      "control.so.dlopen",
      "control.so.android_dlopen_ext",
      "control.so.dlsym",
      "control.secure.status",
      "control.secure.diagnose",
      "control.secure.disable",
      "control.object.field.set",
      "control.object.method.call",
      "webview.list",
      "webview.info",
      "webview.debug",
      "webview.devtools_socket",
      "webview.eval",
      "webview.clear_cache",
      "webview.go_forward",
      "webview.go_back",
      "webview.reload",
      "webview.load_url",
      "webview.eval.result"
    ],
    "commands": [
      "android-shell"
    ],
    "optionalCapabilities": [
      "control.so.dlopen_namespace"
    ]
  }
}
    """
}
