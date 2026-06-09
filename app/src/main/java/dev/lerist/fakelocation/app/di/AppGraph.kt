package dev.lerist.fakelocation.app.di

import android.content.Context
import dev.lerist.fakelocation.core.hookbridge.CompatHookBridge
import dev.lerist.fakelocation.core.hookbridge.NativeHookBridge
import dev.lerist.fakelocation.core.ipc.InMemoryMockServiceRegistry
import dev.lerist.fakelocation.core.runtime.HiddenApiController
import dev.lerist.fakelocation.core.runtime.RuntimeAssetManager
import dev.lerist.fakelocation.injector.DefaultInjectionOrchestrator

data class AppGraph(
    val assetManager: RuntimeAssetManager,
    val hiddenApiController: HiddenApiController,
    val serviceRegistry: InMemoryMockServiceRegistry,
    val nativeHookBridge: NativeHookBridge,
    val compatHookBridge: CompatHookBridge,
    val injectionOrchestrator: DefaultInjectionOrchestrator,
) {
    companion object {
        fun create(context: Context): AppGraph {
            val assetManager = RuntimeAssetManager(context)
            val hiddenApiController = HiddenApiController()
            val serviceRegistry = InMemoryMockServiceRegistry()
            val nativeHookBridge = NativeHookBridge()
            val compatHookBridge = CompatHookBridge()
            val orchestrator = DefaultInjectionOrchestrator(assetManager)
            return AppGraph(
                assetManager = assetManager,
                hiddenApiController = hiddenApiController,
                serviceRegistry = serviceRegistry,
                nativeHookBridge = nativeHookBridge,
                compatHookBridge = compatHookBridge,
                injectionOrchestrator = orchestrator,
            )
        }
    }
}
