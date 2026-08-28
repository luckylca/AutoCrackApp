#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt")
text = path.read_text(encoding="utf-8")
old = "    /** Single-step only the explicitly selected, validated LLDB thread. */"
new = "    /** Single-step only the thread identified by the most recent LLDB stop reply. */"
if text.count(old) != 1:
    raise SystemExit(f"expected exactly one generated explicit-step comment, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Prepared generated step comment for Phase 5.15.4 patch")
