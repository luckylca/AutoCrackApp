package com.luckylca.autocrack.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun typedAttachUsesBoundedContinueClassWaitInsteadOfOrdinaryRequestTimeout() {
        assertEquals(90_000, HostDebuggerRemoteClient.ATTACH_WAIT_TIMEOUT_MILLIS)
        assertEquals(5_000, HostDebuggerRemoteClient.RUN_REPLY_POLL_TIMEOUT_MILLIS)
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
    fun phase514ClientContainsNoWriteOrBreakpointAdapters() {
        val methodNames = HostDebuggerRemoteClient::class.java.methods.map { method -> method.name }.toSet()
        assertFalse("writeMemory" in methodNames)
        assertFalse("writeRegister" in methodNames)
        assertFalse("insertBreakpoint" in methodNames)
        assertFalse("removeBreakpoint" in methodNames)
    }
}
