# SimpleHook 0.1.1

## Overview

SimpleHook is an Android Java/Kotlin runtime debugging tool for applications and devices you own or are authorized to test. It combines a rootfs CLI, a persistent companion/provider APK, and an LSPosed/Xposed-compatible module entry point. It does not install or modify a system framework.

## Architecture

- `simplehook` is the CLI installed through AutoCrackApp's existing verified toolpack mechanism.
- `simplehook-runtime-0.1.1.apk` is a companion and Xposed-compatible module. Its root/shell provider owns persistent rules, runtime state, inspection requests, and rotated JSONL logs.
- Injected runtime code performs exact reflection matching and installs hooks through `XposedBridge.hookMethod`.
- Target processes read rules through LSPosed/Xposed `XSharedPreferences`. Heartbeats, states, logs, and inspect results return through an explicit, token-authenticated ordered broadcast channel. Delivery uses a bounded retry queue with event IDs; Android 14 and newer also verify the shared sender identity.
- `simplehook-core` contains schema, type coercion, condition, state, and safety-limit logic shared by the runtime tests.
- `SimpleHookTestApp.apk` provides stable, owned targets for device validation.

The CLI talks to the Android provider through the existing `android-shell` bridge. No new toolpack manager or Agent prompt is introduced.

## Installation

1. Install the trusted `simplehook-toolpack-0.1.1.zip` in AutoCrackApp.
2. Install `simplehook-runtime-0.1.1.apk` on the Android device.
3. In LSPosed or another compatible runtime, enable SimpleHook Runtime only for test packages you own.
4. After enabling or upgrading, refresh the module in the LSPosed manager and restart selected target processes. Reboot the device only when the installed framework explicitly requires it.
5. Run `simplehook doctor --json` and `simplehook status --json`.

The module does not install root, LSPosed, Xposed, or any other system component.

## Requirements

- Android API 26 or newer; runtime APK targets API 36.
- Root is required for the AutoCrackApp Android host bridge.
- An Xposed API 93-compatible runtime such as LSPosed is required for hooks and cross-process preferences.
- The target package must be selected in the module scope.

## CLI

```text
simplehook status [--json]
simplehook environment [--json]
simplehook doctor [--json]
simplehook rules list [--json]
simplehook rules show ID [--json]
simplehook rules add [FILE|-] [--dry-run] [--json]
simplehook rules update ID [FILE|-] [--dry-run] [--json]
simplehook rules enable ID [--json]
simplehook rules disable ID [--json]
simplehook rules remove ID [--json]
simplehook rules validate FILE [--json]
simplehook logs [--follow] [--rule ID] [--package PACKAGE] [--limit N] [--json]
simplehook apply [--json]
simplehook reload [--json]
simplehook inspect class CLASS [--package PACKAGE] [--timeout SEC] [--json]
simplehook inspect methods CLASS [--package PACKAGE] [--timeout SEC] [--json]
simplehook inspect fields CLASS [--package PACKAGE] [--timeout SEC] [--json]
```

`--json` may appear before or after a subcommand. Non-follow log output is JSONL by default and a JSON object with `--json`.

## Rule Schema

Rules conform to `schema/simplehook-rule-v1.schema.json`. Overloads are always selected with the complete `target.parameters` array. Supported action values cover boolean, byte, short, int, long, float, double, char, String, boxed primitive types, and null where the declared target is nullable.

```json
{
  "schema_version": 1,
  "id": "test_get_int",
  "enabled": true,
  "package": "com.luckylca.simplehook.testapp",
  "process": null,
  "target": {
    "class": "com.luckylca.simplehook.testapp.HookTargets",
    "method": "getInt",
    "constructor": false,
    "parameters": [],
    "return_type": "int"
  },
  "action": {"type": "replace_return", "value": 100},
  "logging": {"enabled": true, "arguments": true, "return_value": true}
}
```

