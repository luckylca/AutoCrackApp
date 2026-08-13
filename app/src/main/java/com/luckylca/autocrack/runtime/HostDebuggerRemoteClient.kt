package com.luckylca.autocrack.runtime

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.math.min

/** A continue-class request was sent, but its final stop/exit reply did not arrive in time. */
class GdbRemoteRunTimeoutException(message: String) : IOException(message)

/**
 * Minimal client for the loopback gdb-remote endpoint exposed by the trusted Android lldb-server.
 *
 * Phase 5.14 deliberately implements only a fixed typed attach operation plus observation and
 * execution-control commands. There is intentionally no arbitrary/raw packet adapter and no
 * register-write, memory-write or breakpoint adapter.
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
    private var lastStopReply: String? = null

    val connected: Boolean
        get() = socket?.let { candidate -> candidate.isConnected && !candidate.isClosed } == true

    /**
     * Connect to a targetless lldb-server and perform the same important transport ordering used
     * by LLDB itself: initial ACK, no-ack negotiation, then capability discovery.
     */
    fun connect(): GdbRemoteHandshake {
        require(port in MIN_PORT..MAX_PORT) { "Debugger port must be $MIN_PORT..$MAX_PORT" }
        check(!connected) { "GDB remote client is already connected" }

        val created = Socket()
        created.tcpNoDelay = true
        created.connect(ipv4LoopbackEndpoint(port), connectTimeoutMillis)
        created.soTimeout = observationTimeoutMillis
        socket = created
        input = created.getInputStream()
        output = created.getOutputStream()

        return try {
            sendRawByte(ACK_BYTE)
            val noAckReply = requestWithTimeout(
                payload = "QStartNoAckMode",
                timeoutMillis = HANDSHAKE_TIMEOUT_MILLIS,
                operationName = "QStartNoAckMode",
            )
            if (noAckReply == "OK") noAckMode = true

            val supported = request("qSupported:multiprocess+;QStartNoAckMode+;vContSupported+")
            val capabilities = supported
                .split(';')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()

            GdbRemoteHandshake(
                capabilities = capabilities,
                noAckModeEnabled = noAckMode,
            )
        } catch (exception: Exception) {
            close()
            throw exception
        }
    }

    fun attach(pid: Int): String {
        require(pid > 0) { "Attach PID must be positive" }

        val detachOnErrorReply = requestWithTimeout(
            payload = "QSetDetachOnError:1",
            timeoutMillis = ATTACH_PREPARE_TIMEOUT_MILLIS,
            operationName = "QSetDetachOnError",
        )
        require(detachOnErrorReply == "OK" || detachOnErrorReply.isBlank()) {
            "lldb-server rejected QSetDetachOnError: $detachOnErrorReply"
        }

        val response = requestRunUntilStop(
            payload = "vAttach;${pid.toString(16)}",
            totalTimeoutMillis = ATTACH_WAIT_TIMEOUT_MILLIS,
            operationName = "vAttach",
        )
        require(!response.startsWith('E')) { "LLDB attach failed: $response" }
        require(response.startsWith('T') || response.startsWith('S')) {
            "Unexpected LLDB attach response: $response"
        }
        rememberStopReply(response)
        return response
    }

    fun queryStopReason(): String {
        val response = request("?")
        GdbRemoteRunReplyValidator.requireStopOrExit("stop reason", response)
        rememberStopReply(response)
        return response
    }

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
        return GdbRemoteMemoryRead(address = address, bytes = bytes)
    }

    fun continueUntilStop(): String {
        val response = requestWithTimeout("vCont;c", timeoutMillis = 0)
        GdbRemoteRunReplyValidator.requireStopOrExit("continue", response)
        rememberStopReply(response)
        return response
    }

    /** Single-step only the thread identified by the most recent LLDB stop reply. */
    fun step(): String {
        val stopReply = requireNotNull(lastStopReply) {
            "Cannot single-step before LLDB has reported a stopped thread"
        }
        val payload = GdbRemoteExecutionPacketFactory.stepFromStopReply(stopReply)
        val response = requestRunUntilStop(
            payload = payload,
            totalTimeoutMillis = STEP_WAIT_TIMEOUT_MILLIS,
            operationName = payload,
        )
        GdbRemoteRunReplyValidator.requireStopOrExit(payload, response)
        rememberStopReply(response)
        return response
    }

    /** Send only the fixed gdb-remote interrupt byte; this is not a raw-packet adapter. */
    fun interrupt() {
        requireConnected()
        synchronized(writeLock) {
            requireNotNull(output).apply {
                write(INTERRUPT_BYTE)
                flush()
            }
        }
    }

    /**
     * Recover a continue-class command whose bounded reader already timed out.
     *
     * The interrupt byte is sent separately by [interrupt]. This method sends no packet at all; it
     * only consumes the next validated stop/exit reply so a late step can be brought back to a
     * known stopped state without issuing another step/continue request.
     */
    fun awaitStopAfterInterrupt(): String = synchronized(requestLock) {
        requireConnected()
        val activeSocket = requireNotNull(socket)
        val previousTimeout = activeSocket.soTimeout
        activeSocket.soTimeout = min(RUN_REPLY_POLL_TIMEOUT_MILLIS, INTERRUPT_RECOVERY_WAIT_TIMEOUT_MILLIS)
        val deadlineNanos =
            System.nanoTime() + INTERRUPT_RECOVERY_WAIT_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        try {
            while (true) {
                val response = readPacketWithDeadline(
                    activeSocket = activeSocket,
                    deadlineNanos = deadlineNanos,
                    operationName = "interrupt recovery",
                    totalTimeoutMillis = INTERRUPT_RECOVERY_WAIT_TIMEOUT_MILLIS,
                )
                if (isConsoleOutputPacket(response)) continue
                GdbRemoteRunReplyValidator.requireStopOrExit("interrupt recovery", response)
                rememberStopReply(response)
                return@synchronized response
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            if (!activeSocket.isClosed) activeSocket.soTimeout = previousTimeout
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
        lastStopReply = null
    }

    private fun rememberStopReply(response: String) {
        if (GdbRemoteRunReplyValidator.isStopOrExit(response)) lastStopReply = response
    }

    private fun request(payload: String): String = requestWithTimeout(
        payload = payload,
        timeoutMillis = observationTimeoutMillis,
        operationName = payload.substringBefore(':').substringBefore(';'),
    )

    private fun requestWithTimeout(
        payload: String,
        timeoutMillis: Int,
        operationName: String = payload,
    ): String = synchronized(requestLock) {
        require(payload.isNotBlank()) { "GDB remote payload must not be blank" }
        require(payload.length <= MAX_PACKET_PAYLOAD_CHARS) { "GDB remote payload too large" }
        requireConnected()
        val activeSocket = requireNotNull(socket)
        val previousTimeout = activeSocket.soTimeout
        activeSocket.soTimeout = timeoutMillis
        try {
            sendPacket(payload)
            while (true) {
                val response = try {
                    readPacket()
                } catch (exception: SocketTimeoutException) {
                    throw IOException(
                        "GDB remote $operationName read timed out after ${timeoutMillis}ms",
                        exception,
                    )
                }
                if (isConsoleOutputPacket(response)) continue
                return@synchronized response
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            if (!activeSocket.isClosed) activeSocket.soTimeout = previousTimeout
        }
    }

    private fun requestRunUntilStop(
        payload: String,
        totalTimeoutMillis: Int,
        operationName: String,
    ): String = synchronized(requestLock) {
        require(payload.isNotBlank()) { "GDB remote payload must not be blank" }
        require(totalTimeoutMillis > 0) { "Run packet timeout must be positive" }
        requireConnected()
        val activeSocket = requireNotNull(socket)
        val previousTimeout = activeSocket.soTimeout
        activeSocket.soTimeout = min(RUN_REPLY_POLL_TIMEOUT_MILLIS, totalTimeoutMillis)
        val deadlineNanos = System.nanoTime() + totalTimeoutMillis * NANOS_PER_MILLISECOND
        try {
            sendPacket(payload)
            while (true) {
                val response = readPacketWithDeadline(
                    activeSocket = activeSocket,
                    deadlineNanos = deadlineNanos,
                    operationName = operationName,
                    totalTimeoutMillis = totalTimeoutMillis,
                )
                if (isConsoleOutputPacket(response)) continue
                return@synchronized response
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            if (!activeSocket.isClosed) activeSocket.soTimeout = previousTimeout
        }
    }

    private fun readPacketWithDeadline(
        activeSocket: Socket,
        deadlineNanos: Long,
        operationName: String,
        totalTimeoutMillis: Int,
    ): String {
        var value: Int
        do {
            value = readByteWithDeadline(
                activeSocket,
                deadlineNanos,
                operationName,
                totalTimeoutMillis,
            )
            if (value < 0) throw EOFException("GDB remote peer closed the connection")
        } while (value != PACKET_START_BYTE)

        val payload = ByteArrayOutputCollector(MAX_PACKET_RESPONSE_BYTES)
        while (true) {
            val next = readByteWithDeadline(
                activeSocket,
                deadlineNanos,
                operationName,
                totalTimeoutMillis,
            )
            if (next < 0) throw EOFException("GDB remote peer closed the connection")
            if (next == CHECKSUM_SEPARATOR_BYTE) break
            payload.add(next)
        }
        val checksumHigh = readByteWithDeadline(
            activeSocket,
            deadlineNanos,
            operationName,
            totalTimeoutMillis,
        )
        val checksumLow = readByteWithDeadline(
            activeSocket,
            deadlineNanos,
            operationName,
            totalTimeoutMillis,
        )
        if (checksumHigh < 0 || checksumLow < 0) throw EOFException("Incomplete GDB remote checksum")
        return validatePacket(payload.toByteArray(), checksumHigh, checksumLow)
    }

    private fun readByteWithDeadline(
        activeSocket: Socket,
        deadlineNanos: Long,
        operationName: String,
        totalTimeoutMillis: Int,
    ): Int {
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                throw GdbRemoteRunTimeoutException(
                    "GDB remote $operationName timed out after ${totalTimeoutMillis}ms waiting for stop reply",
                )
            }
            val remainingMillis = (remainingNanos / NANOS_PER_MILLISECOND)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            activeSocket.soTimeout = min(RUN_REPLY_POLL_TIMEOUT_MILLIS, remainingMillis)
            try {
                return requireNotNull(input).read()
            } catch (_: SocketTimeoutException) {
                // Wake up periodically but preserve parser state until the total deadline.
            }
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
        if (checksumHigh < 0 || checksumLow < 0) throw EOFException("Incomplete GDB remote checksum")
        return validatePacket(payload.toByteArray(), checksumHigh, checksumLow)
    }

    private fun validatePacket(bytes: ByteArray, checksumHigh: Int, checksumLow: Int): String {
        val expected = hexPairToInt(checksumHigh, checksumLow)
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
        payload.length > 1 && payload.first() == 'O' &&
            payload.drop(1).length % 2 == 0 && payload.drop(1).all(::isHexDigit)

    private fun hexPairToInt(high: Int, low: Int): Int = (hexNibble(high) shl 4) or hexNibble(low)

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
        internal const val ATTACH_WAIT_TIMEOUT_MILLIS = 90_000
        internal const val RUN_REPLY_POLL_TIMEOUT_MILLIS = 5_000
        internal const val INTERRUPT_RECOVERY_WAIT_TIMEOUT_MILLIS = 30_000

        internal fun ipv4LoopbackEndpoint(port: Int): InetSocketAddress {
            require(port in MIN_PORT..MAX_PORT) { "Debugger port must be $MIN_PORT..$MAX_PORT" }
            val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            return InetSocketAddress(address, port)
        }

        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000
        private const val DEFAULT_OBSERVATION_TIMEOUT_MILLIS = 5_000
        private const val HANDSHAKE_TIMEOUT_MILLIS = 6_000
        private const val ATTACH_PREPARE_TIMEOUT_MILLIS = 10_000
        private const val STEP_WAIT_TIMEOUT_MILLIS = 30_000
        private const val MAX_PACKET_PAYLOAD_CHARS = 8_192
        private const val MAX_PACKET_RESPONSE_BYTES = 1_048_576
        private const val MAX_ACK_RETRIES = 3
        private const val PACKET_START_BYTE = '$'.code
        private const val CHECKSUM_SEPARATOR_BYTE = '#'.code
        private const val ACK_BYTE = '+'.code
        private const val NACK_BYTE = '-'.code
        private const val INTERRUPT_BYTE = 0x03
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

data class GdbRemoteHandshake(
    val capabilities: Set<String>,
    val noAckModeEnabled: Boolean = false,
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

data class GdbRemoteRegisterValue(val index: Int, val rawHex: String)

data class GdbRemoteMemoryRead(val address: Long, val bytes: ByteArray) {
    val hex: String
        get() = bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Validate only protocol-defined continue-class stop/exit replies. */
object GdbRemoteRunReplyValidator {
    fun isStopOrExit(response: String): Boolean =
        response.firstOrNull() == 'T' || response.firstOrNull() == 'S' ||
            response.firstOrNull() == 'W' || response.firstOrNull() == 'X'

    fun requireStopOrExit(operationName: String, response: String): String {
        require(!response.startsWith('E')) { "LLDB $operationName failed: $response" }
        require(isStopOrExit(response)) { "Unexpected LLDB $operationName response: $response" }
        return response
    }
}

/** Parse only the stopped-thread identity reported by LLDB itself. */
object GdbRemoteStopReplyParser {
    fun threadId(stopReply: String): String? {
        if (stopReply.length < 3 || !stopReply.startsWith('T')) return null
        if (!stopReply.substring(1, 3).all(::isHexDigit)) return null

        val fields = stopReply.substring(3).split(';')
        val value = fields
            .firstOrNull { field -> field.startsWith("thread:") }
            ?.substringAfter(':')
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (!THREAD_ID_PATTERN.matches(value)) return null

        val tidPart = value.substringAfterLast('.')
        val tid = tidPart.toULongOrNull(16) ?: return null
        if (tid == 0uL) return null
        return value
    }

    fun requireThreadId(stopReply: String): String = requireNotNull(threadId(stopReply)) {
        "LLDB stop reply does not contain a valid positive stopped thread id"
    }

    private val THREAD_ID_PATTERN = Regex("^(?:p[0-9a-f]+\\.)?[0-9a-f]+$")
}

/** Fixed execution-control packet shapes; no caller can provide an arbitrary packet string. */
object GdbRemoteExecutionPacketFactory {
    fun stepFromStopReply(stopReply: String): String =
        "vCont;s:${GdbRemoteStopReplyParser.requireThreadId(stopReply)}"
}

object GdbRemoteRegisterInfoParser {
    fun parse(index: Int, payload: String): GdbRemoteRegisterInfo {
        require(index >= 0) { "Register index must be non-negative" }
        val values = payload.split(';').mapNotNull { field ->
            val separator = field.indexOf(':')
            if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
        }.toMap()
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
        return "\$$payload#%02x".format(checksum).toByteArray(StandardCharsets.US_ASCII)
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
