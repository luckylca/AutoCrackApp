# mitmproxy full interception skill

Use this Toolpack for interactive or scripted HTTP(S), WebSocket and other
protocol interception supported by upstream mitmproxy.

AutoCrack packages the official **mitmproxy 12.2.3 Linux aarch64 standalone
release** without replacing the upstream programs. The following commands are
the original upstream executables:

```bash
mitmproxy
mitmdump
mitmweb
```

## Why the standalone distribution is used

mitmproxy 12.2.3's Python package requires a newer Python runtime than the
Debian Bookworm rootfs provides. The official Linux aarch64 standalone binaries
embed the upstream Python runtime and all runtime dependencies, so AutoCrack
does not downgrade mitmproxy just to fit the rootfs Python version.

The embedded runtime still supports upstream addon scripts. A script loaded
with `mitmdump -s` can import `mitmproxy.http`, `mitmproxy.ctx`, and the
other modules exposed to normal mitmproxy addons.

## First steps

```bash
mitmproxy --version
mitmdump --help
mitmweb --help

# Save flows for later analysis.
mitmdump -w /workspace/flows.mitm

# Load an upstream-style addon.
mitmdump -s /workspace/addon.py -w /workspace/flows.mitm
```

Always use `--help` / `--options` / `--commands` for the exact upstream
surface instead of assuming AutoCrack-specific flags.

## Android interception workflow

A typical authorized-device workflow is:

1. Start `mitmdump` or `mitmweb` on a chosen rootfs port.
2. Configure the test device/application to use that proxy.
3. Install/trust the generated CA only in an authorized test environment when
   HTTPS interception is required.
4. If the target uses certificate pinning, use the existing Frida/SimpleHook
   capabilities only on software you are authorized to test.
5. Save flows under `/workspace` and inspect/replay them with upstream
   mitmproxy commands/addons.

AutoCrack may add Android proxy/certificate convenience helpers later, but they
must remain additive. The native mitmproxy command surface is authoritative.

## Addon API smoke

The Toolpack carries `examples/autocrack_addon_smoke.py`. It is a normal
upstream addon that imports mitmproxy modules and immediately shuts down after
startup. It exists only to verify that the embedded addon/programmatic runtime
survived packaging:

```bash
mitmdump -q -s /opt/autocrack/toolpacks/active/mitmproxy/examples/autocrack_addon_smoke.py
```

## Resource note

The three upstream binaries each contain a standalone Python application and
are intentionally packaged independently. AutoCrack does not deduplicate them
by modifying the binaries because doing so would cease to be the official
upstream executables.
