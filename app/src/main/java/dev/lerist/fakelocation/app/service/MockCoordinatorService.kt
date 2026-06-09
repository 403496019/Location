package dev.lerist.fakelocation.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MockCoordinatorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
