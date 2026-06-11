package dev.lerist.fakelocation.core.runtime

import android.content.Context
import android.os.Build
import dev.lerist.fakelocation.core.model.MockCellRecord
import dev.lerist.fakelocation.core.model.MockSessionState
import dev.lerist.fakelocation.core.model.MockWifiProfile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

enum class RuntimeArtifactKind {
    JAVA_PAYLOAD,
    NATIVE_LOADER,
    HOOK_BRIDGE,
    INJECTOR,
    METADATA,
}

data class RuntimeAssetDescriptor(
    val logicalName: String,
    val relativePath: String,
    val assetRelativePath: String? = null,
    val abi: String? = null,
    val kind: RuntimeArtifactKind,
    val versionTag: String,
    val sourceTag: String,
)

data class StagedRuntimeArtifact(
    val descriptor: RuntimeAssetDescriptor,
    val privateFile: File,
    val sharedPathHint: String,
    val sizeBytes: Long,
    val sha256: String,
    val isPlaceholder: Boolean,
)

data class RuntimeLayoutSnapshot(
    val privateRoot: File,
    val stagedRoot: File,
    val payloadRoot: File,
    val nativeRoot: File,
    val injectorRoot: File,
    val scriptsRoot: File,
    val metadataRoot: File,
    val logsRoot: File,
    val manifestFile: File,
    val sharedRootHint: File,
) {
    fun directories(): List<File> {
        return listOf(
            privateRoot,
            stagedRoot,
            payloadRoot,
            nativeRoot,
            injectorRoot,
            scriptsRoot,
            metadataRoot,
            logsRoot,
        )
    }
}

data class RuntimePreparationReport(
    val layout: RuntimeLayoutSnapshot,
    val artifacts: List<StagedRuntimeArtifact>,
    val manifestVersion: String,
    val generatedAtMillis: Long,
)

enum class ArtifactIntegrityStatus {
    OK,
    MISSING,
    EMPTY,
    HASH_MISMATCH,
}

data class ArtifactIntegrityReport(
    val logicalName: String,
    val status: ArtifactIntegrityStatus,
    val filePath: String,
    val expectedSizeBytes: Long,
    val actualSizeBytes: Long?,
    val expectedSha256: String,
    val actualSha256: String?,
    val isPlaceholder: Boolean,
)

data class RuntimeIntegrityReport(
    val manifestExists: Boolean,
    val manifestPath: String,
    val artifactReports: List<ArtifactIntegrityReport>,
    val allPresent: Boolean,
    val allNonEmpty: Boolean,
    val allHashesMatch: Boolean,
    val placeholderArtifactCount: Int,
    val checkedAtMillis: Long,
)

class RuntimeAssetManager(private val context: Context) {
    private var lastReport: RuntimePreparationReport? = null

    fun privateRuntimeRoot(): File = File(context.filesDir, "flrt")

    fun sharedRuntimeRoot(): File = File("/data/fl")

    fun privateMetadataFile(name: String): File = File(privateRuntimeRoot(), "staged/fl/metadata/$name")

    fun sharedMetadataFile(name: String): File = File(sharedRuntimeRoot(), "metadata/$name")

    fun getLastPreparationReport(): RuntimePreparationReport? = lastReport

    fun prepareRuntimeLayout(): RuntimePreparationReport {
        val layout = createLayout()
        val descriptors = defaultDescriptors()
        val artifacts = descriptors.map { descriptor ->
            val targetFile = File(layout.stagedRoot, descriptor.relativePath)
            targetFile.parentFile?.mkdirs()
            val materializedFromAsset = materializeAssetIfPresent(descriptor, targetFile)
            if (!materializedFromAsset) {
                targetFile.writeText(buildArtifactContent(descriptor))
                if (descriptor.kind == RuntimeArtifactKind.INJECTOR) {
                    targetFile.setExecutable(true, false)
                }
            }
            StagedRuntimeArtifact(
                descriptor = descriptor,
                privateFile = targetFile,
                sharedPathHint = File(layout.sharedRootHint, descriptor.relativePath).absolutePath,
                sizeBytes = targetFile.length(),
                sha256 = sha256Of(targetFile),
                isPlaceholder = !materializedFromAsset,
            )
        }
        val report = RuntimePreparationReport(
            layout = layout,
            artifacts = artifacts,
            manifestVersion = "phase1-runtime-v5",
            generatedAtMillis = System.currentTimeMillis(),
        )
        layout.manifestFile.writeText(buildManifest(report))
        lastReport = report
        return report
    }

