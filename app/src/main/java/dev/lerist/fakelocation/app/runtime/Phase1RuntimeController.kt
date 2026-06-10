package dev.lerist.fakelocation.app.runtime

import android.content.Context
import dev.lerist.fakelocation.core.hookbridge.CompatHookBridge
import dev.lerist.fakelocation.core.hookbridge.HookInstallResult
import dev.lerist.fakelocation.core.hookbridge.NativeHookBridge
import dev.lerist.fakelocation.core.ipc.InMemoryMockLocationManager
import dev.lerist.fakelocation.core.ipc.InMemoryMockServiceRegistry
import dev.lerist.fakelocation.core.ipc.InMemoryMockStateStore
import dev.lerist.fakelocation.core.ipc.InMemoryMockWifiManager
import dev.lerist.fakelocation.core.ipc.InMemoryNativeCatchManager
import dev.lerist.fakelocation.core.model.MockLocation
import dev.lerist.fakelocation.core.model.MockSessionState
import dev.lerist.fakelocation.core.model.MockWifiProfile
import dev.lerist.fakelocation.core.runtime.HiddenApiController
import dev.lerist.fakelocation.core.runtime.RuntimeIntegrityReport
import dev.lerist.fakelocation.core.runtime.RuntimeAssetManager
import dev.lerist.fakelocation.core.runtime.RuntimePreparationReport
import dev.lerist.fakelocation.core.runtime.StagedRuntimeArtifact
import dev.lerist.fakelocation.injector.CommandExecutionReport
import dev.lerist.fakelocation.injector.DefaultInjectionOrchestrator
import dev.lerist.fakelocation.injector.InjectionPreflightReport
import dev.lerist.fakelocation.injector.InjectionPlan
import dev.lerist.fakelocation.injector.InjectionTask
import dev.lerist.fakelocation.injector.InjectionTaskExecution
import dev.lerist.fakelocation.injector.GeneratedRootScript
import dev.lerist.fakelocation.injector.InjectorEnvironment
import dev.lerist.fakelocation.injector.RootScriptBundle
import dev.lerist.fakelocation.injector.RootScriptMode
import dev.lerist.fakelocation.injector.RuntimeMirrorSyncResult
import dev.lerist.fakelocation.payload.PayloadActivationReport
import dev.lerist.fakelocation.payload.SharedPayloadEntrypoint
import java.io.File

data class LocationChainProbe(
    val simulatedConsumer: String,
    val hookInstalled: Boolean,
    val serviceRegistered: Boolean,
    val sessionLocationEnabled: Boolean,
    val resolvedLocation: MockLocation?,
    val success: Boolean,
    val notes: List<String>,
)

data class RuntimeSnapshot(
    val runtimePrepared: Boolean,
    val hiddenApiAttempted: Boolean,
    val hiddenApiApplied: Boolean,
    val initStageActivated: Boolean,
    val appHookStageActivated: Boolean,
    val sessionRunning: Boolean,
    val runtimeManifestVersion: String?,
    val runtimeManifestFile: File?,
    val runtimeDirectories: List<File>,
    val stagedArtifacts: List<StagedRuntimeArtifact>,
    val runtimeIntegrityReport: RuntimeIntegrityReport?,
    val injectionPreflightReport: InjectionPreflightReport?,
    val injectorEnvironment: InjectorEnvironment?,
    val rootProbeReport: CommandExecutionReport?,
    val runtimeMirrorSyncResult: RuntimeMirrorSyncResult?,
    val rootScriptBundle: RootScriptBundle?,
    val generatedRootScripts: List<GeneratedRootScript>,
    val registeredServices: List<String>,
    val sessionState: MockSessionState,
    val locationChainProbe: LocationChainProbe,
    val injectionPlans: List<InjectionPlan>,
    val injectionTasks: List<InjectionTask>,
    val taskExecutions: List<InjectionTaskExecution>,
    val payloadReports: List<PayloadActivationReport>,
    val hookHistory: List<HookInstallResult>,
)

