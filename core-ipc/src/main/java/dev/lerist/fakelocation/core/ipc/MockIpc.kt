package dev.lerist.fakelocation.core.ipc

import dev.lerist.fakelocation.core.model.MockLocation
import dev.lerist.fakelocation.core.model.MockCellRecord
import dev.lerist.fakelocation.core.model.MockSessionState
import dev.lerist.fakelocation.core.model.MockWifiProfile
import java.util.concurrent.CopyOnWriteArrayList

object ServiceNames {
    const val MOCK_LOCATION = "service_fl_ml"
    const val MOCK_WIFI = "service_fl_mw"
    const val MOCK_CELLS = "service_fl_mc"
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

interface MockCellManager {
    fun startMockCells()
    fun stopMockCells()
    fun updateCells(cells: List<MockCellRecord>)
}

data class NativeLocationSyncReport(
    val success: Boolean,
    val active: Boolean,
    val backend: String,
    val sharedPath: String?,
    val detail: String,
    val syncedAtMillis: Long,
)

interface NativeCatchManager {
    fun isHookEngineReady(): Boolean
    fun pushMockState(state: MockSessionState): NativeLocationSyncReport
    fun pushMockLocation(location: MockLocation): NativeLocationSyncReport
    fun stopMockLocation(): NativeLocationSyncReport
    fun getLastSyncReport(): NativeLocationSyncReport?
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

class InMemoryMockCellManager(
    private val stateStore: InMemoryMockStateStore,
) : MockCellManager {
    override fun startMockCells() {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(cellsEnabled = true),
            )
        }
    }

    override fun stopMockCells() {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(cellsEnabled = false),
            )
        }
    }

    override fun updateCells(cells: List<MockCellRecord>) {
        stateStore.update { state ->
            state.copy(
                toggles = state.toggles.copy(cellsEnabled = true),
                currentCells = cells,
            )
        }
    }
}

class InMemoryNativeCatchManager(
    private val hookReadyProvider: () -> Boolean,
) : NativeCatchManager {
    override fun isHookEngineReady(): Boolean = hookReadyProvider()

    @Volatile
    private var lastSyncReport: NativeLocationSyncReport? = null

    override fun pushMockState(state: MockSessionState): NativeLocationSyncReport {
        return NativeLocationSyncReport(
            success = false,
            active = state.toggles.locationEnabled || state.toggles.wifiEnabled || state.toggles.cellsEnabled,
            backend = "in-memory",
            sharedPath = null,
            detail = "Native sync is not implemented for the in-memory catch manager",
            syncedAtMillis = System.currentTimeMillis(),
        ).also { lastSyncReport = it }
    }

    override fun pushMockLocation(location: MockLocation): NativeLocationSyncReport {
        return NativeLocationSyncReport(
            success = false,
            active = true,
            backend = "in-memory",
            sharedPath = null,
            detail = "Native sync is not implemented for the in-memory catch manager",
            syncedAtMillis = System.currentTimeMillis(),
        ).also { lastSyncReport = it }
    }

    override fun stopMockLocation(): NativeLocationSyncReport {
        return NativeLocationSyncReport(
            success = false,
            active = false,
            backend = "in-memory",
            sharedPath = null,
            detail = "Native sync is not implemented for the in-memory catch manager",
            syncedAtMillis = System.currentTimeMillis(),
        ).also { lastSyncReport = it }
    }

    override fun getLastSyncReport(): NativeLocationSyncReport? = lastSyncReport
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
