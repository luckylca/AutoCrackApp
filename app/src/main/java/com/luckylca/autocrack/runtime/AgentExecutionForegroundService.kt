package com.luckylca.autocrack.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.luckylca.autocrack.MainActivity
import java.util.UUID

internal data class AgentExecutionLeaseSnapshot(
    val count: Int,
    val latestPackageName: String?,
)

internal class AgentExecutionLeaseState {
    private val activeLeases = linkedMapOf<String, String>()

    @Synchronized
    fun acquire(leaseId: String, packageName: String): AgentExecutionLeaseSnapshot {
        require(leaseId.isNotBlank()) { "Agent execution lease ID must not be blank" }
        activeLeases[leaseId] = packageName.ifBlank { "unknown" }
        return snapshotLocked()
    }

    @Synchronized
    fun release(leaseId: String): AgentExecutionLeaseSnapshot {
        if (leaseId.isNotBlank()) activeLeases.remove(leaseId)
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): AgentExecutionLeaseSnapshot = snapshotLocked()

    private fun snapshotLocked(): AgentExecutionLeaseSnapshot = AgentExecutionLeaseSnapshot(
        count = activeLeases.size,
        latestPackageName = activeLeases.values.lastOrNull(),
    )
}

/**
 * Keeps bounded Agent/model tool execution alive while AutoCrackApp is backgrounded by a target launch.
 *
 * A lease is independent from the PTY foreground service. Multiple overlapping Agent executions can hold
 * leases simultaneously; the service stops only after the final lease is released.
 */
class AgentExecutionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoCrackApp Agent 任务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持长时间 Agent、rootfs、Frida 与调试工具任务运行"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val leaseId = intent?.getStringExtra(EXTRA_LEASE_ID)?.takeIf(String::isNotBlank)
        if (intent?.action != ACTION_ACQUIRE || leaseId == null) {
            // Every startForegroundService() request must transition promptly even if a stale start
            // intent arrives after its in-process lease was already released.
            startForegroundWithDeclaredType(buildNotification(null, 1))
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }

        val snapshot = LEASE_STATE.snapshot()
        if (snapshot.count == 0) {
            startForegroundWithDeclaredType(buildNotification(null, 1))
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }
        startForegroundWithDeclaredType(
            buildNotification(snapshot.latestPackageName, snapshot.count),
        )
        return START_NOT_STICKY
    }

    private fun buildNotification(packageName: String?, leaseCount: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val targetText = packageName?.takeIf { it != "unknown" }?.let { "目标 $it · " }.orEmpty()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("AutoCrackApp Agent 运行中")
            .setContentText("${targetText}活跃任务 $leaseCount")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundWithDeclaredType(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun stopForegroundAndSelf(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "autocrack_agent_execution"
        private const val NOTIFICATION_ID = 5602
        private const val ACTION_ACQUIRE = "com.luckylca.autocrack.action.AGENT_EXECUTION_ACQUIRE"
        private const val EXTRA_LEASE_ID = "lease_id"
        private val LEASE_STATE = AgentExecutionLeaseState()

        fun acquire(context: Context, packageName: String): String {
            val leaseId = UUID.randomUUID().toString()
            LEASE_STATE.acquire(leaseId, packageName)
            val intent = Intent(context, AgentExecutionForegroundService::class.java)
                .setAction(ACTION_ACQUIRE)
                .putExtra(EXTRA_LEASE_ID, leaseId)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                LEASE_STATE.release(leaseId)
                throw error
            }
            return leaseId
        }

        fun release(context: Context, leaseId: String) {
            if (leaseId.isBlank()) return
            val snapshot = LEASE_STATE.release(leaseId)
            if (snapshot.count == 0) {
                // release() may run while the selected target is foreground. stopService() is safe
                // from that background state and avoids a second Android 12+ FGS-start decision.
                context.stopService(Intent(context, AgentExecutionForegroundService::class.java))
            }
        }
    }
}
