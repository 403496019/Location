package dev.lerist.fakelocation.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.lerist.fakelocation.app.appGraph
import dev.lerist.fakelocation.app.R

class ForegroundControlService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                appGraph.runtimeController.bootstrap()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FakeLocation Runtime",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val snapshot = appGraph.runtimeController.snapshot()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                "Phase1 active: runtime=${snapshot.runtimePrepared}, session=${snapshot.sessionRunning}",
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "flrt_phase1"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "dev.lerist.fakelocation.action.START_FOREGROUND"
        const val ACTION_STOP = "dev.lerist.fakelocation.action.STOP_FOREGROUND"
    }
}
