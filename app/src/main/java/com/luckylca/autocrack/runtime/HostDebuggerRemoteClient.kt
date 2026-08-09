package com.luckylca.autocrack.runtime

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Minimal client for the loopback gdb-remote endpoint exposed by the trusted Android lldb-server.
 *
 * Phase 5.14 deliberately implements only observation and execution-control commands:
 * handshake/stop query, register reads, bounded memory reads, continue, single-step and interrupt.
 * There is intentionally no packet adapter for register writes, memory writes or breakpoints.
 */
class HostDebuggerRemoteClient(
    private val port: Int,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val observationTimeoutMillis: Int = DEFAULT_OBSERVATION_TIMEOUT_MILLIS,
) : Closeable {
    private val requestLock = Any()
    private val writeLock = Any()

    @Volatile
    private var noAckMode = false

    @Volatile
    private var socket: Socket? = null

    private var input: InputStream? = null
    private var output: OutputStream? = null

    val connected: Boolean
        get() = socket?.let { candidate -> candidate.isConnected && !candidate.isClosed } == true

    fun connect(): GdbRemoteHandshake {
        require(port in MIN_PORT..MAX_PORT) { "Debugger port must be $MIN_PORT..$MAX_PORT" }
        check(!connected) { "GDB remote client is already connected" }

        val created = Socket()
        created.tcpNoDelay = true
        created.connect(
            ipv4LoopbackEndpoint(port),
            connectTimeoutMillis,
        )
        created.soTimeout = observationTimeoutMillis
        socket = created
        input = created.getInputStream()
        output = created.getOutputStream()

        return try {
            val supported = request(
                "qSupported:multiprocess+;QStartNoAckMode+;vContSupported+",
            )
            val capabilities = supported
                .split(';')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()

            if (capabilities.any { capability -> capability == "QStartNoAckMode+" }) {
                val result = request("QStartNoAckMode")
                require(result == "OK") { "lldb-server rejected no-ack mode: $result" }
                noAckMode = true
            }

            val stopReply = request("?")
            GdbRemoteHandshake(
                capabilities = capabilities,
                stopReply = stopReply,
            )
        } catch (exception: Exception) {
            close()
            throw exception
        }
    }

    fun queryStopReason(): String = request("?")

    fun queryRegisters(maxCount: Int = DEFAULT_REGISTER_LIMIT): List<GdbRemoteRegisterInfo> {
        require(maxCount in 1..MAX_REGISTER_LIMIT) {
            "Register metadata limit must be 1..$MAX_REGISTER_LIMIT"
        }
        val registers = mutableListOf<GdbRemoteRegisterInfo>()
        for (index in 0 until maxCount) {
            val response = request("qRegisterInfo${index.toString(16)}")
            if (response.isBlank() || response.startsWith('E')) break
            registers += GdbRemoteRegisterInfoParser.parse(index, response)
        }
        return registers
    }

    fun readRegister(index: Int): GdbRemoteRegisterValue {
        require(index in 0 until MAX_REGISTER_LIMIT) { "Register index out of range" }
        val response = request("p${index.toString(16)}")
        require(!response.startsWith('E')) { "Register read failed: $response" }
        require(response.length % 2 == 0 && response.all(::isHexDigit)) {
            "Unexpected register payload"
        }
        return GdbRemoteRegisterValue(index = index, rawHex = response.lowercase())
    }

    fun readMemory(address: Long, length: Int): GdbRemoteMemoryRead {
        require(address >= 0L) { "Memory address must be non-negative" }
        require(length in 1..MAX_MEMORY_READ_BYTES) {
            "Memory read length must be 1..$MAX_MEMORY_READ_BYTES bytes"
        }
        val response = request("m${address.toString(16)},${length.toString(16)}")
        require(!response.startsWith('E')) { "Memory read failed: $response" }
        val bytes = GdbRemotePacketCodec.decodeHex(response)
        require(bytes.size == length) {
            "Memory read length mismatch: expected=$length actual=${bytes.size}"
        }
        return GdbRemoteMemoryRead(
            address = address,
            bytes = bytes,
        )
    }

    /**
     * Resume the target and wait until lldb-server reports the next stop.
     * Run this from an IO coroutine. [interrupt] may be called from another coroutine while this waits.
     */
    fun continueUntilStop(): String = requestWithTimeout("vCont;c", timeoutMillis = 0)

    /** Single-instruction step. The target should immediately produce another stop reply. */
    fun step(): String = requestWithTimeout(
        payload = "vCont;s",
        timeoutMillis = CONTROL_COMMAND_TIMEOUT_MILLIS,
    )

    /**
     * Send the gdb-remote asynchronous interrupt byte. This is not a target PID signal and does not
     * use Android kill(2); lldb-server translates the protocol interrupt into debugger control.
     */
    fun interrupt() {
        requireConnected()
        synchronized(writeLock) {
            requireNotNull(output).apply {
                write(INTERRUPT_BYTE)
                flush()
            }
        }
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        noAckMode = false
    }

    private fun request(payload: String): String = requestWithTimeout(
        payload = payload,
        timeoutMillis = observationTimeoutMillis,
    )

    private fun requestWithTimeout(payload: String, timeoutMillis: Int): String = synchronized(requestLock) {
        require(payload.isNotBlank()) { "GDB remote payload must not be blank" }
        require(payload.length <= MAX_PACKET_PAYLOAD_CHARS) { "GDB remote payload too large" }
        requireConnected()
        val activeSocket = requireNotNull(socket)
        val previousTimeout = activeSocket.soTimeout
        activeSocket.soTimeout = timeoutMillis
        try {
            sendPacket(payload)
            while (true) {
                val response = readPacket()
                // Remote console output is encoded as O<hex bytes> and may precede a stop reply.
                if (isConsoleOutputPacket(response)) continue
                return@synchronized response
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            if (!activeSocket.isClosed) activeSocket.soTimeout = previousTimeout
        }
    }

    private fun sendPacket(payload: String) {
        val frame = GdbRemotePacketCodec.frame(payload)
        synchronized(writeLock) {
            val stream = requireNotNull(output)
            repeat(MAX_ACK_RETRIES) { attempt ->
                stream.write(frame)
                stream.flush()
                if (noAckMode) return
                when (readRawByte()) {
                    ACK_BYTE -> return
                    NACK_BYTE -> if (attempt == MAX_ACK_RETRIES - 1) {
                        error("GDB remote peer repeatedly rejected packet")
                    }
                    else -> error("Unexpected GDB remote acknowledgement")
                }
            }
        }
    }

    private fun readPacket(): String {
        val stream = requireNotNull(input)
        var value: Int
        do {
            value = stream.read()
            if (value < 0) throw EOFException("GDB remote peer closed the connection")
        } while (value != PACKET_START_BYTE)

        val payload = ByteArrayOutputCollector(MAX_PACKET_RESPONSE_BYTES)
        while (true) {
            val next = stream.read()
            if (next < 0) throw EOFException("GDB remote peer closed the connection")
            if (next == CHECKSUM_SEPARATOR_BYTE) break
            payload.add(next)
        }
        val checksumHigh = stream.read()
        val checksumLow = stream.read()
        if (checksumHigh < 0 || checksumLow < 0) {
            throw EOFException("Incomplete GDB remote checksum")
        }
        val expected = hexPairToInt(checksumHigh, checksumLow)
        val bytes = payload.toByteArray()
        val actual = GdbRemotePacketCodec.checksum(bytes)
        if (actual != expected) {
            if (!noAckMode) sendRawByte(NACK_BYTE)
            error("GDB remote checksum mismatch")
        }
        if (!noAckMode) sendRawByte(ACK_BYTE)
        return bytes.toString(StandardCharsets.US_ASCII)
    }

    private fun readRawByte(): Int {
        val value = requireNotNull(input).read()
        if (value < 0) throw EOFException("GDB remote peer closed the connection")
        return value
    }

    private fun sendRawByte(value: Int) {
        synchronized(writeLock) {
            requireNotNull(output).apply {
                write(value)
                flush()
            }
        }
    }

    private fun requireConnected() {
        check(connected) { "GDB remote client is not connected" }
    }

    private fun isConsoleOutputPacket(payload: String): Boolean =
        payload.length > 1 &&
            payload.first() == 'O' &&
            payload.drop(1).length % 2 == 0 &&
            payload.drop(1).all(::isHexDigit)

    private fun hexPairToInt(high: Int, low: Int): Int =
        (hexNibble(high) shl 4) or hexNibble(low)

    private fun hexNibble(value: Int): Int = when (value.toChar()) {
        in '0'..'9' -> value - '0'.code
        in 'a'..'f' -> value - 'a'.code + 10
        in 'A'..'F' -> value - 'A'.code + 10
        else -> error("Invalid hexadecimal checksum")
    }

    private class ByteArrayOutputCollector(private val limit: Int) {
        private val bytes = ArrayList<Byte>()

        fun add(value: Int) {
            require(bytes.size < limit) { "GDB remote response exceeded $limit bytes" }
            bytes += value.toByte()
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { index -> bytes[index] }
    }

    companion object {
        const val MAX_MEMORY_READ_BYTES = 4096
        const val MAX_REGISTER_LIMIT = 512
        const val DEFAULT_REGISTER_LIMIT = 128

        /**
         * Build the endpoint from raw IPv4 bytes so Android cannot resolve a loopback hostname to
         * ::1 while lldb-server is intentionally bound to 127.0.0.1 only.
         */
        internal fun ipv4LoopbackEndpoint(port: Int): InetSocketAddress {
            require(port in MIN_PORT..MAX_PORT) { "Debugger port must be $MIN_PORT..$MAX_PORT" }
            val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            return InetSocketAddress(address, port)
        }

        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000
        private const val DEFAULT_OBSERVATION_TIMEOUT_MILLIS = 5_000
        private const val CONTROL_COMMAND_TIMEOUT_MILLIS = 15_000
        private const val MAX_PACKET_PAYLOAD_CHARS = 8_192
        private const val MAX_PACKET_RESPONSE_BYTES = 1_048_576
        private const val MAX_ACK_RETRIES = 3
        private const val PACKET_START_BYTE = '$'.code
        private const val CHECKSUM_SEPARATOR_BYTE = '#'.code
        private const val ACK_BYTE = '+'.code
        private const val NACK_BYTE = '-'.code
        private const val INTERRUPT_BYTE = 0x03
    }
}

data class GdbRemoteHandshake(
    val capabilities: Set<String>,
    val stopReply: String,
)

data class GdbRemoteRegisterInfo(
    val index: Int,
    val name: String,
    val bitSize: Int?,
    val byteOffset: Int?,
    val encoding: String?,
    val format: String?,
    val registerSet: String?,
    val genericName: String?,
)

data class GdbRemoteRegisterValue(
    val index: Int,
    val rawHex: String,
)

data class GdbRemoteMemoryRead(
    val address: Long,
    val bytes: ByteArray,
) {
    val hex: String
        get() = bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

object GdbRemoteRegisterInfoParser {
    fun parse(index: Int, payload: String): GdbRemoteRegisterInfo {
        require(index >= 0) { "Register index must be non-negative" }
        val values = payload
            .split(';')
            .mapNotNull { field ->
                val separator = field.indexOf(':')
                if (separator <= 0) null else {
                    field.substring(0, separator) to field.substring(separator + 1)
                }
            }
            .toMap()
        val name = values["name"]?.takeIf(String::isNotBlank)
            ?: error("Register metadata is missing name")
        return GdbRemoteRegisterInfo(
            index = index,
            name = name,
            bitSize = values["bitsize"]?.toIntOrNull(),
            byteOffset = values["offset"]?.toIntOrNull(),
            encoding = values["encoding"],
            format = values["format"],
            registerSet = values["set"],
            genericName = values["generic"],
        )
    }
}

object GdbRemotePacketCodec {
    fun frame(payload: String): ByteArray {
        require(payload.all { character -> character.code in 0x20..0x7e }) {
            "GDB remote payload must be printable ASCII"
        }
        val payloadBytes = payload.toByteArray(StandardCharsets.US_ASCII)
        val checksum = checksum(payloadBytes)
        val framed = "\$$payload#%02x".format(checksum)
        return framed.toByteArray(StandardCharsets.US_ASCII)
    }

    fun checksum(payload: ByteArray): Int = payload.fold(0) { sum, byte ->
        (sum + (byte.toInt() and 0xff)) and 0xff
    }

    fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex value must contain complete bytes" }
        require(value.all(::isHexDigit)) { "Hex value contains an invalid character" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

private fun isHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
