#!/usr/bin/env python3
from pathlib import Path

remote = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt")
data = remote.read_bytes()

nul_needle = b".trimEnd('\x00')"
nul_replacement = b".trimEnd(Char(0))"
nul_count = data.count(nul_needle)
if nul_count != 1:
    raise SystemExit(
        f"{remote}: expected exactly one generated NUL trim expression, found {nul_count}"
    )
data = data.replace(nul_needle, nul_replacement, 1)

regex_needle = b'Regex("^(?:p[0-9a-f]+\\.)?[0-9a-f]+$")'
regex_replacement = b'Regex("^(?:p[0-9a-f]+\\\\.)?[0-9a-f]+$")'
regex_count = data.count(regex_needle)
if regex_count != 1:
    raise SystemExit(
        f"{remote}: expected exactly one generated one-backslash thread regex, found {regex_count}"
    )
data = data.replace(regex_needle, regex_replacement, 1)

if b"\x00" in data:
    raise SystemExit(f"{remote}: generated Kotlin still contains NUL bytes")
if regex_needle in data:
    raise SystemExit(f"{remote}: generated Kotlin still contains invalid one-backslash regex escape")
remote.write_bytes(data)
print("Repaired generated Kotlin NUL and regex escapes for phase 5.14.15")
