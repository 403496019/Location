package dev.lerist.fakelocation.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.lerist.fakelocation.app.appGraph

class MockServiceProviderService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REGISTER_LOCAL_SERVICES -> appGraph.runtimeController.bootstrap()
            ACTION_REFRESH_SNAPSHOT -> appGraph.runtimeController.snapshot()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REGISTER_LOCAL_SERVICES =
            "dev.lerist.fakelocation.action.REGISTER_LOCAL_SERVICES"
        const val ACTION_REFRESH_SNAPSHOT = "dev.lerist.fakelocation.action.REFRESH_SNAPSHOT"
    }
}
