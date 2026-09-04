# uiautomator2 full UI automation skill

Use this Toolpack for complete upstream uiautomator2 Android automation from the
AutoCrack Debian rootfs.

This package intentionally preserves the upstream **uiautomator2 3.7.0** Python
API, the upstream `uiautomator2` command, the upstream `u2cli` command, and the
device-side assets shipped by upstream. AutoCrack does not replace uiautomator2
with a reduced click/dump wrapper.

## Runtime dependency

The rootfs must provide a working `adb` command. AutoCrack's Debian rootfs ships
Android platform-tools for this purpose. Upstream `adbutils` searches `PATH`
first, so it uses the rootfs `adb` rather than an x86-only bundled executable.

## Upstream commands

Inspect the exact command surface before using less-common operations:

```bash
uiautomator2 --help
uiautomator2 version
uiautomator2 init --help
uiautomator2 screenshot --help
u2cli --help
```

Both commands are the upstream entrypoints.

## Full Python API

The complete package is importable:

```python
import uiautomator2 as u2

d = u2.connect()
print(d.info)
print(d.dump_hierarchy())

d(text="Login").click()
d(resourceId="com.example:id/name").set_text("alice")
d.swipe(0.8, 0.8, 0.2, 0.2)
d.screenshot("/workspace/screen.png")

with d.session("com.example") as app:
    print(app.app_current())
```

Selectors, XPath, hierarchy, screenshots, app/session control, watchers,
gestures, text input, shell/device access and other upstream APIs remain
available.

## Device-side upstream assets

The Toolpack carries the exact upstream assets embedded in the 3.7.0 wheel:

- `uiautomator2/assets/app-uiautomator.apk`
- `uiautomator2/assets/u2.jar`
- `uiautomator2/assets/version.json`

Use `uiautomator2 copy-assets` if a workflow needs copies in the workspace.

## Typical AutoCrack validation loop

1. Inspect or modify the target with JADX/SimpleHook/Frida/runtime-control.
2. Use uiautomator2 to launch and navigate the target.
3. Trigger the exact UI path under test.
4. Capture hierarchy/screenshots/logs.
5. Compare expected runtime behavior.
6. Repeat until the hook or patch is verified.

Keep screenshots and large hierarchy dumps under `/workspace`.

## Packaging note

Upstream `adbutils 2.11.0` does not publish a Linux ARM64 wheel. Its upstream
sdist is pure Python and is packaged unchanged, while the actual `adb`
executable comes from the Debian ARM64 rootfs. This avoids downgrading
uiautomator2 and avoids pretending that the upstream x86 wheel is ARM64-native.
