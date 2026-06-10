package dev.lerist.fakelocation.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.lerist.fakelocation.app.appGraph

class MockCoordinatorService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOTSTRAP -> appGraph.runtimeController.bootstrap()
            ACTION_ACTIVATE_APP_HOOK -> appGraph.runtimeController.activateAppHookStage()
            ACTION_START_SESSION -> appGraph.runtimeController.startSession()
            ACTION_STOP_SESSION -> appGraph.runtimeController.stopSession()
            ACTION_UPDATE_DEMO_LOCATION -> appGraph.runtimeController.updateDemoLocation()
            ACTION_UPDATE_DEMO_WIFI -> appGraph.runtimeController.updateDemoWifi()
            ACTION_RUN_PREFLIGHT_CHECKS -> appGraph.runtimeController.runPreflightChecks()
            ACTION_PROBE_ROOT -> appGraph.runtimeController.probeRootShell()
            ACTION_SYNC_RUNTIME_MIRROR -> appGraph.runtimeController.syncRuntimeMirror()
            ACTION_EXECUTE_DRY_RUN_TASKS -> appGraph.runtimeController.executeDryRunInjectionTasks()
            ACTION_EXECUTE_ROOT_TASKS -> appGraph.runtimeController.executeRootInjectionTasks()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_BOOTSTRAP = "dev.lerist.fakelocation.action.BOOTSTRAP"
        const val ACTION_ACTIVATE_APP_HOOK = "dev.lerist.fakelocation.action.ACTIVATE_APP_HOOK"
        const val ACTION_START_SESSION = "dev.lerist.fakelocation.action.START_SESSION"
        const val ACTION_STOP_SESSION = "dev.lerist.fakelocation.action.STOP_SESSION"
        const val ACTION_UPDATE_DEMO_LOCATION = "dev.lerist.fakelocation.action.UPDATE_DEMO_LOCATION"
        const val ACTION_UPDATE_DEMO_WIFI = "dev.lerist.fakelocation.action.UPDATE_DEMO_WIFI"
        const val ACTION_RUN_PREFLIGHT_CHECKS =
            "dev.lerist.fakelocation.action.RUN_PREFLIGHT_CHECKS"
        const val ACTION_PROBE_ROOT = "dev.lerist.fakelocation.action.PROBE_ROOT"
        const val ACTION_SYNC_RUNTIME_MIRROR = "dev.lerist.fakelocation.action.SYNC_RUNTIME_MIRROR"
        const val ACTION_EXECUTE_DRY_RUN_TASKS =
            "dev.lerist.fakelocation.action.EXECUTE_DRY_RUN_TASKS"
        const val ACTION_EXECUTE_ROOT_TASKS =
            "dev.lerist.fakelocation.action.EXECUTE_ROOT_TASKS"
    }
}
