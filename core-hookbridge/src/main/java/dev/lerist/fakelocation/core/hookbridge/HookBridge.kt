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
    private val installedSpecs = linkedSetOf<HookMethodSpec>()
    private val supportedSpecs = setOf(
        HookMethodSpec(
            targetClassName = "com.android.server.location.LocationManagerService",
            targetMethodName = "getLastLocation",
        ),
        HookMethodSpec(
            targetClassName = "com.android.phone.PhoneInterfaceManager",
            targetMethodName = "getAllCellInfo",
        ),
    )

    override fun getVersionCode(): Int = 1

    override fun isAvailable(): Boolean = true

    override fun install(spec: HookMethodSpec): HookInstallResult {
        val normalized = spec.normalized()
        val installed = supportedSpecs.contains(normalized)
        if (installed) {
            installedSpecs += normalized
        }
        return HookInstallResult(
            bridgeName = "native",
            spec = spec,
            installed = installed,
            message = if (installed) {
                "Native bridge accepted ${spec.targetClassName}.${spec.targetMethodName} into the simulated hook registry"
            } else {
                "Native bridge does not yet implement ${spec.targetClassName}.${spec.targetMethodName}"
            },
        ).also(history::add)
    }

    override fun getInstallHistory(): List<HookInstallResult> = history.toList()

    fun getInstalledSpecs(): Set<HookMethodSpec> = installedSpecs.toSet()

    fun hasInstalledHook(
        targetClassName: String,
        targetMethodName: String,
    ): Boolean {
        return installedSpecs.contains(
            HookMethodSpec(
                targetClassName = targetClassName,
                targetMethodName = targetMethodName,
            ).normalized(),
        )
    }
}

class CompatHookBridge : HookBridge {
    private val history = mutableListOf<HookInstallResult>()
    private val installedSpecs = linkedSetOf<HookMethodSpec>()
    private val supportedSpecs = setOf(
        HookMethodSpec(
            targetClassName = "android.net.wifi.WifiManager",
            targetMethodName = "getScanResults",
        ),
    )

    override fun getVersionCode(): Int = 0

    override fun isAvailable(): Boolean = true

    override fun install(spec: HookMethodSpec): HookInstallResult {
        val normalized = spec.normalized()
        val installed = supportedSpecs.contains(normalized)
        if (installed) {
            installedSpecs += normalized
        }
        return HookInstallResult(
            bridgeName = "compat",
            spec = spec,
            installed = installed,
            message = if (installed) {
                "Compat bridge accepted ${spec.targetClassName}.${spec.targetMethodName} into the fallback registry"
            } else {
                "Compat bridge does not yet implement ${spec.targetClassName}.${spec.targetMethodName}"
            },
        ).also(history::add)
    }

    override fun getInstallHistory(): List<HookInstallResult> = history.toList()

    fun getInstalledSpecs(): Set<HookMethodSpec> = installedSpecs.toSet()
}

private fun HookMethodSpec.normalized(): HookMethodSpec {
    return copy(
        targetClassName = targetClassName.trim(),
        targetMethodName = targetMethodName.trim(),
    )
}
