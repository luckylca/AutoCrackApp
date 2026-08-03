package com.luckylca.autocrack.runtime

internal enum class ChrootExecutionKind {
    ONE_SHOT,
    PERSISTENT_PTY,
}

internal object ChrootExecutionGate {
    private var activeKind: ChrootExecutionKind? = null

    @Synchronized
    fun acquire(kind: ChrootExecutionKind) {
        check(activeKind == null) {
            when (activeKind) {
                ChrootExecutionKind.PERSISTENT_PTY ->
                    "持久 PTY 正在运行；请先关闭 PTY，再执行一次性 Debian 命令或工具包自检"

                ChrootExecutionKind.ONE_SHOT ->
                    "一次性 Debian 命令正在运行；请等待命令完成后再启动 PTY"

                null -> "chroot 执行门状态异常"
            }
        }
        activeKind = kind
    }

    @Synchronized
    fun requireOwner(kind: ChrootExecutionKind) {
        check(activeKind == kind) {
            "chroot 执行门所有者不匹配：expected=$kind, actual=$activeKind"
        }
    }

    @Synchronized
    fun requireIdle() {
        check(activeKind == null) {
            when (activeKind) {
                ChrootExecutionKind.PERSISTENT_PTY ->
                    "持久 PTY 正在运行，禁止清理其 chroot 挂载"

                ChrootExecutionKind.ONE_SHOT ->
                    "一次性 Debian 命令正在运行，禁止并发清理 chroot 挂载"

                null -> "chroot 执行门状态异常"
            }
        }
    }

    @Synchronized
    fun release(kind: ChrootExecutionKind) {
        requireOwner(kind)
        activeKind = null
    }

    @Synchronized
    fun current(): ChrootExecutionKind? = activeKind

    @Synchronized
    internal fun resetForTest() {
        activeKind = null
    }
}
