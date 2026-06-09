package dev.lerist.fakelocation.core.hookbridge

data class HookMethodSpec(
    val targetClassName: String,
    val targetMethodName: String,
    val signatureHint: String? = null,
)

data class HookInstallResult(
    val installed: Boolean,
    val message: String,
)

interface HookBridge {
    fun getVersionCode(): Int
    fun isAvailable(): Boolean
    fun install(spec: HookMethodSpec): HookInstallResult
}

class NativeHookBridge : HookBridge {
    override fun getVersionCode(): Int = 1

    override fun isAvailable(): Boolean = false

    override fun install(spec: HookMethodSpec): HookInstallResult {
        return HookInstallResult(
            installed = false,
            message = "Native hook bridge is not wired yet for ${spec.targetClassName}.${spec.targetMethodName}",
        )
    }
}

class CompatHookBridge : HookBridge {
    override fun getVersionCode(): Int = 0

    override fun isAvailable(): Boolean = false

    override fun install(spec: HookMethodSpec): HookInstallResult {
        return HookInstallResult(
            installed = false,
            message = "Compat hook bridge is reserved for a future FLXPH-like layer",
        )
    }
}
