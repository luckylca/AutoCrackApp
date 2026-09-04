# ui-inspect skill

Use this CLI for live Android UI discovery and temporary View/UI mutations.

## First steps

```bash
ui-inspect --help
runtime-inspect doctor --package PKG --json
ui-inspect windows --package PKG --json
```

Use `--json` for Agent workflows. Treat `ok:false` or `supported:false` as a real diagnostic result, not an empty success.

## Typical workflow

1. Discover with `windows`, `tree`, `find`, or `at`.
2. Preserve returned `obj_*` handles exactly.
3. Inspect with `props`, `parent`, `children`, `siblings`, `listeners`, or `stack`.
4. Pass handles to `runtime-inspect object/class-describe` when Java metadata is needed.
5. Use `image` and, when a token is returned, `image-result`.
6. Use `action` only for an explicit runtime mutation.

Compose semantics are best-effort and version-dependent. Secure/DRM/vendor surfaces can refuse capture even when normal View capture works.
