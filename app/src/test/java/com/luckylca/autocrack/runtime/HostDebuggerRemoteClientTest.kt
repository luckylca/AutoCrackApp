package com.luckylca.autocrack.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDebuggerRemoteClientTest {
    @Test
    fun framesGdbRemotePacketWithChecksum() {
        assertEquals(
            "\$qSupported#37",
            String(GdbRemotePacketCodec.frame("qSupported"), Charsets.US_ASCII),
        )
    }

    @Test
    fun checksumMatchesKnownPacket() {
        assertEquals(
            0x37,
            GdbRemotePacketCodec.checksum("qSupported".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun decodesBoundedMemoryHex() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte()),
            GdbRemotePacketCodec.decodeHex("007f80ff"),
        )
    }

    @Test
    fun rejectsMalformedMemoryHex() {
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemotePacketCodec.decodeHex("abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemotePacketCodec.decodeHex("zz")
        }
    }

    @Test
    fun debuggerEndpointIsLiteralIpv4Loopback() {
        val endpoint = HostDebuggerRemoteClient.ipv4LoopbackEndpoint(5039)

        assertEquals("127.0.0.1", endpoint.address.hostAddress)
        assertEquals(4, endpoint.address.address.size)
        assertEquals(5039, endpoint.port)
    }

    @Test
    fun typedAttachAndInterruptRecoveryUseBoundedContinueClassWaits() {
        assertEquals(90_000, HostDebuggerRemoteClient.ATTACH_WAIT_TIMEOUT_MILLIS)
        assertEquals(5_000, HostDebuggerRemoteClient.RUN_REPLY_POLL_TIMEOUT_MILLIS)
        assertEquals(2_000, HostDebuggerRemoteClient.STEP_WAIT_TIMEOUT_MILLIS)
        assertEquals(30_000, HostDebuggerRemoteClient.INTERRUPT_RECOVERY_WAIT_TIMEOUT_MILLIS)
    }

    @Test
    fun handshakeTracksNoAckModeWithoutChangingCapabilityContract() {
        val handshake = GdbRemoteHandshake(
            capabilities = setOf("PacketSize=20000", "QStartNoAckMode+"),
            noAckModeEnabled = true,
        )

        assertEquals(2, handshake.capabilities.size)
        assertEquals(true, handshake.noAckModeEnabled)
    }

    @Test
    fun parsesRegisterMetadataWithoutAssumingArchitectureLayout() {
        val info = GdbRemoteRegisterInfoParser.parse(
            index = 0,
            payload = "name:x0;bitsize:64;offset:0;encoding:uint;format:hex;set:General Purpose Registers;generic:arg1;",
        )

        assertEquals(0, info.index)
        assertEquals("x0", info.name)
        assertEquals(64, info.bitSize)
        assertEquals(0, info.byteOffset)
        assertEquals("uint", info.encoding)
        assertEquals("hex", info.format)
        assertEquals("General Purpose Registers", info.registerSet)
        assertEquals("arg1", info.genericName)
    }

    @Test
    fun rejectsRegisterMetadataWithoutName() {
        assertThrows(IllegalStateException::class.java) {
            GdbRemoteRegisterInfoParser.parse(
                index = 0,
                payload = "bitsize:64;encoding:uint;",
            )
        }
    }

    @Test
    fun stopReplyParserExtractsExactStoppedThread() {
        val stop = "T13thread:393;name:bin.mt.plus;20:4c1ddb367e000000;reason:signal;"

        assertEquals("393", GdbRemoteStopReplyParser.threadId(stop))
        assertEquals("vCont;s:393", GdbRemoteExecutionPacketFactory.stepFromStopReply(stop))
    }

    @Test
    fun stopReplyParserSupportsMultiprocessThreadSyntax() {
        val stop = "T05thread:p123.456;reason:trace;"

        assertEquals("p123.456", GdbRemoteStopReplyParser.threadId(stop))
        assertEquals("vCont;s:p123.456", GdbRemoteExecutionPacketFactory.stepFromStopReply(stop))
    }

    @Test
    fun threadSpecificStepRejectsMissingZeroOrMalformedThreadId() {
        assertNull(GdbRemoteStopReplyParser.threadId("S05"))
        assertNull(GdbRemoteStopReplyParser.threadId("T05thread:0;reason:trace;"))
        assertNull(GdbRemoteStopReplyParser.threadId("T05thread:393,evil;reason:trace;"))
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteExecutionPacketFactory.stepFromStopReply("T05reason:trace;")
        }
    }

    @Test
    fun threadSpecificStepFactoryNeverFallsBackToBareStep() {
        val packet = GdbRemoteExecutionPacketFactory.stepFromStopReply(
            "T05thread:393;reason:trace;",
        )

        assertEquals("vCont;s:393", packet)
        assertFalse(packet == "vCont;s")
    }

    @Test
    fun typedThreadIdsAreValidatedBeforePacketConstruction() {
        assertEquals("1b24", GdbRemoteThreadIdValidator.normalize("1B24"))
        assertEquals("p123.456", GdbRemoteThreadIdValidator.normalize("p123.456"))
        assertEquals("vCont;s:1b24", GdbRemoteExecutionPacketFactory.stepThread("1B24"))
        assertTrue(GdbRemoteThreadIdValidator.matchesTid("1b24", 6948))
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteExecutionPacketFactory.stepThread("1b24;vCont;c")
        }
    }

    @Test
    fun parsesBoundedThreadListAndNames() {
        assertEquals(
            listOf("1b24", "p123.456"),
            GdbRemoteThreadInfoParser.parseThreadBatch("m1b24,p123.456"),
        )
        assertTrue(GdbRemoteThreadInfoParser.parseThreadBatch("l").isEmpty())
        assertEquals(
            "Signal Catcher",
            GdbRemoteThreadInfoParser.parseExtraInfo("5369676e616c2043617463686572"),
        )
        assertNull(GdbRemoteThreadInfoParser.parseExtraInfo("E01"))
    }

    @Test
    fun typedHardwareBreakpointPacketsAreFixedAndAddressValidated() {
        assertEquals(
            "Z1,7f12345000,4",
            GdbRemoteBreakpointPacketFactory.insertHardwareExecution(0x7f12345000L),
        )
        assertEquals(
            "z1,7f12345000,4",
            GdbRemoteBreakpointPacketFactory.removeHardwareExecution(0x7f12345000L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteBreakpointPacketFactory.insertHardwareExecution(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteBreakpointPacketFactory.insertHardwareExecution(0x1002L)
        }
    }

    @Test
    fun hardwareBreakpointHitPolicyRequiresTrapPcMatchAndNonSignalReason() {
        val address = 0x7f12345000L
        assertTrue(GdbRemoteHardwareBreakpointHitPolicy.isTrustedHit("T05thread:393;reason:breakpoint;", address, address))
        assertFalse(GdbRemoteHardwareBreakpointHitPolicy.isTrustedHit("T05thread:393;reason:trace;", address, address))
        assertFalse(GdbRemoteHardwareBreakpointHitPolicy.isTrustedHit("T05thread:393;reason:signal;", address, address))
        assertFalse(GdbRemoteHardwareBreakpointHitPolicy.isTrustedHit("T05thread:393;reason:breakpoint;", address + 4, address))
    }

    @Test
    fun decodesAarch64LittleEndianProgramCounter() {
        assertEquals(
            0x0000007daa219a8cL,
            GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong("8c9a21aa7d000000"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong("0000000000000000")
        }
    }

    @Test
    fun runReplyValidatorAcceptsOnlyStopOrExitPackets() {
        assertTrue(GdbRemoteRunReplyValidator.isStopOrExit("T05thread:393;reason:trace;"))
        assertTrue(GdbRemoteRunReplyValidator.isStopOrExit("S05"))
        assertTrue(GdbRemoteRunReplyValidator.isStopOrExit("W00"))
        assertTrue(GdbRemoteRunReplyValidator.isStopOrExit("X09"))
        assertFalse(GdbRemoteRunReplyValidator.isStopOrExit("E37"))
        assertFalse(GdbRemoteRunReplyValidator.isStopOrExit("OK"))
    }

    @Test
    fun e37CanNeverBeAcceptedAsContinueStopReply() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteRunReplyValidator.requireStopOrExit("continue", "E37")
        }
        assertTrue(error.message.orEmpty().contains("LLDB continue failed: E37"))
    }

    @Test
    fun boundedRunTimeoutHasDistinctTypeForConservativeStateRecovery() {
        val timeout: java.io.IOException = GdbRemoteRunTimeoutException("step timed out")
        assertEquals(GdbRemoteRunTimeoutException::class.java, timeout.javaClass)
        assertEquals("step timed out", timeout.message)
    }

    @Test
    fun phase515ClientExposesOnlyTypedHardwareBreakpointAdapters() {
        val methodNames = HostDebuggerRemoteClient::class.java.methods.map { method -> method.name }.toSet()
        assertFalse("writeMemory" in methodNames)
        assertFalse("writeRegister" in methodNames)
        assertFalse("insertBreakpoint" in methodNames)
        assertFalse("removeBreakpoint" in methodNames)
        assertFalse("sendRawPacket" in methodNames)
        assertTrue("setHardwareExecutionBreakpoint" in methodNames)
        assertTrue("removeHardwareExecutionBreakpoint" in methodNames)
    }
}
