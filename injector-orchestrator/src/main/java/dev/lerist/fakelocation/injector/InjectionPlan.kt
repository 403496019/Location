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
    val rootScript: GeneratedRootScript,
    val dryRunCommand: String,
    val executeCommand: String,
    val logFilePath: String,
    val markerFilePath: String,
    val planFilePath: String,
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
    val nativeBinaryReadiness: List<String>,
    val blockingIssues: List<String>,
    val warnings: List<String>,
    val recommendations: List<String>,
    val generatedAtMillis: Long,
)

class DefaultInjectionOrchestrator(
    private val runtimeAssetManager: RuntimeAssetManager,
    private val shellExecutor: AndroidShellExecutor,
) {
    private var lastScriptBundle: RootScriptBundle? = null

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
        val scriptBundle = buildRootScriptBundle(report)
        return defaultPlans().map { plan ->
            val payloadArtifact = requireArtifact(report, plan.payloadLogicalName)
            val loaderArtifact = requireArtifact(report, plan.loaderLogicalName)
            val hookBridgeArtifact = requireArtifact(report, plan.hookBridgeLogicalName)
            val injectorArtifact = requireArtifact(report, plan.injectorLogicalName)
            val rootScript = requireTaskScript(scriptBundle, plan.processName)
            val safeProcess = safeProcessName(plan.processName)
            val logFilePath = "/data/fl/logs/${scriptNameForPlan(plan).removeSuffix(".sh")}.log"
            val markerFilePath =
                "/data/fl/logs/last_injection_${safeProcess}_${plan.stage.name.lowercase()}.txt"
            val planFilePath =
                "/data/fl/logs/injection_plan_${safeProcess}_${plan.stage.name.lowercase()}.txt"
            val notes = buildList {
                add("stage=${plan.stage}")
                add("entrypoint=${plan.javaEntrypoint}")
                add("payload=${payloadArtifact.sharedPathHint}")
                add("loader=${loaderArtifact.sharedPathHint}")
                add("hookBridge=${hookBridgeArtifact.sharedPathHint}")
                add("injector=${injectorArtifact.sharedPathHint}")
                add("script=${rootScript.sharedPathHint}")
                add("logFile=$logFilePath")
                add("markerFile=$markerFilePath")
                add("planFile=$planFilePath")
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
                rootScript = rootScript,
                dryRunCommand = "sh ${shellQuote(rootScript.sharedPathHint)} dry-run",
                executeCommand = "sh ${shellQuote(rootScript.sharedPathHint)} execute",
                logFilePath = logFilePath,
                markerFilePath = markerFilePath,
                planFilePath = planFilePath,
                requiresRoot = true,
                notes = notes,
            )
        }
    }

    fun probeInjectorEnvironment(): InjectorEnvironment = shellExecutor.probeEnvironment()

    fun buildRootScriptBundle(report: RuntimePreparationReport): RootScriptBundle {
        lastScriptBundle?.let { bundle ->
            if (bundle.scriptRoot == report.layout.scriptsRoot && bundle.scripts.all { it.privateFile.exists() }) {
                return bundle
            }
        }
        val scriptRoot = report.layout.scriptsRoot
        scriptRoot.mkdirs()

        val taskScripts = defaultPlans().map { plan ->
            writeScript(
                root = scriptRoot,
                sharedRootHint = report.layout.sharedRootHint.absolutePath,
                name = scriptNameForPlan(plan),
                purpose = "inject ${plan.processName} using ${plan.stage}",
                targetProcess = plan.processName,
                content = buildInjectionScript(report, plan),
            )
        }
        val syncScript = writeScript(
            root = scriptRoot,
            sharedRootHint = report.layout.sharedRootHint.absolutePath,
            name = "sync_runtime.sh",
            purpose = "sync staged runtime into /data/fl mirror",
            content = buildSyncScript(report, taskScripts),
        )
        return RootScriptBundle(
            scriptRoot = scriptRoot,
            sharedScriptRootHint = "${report.layout.sharedRootHint.absolutePath}/scripts",
            scripts = listOf(syncScript) + taskScripts,
            generatedAtMillis = System.currentTimeMillis(),
        ).also { lastScriptBundle = it }
    }

    fun getLastScriptBundle(): RootScriptBundle? = lastScriptBundle

    fun buildPreflightReport(report: RuntimePreparationReport): InjectionPreflightReport {
        val integrity = runtimeAssetManager.verifyPreparationReport(report)
        val environment = probeInjectorEnvironment()
        val tasks = buildInjectionTasks(report)
        val nativeBinaryReadiness = buildNativeBinaryReadiness(report)
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
            nativeBinaryReadiness
                .filter { it.contains("not_elf") || it.contains("missing") }
                .forEach { add(it) }
        }
        val warnings = buildList {
            if (integrity.placeholderArtifactCount > 0) {
                add("runtime still contains placeholder artifacts")
            }
            if (tasks.any { it.plan.role == TargetProcessRole.SYSTEM_SERVER }) {
                add("system_server injection remains high-risk and should stay behind preflight gating")
            }
            nativeBinaryReadiness
                .filter { it.contains("elf_ok") || it.contains("empty") }
                .forEach { add(it) }
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
            if (nativeBinaryReadiness.any { it.contains("not_elf") || it.contains("missing") }) {
                add("replace non-ELF native artifacts before attempting remote injection")
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
                integrity.allHashesMatch &&
                nativeBinaryReadiness.none { it.contains("not_elf") || it.contains("missing") },
            nativeBinaryReadiness = nativeBinaryReadiness,
            blockingIssues = blockingIssues,
            warnings = warnings,
            recommendations = recommendations,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun buildRuntimeMirrorSyncPlan(report: RuntimePreparationReport): RuntimeMirrorSyncPlan {
        val scriptBundle = buildRootScriptBundle(report)
        val syncScript = scriptBundle.scripts.firstOrNull { it.name == "sync_runtime.sh" }
            ?: error("Missing sync_runtime.sh script")
        val sharedRoot = report.layout.sharedRootHint.absolutePath
        val directories = listOf(
            sharedRoot,
            "$sharedRoot/payload",
            "$sharedRoot/native",
            "$sharedRoot/bin",
            "$sharedRoot/scripts",
            "$sharedRoot/metadata",
            "$sharedRoot/logs",
        )
        val mkdirCommands = directories.map { "mkdir -p ${shellQuote(it)}" }
        val artifactCopyCommands = report.artifacts.map { artifact ->
            "cp ${shellQuote(artifact.privateFile.absolutePath)} ${shellQuote(artifact.sharedPathHint)}"
        }
        val scriptCopyCommands = scriptBundle.scripts.map { script ->
            "cp ${shellQuote(script.privateFile.absolutePath)} ${shellQuote(script.sharedPathHint)}"
        }
        val copyCommands = artifactCopyCommands + scriptCopyCommands
        val chmodCommands = buildList {
            add("chmod 755 ${shellQuote(sharedRoot)}")
            add("chmod 755 ${shellQuote("$sharedRoot/payload")}")
            add("chmod 755 ${shellQuote("$sharedRoot/native")}")
            add("chmod 755 ${shellQuote("$sharedRoot/bin")}")
            add("chmod 755 ${shellQuote("$sharedRoot/scripts")}")
            add("chmod 755 ${shellQuote("$sharedRoot/metadata")}")
            add("chmod 755 ${shellQuote("$sharedRoot/logs")}")
            add("chmod 755 ${shellQuote("$sharedRoot/bin/inj64")}")
            scriptBundle.scripts.forEach { script ->
                add("chmod 755 ${shellQuote(script.sharedPathHint)}")
            }
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
            add("chown root:root ${shellQuote("$sharedRoot/scripts")}")
            add("chown root:root ${shellQuote("$sharedRoot/metadata")}")
            add("chown root:root ${shellQuote("$sharedRoot/logs")}")
            report.artifacts.forEach { artifact ->
                add("chown root:root ${shellQuote(artifact.sharedPathHint)}")
            }
            scriptBundle.scripts.forEach { script ->
                add("chown root:root ${shellQuote(script.sharedPathHint)}")
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
            *scriptBundle.scripts.map { script ->
                "test -f ${shellQuote(script.sharedPathHint)}"
            }.toTypedArray(),
            "ls -R ${shellQuote(sharedRoot)}",
        )
        return RuntimeMirrorSyncPlan(
            mkdirCommands = mkdirCommands,
            copyCommands = copyCommands,
            chmodCommands = chmodCommands,
            ownershipCommands = ownershipCommands,
            selinuxCommands = selinuxCommands,
            verificationCommands = verificationCommands,
            finalCommand = "sh ${shellQuote(syncScript.privateFile.absolutePath)} apply",
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
        return executeTasks(report, RootScriptMode.DRY_RUN)
    }

    fun executeTasks(
        report: RuntimePreparationReport,
        mode: RootScriptMode,
    ): List<InjectionTaskExecution> {
        return buildInjectionTasks(report).map { task ->
            val command = when (mode) {
                RootScriptMode.DRY_RUN ->
                    "sh ${shellQuote(task.rootScript.privateFile.absolutePath)} ${mode.argument}"
                RootScriptMode.EXECUTE -> task.executeCommand
            }
            val executionReport = shellExecutor.execute(
                command = command,
                preferRoot = task.requiresRoot,
                timeoutMs = if (mode == RootScriptMode.EXECUTE) 12_000 else 8_000,
            )
            InjectionTaskExecution(
                task = task,
                mode = mode,
                report = executionReport,
                artifactCapture = if (mode == RootScriptMode.EXECUTE) {
                    captureExecutionArtifacts(task)
                } else {
                    null
                },
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

    private fun requireTaskScript(
        bundle: RootScriptBundle,
        processName: String,
    ): GeneratedRootScript {
        return bundle.scripts.firstOrNull { it.targetProcess == processName }
            ?: error("Missing root script for process: $processName")
    }

    private fun scriptNameForPlan(plan: InjectionPlan): String {
        val safeProcess = safeProcessName(plan.processName)
        return "inject_${safeProcess}.sh"
    }

    private fun safeProcessName(processName: String): String {
        return processName.replace('.', '_').replace(':', '_')
    }

    private fun buildInjectorInvocation(
        plan: InjectionPlan,
        payloadArtifact: StagedRuntimeArtifact,
        loaderArtifact: StagedRuntimeArtifact,
        hookBridgeArtifact: StagedRuntimeArtifact,
        injectorArtifact: StagedRuntimeArtifact,
        useSharedPaths: Boolean,
    ): String {
        val payload = if (useSharedPaths) payloadArtifact.sharedPathHint else payloadArtifact.privateFile.absolutePath
        val loader = if (useSharedPaths) loaderArtifact.sharedPathHint else loaderArtifact.privateFile.absolutePath
        val hookBridge = if (useSharedPaths) hookBridgeArtifact.sharedPathHint else hookBridgeArtifact.privateFile.absolutePath
        val injector = if (useSharedPaths) injectorArtifact.sharedPathHint else injectorArtifact.privateFile.absolutePath
        return buildString {
            append(shellQuote(injector))
            append(" --target-process ")
            append(shellQuote(plan.processName))
            append(" --stage ")
            append(plan.stage.name.lowercase())
            append(" --abi ")
            append(shellQuote(plan.abi))
            append(" --loader ")
            append(shellQuote(loader))
            append(" --hook-bridge ")
            append(shellQuote(hookBridge))
            append(" --payload ")
            append(shellQuote(payload))
            append(" --entry ")
            append(shellQuote(plan.javaEntrypoint))
        }
    }

    private fun writeScript(
        root: java.io.File,
        sharedRootHint: String,
        name: String,
        purpose: String,
        targetProcess: String? = null,
        content: String,
    ): GeneratedRootScript {
        val file = java.io.File(root, name)
        file.writeText(content)
        file.setExecutable(true, false)
        return GeneratedRootScript(
            name = name,
            privateFile = file,
            sharedPathHint = "$sharedRootHint/scripts/$name",
            purpose = purpose,
            targetProcess = targetProcess,
        )
    }

    private fun buildSyncScript(
        report: RuntimePreparationReport,
        taskScripts: List<GeneratedRootScript>,
    ): String {
        val plan = buildRuntimeMirrorSyncPlanContent(report, taskScripts)
        return """
            |#!/system/bin/sh
            |set -eu
            |
            |MODE="${'$'}{1:-apply}"
            |echo "[sync_runtime] mode=${'$'}MODE"
            |if [ "${'$'}MODE" = "dry-run" ]; then
            |  echo ${shellQuote(plan.joinToString(" && "))}
            |  exit 0
            |fi
            |
            |${plan.joinToString("\n")}
        """.trimMargin()
    }

    private fun buildRuntimeMirrorSyncPlanContent(
        report: RuntimePreparationReport,
        taskScripts: List<GeneratedRootScript>,
    ): List<String> {
        val sharedRoot = report.layout.sharedRootHint.absolutePath
        val directories = listOf(
            sharedRoot,
            "$sharedRoot/payload",
            "$sharedRoot/native",
            "$sharedRoot/bin",
            "$sharedRoot/scripts",
            "$sharedRoot/metadata",
            "$sharedRoot/logs",
        )
        val mkdirCommands = directories.map { "mkdir -p ${shellQuote(it)}" }
        val artifactCopyCommands = report.artifacts.map { artifact ->
            "cp ${shellQuote(artifact.privateFile.absolutePath)} ${shellQuote(artifact.sharedPathHint)}"
        }
        val selfScript = "${report.layout.scriptsRoot.absolutePath}/sync_runtime.sh"
        val scriptCopyCommands = buildList {
            add("cp ${shellQuote(selfScript)} ${shellQuote("$sharedRoot/scripts/sync_runtime.sh")}")
            addAll(taskScripts.map { script ->
                "cp ${shellQuote(script.privateFile.absolutePath)} ${shellQuote(script.sharedPathHint)}"
            })
        }
        val chmodCommands = buildList {
            add("chmod 755 ${shellQuote(sharedRoot)}")
            add("chmod 755 ${shellQuote("$sharedRoot/payload")}")
            add("chmod 755 ${shellQuote("$sharedRoot/native")}")
            add("chmod 755 ${shellQuote("$sharedRoot/bin")}")
            add("chmod 755 ${shellQuote("$sharedRoot/scripts")}")
            add("chmod 755 ${shellQuote("$sharedRoot/metadata")}")
            add("chmod 755 ${shellQuote("$sharedRoot/logs")}")
            add("chmod 755 ${shellQuote("$sharedRoot/bin/inj64")}")
            add("chmod 755 ${shellQuote("$sharedRoot/scripts/sync_runtime.sh")}")
            taskScripts.forEach { script ->
                add("chmod 755 ${shellQuote(script.sharedPathHint)}")
            }
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
            add("chown root:root ${shellQuote("$sharedRoot/scripts")}")
            add("chown root:root ${shellQuote("$sharedRoot/metadata")}")
            add("chown root:root ${shellQuote("$sharedRoot/logs")}")
            report.artifacts.forEach { artifact ->
                add("chown root:root ${shellQuote(artifact.sharedPathHint)}")
            }
            add("chown root:root ${shellQuote("$sharedRoot/scripts/sync_runtime.sh")}")
            taskScripts.forEach { script ->
                add("chown root:root ${shellQuote(script.sharedPathHint)}")
            }
        }
        val selinuxCommands = listOf(
            "restorecon -RF ${shellQuote(sharedRoot)} || toybox restorecon -RF ${shellQuote(sharedRoot)} || true",
            "ls -lZ ${shellQuote(sharedRoot)} || true",
        )
        val verificationCommands = buildList {
            add("test -f ${shellQuote("$sharedRoot/payload/2da3c574.s")}")
            add("test -f ${shellQuote("$sharedRoot/native/libfl_init64.so")}")
            add("test -f ${shellQuote("$sharedRoot/native/libfl_app64.so")}")
            add("test -f ${shellQuote("$sharedRoot/native/liblh64.so")}")
            add("test -f ${shellQuote("$sharedRoot/bin/inj64")}")
            add("test -f ${shellQuote("$sharedRoot/scripts/sync_runtime.sh")}")
            taskScripts.forEach { script ->
                add("test -f ${shellQuote(script.sharedPathHint)}")
            }
            add("ls -R ${shellQuote(sharedRoot)}")
        }
        return buildList {
            add("echo syncing_runtime_from=${shellQuote(report.layout.stagedRoot.absolutePath)}")
            addAll(mkdirCommands)
            addAll(artifactCopyCommands)
            addAll(scriptCopyCommands)
            addAll(chmodCommands)
            addAll(ownershipCommands)
            addAll(selinuxCommands)
            addAll(verificationCommands)
        }
    }

    private fun buildInjectionScript(
        report: RuntimePreparationReport,
        plan: InjectionPlan,
    ): String {
        val payloadArtifact = requireArtifact(report, plan.payloadLogicalName)
        val loaderArtifact = requireArtifact(report, plan.loaderLogicalName)
        val hookBridgeArtifact = requireArtifact(report, plan.hookBridgeLogicalName)
        val injectorArtifact = requireArtifact(report, plan.injectorLogicalName)
        val sharedInvocation = buildInjectorInvocation(
            plan = plan,
            payloadArtifact = payloadArtifact,
            loaderArtifact = loaderArtifact,
            hookBridgeArtifact = hookBridgeArtifact,
            injectorArtifact = injectorArtifact,
            useSharedPaths = true,
        )
        val logFile = "/data/fl/logs/${scriptNameForPlan(plan).removeSuffix(".sh")}.log"
        return """
            |#!/system/bin/sh
            |set -eu
            |
            |MODE="${'$'}{1:-dry-run}"
            |TARGET_PID="$(pidof ${shellQuote(plan.processName)} 2>/dev/null | awk '{print ${'$'}1}')"
            |SELINUX="$(getenforce 2>/dev/null || echo unknown)"
            |LOG_FILE=${shellQuote(logFile)}
            |echo "[inject] process=${plan.processName} stage=${plan.stage} mode=${'$'}MODE"
            |CMD="${sharedInvocation} --log-file ${shellQuote(logFile)}"
            |
            |mkdir -p /data/fl/logs 2>/dev/null || true
            |echo "script_target=${plan.processName} stage=${plan.stage} mode=${'$'}MODE pid=${'$'}TARGET_PID selinux=${'$'}SELINUX" >> "${'$'}LOG_FILE"
            |
            |if [ -z "${'$'}TARGET_PID" ]; then
            |  echo "target process not running: ${plan.processName}" >&2
            |  exit 21
            |fi
            |
            |if [ ! -f ${shellQuote(payloadArtifact.sharedPathHint)} ]; then
            |  echo "missing payload: ${payloadArtifact.sharedPathHint}" >&2
            |  exit 22
            |fi
            |
            |if [ ! -f ${shellQuote(loaderArtifact.sharedPathHint)} ]; then
            |  echo "missing loader: ${loaderArtifact.sharedPathHint}" >&2
            |  exit 23
            |fi
            |
            |if [ ! -f ${shellQuote(hookBridgeArtifact.sharedPathHint)} ]; then
            |  echo "missing hook bridge: ${hookBridgeArtifact.sharedPathHint}" >&2
            |  exit 24
            |fi
            |
            |if [ "${'$'}MODE" = "dry-run" ]; then
            |  echo "${'$'}CMD --dry-run"
            |  exit 0
            |fi
            |
            |if [ ! -x ${shellQuote(injectorArtifact.sharedPathHint)} ]; then
            |  echo "missing injector: ${injectorArtifact.sharedPathHint}" >&2
            |  exit 20
            |fi
            |
            |if [ "${'$'}SELINUX" = "Enforcing" ]; then
            |  echo "warning: SELinux is enforcing; original flow commonly expects permissive" >> "${'$'}LOG_FILE"
            |fi
            |
            |exec ${'$'}CMD
        """.trimMargin()
    }

    private fun captureExecutionArtifacts(task: InjectionTask): ExecutionArtifactCapture {
        val inspection = shellExecutor.execute(
            command = buildArtifactInspectionCommand(task),
            preferRoot = true,
            timeoutMs = 8_000,
        )
        val stdout = inspection.stdout
        return ExecutionArtifactCapture(
            inspectionReport = inspection,
            logExcerpt = extractSection(stdout, "LOG"),
            markerContent = extractSection(stdout, "MARKER"),
            planContent = extractSection(stdout, "PLAN"),
        )
    }

    private fun buildArtifactInspectionCommand(task: InjectionTask): String {
        return """
            |if [ -f ${shellQuote(task.logFilePath)} ]; then
            |  echo '__FL_LOG_BEGIN__'
            |  tail -n 80 ${shellQuote(task.logFilePath)} || cat ${shellQuote(task.logFilePath)}
            |  echo '__FL_LOG_END__'
            |fi
            |if [ -f ${shellQuote(task.markerFilePath)} ]; then
            |  echo '__FL_MARKER_BEGIN__'
            |  cat ${shellQuote(task.markerFilePath)}
            |  echo '__FL_MARKER_END__'
            |fi
            |if [ -f ${shellQuote(task.planFilePath)} ]; then
            |  echo '__FL_PLAN_BEGIN__'
            |  cat ${shellQuote(task.planFilePath)}
            |  echo '__FL_PLAN_END__'
            |fi
        """.trimMargin().replace("\r\n", "\n")
    }

    private fun extractSection(
        stdout: String,
        kind: String,
    ): String? {
        val begin = "__FL_${kind}_BEGIN__"
        val end = "__FL_${kind}_END__"
        val startIndex = stdout.indexOf(begin)
        val endIndex = stdout.indexOf(end)
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            return null
        }
        return stdout.substring(startIndex + begin.length, endIndex)
            .trim()
            .ifBlank { null }
    }

    private fun buildNativeBinaryReadiness(report: RuntimePreparationReport): List<String> {
        return report.artifacts.mapNotNull { artifact ->
            when (artifact.descriptor.kind) {
                dev.lerist.fakelocation.core.runtime.RuntimeArtifactKind.NATIVE_LOADER,
                dev.lerist.fakelocation.core.runtime.RuntimeArtifactKind.HOOK_BRIDGE,
                dev.lerist.fakelocation.core.runtime.RuntimeArtifactKind.INJECTOR -> {
                    when {
                        !artifact.privateFile.exists() ->
                            "${artifact.descriptor.logicalName}: missing"
                        artifact.privateFile.length() <= 0L ->
                            "${artifact.descriptor.logicalName}: empty"
                        isElfFile(artifact.privateFile) ->
                            "${artifact.descriptor.logicalName}: elf_ok"
                        else ->
                            "${artifact.descriptor.logicalName}: not_elf"
                    }
                }
                else -> null
            }
        }
    }

    private fun isElfFile(file: java.io.File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 &&
                    header[0] == 0x7f.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }
}
