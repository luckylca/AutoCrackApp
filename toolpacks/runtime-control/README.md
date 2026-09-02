# runtime-control

`runtime-control` is the optional Layout-Inspect-style mutation/control Toolpack. It is only a CLI surface over the shared AutoCrack Runtime provider `content://com.luckylca.autocrack.runtime`; it does not install a separate Xposed module.

## Runtime contract

- Requires the shared AutoCrack Runtime APK to be installed and LSPosed-scoped into the target package.
- Uses `android-shell` for host/rootfs communication.
- Emits deterministic JSON with `--json`.
- Mutating operations are explicit and current-runtime-only unless the command says otherwise.

## Commands

```bash
runtime-control status --json
runtime-control webview-list --package com.example.app --json
runtime-control webview-info --package com.example.app --handle obj_webview --json
runtime-control webview-debug --package com.example.app --json
runtime-control webview-debug --package com.example.app --disable --json
runtime-control webview-eval --package com.example.app obj_webview 'document.title' --json
runtime-control webview-eval-result --package com.example.app js_token --json
runtime-control webview-load-url --package com.example.app obj_webview https://example.com --json
runtime-control webview-reload --package com.example.app obj_webview --json
runtime-control webview-go-back --package com.example.app obj_webview --json
runtime-control webview-go-forward --package com.example.app obj_webview --json
runtime-control webview-clear-cache --package com.example.app --handle obj_webview --include-disk --json
runtime-control secure-status --package com.example.app --json
runtime-control secure-disable --package com.example.app --json
runtime-control so-inject --package com.example.app /data/local/tmp/libfoo.so --json
runtime-control so-dlopen --package com.example.app /data/local/tmp/libfoo.so --flags 2 --json
runtime-control activity-start --package com.example.app --component com.example.app/.MainActivity --json
runtime-control process-kill --package com.example.app --delay-ms 350 --json
```

## Strategy boundaries

`so-inject` uses target-process `System.load`; `so-dlopen` uses the AutoCrack JNI bridge and native `dlopen` and reports linker namespace/ABI/SELinux failures directly. `secure-disable` clears `Window.FLAG_SECURE` on known Activity windows; private SurfaceControl, DRM, and vendor-secure producers are reported as outside the stable Java strategy.


## Object control

`runtime-control object-field-set --package PKG HANDLE FIELD --value-json JSON` writes a scalar field through a runtime object handle. Supported field types are String/CharSequence, primitive/boxed numeric types, boolean, char, enum names, and null for non-primitive fields. Complex object construction is intentionally rejected.

`runtime-control object-method-call --package PKG HANDLE METHOD --arg-types-json JSON --args-json JSON` invokes an explicitly named reflected method with at most 16 scalar arguments. The caller must provide exact parameter type names. This is a bounded runtime-control feature and returns a summarized result or object handle.

`runtime-control so-android-dlopen-ext --package PKG /system/lib64/liblog.so --ext-flags 0 --json` uses Android bionic `android_dlopen_ext`. It returns the handle or linker error and explicitly reports that namespace bypass is not claimed.

`runtime-control so-dlsym --package PKG symbolName --json` resolves a native symbol through `dlsym`. Use `--handle 0x...` with a handle returned by `so-dlopen`, or omit it for `RTLD_DEFAULT`. This only returns the address and does not call the function.