class Phase1RuntimeController(
    private val context: Context,
    private val assetManager: RuntimeAssetManager,
    private val hiddenApiController: HiddenApiController,
    private val stateStore: InMemoryMockStateStore,
    private val serviceRegistry: InMemoryMockServiceRegistry,
    private val locationManager: InMemoryMockLocationManager,
    private val wifiManager: InMemoryMockWifiManager,
    private val nativeCatchManager: InMemoryNativeCatchManager,
    private val nativeHookBridge: NativeHookBridge,
    private val compatHookBridge: CompatHookBridge,
    private val injectionOrchestrator: DefaultInjectionOrchestrator,
    private val payloadEntrypoint: SharedPayloadEntrypoint,
) {
    private var preparationReport: RuntimePreparationReport? = null
    private var runtimeDirectories: List<File> = emptyList()
    private var hiddenApiAttempted = false
    private var hiddenApiApplied = false
    private var initStageActivated = false
    private var appHookStageActivated = false
    private var sessionRunning = false
    private var runtimeIntegrityReport: RuntimeIntegrityReport? = null
    private var injectionPreflightReport: InjectionPreflightReport? = null
    private var injectorEnvironment: InjectorEnvironment? = null
    private var rootProbeReport: CommandExecutionReport? = null
    private var runtimeMirrorSyncResult: RuntimeMirrorSyncResult? = null
    private var rootScriptBundle: RootScriptBundle? = null
    private var taskExecutions: List<InjectionTaskExecution> = emptyList()
    private val payloadReports = mutableListOf<PayloadActivationReport>()

    fun bootstrap(): RuntimeSnapshot {
        if (runtimeDirectories.isEmpty()) {
            preparationReport = injectionOrchestrator.warmUpRuntime()
            runtimeDirectories = preparationReport?.layout?.directories().orEmpty()
        }
        val report = preparationReport
        if (report != null) {
            rootScriptBundle = injectionOrchestrator.buildRootScriptBundle(report)
            runtimeIntegrityReport = assetManager.verifyPreparationReport(report)
            injectionPreflightReport = injectionOrchestrator.buildPreflightReport(report)
        }
        if (injectorEnvironment == null) {
            injectorEnvironment = injectionOrchestrator.probeInjectorEnvironment()
        }
        if (!hiddenApiAttempted) {
            hiddenApiAttempted = true
            hiddenApiApplied = hiddenApiController.applyBestEffortExemptions()
        }
        if (!initStageActivated) {
            payloadReports += payloadEntrypoint.init(context)
            initStageActivated = true
        }
        return snapshot()
    }

    fun activateAppHookStage(): RuntimeSnapshot {
        bootstrap()
        if (!appHookStageActivated) {
            payloadReports += payloadEntrypoint.appHook(context)
            appHookStageActivated = true
        }
        return snapshot()
    }

    fun startSession(): RuntimeSnapshot {
        activateAppHookStage()
        sessionRunning = true
        if (stateStore.getState().currentLocation == null) {
            locationManager.updateLocation(defaultDemoLocation())
        } else {
            locationManager.startMockLocation()
        }
        return snapshot()
    }

    fun stopSession(): RuntimeSnapshot {
        sessionRunning = false
        locationManager.stopMockLocation()
        wifiManager.stopMockWifi()
        return snapshot()
    }

    fun updateDemoLocation(): RuntimeSnapshot {
        bootstrap()
        locationManager.updateLocation(defaultDemoLocation())
        return snapshot()
    }

    fun updateDemoWifi(): RuntimeSnapshot {
        bootstrap()
        wifiManager.updateWifi(defaultDemoWifi())
        return snapshot()
    }

    fun probeRootShell(): RuntimeSnapshot {
        bootstrap()
        injectorEnvironment = injectionOrchestrator.probeInjectorEnvironment()
        rootProbeReport = injectionOrchestrator.executeEnvironmentProbe(preferRoot = true)
        preparationReport?.let { injectionPreflightReport = injectionOrchestrator.buildPreflightReport(it) }
        return snapshot()
    }

    fun syncRuntimeMirror(): RuntimeSnapshot {
        bootstrap()
        val report = preparationReport ?: return snapshot()
        injectorEnvironment = injectionOrchestrator.probeInjectorEnvironment()
        runtimeMirrorSyncResult = injectionOrchestrator.executeRuntimeMirrorSync(report)
        rootScriptBundle = injectionOrchestrator.buildRootScriptBundle(report)
        runtimeIntegrityReport = assetManager.verifyPreparationReport(report)
        injectionPreflightReport = injectionOrchestrator.buildPreflightReport(report)
        return snapshot()
    }

    fun runPreflightChecks(): RuntimeSnapshot {
        bootstrap()
        val report = preparationReport ?: return snapshot()
        injectorEnvironment = injectionOrchestrator.probeInjectorEnvironment()
        rootScriptBundle = injectionOrchestrator.buildRootScriptBundle(report)
        runtimeIntegrityReport = assetManager.verifyPreparationReport(report)
        injectionPreflightReport = injectionOrchestrator.buildPreflightReport(report)
        return snapshot()
    }

    fun executeDryRunInjectionTasks(): RuntimeSnapshot {
        activateAppHookStage()
        val report = preparationReport ?: return snapshot()
        injectorEnvironment = injectionOrchestrator.probeInjectorEnvironment()
        rootScriptBundle = injectionOrchestrator.buildRootScriptBundle(report)
        runtimeIntegrityReport = assetManager.verifyPreparationReport(report)
        injectionPreflightReport = injectionOrchestrator.buildPreflightReport(report)
        taskExecutions = injectionOrchestrator.executeDryRunTasks(report)
        return snapshot()
    }

    fun executeRootInjectionTasks(): RuntimeSnapshot {
        activateAppHookStage()
        val report = preparationReport ?: return snapshot()
        injectorEnvironment = injectionOrchestrator.probeInjectorEnvironment()
        rootScriptBundle = injectionOrchestrator.buildRootScriptBundle(report)
        runtimeIntegrityReport = assetManager.verifyPreparationReport(report)
        injectionPreflightReport = injectionOrchestrator.buildPreflightReport(report)
        taskExecutions = injectionOrchestrator.executeTasks(
            report = report,
            mode = RootScriptMode.EXECUTE,
        )
        return snapshot()
    }

    fun snapshot(): RuntimeSnapshot {
        val report = preparationReport
        return RuntimeSnapshot(
            runtimePrepared = runtimeDirectories.isNotEmpty(),
            hiddenApiAttempted = hiddenApiAttempted,
            hiddenApiApplied = hiddenApiApplied,
            initStageActivated = initStageActivated,
            appHookStageActivated = appHookStageActivated,
            sessionRunning = sessionRunning,
            runtimeManifestVersion = report?.manifestVersion,
            runtimeManifestFile = report?.layout?.manifestFile,
            runtimeDirectories = runtimeDirectories,
            stagedArtifacts = report?.artifacts.orEmpty(),
            runtimeIntegrityReport = runtimeIntegrityReport,
            injectionPreflightReport = injectionPreflightReport,
            injectorEnvironment = injectorEnvironment,
            rootProbeReport = rootProbeReport,
            runtimeMirrorSyncResult = runtimeMirrorSyncResult,
            rootScriptBundle = rootScriptBundle ?: injectionOrchestrator.getLastScriptBundle(),
            generatedRootScripts = rootScriptBundle?.scripts.orEmpty(),
            registeredServices = serviceRegistry.listServiceNames(),
            sessionState = stateStore.getState(),
            locationChainProbe = buildLocationChainProbe(),
            injectionPlans = injectionOrchestrator.defaultPlans(),
            injectionTasks = report?.let(injectionOrchestrator::buildInjectionTasks).orEmpty(),
            taskExecutions = taskExecutions,
            payloadReports = payloadReports.toList(),
            hookHistory = nativeHookBridge.getInstallHistory() + compatHookBridge.getInstallHistory(),
        )
    }

    fun isNativeHookReady(): Boolean = nativeCatchManager.isHookEngineReady()

    private fun buildLocationChainProbe(): LocationChainProbe {
        val state = stateStore.getState()
        val hookInstalled = nativeHookBridge.hasInstalledHook(
            targetClassName = "com.android.server.location.LocationManagerService",
            targetMethodName = "getLastLocation",
        )
        val serviceRegistered = serviceRegistry.isRegistered("service_fl_ml")
        val sessionLocationEnabled = state.toggles.locationEnabled
        val resolvedLocation = if (hookInstalled && serviceRegistered && sessionLocationEnabled) {
            state.currentLocation
        } else {
            null
        }
        val notes = buildList {
            if (!hookInstalled) {
                add("LocationManagerService.getLastLocation is not installed in the simulated native hook registry")
            }
            if (!serviceRegistered) {
                add("service_fl_ml is not registered")
            }
            if (!sessionLocationEnabled) {
                add("mock location toggle is disabled")
            }
            if (state.currentLocation == null) {
                add("no current mock location is staged")
            }
            if (resolvedLocation != null) {
                add("simulated system_server consumer resolved the staged mock location")
            }
        }
        return LocationChainProbe(
            simulatedConsumer = "com.android.server.location.LocationManagerService#getLastLocation",
            hookInstalled = hookInstalled,
            serviceRegistered = serviceRegistered,
            sessionLocationEnabled = sessionLocationEnabled,
            resolvedLocation = resolvedLocation,
            success = resolvedLocation != null,
            notes = notes,
        )
    }

    private fun defaultDemoLocation(): MockLocation {
        return MockLocation(
            latitude = 31.2304,
            longitude = 121.4737,
            altitudeMeters = 12.0,
            accuracyMeters = 8f,
            provider = "gps",
        )
    }

    private fun defaultDemoWifi(): MockWifiProfile {
        return MockWifiProfile(
            ssid = "FakeLocation-Lab",
            bssid = "02:00:00:12:34:56",
            frequencyMhz = 5745,
            rssiDbm = -42,
        )
    }
}
