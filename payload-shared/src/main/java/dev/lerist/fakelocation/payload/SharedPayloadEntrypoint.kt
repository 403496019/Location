package dev.lerist.fakelocation.payload

import android.content.Context
import dev.lerist.fakelocation.core.hookbridge.HookMethodSpec
import dev.lerist.fakelocation.core.hookbridge.NativeHookBridge
import dev.lerist.fakelocation.core.ipc.InMemoryMockServiceRegistry
import dev.lerist.fakelocation.core.ipc.ServiceNames

class SharedPayloadEntrypoint(
    private val nativeHookBridge: NativeHookBridge = NativeHookBridge(),
    private val registry: InMemoryMockServiceRegistry = InMemoryMockServiceRegistry(),
) {
    fun init(context: Context) {
        registry.register(ServiceNames.MOCK_LOCATION, "location-placeholder")
        registry.register(ServiceNames.MOCK_WIFI, "wifi-placeholder")
        registry.register(ServiceNames.NATIVE_CATCH, "native-placeholder")
        installSystemServerBootstrapHooks()
    }

    fun appHook(context: Context) {
        installPhoneSubsystemHooks()
    }

    private fun installSystemServerBootstrapHooks() {
        nativeHookBridge.install(
            HookMethodSpec(
                targetClassName = "com.android.server.location.LocationManagerService",
                targetMethodName = "getLastLocation",
            ),
        )
    }

    private fun installPhoneSubsystemHooks() {
        nativeHookBridge.install(
            HookMethodSpec(
                targetClassName = "com.android.phone.PhoneInterfaceManager",
                targetMethodName = "getAllCellInfo",
            ),
        )
    }
}
