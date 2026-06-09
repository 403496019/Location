package dev.lerist.fakelocation.core.ipc

import dev.lerist.fakelocation.core.model.MockLocation
import dev.lerist.fakelocation.core.model.MockSessionState
import dev.lerist.fakelocation.core.model.MockWifiProfile

object ServiceNames {
    const val MOCK_LOCATION = "service_fl_ml"
    const val MOCK_WIFI = "service_fl_mw"
    const val NATIVE_CATCH = "service_fl_na"
}

interface MockLocationManager {
    fun getState(): MockSessionState
    fun startMockLocation()
    fun stopMockLocation()
    fun updateLocation(location: MockLocation)
}

interface MockWifiManager {
    fun startMockWifi()
    fun stopMockWifi()
    fun updateWifi(profile: MockWifiProfile)
}

interface NativeCatchManager {
    fun isHookEngineReady(): Boolean
}

interface MockServiceProvider {
    fun getService(name: String): Any?
}

class InMemoryMockServiceRegistry : MockServiceProvider {
    private val services = linkedMapOf<String, Any>()

    fun register(name: String, service: Any) {
        services[name] = service
    }

    override fun getService(name: String): Any? = services[name]
}
