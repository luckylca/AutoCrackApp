#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt")
text = path.read_text(encoding="utf-8")
old = "        const val AARCH64_INSTRUCTION_BYTES = 4L\n"
new = "        const val AARCH64_INSTRUCTION_BYTES = 4\n"
if text.count(old) != 1:
    raise SystemExit(f"expected exactly one generated AARCH64_INSTRUCTION_BYTES Long constant, found {text.count(old)}")
path.write_text(text.replace(old, new), encoding="utf-8")
print("Applied Phase 5.15.2 generated-source numeric fix")
