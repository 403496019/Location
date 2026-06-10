package dev.lerist.fakelocation.core.ipc

import dev.lerist.fakelocation.core.model.MockLocation
import dev.lerist.fakelocation.core.model.MockSessionState
import dev.lerist.fakelocation.core.model.MockWifiProfile
import java.util.concurrent.CopyOnWriteArrayList

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

fun interface MockStateListener {
    fun onStateChanged(state: MockSessionState)
}

class InMemoryMockStateStore(
    initialState: MockSessionState = MockSessionState(),
) {
    private val listeners = CopyOnWriteArrayList<MockStateListener>()

    @Volatile
    private var state: MockSessionState = initialState

    fun getState(): MockSessionState = state

    fun update(transform: (MockSessionState) -> MockSessionState): MockSessionState {
        val newState = synchronized(this) {
            transform(state).copy(lastUpdatedAtMillis = System.currentTimeMillis()).also {
                state = it
            }
        }
        listeners.forEach { it.onStateChanged(newState) }
        return newState
    }

    fun addListener(listener: MockStateListener) {
        listeners += listener
        listener.onStateChanged(state)
    }

    fun removeListener(listener: MockStateListener) {
        listeners -= listener
    }
}

class InMemoryMockLocationManager(
    private val stateStore: InMemoryMockStateStore,
) : MockLocationManager {
    override fun getState(): MockSessionState = stateStore.getState()

    override fun startMockLocation() {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(locationEnabled = true),
            )
        }
    }

    override fun stopMockLocation() {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(locationEnabled = false),
            )
        }
    }

    override fun updateLocation(location: MockLocation) {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(locationEnabled = true),
                currentLocation = location,
            )
        }
    }
}

class InMemoryMockWifiManager(
    private val stateStore: InMemoryMockStateStore,
) : MockWifiManager {
    override fun startMockWifi() {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(wifiEnabled = true),
            )
        }
    }

    override fun stopMockWifi() {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(wifiEnabled = false),
            )
        }
    }

    override fun updateWifi(profile: MockWifiProfile) {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(wifiEnabled = true),
                currentWifiProfile = profile,
            )
        }
    }
}

class InMemoryNativeCatchManager(
    private val hookReadyProvider: () -> Boolean,
) : NativeCatchManager {
    override fun isHookEngineReady(): Boolean = hookReadyProvider()
}

class InMemoryMockServiceRegistry : MockServiceProvider {
    private val services = linkedMapOf<String, Any>()

    @Synchronized
    fun register(name: String, service: Any) {
        services[name] = service
    }

    @Synchronized
    fun isRegistered(name: String): Boolean = services.containsKey(name)

    @Synchronized
    fun listServiceNames(): List<String> = services.keys.toList()

    @Synchronized
    fun clear() {
        services.clear()
    }

    override fun getService(name: String): Any? = services[name]
}
