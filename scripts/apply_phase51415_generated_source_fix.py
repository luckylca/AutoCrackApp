#!/usr/bin/env python3
from pathlib import Path

remote = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt")
data = remote.read_bytes()
needle = b".trimEnd('\x00')"
replacement = b".trimEnd(Char(0))"
actual = data.count(needle)
if actual != 1:
    raise SystemExit(
        f"{remote}: expected exactly one generated NUL trim expression, found {actual}"
    )
data = data.replace(needle, replacement, 1)
if b"\x00" in data:
    raise SystemExit(f"{remote}: generated Kotlin still contains NUL bytes")
remote.write_bytes(data)
print("Repaired generated Kotlin NUL literal for phase 5.14.15")
