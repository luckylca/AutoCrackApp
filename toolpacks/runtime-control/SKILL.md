# runtime-control skill

Use this CLI for explicit target-process mutations: Activity/process control, WebView operations, FLAG_SECURE changes, object mutation, and native library loading.

## First steps

```bash
runtime-control --help
runtime-inspect doctor --package PKG --json
runtime-control status --json
```

Inspection commands such as `secure-status`, `secure-diagnose`, `so-diagnose`, WebView list/info, and `so-dlsym` are read-oriented. Mutation commands can change or terminate the target and should be used only when required by the task.

For `webview-eval`, preserve a returned token and use `webview-eval-result` until the result is ready. Object operations require exact `obj_*` handles and exact declared parameter types.

Native `dlopen`/`android_dlopen_ext` are available, but linker namespace bypass is not claimed.
