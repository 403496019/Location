package dev.lerist.fakelocation.injector

import dev.lerist.fakelocation.core.model.TargetProcessRole
import dev.lerist.fakelocation.core.runtime.RuntimeAssetManager
import dev.lerist.fakelocation.core.runtime.RuntimeIntegrityReport
import dev.lerist.fakelocation.core.runtime.RuntimePreparationReport
import dev.lerist.fakelocation.core.runtime.StagedRuntimeArtifact

enum class InjectionStage {
    INIT_STAGE,
    APP_HOOK_STAGE,
}

data class InjectionPlan(
    val processName: String,
    val role: TargetProcessRole,
    val stage: InjectionStage,
    val abi: String,
    val nativeLoaderName: String,
    val javaEntrypoint: String,
    val loaderLogicalName: String,
    val payloadLogicalName: String = "java_payload_main",
    val hookBridgeLogicalName: String = "hook_bridge_arm64",
    val injectorLogicalName: String = "injector_arm64",
)

data class InjectionTask(
    val plan: InjectionPlan,
    val payloadArtifact: StagedRuntimeArtifact,
    val loaderArtifact: StagedRuntimeArtifact,
    val hookBridgeArtifact: StagedRuntimeArtifact,
    val injectorArtifact: StagedRuntimeArtifact,
    val dryRunCommand: String,
    val requiresRoot: Boolean,
    val notes: List<String>,
)

data class InjectionPreflightReport(
    val runtimeIntegrity: RuntimeIntegrityReport,
    val injectorEnvironment: InjectorEnvironment,
    val taskCount: Int,
    val rootRequired: Boolean,
    val rootAvailable: Boolean,
    val placeholderArtifactsPresent: Boolean,
    val canAttemptMirrorSync: Boolean,
    val canAttemptInjection: Boolean,
    val blockingIssues: List<String>,
    val warnings: List<String>,
    val recommendations: List<String>,
    val generatedAtMillis: Long,
)

