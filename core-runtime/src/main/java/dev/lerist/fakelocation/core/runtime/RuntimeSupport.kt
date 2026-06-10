package dev.lerist.fakelocation.core.runtime

import android.content.Context
import android.os.Build
import java.io.File
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

    fun getLastPreparationReport(): RuntimePreparationReport? = lastReport

    fun prepareRuntimeLayout(): RuntimePreparationReport {
        val layout = createLayout()
        val descriptors = defaultDescriptors()
        val artifacts = descriptors.map { descriptor ->
            val targetFile = File(layout.stagedRoot, descriptor.relativePath)
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(buildPlaceholderContent(descriptor))
            StagedRuntimeArtifact(
                descriptor = descriptor,
                privateFile = targetFile,
                sharedPathHint = File(layout.sharedRootHint, descriptor.relativePath).absolutePath,
                sizeBytes = targetFile.length(),
                sha256 = sha256Of(targetFile),
                isPlaceholder = true,
            )
        }
        val report = RuntimePreparationReport(
            layout = layout,
            artifacts = artifacts,
            manifestVersion = "phase1-runtime-v2",
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
                sha256Of(file) != artifact.sha256 -> ArtifactIntegrityReport(
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
                    actualSha256 = artifact.sha256,
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
        val metadataRoot = File(stagedRoot, "metadata")
        val logsRoot = File(privateRoot, "logs")
        val manifestFile = File(metadataRoot, "runtime-manifest-v2.txt")
        RuntimeLayoutSnapshot(
            privateRoot = privateRoot,
            stagedRoot = stagedRoot,
            payloadRoot = payloadRoot,
            nativeRoot = nativeRoot,
            injectorRoot = injectorRoot,
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
                kind = RuntimeArtifactKind.JAVA_PAYLOAD,
                versionTag = "phase1-demo",
                sourceTag = "round15-confirmed-entry",
            ),
            RuntimeAssetDescriptor(
                logicalName = "loader_init_arm64",
                relativePath = "native/libfl_init64.so",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.NATIVE_LOADER,
                versionTag = "phase1-demo",
                sourceTag = "analysis-aligned-placeholder",
            ),
            RuntimeAssetDescriptor(
                logicalName = "loader_app_arm64",
                relativePath = "native/libfl_app64.so",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.NATIVE_LOADER,
                versionTag = "phase1-demo",
                sourceTag = "analysis-aligned-placeholder",
            ),
            RuntimeAssetDescriptor(
                logicalName = "hook_bridge_arm64",
                relativePath = "native/liblh64.so",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.HOOK_BRIDGE,
                versionTag = "phase1-demo",
                sourceTag = "analysis-aligned-placeholder",
            ),
            RuntimeAssetDescriptor(
                logicalName = "injector_arm64",
                relativePath = "bin/inj64",
                abi = "arm64-v8a",
                kind = RuntimeArtifactKind.INJECTOR,
                versionTag = "phase1-demo",
                sourceTag = "analysis-aligned-placeholder",
            ),
            RuntimeAssetDescriptor(
                logicalName = "hook_registry_seed",
                relativePath = "metadata/hook-registry-seed.txt",
                kind = RuntimeArtifactKind.METADATA,
                versionTag = "phase1-demo",
                sourceTag = "payload-bridge-seed",
            ),
        )
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
