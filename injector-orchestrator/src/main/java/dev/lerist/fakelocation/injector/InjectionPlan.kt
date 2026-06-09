package dev.lerist.fakelocation.injector

import dev.lerist.fakelocation.core.model.TargetProcessRole
import dev.lerist.fakelocation.core.runtime.RuntimeAssetManager

enum class InjectionStage {
    INIT_STAGE,
    APP_HOOK_STAGE,
}

data class InjectionPlan(
    val processName: String,
    val role: TargetProcessRole,
    val stage: InjectionStage,
    val abi: String,
    val nativeLoaderName: String,
    val javaEntrypoint: String,
)

class DefaultInjectionOrchestrator(
    private val runtimeAssetManager: RuntimeAssetManager,
) {
    fun warmUpRuntime(): Unit = runtimeAssetManager.prepareRuntimeLayout()

    fun defaultPlans(): List<InjectionPlan> {
        return listOf(
            InjectionPlan(
                processName = "system_server",
                role = TargetProcessRole.SYSTEM_SERVER,
                stage = InjectionStage.INIT_STAGE,
                abi = "arm64-v8a",
                nativeLoaderName = "libfl_init64.so",
                javaEntrypoint = "init",
            ),
            InjectionPlan(
                processName = "com.android.phone",
                role = TargetProcessRole.PHONE_SUBSYSTEM,
                stage = InjectionStage.APP_HOOK_STAGE,
                abi = "arm64-v8a",
                nativeLoaderName = "libfl_app64.so",
                javaEntrypoint = "appHook",
            ),
        )
    }
}
