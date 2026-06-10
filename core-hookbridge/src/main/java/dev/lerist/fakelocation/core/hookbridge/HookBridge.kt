package dev.lerist.fakelocation.core.hookbridge

/**
 * Specifies a target method for hook installation.
 */
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

/**
 * Native hook bridge.
 *
 * In the main app process this maintains a registry for panel
 * observability.  The real ART-level hook installation happens inside the
 * target process (system_server / com.android.phone) when liblh64.so is
 * loaded by the injected loader — NOT in the main APK process.
 *
 * The {@code nativeLoaded} flag indicates whether lh64.so happens to be
 * loaded in *this* process (e.g. for testing).  It does NOT represent
 * whether the remote target process has hooks active.
 */
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

    /**
     * Whether liblh64.so is loaded in *this* process.
     * This is almost always false in the main APK process because
     * liblh64 is deployed to /data/fl/native/ for remote dlopen,
     * not installed as a conventional JNI library.
     * Do NOT use this to judge whether remote injection succeeded.
     */
    var nativeLoaded: Boolean = false
        private set

    override fun getVersionCode(): Int = if (nativeLoaded) 6 else 1

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
                "Native bridge accepted ${spec.targetClassName}.${spec.targetMethodName} into the " +
                    "${if (nativeLoaded) "native" else "simulated"} registry"
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
