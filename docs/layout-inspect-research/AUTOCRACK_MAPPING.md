# AutoCrack Mapping

| Original area | Shared capability | CLI owner | Execution boundary |
|---|---|---|---|
| Capture layout/windows | `ui.windows`, `ui.tree`, `ui.at` | `ui-inspect` | target runtime |
| View properties/listeners/stacks/image/actions | `ui.props`, `ui.listeners`, `ui.stacks`, `ui.image`, `ui.action` | `ui-inspect` | target runtime; artifact file for image |
| Process/runtime activities | `runtime.process`, `runtime.activities` | `runtime-inspect` | target runtime plus host process metadata |
| Declared activities | `runtime.activities.declared` | `runtime-inspect` | PackageManager/host |
| Class loaders/search/preview | `runtime.classloaders`, `runtime.class.search`, `runtime.class.describe` | `runtime-inspect` | target runtime |
| Object preview/dump | `object.describe`, `object.fields`, `object.dump`, `object.release` | `runtime-inspect` | target runtime |
| Maps/ranges/modules/SO | `memory.maps`, `memory.read`, `memory.modules`, `memory.so` | `memory-dump` | root `/proc` plus target runtime/native |
| Dex/XML/assets | `memory.dex`, `memory.xml`, `memory.assets` | `memory-dump` | target runtime/native; artifacts |
| Activity start/process kill | `control.activity.start`, `control.process.kill` | `runtime-control` | host root or target runtime |
| SO injection/secure/WebView | `control.so.inject`, `control.secure`, `webview.*` | `runtime-control` | target runtime |
| Method/constructor/field hooks | `hook.*` | `simplehook` | target runtime |

The four Toolpacks contain command UX only. They do not each install Xposed
hooks. One companion APK owns one Xposed entry, dispatcher, channel and all
registries. SimpleHook keeps a separate CLI and schema but is a capability
consumer of that same runtime.

`android-shell` is treated as baseline host infrastructure for dynamic
Toolpacks. Existing installations and the command name remain compatible.
