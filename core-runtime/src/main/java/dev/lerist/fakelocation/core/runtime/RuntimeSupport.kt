package dev.lerist.fakelocation.core.runtime

import android.content.Context
import java.io.File

data class RuntimeAssetDescriptor(
    val logicalName: String,
    val assetPath: String,
    val abi: String,
    val versionTag: String,
)

class RuntimeAssetManager(private val context: Context) {
    fun privateRuntimeRoot(): File = File(context.filesDir, "flrt")

    fun sharedRuntimeRoot(): File = File("/data/fl")

    fun prepareRuntimeLayout(): List<File> {
        val privateRoot = privateRuntimeRoot()
        val payloadRoot = File(privateRoot, "payload")
        val nativeRoot = File(privateRoot, "native")
        listOf(privateRoot, payloadRoot, nativeRoot).forEach { it.mkdirs() }
        return listOf(privateRoot, payloadRoot, nativeRoot)
    }
}

class HiddenApiController {
    fun applyBestEffortExemptions(): Boolean {
        // Phase 1 skeleton: the actual VMRuntime-based exemption flow will live here.
        return false
    }
}
