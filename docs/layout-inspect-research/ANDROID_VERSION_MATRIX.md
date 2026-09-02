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

Current owned device: Android API 36, arm64-v8a, rooted through KernelSU. This
device is the release gate for Java-level runtime, cross-tool, UI, WebView,
maps/SO and explicitly supported Dex strategies. Older rows require automated
host coverage and a real device before being upgraded from contract support to
device-verified support.
