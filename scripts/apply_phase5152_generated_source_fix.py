#!/usr/bin/env python3
from pathlib import Path

bridge_path = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt")
bridge_text = bridge_path.read_text(encoding="utf-8")
old = "        const val AARCH64_INSTRUCTION_BYTES = 4L\n"
new = "        const val AARCH64_INSTRUCTION_BYTES = 4\n"
if bridge_text.count(old) != 1:
    raise SystemExit(
        f"expected exactly one generated AARCH64_INSTRUCTION_BYTES Long constant, found {bridge_text.count(old)}"
    )
bridge_path.write_text(bridge_text.replace(old, new), encoding="utf-8")

test_path = Path("app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerCodeContextTest.kt")
test_text = test_path.read_text(encoding="utf-8")
old_offset = "        assertEquals(0x16688cL, segment.relativeOffset(0x7da9e6688cL))\n"
new_offset = "        assertEquals(0x1688cL, segment.relativeOffset(0x7da9e6688cL))\n"
if test_text.count(old_offset) != 1:
    raise SystemExit(
        f"expected exactly one generated wrong module-offset assertion, found {test_text.count(old_offset)}"
    )
test_path.write_text(test_text.replace(old_offset, new_offset), encoding="utf-8")

print("Applied Phase 5.15.2 generated-source numeric and module-offset test fixes")
