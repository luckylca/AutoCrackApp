#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt")
text = path.read_text(encoding="utf-8")
old = "    /** Single-step only the explicitly selected, validated LLDB thread. */"
new = "    /** Single-step only the thread identified by the most recent LLDB stop reply. */"
count = text.count(old)
if count != 1:
    raise SystemExit(f"{path}: expected one thread-aware step comment, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Applied Phase 5.15.4 compatibility shim for thread-aware step comment")
