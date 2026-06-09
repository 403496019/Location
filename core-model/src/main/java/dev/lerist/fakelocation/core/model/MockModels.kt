package dev.lerist.fakelocation.core.model

data class MockLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float = 10f,
    val provider: String = "gps",
)

data class MockWifiProfile(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int? = null,
    val rssiDbm: Int? = null,
)

data class MockCellRecord(
    val mcc: Int,
    val mnc: Int,
    val lacOrTac: Int,
    val cidOrNci: Long,
)

data class MockFeatureToggles(
    val locationEnabled: Boolean = false,
    val wifiEnabled: Boolean = false,
    val cellsEnabled: Boolean = false,
    val driftEnabled: Boolean = false,
    val floatEnabled: Boolean = false,
)

data class MockSessionState(
    val toggles: MockFeatureToggles = MockFeatureToggles(),
    val currentLocation: MockLocation? = null,
    val currentWifiProfile: MockWifiProfile? = null,
    val currentCells: List<MockCellRecord> = emptyList(),
    val allowedMockPackages: Set<String> = emptySet(),
    val safeApps: Set<String> = emptySet(),
    val lastUpdatedAtMillis: Long = 0L,
)

enum class TargetProcessRole {
    SYSTEM_SERVER,
    PHONE_SUBSYSTEM,
    OPTIONAL_EXTENSION,
}
