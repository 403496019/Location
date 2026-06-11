package dev.lerist.fakelocation.app.di

import android.content.Context
import dev.lerist.fakelocation.app.runtime.Phase1RuntimeController
import dev.lerist.fakelocation.app.runtime.SharedMetadataNativeCatchManager
import dev.lerist.fakelocation.core.ipc.InMemoryMockCellManager
import dev.lerist.fakelocation.core.hookbridge.CompatHookBridge
import dev.lerist.fakelocation.core.hookbridge.NativeHookBridge
import dev.lerist.fakelocation.core.ipc.InMemoryMockLocationManager
import dev.lerist.fakelocation.core.ipc.NativeCatchManager
import dev.lerist.fakelocation.core.ipc.InMemoryMockStateStore
import dev.lerist.fakelocation.core.ipc.InMemoryMockWifiManager
import dev.lerist.fakelocation.core.ipc.InMemoryMockServiceRegistry
import dev.lerist.fakelocation.core.runtime.HiddenApiController
import dev.lerist.fakelocation.core.runtime.RuntimeAssetManager
import dev.lerist.fakelocation.injector.AndroidShellExecutor
import dev.lerist.fakelocation.injector.DefaultInjectionOrchestrator
import dev.lerist.fakelocation.payload.SharedPayloadEntrypoint

data class AppGraph(
    val assetManager: RuntimeAssetManager,
    val hiddenApiController: HiddenApiController,
    val stateStore: InMemoryMockStateStore,
    val serviceRegistry: InMemoryMockServiceRegistry,
    val locationManager: InMemoryMockLocationManager,
    val wifiManager: InMemoryMockWifiManager,
    val cellManager: InMemoryMockCellManager,
    val nativeCatchManager: NativeCatchManager,
    val nativeHookBridge: NativeHookBridge,
    val compatHookBridge: CompatHookBridge,
    val shellExecutor: AndroidShellExecutor,
    val injectionOrchestrator: DefaultInjectionOrchestrator,
    val payloadEntrypoint: SharedPayloadEntrypoint,
    val runtimeController: Phase1RuntimeController,
) {
    companion object {
        fun create(context: Context): AppGraph {
            val assetManager = RuntimeAssetManager(context)
            val hiddenApiController = HiddenApiController()
            val stateStore = InMemoryMockStateStore()
            val serviceRegistry = InMemoryMockServiceRegistry()
            val locationManager = InMemoryMockLocationManager(stateStore)
            val wifiManager = InMemoryMockWifiManager(stateStore)
            val cellManager = InMemoryMockCellManager(stateStore)
            val nativeHookBridge = NativeHookBridge()
            val compatHookBridge = CompatHookBridge()
            val shellExecutor = AndroidShellExecutor()
            val nativeCatchManager = SharedMetadataNativeCatchManager(
                runtimeAssetManager = assetManager,
                shellExecutor = shellExecutor,
            ) {
                nativeHookBridge.isAvailable() || compatHookBridge.isAvailable()
            }
            val orchestrator = DefaultInjectionOrchestrator(
                runtimeAssetManager = assetManager,
                shellExecutor = shellExecutor,
            )
            val payloadEntrypoint = SharedPayloadEntrypoint(
                nativeHookBridge = nativeHookBridge,
                compatHookBridge = compatHookBridge,
                registry = serviceRegistry,
                locationManager = locationManager,
                wifiManager = wifiManager,
                cellManager = cellManager,
                nativeCatchManager = nativeCatchManager,
            )
            val runtimeController = Phase1RuntimeController(
                context = context.applicationContext,
                assetManager = assetManager,
                hiddenApiController = hiddenApiController,
                stateStore = stateStore,
                serviceRegistry = serviceRegistry,
                locationManager = locationManager,
                wifiManager = wifiManager,
                cellManager = cellManager,
                nativeCatchManager = nativeCatchManager,
                nativeHookBridge = nativeHookBridge,
                compatHookBridge = compatHookBridge,
                shellExecutor = shellExecutor,
                injectionOrchestrator = orchestrator,
                payloadEntrypoint = payloadEntrypoint,
            )
            return AppGraph(
                assetManager = assetManager,
                hiddenApiController = hiddenApiController,
                stateStore = stateStore,
                serviceRegistry = serviceRegistry,
                locationManager = locationManager,
                wifiManager = wifiManager,
                cellManager = cellManager,
                nativeCatchManager = nativeCatchManager,
                nativeHookBridge = nativeHookBridge,
                compatHookBridge = compatHookBridge,
                shellExecutor = shellExecutor,
                injectionOrchestrator = orchestrator,
                payloadEntrypoint = payloadEntrypoint,
                runtimeController = runtimeController,
            )
        }
    }
}
