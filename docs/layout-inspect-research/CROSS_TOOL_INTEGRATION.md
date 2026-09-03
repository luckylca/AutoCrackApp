# Cross-tool Integration Contract

This document records the Phase I integration contract for the shared AutoCrack Runtime migration. It is intentionally split into two layers:

1. **Host contract validation** checks that Toolpack manifests, commands, and capability declarations are internally consistent. It does not talk to a target device.
2. **Device positive validation** proves the same flows against an LSPosed-scoped target process. Those positive checks still depend on the target loading the current `autocrack-runtime` APK.

## Maintained Toolpacks

The maintained runtime-facing Toolpacks are:

- `ui-inspect`
- `runtime-inspect`
- `memory-dump`
- `runtime-control`
- `simplehook`

All five packages must generate `schemaVersion: 2` manifests with `payloadEntry=payload.zip`, payload SHA-256/size, command declarations, self-tests, `requires.runtime >=1.0.0`, and the `android-shell` command requirement. `simplehook` keeps its rule files at `schema_version: 1`; only its Toolpack packaging manifest uses schema v2.

## Required flows

### 1. `ui-inspect at` → `runtime-inspect object`

Purpose: prove that a View picked by coordinates becomes a reusable object handle.

Host contract:

- `ui-inspect` exposes `at`.
- `runtime-inspect` exposes `object`.
- Manifests declare `ui.at` and `object.describe`.

Device positive check:

1. Start an owned target fixture.
2. Run `ui-inspect at --package <pkg> X Y --json`.
3. Extract the returned handle.
4. Run `runtime-inspect object --package <pkg> <handle> --json`.
5. Assert that the handle resolves to the same View/object family and is not stale.

### 2. View listener → class describe → SimpleHook rule → click/log

Purpose: prove that UI listener discovery can drive precise method-hook rule authoring.

Host contract:

- `ui-inspect` exposes `listeners`.
- `runtime-inspect` exposes `class-describe`.
- `simplehook` exposes `rules`, `reload`, and `logs`.
- Manifests declare `ui.listeners`, `runtime.class.describe`, and `hook.reload`.

Device positive check:

1. Pick or find a clickable View in an owned target fixture.
2. Run `ui-inspect listeners` on that handle.
3. Describe the listener class with `runtime-inspect class-describe`.
4. Add a bounded `simplehook` rule for the selected method.
5. Trigger the click and assert that `simplehook logs` records the expected event.

### 3. ClassLoader handle → memory DEX inspection/export

Purpose: prove that Runtime class-loader handles can feed memory/Dex tooling.

Host contract:

- `runtime-inspect` exposes `classloaders`.
- `memory-dump` exposes `dex-list`, `dex-art-probe`, and `dex-art-export`.
- Manifests declare `runtime.classloaders`, `memory.dex.list`, `memory.dex.art_probe`, and `memory.dex.art_export.open`.

Device positive check:

1. Run `runtime-inspect classloaders --package <pkg> --json`.
2. Select a returned loader handle.
3. Run `memory-dump dex-list --package <pkg> --loader <handle> --json`.
4. For Android-version-compatible layouts, run bounded `dex-art-probe` or token-bound `dex-art-export` and verify hashes/limits.

### 4. WebView discovery → debug/eval/result

Purpose: prove WebView discovery, debug enablement, JS execution, and result retrieval through `runtime-control`.

Host contract:

- `runtime-control` exposes `webview-list`, `webview-debug`, `webview-eval`, `webview-eval-result`, and `webview-devtools-sockets`.
- Manifest declares `webview.list`, `webview.debug`, `webview.eval`, `webview.eval.result`, and `webview.devtools_socket`.

Device positive check:

1. Start the owned WebView fixture.
2. Run `runtime-control webview-list --package <pkg> --json`.
3. Enable debugging with `runtime-control webview-debug`.
4. Execute a small deterministic JavaScript expression with `webview-eval`.
5. Poll the token through `webview-eval-result` and assert the returned value.
6. Optionally correlate the process with `webview-devtools-sockets`.

## Host validator

Run:

```bash
python3 scripts/validate_cross_tool_contract.py --json
```

The validator performs only host-side checks:

- runs each maintained CLI with `--help`;
- reads each generated `dist/manifest.json`;
- verifies manifest schema v2 and payload metadata;
- verifies required command/capability declarations;
- verifies the four Phase I flow contracts above.

A PASS from this script means the Toolpack interfaces are internally consistent. It is not a substitute for target-device positive validation.
