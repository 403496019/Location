package dev.lerist.fakelocation.core.hookbridge

data class HookMethodSpec(
    val targetClassName: String,
    val targetMethodName: String,
    val signatureHint: String? = null,
)

data class HookInstallResult(
    val bridgeName: String,
    val spec: HookMethodSpec,
    val installed: Boolean,
    val message: String,
)

interface HookBridge {
    fun getVersionCode(): Int
    fun isAvailable(): Boolean
    fun install(spec: HookMethodSpec): HookInstallResult
    fun getInstallHistory(): List<HookInstallResult>
}

class NativeHookBridge : HookBridge {
    private val history = mutableListOf<HookInstallResult>()

    override fun getVersionCode(): Int = 1

    override fun isAvailable(): Boolean = false

    override fun install(spec: HookMethodSpec): HookInstallResult {
        return HookInstallResult(
            bridgeName = "native",
            spec = spec,
            installed = false,
            message = "Native hook bridge is not wired yet for ${spec.targetClassName}.${spec.targetMethodName}",
        ).also(history::add)
    }

    override fun getInstallHistory(): List<HookInstallResult> = history.toList()
}

class CompatHookBridge : HookBridge {
    private val history = mutableListOf<HookInstallResult>()

    override fun getVersionCode(): Int = 0

    override fun isAvailable(): Boolean = false

    override fun install(spec: HookMethodSpec): HookInstallResult {
        return HookInstallResult(
            bridgeName = "compat",
            spec = spec,
            installed = false,
            message = "Compat hook bridge is reserved for a future FLXPH-like layer",
        ).also(history::add)
    }

    override fun getInstallHistory(): List<HookInstallResult> = history.toList()
}