    fun verifyPreparationReport(
        report: RuntimePreparationReport = lastReport ?: error("Runtime not prepared yet."),
    ): RuntimeIntegrityReport {
        val artifactReports = report.artifacts.map { artifact ->
            val file = artifact.privateFile
            val mutableAtRuntime = isRuntimeMutableArtifact(artifact.descriptor.logicalName)
            when {
                !file.exists() -> ArtifactIntegrityReport(
                    logicalName = artifact.descriptor.logicalName,
                    status = ArtifactIntegrityStatus.MISSING,
                    filePath = file.absolutePath,
                    expectedSizeBytes = artifact.sizeBytes,
                    actualSizeBytes = null,
                    expectedSha256 = artifact.sha256,
                    actualSha256 = null,
                    isPlaceholder = artifact.isPlaceholder,
                )
                file.length() <= 0L -> ArtifactIntegrityReport(
                    logicalName = artifact.descriptor.logicalName,
                    status = ArtifactIntegrityStatus.EMPTY,
                    filePath = file.absolutePath,
                    expectedSizeBytes = artifact.sizeBytes,
                    actualSizeBytes = file.length(),
                    expectedSha256 = artifact.sha256,
                    actualSha256 = sha256Of(file),
                    isPlaceholder = artifact.isPlaceholder,
                )
                !mutableAtRuntime && sha256Of(file) != artifact.sha256 -> ArtifactIntegrityReport(
                    logicalName = artifact.descriptor.logicalName,
                    status = ArtifactIntegrityStatus.HASH_MISMATCH,
                    filePath = file.absolutePath,
                    expectedSizeBytes = artifact.sizeBytes,
                    actualSizeBytes = file.length(),
                    expectedSha256 = artifact.sha256,
                    actualSha256 = sha256Of(file),
                    isPlaceholder = artifact.isPlaceholder,
                )
                else -> ArtifactIntegrityReport(
                    logicalName = artifact.descriptor.logicalName,
                    status = ArtifactIntegrityStatus.OK,
                    filePath = file.absolutePath,
                    expectedSizeBytes = artifact.sizeBytes,
                    actualSizeBytes = file.length(),
                    expectedSha256 = artifact.sha256,
                    actualSha256 = sha256Of(file),
                    isPlaceholder = artifact.isPlaceholder,
                )
            }
        }
        return RuntimeIntegrityReport(
            manifestExists = report.layout.manifestFile.exists(),
            manifestPath = report.layout.manifestFile.absolutePath,
            artifactReports = artifactReports,
            allPresent = artifactReports.none { it.status == ArtifactIntegrityStatus.MISSING },
            allNonEmpty = artifactReports.none { it.status == ArtifactIntegrityStatus.EMPTY },
            allHashesMatch = artifactReports.none { it.status == ArtifactIntegrityStatus.HASH_MISMATCH },
            placeholderArtifactCount = artifactReports.count { it.isPlaceholder },
            checkedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun createLayout(): RuntimeLayoutSnapshot {
        val privateRoot = privateRuntimeRoot()
        val stagedRoot = File(privateRoot, "staged/fl")
        val payloadRoot = File(stagedRoot, "payload")
        val nativeRoot = File(stagedRoot, "native")
        val injectorRoot = File(stagedRoot, "bin")
        val scriptsRoot = File(stagedRoot, "scripts")
        val metadataRoot = File(stagedRoot, "metadata")
        val logsRoot = File(privateRoot, "logs")
        val manifestFile = File(metadataRoot, "runtime-manifest-v5.txt")
        RuntimeLayoutSnapshot(
            privateRoot = privateRoot,
            stagedRoot = stagedRoot,
            payloadRoot = payloadRoot,
            nativeRoot = nativeRoot,
            injectorRoot = injectorRoot,
            scriptsRoot = scriptsRoot,
            metadataRoot = metadataRoot,
            logsRoot = logsRoot,
            manifestFile = manifestFile,
            sharedRootHint = sharedRuntimeRoot(),
        ).also { layout ->
            layout.directories().forEach { it.mkdirs() }
            layout.manifestFile.parentFile?.mkdirs()
        }
        return RuntimeLayoutSnapshot(
            privateRoot = privateRoot,
            stagedRoot = stagedRoot,
            payloadRoot = payloadRoot,
            nativeRoot = nativeRoot,
            injectorRoot = injectorRoot,
            scriptsRoot = scriptsRoot,
            metadataRoot = metadataRoot,
            logsRoot = logsRoot,
            manifestFile = manifestFile,
            sharedRootHint = sharedRuntimeRoot(),
        )
    }

    private fun defaultDescriptors(): List<RuntimeAssetDescriptor> {
        return listOf(
            RuntimeAssetDescriptor(
                logicalName = "java_payload_main",
                relativePath = "payload/2da3c574.s",
                assetRelativePath = null,
                kind = RuntimeArtifactKind.JAVA_PAYLOAD,
                versionTag = "phase1-demo",
                sourceTag = "round15-confirmed-entry",
            ),
            RuntimeAssetDescriptor(
                logicalName = "loader_init_arm64",
                relativePath = "native/libfl_init64.so",
                assetRelativePath = "runtime/native/libfl_init64.so",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.NATIVE_LOADER,
                versionTag = "phase1-native-v1",
                sourceTag = "native-runtime-cmake",
            ),
            RuntimeAssetDescriptor(
                logicalName = "loader_app_arm64",
                relativePath = "native/libfl_app64.so",
                assetRelativePath = "runtime/native/libfl_app64.so",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.NATIVE_LOADER,
                versionTag = "phase1-native-v1",
                sourceTag = "native-runtime-cmake",
            ),
            RuntimeAssetDescriptor(
                logicalName = "hook_bridge_arm64",
                relativePath = "native/liblh64.so",
                assetRelativePath = "runtime/native/liblh64.so",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.HOOK_BRIDGE,
                versionTag = "phase1-native-v1",
                sourceTag = "native-runtime-cmake",
            ),
            RuntimeAssetDescriptor(
                logicalName = "injector_arm64",
                relativePath = "bin/inj64",
                assetRelativePath = "runtime/bin/inj64",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.INJECTOR,
                versionTag = "phase1-native-v1",
                sourceTag = "native-runtime-cmake",
            ),
            RuntimeAssetDescriptor(
                logicalName = "hook_registry_seed",
                relativePath = "metadata/hook-registry-seed.txt",
                kind = RuntimeArtifactKind.METADATA,
                versionTag = "phase1-demo",
                sourceTag = "payload-bridge-seed",
            ),
            RuntimeAssetDescriptor(
                logicalName = "mock_location_state",
                relativePath = "metadata/mock-location-state.txt",
                kind = RuntimeArtifactKind.METADATA,
                versionTag = "phase1-shared-state-v1",
                sourceTag = "control-plane-shared-metadata",
            ),
        )
    }

    private fun materializeAssetIfPresent(
        descriptor: RuntimeAssetDescriptor,
        targetFile: File,
    ): Boolean {
        val assetPath = descriptor.assetRelativePath ?: return false
        return try {
            context.assets.open(assetPath).use { input ->
                BufferedInputStream(input).use { buffered ->
                    FileOutputStream(targetFile).use { output ->
                        buffered.copyTo(output)
                    }
                }
            }
            if (descriptor.kind == RuntimeArtifactKind.INJECTOR) {
                targetFile.setExecutable(true, false)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildArtifactContent(descriptor: RuntimeAssetDescriptor): String {
        if (descriptor.kind == RuntimeArtifactKind.INJECTOR) {
            return buildInjectorStubContent(descriptor)
        }
        if (descriptor.logicalName == "hook_registry_seed") {
            return buildHookRegistrySeedContent()
        }
        if (descriptor.logicalName == "mock_location_state") {
            return buildMockStateContent(
                MockSessionState(),
            )
        }
        return buildPlaceholderContent(descriptor)
    }

    private fun buildHookRegistrySeedContent(): String {
        return buildString {
            appendLine("version=phase1-seed-v1")
            appendLine("process=system_server stage=init hook=com.android.server.location.LocationManagerService#getLastLocation")
            appendLine("process=com.android.phone stage=appHook hook=com.android.phone.PhoneInterfaceManager#getAllCellInfo")
            appendLine("process=app stage=compat hook=android.net.wifi.WifiManager#getScanResults")
            appendLine("note=seed file consumed by native hook bridge and loader stubs")
            appendLine("generatedAtMillis=${System.currentTimeMillis()}")
        }
    }

    fun buildMockStateContent(state: MockSessionState): String {
        val location = state.currentLocation
        val wifi = state.currentWifiProfile
        val primaryCell = state.currentCells.firstOrNull()
        return buildString {
            appendLine("version=phase1-mock-state-v1")
            appendLine("location_active=${if (state.toggles.locationEnabled && location != null) 1 else 0}")
            appendLine("latitude=${location?.latitude ?: 31.2304}")
            appendLine("longitude=${location?.longitude ?: 121.4737}")
            appendLine("altitude=${location?.altitudeMeters ?: 12.0}")
            appendLine("accuracy=${location?.accuracyMeters ?: 8f}")
            appendLine("provider=${location?.provider ?: "gps"}")
            appendLine("location_timestamp_millis=${state.lastUpdatedAtMillis}")
            appendLine("wifi_active=${if (state.toggles.wifiEnabled && wifi != null) 1 else 0}")
            appendLine("wifi_ssid=${wifi?.ssid ?: ""}")
            appendLine("wifi_bssid=${wifi?.bssid ?: ""}")
            appendLine("wifi_frequency_mhz=${wifi?.frequencyMhz ?: 0}")
            appendLine("wifi_rssi_dbm=${wifi?.rssiDbm ?: 0}")
            appendLine("cells_active=${if (state.toggles.cellsEnabled && state.currentCells.isNotEmpty()) 1 else 0}")
            appendLine("cells_count=${state.currentCells.size}")
            appendLine("cell_primary_mcc=${primaryCell?.mcc ?: 0}")
            appendLine("cell_primary_mnc=${primaryCell?.mnc ?: 0}")
            appendLine("cell_primary_lac_or_tac=${primaryCell?.lacOrTac ?: 0}")
            appendLine("cell_primary_cid_or_nci=${primaryCell?.cidOrNci ?: 0L}")
            appendLine("cells_payload=${encodeCells(state.currentCells)}")
            appendLine("updatedAtMillis=${System.currentTimeMillis()}")
        }
    }

    private fun encodeCells(cells: List<MockCellRecord>): String {
        return cells.joinToString("|") { cell ->
            listOf(
                cell.mcc,
                cell.mnc,
                cell.lacOrTac,
                cell.cidOrNci,
            ).joinToString(",")
        }
    }

    private fun buildPlaceholderContent(descriptor: RuntimeAssetDescriptor): String {
        return buildString {
            appendLine("placeholder=true")
            appendLine("logicalName=${descriptor.logicalName}")
            appendLine("relativePath=${descriptor.relativePath}")
            appendLine("kind=${descriptor.kind}")
            appendLine("abi=${descriptor.abi ?: "none"}")
            appendLine("versionTag=${descriptor.versionTag}")
            appendLine("sourceTag=${descriptor.sourceTag}")
            appendLine("packageName=${context.packageName}")
            appendLine("sdkInt=${Build.VERSION.SDK_INT}")
            appendLine("supported64BitAbis=${Build.SUPPORTED_64_BIT_ABIS.joinToString()}")
            appendLine("supported32BitAbis=${Build.SUPPORTED_32_BIT_ABIS.joinToString()}")
            appendLine("generatedAtMillis=${System.currentTimeMillis()}")
        }
    }

    private fun buildInjectorStubContent(descriptor: RuntimeAssetDescriptor): String {
        return """
            |#!/system/bin/sh
            |set -eu
            |
            |TARGET_PROCESS=""
            |STAGE=""
            |ABI=""
            |LOADER=""
            |HOOK_BRIDGE=""
            |PAYLOAD=""
            |ENTRYPOINT=""
            |LOG_FILE=""
            |DRY_RUN=0
            |
            |while [ ${'$'}# -gt 0 ]; do
            |  case "${'$'}1" in
            |    --target-process)
            |      TARGET_PROCESS="${'$'}2"
            |      shift 2
            |      ;;
            |    --stage)
            |      STAGE="${'$'}2"
            |      shift 2
            |      ;;
            |    --abi)
            |      ABI="${'$'}2"
            |      shift 2
            |      ;;
            |    --loader)
            |      LOADER="${'$'}2"
            |      shift 2
            |      ;;
            |    --hook-bridge)
            |      HOOK_BRIDGE="${'$'}2"
            |      shift 2
            |      ;;
            |    --payload)
            |      PAYLOAD="${'$'}2"
            |      shift 2
            |      ;;
            |    --entry)
            |      ENTRYPOINT="${'$'}2"
            |      shift 2
            |      ;;
            |    --log-file)
            |      LOG_FILE="${'$'}2"
            |      shift 2
            |      ;;
            |    --dry-run)
            |      DRY_RUN=1
            |      shift
            |      ;;
            |    *)
            |      echo "inj64-stub: unknown argument: ${'$'}1" >&2
            |      exit 64
            |      ;;
            |  esac
            |done
            |
            |require_value() {
            |  NAME="${'$'}1"
            |  VALUE="${'$'}2"
            |  if [ -z "${'$'}VALUE" ]; then
            |    echo "inj64-stub: missing required argument ${'$'}NAME" >&2
            |    exit 65
            |  fi
            |}
            |
            |require_file() {
            |  PATH_VALUE="${'$'}1"
            |  LABEL="${'$'}2"
            |  if [ ! -f "${'$'}PATH_VALUE" ]; then
            |    echo "inj64-stub: missing ${'$'}LABEL file: ${'$'}PATH_VALUE" >&2
            |    exit 66
            |  fi
            |}
            |
            |require_value "--target-process" "${'$'}TARGET_PROCESS"
            |require_value "--stage" "${'$'}STAGE"
            |require_value "--abi" "${'$'}ABI"
            |require_value "--loader" "${'$'}LOADER"
            |require_value "--hook-bridge" "${'$'}HOOK_BRIDGE"
            |require_value "--payload" "${'$'}PAYLOAD"
            |require_value "--entry" "${'$'}ENTRYPOINT"
            |
            |require_file "${'$'}PAYLOAD" "payload"
            |require_file "${'$'}LOADER" "loader"
            |require_file "${'$'}HOOK_BRIDGE" "hook bridge"
            |
            |log_phase() {
            |  PHASE_NAME="${'$'}1"
            |  PHASE_STATUS="${'$'}2"
            |  PHASE_DETAIL="${'$'}3"
            |  TS="$(date +%s 2>/dev/null || echo 0)"
            |  echo "phase=${'$'}PHASE_NAME status=${'$'}PHASE_STATUS ts=${'$'}TS detail=${'$'}PHASE_DETAIL" | tee -a "${'$'}LOG_FILE"
            |}
            |
            |PID="$(pidof "${'$'}TARGET_PROCESS" 2>/dev/null | awk '{print ${'$'}1}')"
            |SELINUX="$(getenforce 2>/dev/null || echo unknown)"
            |if [ -z "${'$'}LOG_FILE" ]; then
            |  SAFE_NAME="$(echo "${'$'}TARGET_PROCESS" | tr '.:' '__')"
            |  LOG_FILE="/data/fl/logs/inj64_${'$'}SAFE_NAME_${'$'}STAGE.log"
            |fi
            |
            |mkdir -p "$(dirname "${'$'}LOG_FILE")" 2>/dev/null || true
            |{
            |  echo "[inj64-stub] logicalName=${descriptor.logicalName}"
            |  echo "target_process=${'$'}TARGET_PROCESS"
            |  echo "stage=${'$'}STAGE"
            |  echo "abi=${'$'}ABI"
            |  echo "entrypoint=${'$'}ENTRYPOINT"
            |  echo "selinux=${'$'}SELINUX"
            |  echo "pid=${'$'}PID"
            |  echo "loader=${'$'}LOADER"
            |  echo "hook_bridge=${'$'}HOOK_BRIDGE"
            |  echo "payload=${'$'}PAYLOAD"
            |  echo "generated_by=${context.packageName}"
            |  echo "generated_at=${System.currentTimeMillis()}"
            |} >> "${'$'}LOG_FILE"
            |
            |if [ -z "${'$'}PID" ]; then
            |  log_phase attach failed "target process missing"
            |  echo "inj64-stub: target process not running: ${'$'}TARGET_PROCESS" >&2
            |  exit 67
            |fi
            |
            |if [ "${'$'}DRY_RUN" = "1" ]; then
            |  log_phase preflight ok "dry-run only"
            |  echo "inj64-stub dry-run ok target=${'$'}TARGET_PROCESS pid=${'$'}PID stage=${'$'}STAGE selinux=${'$'}SELINUX log=${'$'}LOG_FILE"
            |  exit 0
            |fi
            |
            |if [ "${'$'}SELINUX" = "Enforcing" ]; then
            |  echo "inj64-stub: warning selinux enforcing; original workflow usually expects permissive" | tee -a "${'$'}LOG_FILE"
            |fi
            |
            |log_phase attach ok "pid=${'$'}PID target=${'$'}TARGET_PROCESS"
            |sleep 1
            |log_phase loader_prepare ok "loader=${'$'}LOADER abi=${'$'}ABI"
            |sleep 1
            |if grep -q '^placeholder=true' "${'$'}LOADER" 2>/dev/null; then
            |  log_phase loader_prepare warning "loader artifact is placeholder"
            |fi
            |log_phase hook_bridge_prepare ok "bridge=${'$'}HOOK_BRIDGE"
            |sleep 1
            |if grep -q '^placeholder=true' "${'$'}HOOK_BRIDGE" 2>/dev/null; then
            |  log_phase hook_bridge_prepare warning "hook bridge artifact is placeholder"
            |fi
            |log_phase payload_resolve ok "payload=${'$'}PAYLOAD"
            |sleep 1
            |if grep -q '^placeholder=true' "${'$'}PAYLOAD" 2>/dev/null; then
            |  log_phase payload_resolve warning "payload artifact is placeholder"
            |fi
            |log_phase entry_dispatch ok "entry=${'$'}ENTRYPOINT stage=${'$'}STAGE"
            |sleep 1
            |log_phase finalize ok "marker will be written"
            |
            |SAFE_NAME="$(echo "${'$'}TARGET_PROCESS" | tr '.:' '__')"
            |MARKER_FILE="/data/fl/logs/last_injection_${'$'}SAFE_NAME_${'$'}STAGE.txt"
            |{
            |  echo "status=stubbed_state_machine"
            |  echo "target_process=${'$'}TARGET_PROCESS"
            |  echo "pid=${'$'}PID"
            |  echo "stage=${'$'}STAGE"
            |  echo "entrypoint=${'$'}ENTRYPOINT"
            |  echo "selinux=${'$'}SELINUX"
            |  echo "log_file=${'$'}LOG_FILE"
            |  echo "loader=${'$'}LOADER"
            |  echo "hook_bridge=${'$'}HOOK_BRIDGE"
            |  echo "payload=${'$'}PAYLOAD"
            |  echo "phases=attach,loader_prepare,hook_bridge_prepare,payload_resolve,entry_dispatch,finalize"
            |} > "${'$'}MARKER_FILE"
            |
            |log_phase marker ok "marker=${'$'}MARKER_FILE"
            |echo "inj64-stub execute ok target=${'$'}TARGET_PROCESS pid=${'$'}PID stage=${'$'}STAGE log=${'$'}LOG_FILE marker=${'$'}MARKER_FILE"
            |exit 0
        """.trimMargin()
    }

    private fun buildManifest(report: RuntimePreparationReport): String {
        return buildString {
            appendLine("manifestVersion=${report.manifestVersion}")
            appendLine("generatedAtMillis=${report.generatedAtMillis}")
            appendLine("privateRoot=${report.layout.privateRoot.absolutePath}")
            appendLine("sharedRootHint=${report.layout.sharedRootHint.absolutePath}")
            appendLine("artifactCount=${report.artifacts.size}")
            appendLine()
            report.artifacts.forEach { artifact ->
                appendLine("[artifact]")
                appendLine("logicalName=${artifact.descriptor.logicalName}")
                appendLine("relativePath=${artifact.descriptor.relativePath}")
                appendLine("privateFile=${artifact.privateFile.absolutePath}")
                appendLine("sharedPathHint=${artifact.sharedPathHint}")
                appendLine("kind=${artifact.descriptor.kind}")
                appendLine("abi=${artifact.descriptor.abi ?: "none"}")
                appendLine("sizeBytes=${artifact.sizeBytes}")
                appendLine("sha256=${artifact.sha256}")
                appendLine("placeholder=${artifact.isPlaceholder}")
                appendLine()
            }
        }
    }

    private fun isRuntimeMutableArtifact(logicalName: String): Boolean {
        return logicalName == "mock_location_state"
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

class HiddenApiController {
    fun applyBestEffortExemptions(): Boolean {
        return try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val setHiddenApiExemptions =
                vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            getRuntime.isAccessible = true
            setHiddenApiExemptions.isAccessible = true
            val runtime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
            true
        } catch (_: Throwable) {
            false
        }
    }
}
