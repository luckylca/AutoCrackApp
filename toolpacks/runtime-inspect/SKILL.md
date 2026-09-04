# runtime-inspect skill

Use this CLI to inspect the shared AutoCrack Runtime, target processes, Activities, ClassLoaders, classes, and opaque object handles.

## Readiness

```bash
runtime-inspect --help
runtime-inspect doctor --package PKG --json
```

Run `doctor` when starting a multi-step runtime task, when the target was restarted, or after bridge/runtime errors. `doctor` is read-only. The Stage 60 transport preserves the same request UUID across delivery recovery, uses the ordered-broadcast handshake to establish the target Binder endpoint, prefers direct Binder requests once available, and can thaw MIUI/Greeze-frozen targets when required. A missing target heartbeat alone does not prove the LSPosed module is disabled.

## Typical workflow

- `process`, `activities`, `declared-activities`: target/lifecycle context.
- `classloaders` -> `class-search` -> `class-describe`: Java discovery.
- `object`, `object-fields`, `object-dump`: inspect `obj_*` handles returned by this or another runtime Toolpack.
- `object-release` when a handle is no longer needed.

Handles are scoped to the target process/session and can become stale after process restart. Always use `--json` for Agent parsing.
