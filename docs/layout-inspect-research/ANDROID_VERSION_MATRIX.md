# Android Version Matrix

| Capability | API 26-27 | API 28-30 | API 31-34 | API 35-36 |
|---|---|---|---|---|
| Xposed bootstrap | legacy LSPosed contract | same | same | same; explicit broadcast restrictions tested |
| `WindowManagerGlobal.mViews` | reflected list | reflected list | reflected list; fallback observer | reflected list; fallback observer |
| Listener reflection | per-field result | Xposed target context | Xposed target context | per-field failures surfaced |
| ActivityThread records | legacy record fields | field strategy | field strategy | lifecycle registry preferred |
| DexPath/DexFile entries | supported | supported | supported | supported where Java API remains reachable |
| mCookie/mInternalCookie | version strategy required | version strategy required | cookie shape varies | capability-gated, no unverified pointer use |
| DexCaches/VM offsets | unsupported without exact build strategy | optional native strategy | optional native strategy | unsupported until API-36 layout is verified |
| PixelCopy | supported (API 26+) | supported | supported | supported; secure surfaces may reject |
| FLAG_SECURE clear | Activity windows | Activity windows | Activity windows | Activity windows; surface-level secure may remain |
| Runtime assets | `AssetManager.list/open` | same | ApkAssets-backed implementation | same public contract |
| Runtime binary XML | native strategy required | native strategy required | native layout changes | unsupported until verified |
| `System.load` injection | namespace rules apply | stricter namespaces | namespace/SELinux apply | failures reported; no bypass claim |

The intended release-gate device remains Android API 36 arm64-v8a with root/LSPosed access, but the current Mac Runner session did not expose `adb` or `android-shell`. Rows above therefore distinguish contract support from device-verified support. A row is upgraded to device-verified only after the matching real-device matrix has been rerun in the current workspace.
