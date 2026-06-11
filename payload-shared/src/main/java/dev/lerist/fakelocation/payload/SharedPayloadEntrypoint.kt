package dev.lerist.fakelocation.payload

import android.content.Context
import dev.lerist.fakelocation.core.hookbridge.CompatHookBridge
import dev.lerist.fakelocation.core.hookbridge.HookMethodSpec
import dev.lerist.fakelocation.core.hookbridge.NativeHookBridge
import dev.lerist.fakelocation.core.ipc.MockCellManager
import dev.lerist.fakelocation.core.ipc.InMemoryMockServiceRegistry
import dev.lerist.fakelocation.core.ipc.MockLocationManager
import dev.lerist.fakelocation.core.ipc.MockWifiManager
import dev.lerist.fakelocation.core.ipc.NativeCatchManager
import dev.lerist.fakelocation.core.ipc.ServiceNames

data class PayloadActivationReport(
    val stageName: String,
    val installedHooks: Int,
    val registeredServices: List<String>,
    val notes: List<String>,
)

class SharedPayloadEntrypoint(
    private val nativeHookBridge: NativeHookBridge = NativeHookBridge(),
    private val compatHookBridge: CompatHookBridge = CompatHookBridge(),
    private val registry: InMemoryMockServiceRegistry = InMemoryMockServiceRegistry(),
    private val locationManager: MockLocationManager,
    private val wifiManager: MockWifiManager,
    private val cellManager: MockCellManager,
    private val nativeCatchManager: NativeCatchManager,
) {
    fun init(@Suppress("UNUSED_PARAMETER") context: Context): PayloadActivationReport {
        ensureRegistered(ServiceNames.MOCK_LOCATION, locationManager)
        ensureRegistered(ServiceNames.MOCK_WIFI, wifiManager)
        ensureRegistered(ServiceNames.MOCK_CELLS, cellManager)
        ensureRegistered(ServiceNames.NATIVE_CATCH, nativeCatchManager)
        val installResults = installSystemServerBootstrapHooks()
        return PayloadActivationReport(
            stageName = "init",
            installedHooks = installResults.count { it.installed },
            registeredServices = registry.listServiceNames(),
            notes = installResults.map { it.message },
        )
    }

    fun appHook(@Suppress("UNUSED_PARAMETER") context: Context): PayloadActivationReport {
        val nativeResults = installPhoneSubsystemHooks()
        val compatResults = installCompatFallbackHooks()
        val allResults = nativeResults + compatResults
        return PayloadActivationReport(
            stageName = "appHook",
            installedHooks = allResults.count { it.installed },
            registeredServices = registry.listServiceNames(),
            notes = allResults.map { it.message },
        )
    }

    private fun installSystemServerBootstrapHooks() = listOf(
        nativeHookBridge.install(
            HookMethodSpec(
                targetClassName = "com.android.server.location.LocationManagerService",
                targetMethodName = "getLastLocation",
            ),
        ),
    )

    private fun installPhoneSubsystemHooks() = listOf(
        nativeHookBridge.install(
            HookMethodSpec(
                targetClassName = "com.android.phone.PhoneInterfaceManager",
                targetMethodName = "getAllCellInfo",
            ),
        ),
    )

    private fun installCompatFallbackHooks() = listOf(
        compatHookBridge.install(
            HookMethodSpec(
                targetClassName = "android.net.wifi.WifiManager",
                targetMethodName = "getScanResults",
            ),
        ),
    )

    private fun ensureRegistered(name: String, service: Any) {
        if (!registry.isRegistered(name)) {
            registry.register(name, service)
        }
    }
}
