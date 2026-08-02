package com.luckylca.autocrack.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.luckylca.autocrack.MainActivity

class PtySessionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoCrackApp 终端会话",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持 Debian PTY、调试器和长时间逆向任务运行"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: "unknown"
        val pid = intent?.getIntExtra(EXTRA_PID, -1) ?: -1
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("AutoCrackApp Debian 终端运行中")
            .setContentText("会话 $sessionId · PID $pid")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "autocrack_pty_session"
        private const val NOTIFICATION_ID = 5601
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_PID = "pid"

        fun start(context: Context, sessionId: String, pid: Int) {
            val intent = Intent(context, PtySessionForegroundService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_PID, pid)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PtySessionForegroundService::class.java))
        }
    }
}
