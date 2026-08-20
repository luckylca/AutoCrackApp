#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerStoppedSignalPassthroughTest.kt")
text = path.read_text(encoding="utf-8")

old_packet = '''        assertEquals(11, GdbRemoteStoppedSignalPassthrough.signalNumber(stop))
        assertEquals("vCont;C0b", GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop))'''
new_packet = '''        assertEquals(11, GdbRemoteStoppedSignalPassthrough.signalNumber(stop))
        assertEquals(
            "vCont;C0b:5b6a;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )'''
if text.count(old_packet) != 1:
    raise SystemExit("legacy SIGSEGV packet expectation not found exactly once")
text = text.replace(old_packet, new_packet, 1)

old_trap = '''    @Test
    fun neverAutoPassesSigtrap() {
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply("T05thread:64b;reason:signal;")
        }
    }'''
new_trap = '''    @Test
    fun explicitSignalReasonSigtrapIsThreadScoped() {
        val stop = "T05thread:64b;reason:signal;"
        assertEquals(5, GdbRemoteStoppedSignalPassthrough.signalNumber(stop))
        assertEquals(
            "vCont;C05:64b;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )
    }'''
if text.count(old_trap) != 1:
    raise SystemExit("legacy SIGTRAP rejection expectation not found exactly once")
text = text.replace(old_trap, new_trap, 1)

# The 5.15.5 state-machine test separately verifies that debugger SIGTRAP
# (for example reason:breakpoint) is still rejected from automatic signal pass-through.
path.write_text(text, encoding="utf-8")
print("Updated Phase 5.15.4 legacy signal tests for 5.15.5 thread-scoped semantics")
