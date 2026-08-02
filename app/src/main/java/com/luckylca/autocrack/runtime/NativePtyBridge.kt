package com.luckylca.autocrack.runtime

internal object NativePtyBridge {
    init {
        System.loadLibrary("autocrack_pty")
    }

    external fun nativeOpen(
        program: String,
        arguments: Array<String>,
        rows: Int,
        columns: Int,
    ): Long

    external fun nativeRead(
        handle: Long,
        maxBytes: Int,
        timeoutMillis: Int,
    ): ByteArray?

    external fun nativeWrite(handle: Long, data: ByteArray): Int

    external fun nativeResize(handle: Long, rows: Int, columns: Int): Boolean

    external fun nativeSignal(handle: Long, signalNumber: Int): Boolean

    external fun nativeWait(handle: Long, timeoutMillis: Int): Int

    external fun nativeClose(handle: Long, signalNumber: Int): Int

    external fun nativePid(handle: Long): Int

    external fun nativeIsAlive(handle: Long): Boolean

    const val STILL_RUNNING: Int = Int.MIN_VALUE
}