class DefaultInjectionOrchestrator(
    private val runtimeAssetManager: RuntimeAssetManager,
    private val shellExecutor: AndroidShellExecutor,
) {
    fun warmUpRuntime(): RuntimePreparationReport = runtimeAssetManager.prepareRuntimeLayout()

    fun defaultPlans(): List<InjectionPlan> {
        return listOf(
            InjectionPlan(
                processName = "system_server",
                role = TargetProcessRole.SYSTEM_SERVER,
                stage = InjectionStage.INIT_STAGE,
                abi = "arm64-v8a",
                nativeLoaderName = "libfl_init64.so",
                javaEntrypoint = "init",
                loaderLogicalName = "loader_init_arm64",
            ),
            InjectionPlan(
                processName = "com.android.phone",
                role = TargetProcessRole.PHONE_SUBSYSTEM,
                stage = InjectionStage.APP_HOOK_STAGE,
                abi = "arm64-v8a",
                nativeLoaderName = "libfl_app64.so",
                javaEntrypoint = "appHook",
                loaderLogicalName = "loader_app_arm64",
            ),
        )
    }

    fun buildInjectionTasks(report: RuntimePreparationReport): List<InjectionTask> {
        return defaultPlans().map { plan ->
            val payloadArtifact = requireArtifact(report, plan.payloadLogicalName)
            val loaderArtifact = requireArtifact(report, plan.loaderLogicalName)
            val hookBridgeArtifact = requireArtifact(report, plan.hookBridgeLogicalName)
            val injectorArtifact = requireArtifact(report, plan.injectorLogicalName)
            val notes = buildList {
                add("stage=${plan.stage}")
                add("entrypoint=${plan.javaEntrypoint}")
                add("payload=${payloadArtifact.sharedPathHint}")
                add("loader=${loaderArtifact.sharedPathHint}")
                add("hookBridge=${hookBridgeArtifact.sharedPathHint}")
                add("injector=${injectorArtifact.sharedPathHint}")
                if (plan.role == TargetProcessRole.SYSTEM_SERVER) {
                    add("priority=highest")
                }
                if (payloadArtifact.isPlaceholder || loaderArtifact.isPlaceholder) {
                    add("placeholderArtifacts=true")
                }
            }
            InjectionTask(
                plan = plan,
                payloadArtifact = payloadArtifact,
                loaderArtifact = loaderArtifact,
                hookBridgeArtifact = hookBridgeArtifact,
                injectorArtifact = injectorArtifact,
                dryRunCommand = buildDryRunCommand(
                    plan = plan,
                    payloadArtifact = payloadArtifact,
                    loaderArtifact = loaderArtifact,
                    hookBridgeArtifact = hookBridgeArtifact,
                    injectorArtifact = injectorArtifact,
                ),
                requiresRoot = true,
                notes = notes,
            )
        }
    }

    fun probeInjectorEnvironment(): InjectorEnvironment = shellExecutor.probeEnvironment()

    fun buildPreflightReport(report: RuntimePreparationReport): InjectionPreflightReport {
        val integrity = runtimeAssetManager.verifyPreparationReport(report)
        val environment = probeInjectorEnvironment()
        val tasks = buildInjectionTasks(report)
        val blockingIssues = buildList {
            if (!integrity.manifestExists) {
                add("runtime manifest missing")
            }
            if (!integrity.allPresent) {
                add("one or more staged runtime artifacts are missing")
            }
            if (!integrity.allNonEmpty) {
                add("one or more staged runtime artifacts are empty")
            }
            if (!integrity.allHashesMatch) {
                add("one or more staged runtime artifacts failed hash verification")
            }
            if (!environment.hasSuBinary) {
                add("su binary not found; root-required mirror sync and injection cannot run")
            }
        }
        val warnings = buildList {
            if (integrity.placeholderArtifactCount > 0) {
                add("runtime still contains placeholder artifacts")
            }
            if (tasks.any { it.plan.role == TargetProcessRole.SYSTEM_SERVER }) {
                add("system_server injection remains high-risk and should stay behind preflight gating")
            }
        }
        val recommendations = buildList {
            if (!environment.hasSuBinary) {
                add("provide a rooted test device with working su access")
            }
            if (integrity.placeholderArtifactCount > 0) {
                add("replace placeholder inj64/libfl_init64/libfl_app64/liblh64 artifacts before real injection")
            }
            if (!integrity.allHashesMatch || !integrity.allPresent || !integrity.allNonEmpty) {
                add("re-run runtime preparation and verify staged artifacts before continuing")
            }
            add("run runtime mirror sync before attempting injection tasks")
        }
        return InjectionPreflightReport(
            runtimeIntegrity = integrity,
            injectorEnvironment = environment,
            taskCount = tasks.size,
            rootRequired = tasks.any { it.requiresRoot },
            rootAvailable = environment.hasSuBinary,
            placeholderArtifactsPresent = integrity.placeholderArtifactCount > 0,
            canAttemptMirrorSync = environment.hasSuBinary && integrity.allPresent && integrity.allNonEmpty,
            canAttemptInjection = environment.hasSuBinary &&
                integrity.allPresent &&
                integrity.allNonEmpty &&
                integrity.allHashesMatch,
            blockingIssues = blockingIssues,
            warnings = warnings,
            recommendations = recommendations,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun buildRuntimeMirrorSyncPlan(report: RuntimePreparationReport): RuntimeMirrorSyncPlan {
        val sharedRoot = report.layout.sharedRootHint.absolutePath
        val stagedRoot = report.layout.stagedRoot.absolutePath
        val directories = listOf(
            sharedRoot,
            "$sharedRoot/payload",
            "$sharedRoot/native",
            "$sharedRoot/bin",
            "$sharedRoot/metadata",
        )
        val mkdirCommands = directories.map { "mkdir -p ${shellQuote(it)}" }
        val copyCommands = report.artifacts.map { artifact ->
            "cp ${shellQuote(artifact.privateFile.absolutePath)} ${shellQuote(artifact.sharedPathHint)}"
        }
        val chmodCommands = buildList {
            add("chmod 755 ${shellQuote(sharedRoot)}")
            add("chmod 755 ${shellQuote("$sharedRoot/payload")}")
            add("chmod 755 ${shellQuote("$sharedRoot/native")}")
            add("chmod 755 ${shellQuote("$sharedRoot/bin")}")
            add("chmod 755 ${shellQuote("$sharedRoot/metadata")}")
            add("chmod 755 ${shellQuote("$sharedRoot/bin/inj64")}")
            add("chmod 644 ${shellQuote("$sharedRoot/payload/2da3c574.s")}")
            add("chmod 644 ${shellQuote("$sharedRoot/native/libfl_init64.so")}")
            add("chmod 644 ${shellQuote("$sharedRoot/native/libfl_app64.so")}")
            add("chmod 644 ${shellQuote("$sharedRoot/native/liblh64.so")}")
            add("chmod 644 ${shellQuote("$sharedRoot/metadata/hook-registry-seed.txt")}")
        }
        val ownershipCommands = buildList {
            add("chown root:root ${shellQuote(sharedRoot)}")
            add("chown root:root ${shellQuote("$sharedRoot/payload")}")
            add("chown root:root ${shellQuote("$sharedRoot/native")}")
            add("chown root:root ${shellQuote("$sharedRoot/bin")}")
            add("chown root:root ${shellQuote("$sharedRoot/metadata")}")
            report.artifacts.forEach { artifact ->
                add("chown root:root ${shellQuote(artifact.sharedPathHint)}")
            }
        }
        val selinuxCommands = listOf(
            "restorecon -RF ${shellQuote(sharedRoot)} || toybox restorecon -RF ${shellQuote(sharedRoot)} || true",
            "ls -lZ ${shellQuote(sharedRoot)} || true",
        )
        val verificationCommands = listOf(
            "test -f ${shellQuote("$sharedRoot/payload/2da3c574.s")}",
            "test -f ${shellQuote("$sharedRoot/native/libfl_init64.so")}",
            "test -f ${shellQuote("$sharedRoot/native/libfl_app64.so")}",
            "test -f ${shellQuote("$sharedRoot/native/liblh64.so")}",
            "test -f ${shellQuote("$sharedRoot/bin/inj64")}",
            "ls -R ${shellQuote(sharedRoot)}",
        )
        val allCommands = buildList {
            add("echo syncing_runtime_from=${shellQuote(stagedRoot)}")
            addAll(mkdirCommands)
            addAll(copyCommands)
            addAll(chmodCommands)
            addAll(ownershipCommands)
            addAll(selinuxCommands)
            addAll(verificationCommands)
        }
        return RuntimeMirrorSyncPlan(
            mkdirCommands = mkdirCommands,
            copyCommands = copyCommands,
            chmodCommands = chmodCommands,
            ownershipCommands = ownershipCommands,
            selinuxCommands = selinuxCommands,
            verificationCommands = verificationCommands,
            finalCommand = allCommands.joinToString(" && "),
        )
    }

    fun executeRuntimeMirrorSync(report: RuntimePreparationReport): RuntimeMirrorSyncResult {
        val plan = buildRuntimeMirrorSyncPlan(report)
        return RuntimeMirrorSyncResult(
            plan = plan,
            report = shellExecutor.execute(
                command = plan.finalCommand,
                preferRoot = true,
                timeoutMs = 12_000,
            ),
        )
    }

    fun executeDryRunTasks(report: RuntimePreparationReport): List<InjectionTaskExecution> {
        return buildInjectionTasks(report).map { task ->
            InjectionTaskExecution(
                task = task,
                report = shellExecutor.execute(
                    command = task.dryRunCommand,
                    preferRoot = task.requiresRoot,
                ),
            )
        }
    }

    fun executeEnvironmentProbe(preferRoot: Boolean): CommandExecutionReport {
        val probeCommand = if (preferRoot) "id && getenforce" else "id"
        return shellExecutor.execute(
            command = probeCommand,
            preferRoot = preferRoot,
        )
    }

    private fun requireArtifact(
        report: RuntimePreparationReport,
        logicalName: String,
    ): StagedRuntimeArtifact {
        return report.artifacts.firstOrNull { it.descriptor.logicalName == logicalName }
            ?: error("Missing staged runtime artifact: $logicalName")
    }

    private fun buildDryRunCommand(
        plan: InjectionPlan,
        payloadArtifact: StagedRuntimeArtifact,
        loaderArtifact: StagedRuntimeArtifact,
        hookBridgeArtifact: StagedRuntimeArtifact,
        injectorArtifact: StagedRuntimeArtifact,
    ): String {
        return buildString {
            append(injectorArtifact.sharedPathHint)
            append(" --target-process ")
            append(plan.processName)
            append(" --stage ")
            append(plan.stage.name.lowercase())
            append(" --abi ")
            append(plan.abi)
            append(" --loader ")
            append(loaderArtifact.sharedPathHint)
            append(" --hook-bridge ")
            append(hookBridgeArtifact.sharedPathHint)
            append(" --payload ")
            append(payloadArtifact.sharedPathHint)
            append(" --entry ")
            append(plan.javaEntrypoint)
            append(" --dry-run")
        }
    }
}
