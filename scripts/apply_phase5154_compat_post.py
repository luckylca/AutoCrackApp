#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt")
text = path.read_text(encoding="utf-8")
old = "    /** Single-step only the thread identified by the most recent LLDB stop reply. */"
new = "    /** Single-step only the explicitly selected, validated LLDB thread. */"
if text.count(old) != 1:
    raise SystemExit(f"expected exactly one generated compatibility step comment, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Restored generated explicit-step comment after Phase 5.15.4 patch")
