# Phase 63 — Device Toolpack and Agent Validation Closure

Status: COMPLETE on device `a4976c80`.

This phase validates the production Pi-style Mobile Agent path after the S+A Toolpack expansion. The target device was cleaned so AutoCrack-related APKs were limited to:

1. `com.luckylca.autocrack`
2. `com.luckylca.autocrack.runtime`
3. `com.luckylca.runtimeinspector.testapp`

No legacy SimpleHook/runtime-inspector test APKs or uiautomator2 helper APKs remained after cleanup.

## Production installation state

The device contained 23 production-trusted Toolpacks. The full Mobile Agent self-test was generated from the device's current installed records, not from stale repository assumptions.

Final all-Toolpack result:

- packs: 23/23
- manifest self-tests: 54/54 PASS
- required paths: 150/150
- command shims: 51/51
- `SKILL.md`: 23/23
- `installedToolpacks`: 23
- `cleanupCompleted`: true
- marker: `ALL_23_AGENT_SELFTESTS_OK`

## Real functional matrix

Real Agent `exec_bash` validations were run against the unified Runtime Test App, a real APK, a real ARM64 ELF, live loopback traffic, and the actual Android Runtime provider process.

Validated areas:

- APKiD on the real unified test APK.
- Androguard APK parsing and Python API on the real unified test APK.
- LIEF ELF JSON reporting on the real native fixture.
- capa ELF analysis on the real native fixture.
- mitmproxy loopback HTTP interception and offline flow replay. The captured flow was 17,497 bytes and returned HTTP 200.
- tcpdump plus `pcap-summary` on loopback traffic. The bounded capture produced a 16,872-byte PCAP with 12 IPv4/TCP packets and restored the `127.0.0.1` connection summary.
- uiautomator2 3.7.0 on the real device through adbutils and the desktop ADB server reverse bridge. It started the test app, read device info, dumped hierarchy, captured a screenshot, pressed Back, and restarted the target app.
- NativeBridge / `runtime-control so-diagnose` on the runtime provider, confirming `native_bridge_loaded=true`.
- jnitrace dynamic attach to the real `AutoCrack Runtime Test` process and `libautocrack_runtime_native.so`. It captured JNI `NewStringUTF` calls from live NativeBridge probes; the trace log was 16,605 bytes and the JSON trace was 45,932 bytes.
- Blutter and frida-il2cpp-bridge boundary handling. The unified test APK is intentionally not Flutter or IL2CPP; the tools' package/help surfaces were verified, and the target was correctly classified as non-applicable rather than falsely marked as analyzed.

## Production issues found and fixed

1. Schema-v2 `runtime: null` was parsed by Android `JSONObject.optString()` as the string `"null"`, causing production installer requirement mismatches. `ToolpackRequirements.parse()` now preserves JSON null as Kotlin null and has a round-trip regression test.
2. Androguard production package metadata/self-test drift was corrected. The current 37-wheel lock is rebuilt deterministically, source-set hash matches production trust, and help output checks upstream's actual `Androguard` casing.
3. mitmproxy's `mitmweb --help` can print help and exit 1 upstream. Production self-tests now accept `[0, 1]` for that specific upstream behavior.
4. frida-il2cpp-bridge upstream CLI located its npm module root by directory name, which broke AutoCrack's `packs/<id>/<version>/` layout. The Toolpack patch now walks upward to the first directory containing `package.json`.
5. `android-host-shell` incorrectly treated `/workspaces/...` as `/workspace` because its regex used a raw `\\s` inside a character class. Version `android-host-shell-1.0.3` fixes the mapping and adds self-tests that prevent double-mapping host workspace absolute paths.
6. Agent cleanup previously scanned `/proc/[0-9]*/environ` globally and could hang on procfs entries. Cleanup now filters to root userspace processes whose `/proc/PID/root` matches the managed Debian rootfs before probing environ with a bounded timeout.
7. RootShell zombie handling was strengthened for KernelSU/Android Process wrappers. PID discovery now prefers a public `pid()` method by reflection before the private-field fallback.
8. The debug Mobile Agent harness teardown order now mirrors production: cancel host commands first, then clean session-tagged chroot processes. Cleanup in the debug harness is isolated so report writing is not indefinitely blocked by a KernelSU wrapper edge case.
9. The legacy structured APK/DEX compatibility version was updated to the current production Toolpack version so debug structured regression tests match the installed Pi-style Toolpack set.

## Target-dependent boundaries

Some Toolpacks require a matching live target to validate their full semantic output:

- Blutter needs a Flutter Android ARM64 APK containing `libapp.so` and `libflutter.so`.
- frida-il2cpp-bridge needs a live IL2CPP process or APK containing IL2CPP libraries.

The Phase 63 device target does not contain those binary shapes, so this phase validates their installed package/API/help surface and correct non-applicability detection, not a fabricated Flutter/IL2CPP analysis result.

## Final cleanup

After validation, transient artifacts were cleaned:

- Frida server process stopped.
- uiautomator2 `u2.jar` removed.
- uiautomator2 helper packages removed if present.
- mitmproxy, jnitrace, tcpdump test processes removed.
- `adb reverse` mappings removed.

The only remaining AutoCrack-related Android packages were the main app, shared runtime app, and the unified runtime test app.
