# AutoCrackApp S+A Toolpack Expansion Goal

Status: ACTIVE

This document is the release contract for the next AutoCrackApp Toolpack phase.

## 1. Goal

Integrate every S- and A-tier capability identified for the Mobile Agent:

1. APKiD
2. Androguard
3. jnitrace
4. uiautomator2
5. mitmproxy
6. frida-il2cpp-bridge
7. capa
8. Blutter

The phase is not complete until all eight are represented as production-trusted
AutoCrack Toolpacks and pass the applicable host/rootfs/device validation gates.

## 2. Non-negotiable full-capability rule

AutoCrack integration MUST preserve upstream capability instead of replacing it
with a reduced wrapper.

For every upstream project:

- Preserve the upstream CLI entrypoints when the project provides them.
- Preserve the upstream importable/programmatic API when the project provides one.
- Preserve upstream configuration, plugins/addons/scripts, filters and advanced flags.
- AutoCrack-specific JSON helpers are additive convenience interfaces only.
- Do not silently remove a feature because it is hard to expose through the UI.
- If an upstream feature cannot work on Android ARM64/rootfs, document the exact
  upstream/platform limitation and keep the rest of the upstream surface intact.
- Never report an unsupported feature as implemented.

Examples:

- Androguard must expose the upstream `androguard` CLI and Python package.
- uiautomator2 must expose the full Python package and upstream `u2cli`.
- mitmproxy must expose `mitmproxy`, `mitmdump`, `mitmweb`, and addon APIs.
- capa must expose the full upstream `capa` CLI/rules plus any AutoCrack helper.
- jnitrace must expose the upstream CLI and bundled tracing engine behavior.
- frida-il2cpp-bridge must preserve its upstream CLI/library usage.
- APKiD must preserve the upstream scanner/rules and normal CLI modes.
- Blutter must preserve the upstream Dart AOT extraction/generation pipeline, generated analysis artifacts, scripts and supported architecture/version handling rather than replacing it with a metadata-only detector.

## 3. AutoCrack integration requirements

Every Toolpack must include:

- A pinned upstream version or immutable commit.
- Source URL and SHA-256 for every packaged upstream artifact.
- Deterministic Toolpack packaging.
- `SKILL.md` explaining both upstream and AutoCrack-specific workflows.
- Manifest schema v2 where runtime/tool dependencies exist.
- Accurate `requiredPaths`, commands, requirements and self-tests.
- A production entry in `BuiltInToolpackTrustPolicy`.
- Trust-policy unit coverage.
- Agent command discovery and command ownership markers.
- No dependency on a developer workstation path at runtime.
- ARM64/rootfs compatibility validation for Linux-native payloads.

## 4. Capability-specific acceptance

### APKiD

- Full upstream `apkid` CLI and rule corpus.
- APK/DEX scanning.
- JSON output.
- Packer/protector/obfuscator/compiler/RASP/tracker detection as provided upstream.

### Androguard

- Full Python API import.
- Full upstream `androguard` CLI.
- APK/DEX/AXML/ARSC/certificate/call-graph functionality supplied upstream.

### jnitrace

- Upstream `jnitrace` CLI.
- Spawn and attach modes.
- Library filters, JNI filters, backtraces, remote Frida target support.
- Compatibility with the existing AutoCrack Frida server lifecycle.

### uiautomator2

- Full Python API.
- Upstream `u2cli`.
- Device-side server assets required by upstream.
- Selector/XPath, click/input/swipe, hierarchy, screenshot, app/session and watcher
  capabilities that upstream supports.

### mitmproxy

- `mitmproxy`, `mitmdump`, `mitmweb`.
- Addon scripting and flow serialization.
- HTTP(S), WebSocket and upstream-supported protocol modes.
- AutoCrack may add Android proxy/certificate helpers without replacing upstream.

### frida-il2cpp-bridge

- Full upstream package/CLI/library surface.
- Dump/trace/intercept/runtime IL2CPP functionality supplied upstream.
- Existing AutoCrack Frida transport remains reusable.

### capa

- Full `capa` CLI.
- Full bundled official rule set.
- ELF/other upstream-supported input analysis on the shipped build.
- JSON output remains available.

### Blutter

- Preserve the upstream Blutter analysis pipeline for Flutter/Dart AOT binaries.
- Preserve upstream handling of `libapp.so` / `libflutter.so`, Dart VM/version detection and generated analysis outputs.
- Preserve generated disassembly/symbol/object-pool/script artifacts supplied by upstream.
- Keep upstream build/runtime scripts available; AutoCrack helpers may only add discovery and stable workspace paths.
- Where a particular Flutter/Dart version or binary shape is unsupported upstream, report that exact upstream limitation instead of silently downgrading the analysis.
