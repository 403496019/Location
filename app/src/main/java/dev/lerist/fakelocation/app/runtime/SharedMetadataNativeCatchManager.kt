package dev.lerist.fakelocation.app.runtime

import dev.lerist.fakelocation.core.ipc.NativeCatchManager
import dev.lerist.fakelocation.core.ipc.NativeLocationSyncReport
import dev.lerist.fakelocation.core.model.MockFeatureToggles
import dev.lerist.fakelocation.core.model.MockLocation
import dev.lerist.fakelocation.core.model.MockSessionState
import dev.lerist.fakelocation.core.runtime.RuntimeAssetManager
import dev.lerist.fakelocation.injector.AndroidShellExecutor
import java.io.File

class SharedMetadataNativeCatchManager(
    private val runtimeAssetManager: RuntimeAssetManager,
    private val shellExecutor: AndroidShellExecutor,
    private val hookReadyProvider: () -> Boolean,
) : NativeCatchManager {
    @Volatile
    private var lastSyncReport: NativeLocationSyncReport? = null

    override fun isHookEngineReady(): Boolean = hookReadyProvider()

    override fun pushMockState(state: MockSessionState): NativeLocationSyncReport {
        val timestampMillis = System.currentTimeMillis()
        val localFile = runtimeAssetManager.privateMetadataFile(MOCK_STATE_FILE_NAME)
        val sharedFile = runtimeAssetManager.sharedMetadataFile(MOCK_STATE_FILE_NAME)
        return try {
            writeAtomically(
                target = localFile,
                content = runtimeAssetManager.buildMockStateContent(state),
            )
            val mirrorReport = mirrorToSharedMetadata(localFile, sharedFile)
            NativeLocationSyncReport(
                success = mirrorReport.success,
                active = state.toggles.locationEnabled || state.toggles.wifiEnabled || state.toggles.cellsEnabled,
                backend = "shared-metadata",
                sharedPath = sharedFile.absolutePath,
                detail = mirrorReport.detail,
                syncedAtMillis = timestampMillis,
            ).also { lastSyncReport = it }
        } catch (t: Throwable) {
            NativeLocationSyncReport(
                success = false,
                active = state.toggles.locationEnabled || state.toggles.wifiEnabled || state.toggles.cellsEnabled,
                backend = "shared-metadata",
                sharedPath = sharedFile.absolutePath,
                detail = "Failed to write shared mock state: ${t.message ?: t.javaClass.simpleName}",
                syncedAtMillis = timestampMillis,
            ).also { lastSyncReport = it }
        }
    }

    override fun pushMockLocation(location: MockLocation): NativeLocationSyncReport {
        return pushMockState(
            MockSessionState(
                toggles = MockFeatureToggles(locationEnabled = true),
                currentLocation = location,
                lastUpdatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    override fun stopMockLocation(): NativeLocationSyncReport {
        return pushMockState(MockSessionState())
    }

    override fun getLastSyncReport(): NativeLocationSyncReport? = lastSyncReport

    private fun mirrorToSharedMetadata(
        localFile: File,
        sharedFile: File,
    ): MirrorResult {
        val environment = shellExecutor.probeEnvironment()
        if (!environment.hasSuBinary) {
            return MirrorResult(
                success = false,
                detail = "Local metadata updated, but su is unavailable so /data/fl mirror was not refreshed",
            )
        }
        val tmpFile = File(sharedFile.parentFile, "${sharedFile.name}.tmp")
        val command = buildString {
            append("mkdir -p ")
            append(shellQuote(sharedFile.parentFile!!.absolutePath))
            append(" && cp ")
            append(shellQuote(localFile.absolutePath))
            append(" ")
            append(shellQuote(tmpFile.absolutePath))
            append(" && chmod 644 ")
            append(shellQuote(tmpFile.absolutePath))
            append(" && chown root:root ")
            append(shellQuote(tmpFile.absolutePath))
            append(" && mv ")
            append(shellQuote(tmpFile.absolutePath))
            append(" ")
            append(shellQuote(sharedFile.absolutePath))
        }
        val report = shellExecutor.execute(
            command = command,
            preferRoot = true,
            timeoutMs = 8_000,
        )
        return if (report.success) {
            MirrorResult(
                success = true,
                detail = "Local metadata and /data/fl mirror are both updated",
            )
        } else {
            MirrorResult(
                success = false,
                detail = buildString {
                    append("Local metadata updated, but mirror sync failed")
                    report.exitCode?.let { append(" exit=$it") }
                    if (report.stderr.isNotBlank()) {
                        append(": ")
                        append(report.stderr.lineSequence().firstOrNull() ?: report.stderr)
                    }
                },
            )
        }
    }

    private fun writeAtomically(
        target: File,
        content: String,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private data class MirrorResult(
        val success: Boolean,
        val detail: String,
    )

    companion object {
        private const val MOCK_STATE_FILE_NAME = "mock-location-state.txt"
    }
}