Supported actions are `record`, `replace_return`, `replace_argument`, `before`, `after`, `skip_original`, `field_read`, `field_write`, and `field_record`. Conditions support `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `contains`, `starts_with`, `ends_with`, `is_null`, and `not_null`. Arbitrary code evaluation is intentionally unsupported.

## Examples

```bash
simplehook rules validate examples/replace-return-int.json --json
simplehook rules add examples/replace-return-int.json --dry-run --json
simplehook rules add examples/replace-return-int.json --json
simplehook reload --json
simplehook logs --rule test_get_int --follow
```

Field rules omit `method`, set `target.field`, and use a `field_*` action. Static fields are processed when the class becomes available. Instance fields are processed after a matching object constructor completes.

## Logging

The companion stores JSONL under its device-protected app data. Entries include timestamp, process and thread identity, rule ID, class, method, phase, arguments, return or exception, and elapsed microseconds where applicable. Files rotate at 4 MiB with four files retained. Runtime rate is limited to 100 entries per second, each entry to 32 KiB, and stack traces to 16 KiB. Oversized entries are replaced by a structured `LOG_ENTRY_TOO_LARGE` record that preserves package and rule identity.

## ClassLoader

The runtime starts with the package `PathClassLoader`, observes both `ClassLoader.loadClass` variants plus `BaseDexClassLoader.findClass` and constructor events, records additional loaders, and retries only waiting rules when their target class appears. This covers secondary DEX, split APK loaders, `DexClassLoader`, and common dynamic-loading flows. A missing class is `WAITING_FOR_CLASS`, not a permanent failure. A method that itself triggers the first class load may complete before installation; subsequent calls are hooked after the rule reaches `ACTIVE`.

## Troubleshooting

- `RUNTIME_UNAVAILABLE`: install the runtime APK and verify `simplehook environment --json`.
- `module_enabled` is read from the LSPosed database by the root CLI. `null` means the configuration could not be inspected; it must never be interpreted as disabled.
- `runtime_attached` and `heartbeat_recent` report whether a scoped target process contacted the companion recently. `false` is normal when the target process is stopped and does not imply that the module is disabled.
- `module_scoped` and `scope_packages` report the configured LSPosed target scope independently from runtime heartbeat state.
- `WAITING_FOR_PROCESS`: start the selected package/process.
- `WAITING_FOR_CLASS`: trigger the feature that loads the class, then query status again.
- `CLASS_NOT_FOUND`: the class did not load before the inspect timeout; verify package and class spelling.
- `FAILED`: inspect the rule's `runtime.detail` and JSONL logs.
- Missing heartbeats or logs on a restricted ROM: query `simplehook status --json`, then check whether the ROM blocks explicit foreground broadcasts. SimpleHook retries unacknowledged events with a bounded queue; it never changes AutoStart or other system settings automatically. Rule loading and hook execution use XSharedPreferences and do not depend on the companion process being awake.

## Test App

`SimpleHookTestApp.apk` exposes integer, boolean, String, argument, overload, constructor, static field, instance field, exception, and delayed-class fixtures. The delayed fixture is built into a separate dex asset and loaded with `DexClassLoader` so `WAITING_FOR_CLASS` behavior is tested deterministically.

After installing both APKs, enabling the LSPosed scope, refreshing the module, and unlocking the test device, run the repeatable runtime matrix from the repository:

```bash
python3 toolpacks/simplehook/tests/device_runtime_test.py --serial SERIAL --json
```

The script uses only rules prefixed with `device_simplehook_`, preserves unrelated rules, and reports each actually executed feature as PASS or FAIL.

## Limitations

- Runtime behavior requires a real API 93-compatible framework and cannot be fully exercised by host JVM tests; the included device matrix is the authoritative hook test.
- Removing or changing a signature leaves a dormant callback until process restart; callbacks consult the current generation and perform no action when disabled or removed.
- Instance field actions apply to newly constructed instances after a rule is active, not arbitrary pre-existing instances.
- Wildcards are suffix-only prefixes such as `get*`, require an exact parameter list, expand to at most 64 members, and never allow global `*`.
- Kotlin suspend functions must be addressed by their compiled JVM signature.
