package dev.lerist.fakelocation.injector

import java.io.File
import java.util.concurrent.TimeUnit

enum class ShellKind {
    ROOT_SU,
    USER_SH,
}

data class InjectorEnvironment(
    val hasSuBinary: Boolean,
    val suBinaryPath: String?,
    val hasUserShell: Boolean,
)

data class CommandExecutionReport(
    val shellKind: ShellKind,
    val requestedRoot: Boolean,
    val command: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val timedOut: Boolean,
    val skipped: Boolean,
    val summary: String,
) {
    val success: Boolean
        get() = !timedOut && !skipped && exitCode == 0
}

data class InjectionTaskExecution(
    val task: InjectionTask,
    val mode: RootScriptMode,
    val report: CommandExecutionReport,
    val artifactCapture: ExecutionArtifactCapture? = null,
)

data class ExecutionArtifactCapture(
    val inspectionReport: CommandExecutionReport,
    val logExcerpt: String?,
    val markerContent: String?,
    val planContent: String?,
)

data class GeneratedRootScript(
    val name: String,
    val privateFile: File,
    val sharedPathHint: String,
    val purpose: String,
    val targetProcess: String? = null,
)

data class RootScriptBundle(
    val scriptRoot: File,
    val sharedScriptRootHint: String,
    val scripts: List<GeneratedRootScript>,
    val generatedAtMillis: Long,
)

data class RootScriptExecution(
    val script: GeneratedRootScript,
    val mode: RootScriptMode,
    val report: CommandExecutionReport,
)

enum class RootScriptMode(val argument: String) {
    DRY_RUN("dry-run"),
    EXECUTE("execute"),
}

data class RuntimeMirrorSyncPlan(
    val mkdirCommands: List<String>,
    val copyCommands: List<String>,
    val chmodCommands: List<String>,
    val ownershipCommands: List<String>,
    val selinuxCommands: List<String>,
    val verificationCommands: List<String>,
    val finalCommand: String,
)

data class RuntimeMirrorSyncResult(
    val plan: RuntimeMirrorSyncPlan,
    val report: CommandExecutionReport,
)

class AndroidShellExecutor {
    fun probeEnvironment(): InjectorEnvironment {
        val suPath = findExecutable(
            "/product/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )
        val shPath = findExecutable(
            "/system/bin/sh",
            "/system/xbin/sh",
        )
        return InjectorEnvironment(
            hasSuBinary = suPath != null,
            suBinaryPath = suPath,
            hasUserShell = shPath != null,
        )
    }

    fun execute(
        command: String,
        preferRoot: Boolean,
        timeoutMs: Long = 8_000,
    ): CommandExecutionReport {
        val env = probeEnvironment()
        val shellKind = if (preferRoot && env.hasSuBinary) ShellKind.ROOT_SU else ShellKind.USER_SH
        val shellPath = when (shellKind) {
            ShellKind.ROOT_SU -> env.suBinaryPath ?: "su"
            ShellKind.USER_SH -> if (env.hasUserShell) "/system/bin/sh" else "sh"
        }
        if (preferRoot && !env.hasSuBinary) {
            val now = System.currentTimeMillis()
            return CommandExecutionReport(
                shellKind = ShellKind.ROOT_SU,
                requestedRoot = true,
                command = command,
                exitCode = null,
                stdout = "",
                stderr = "No su binary found on device.",
                startedAtMillis = now,
                finishedAtMillis = now,
                timedOut = false,
                skipped = true,
                summary = "root shell unavailable",
            )
        }

        val startedAt = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(shellPath, "-c", command)
                .redirectErrorStream(false)
                .start()
            val finishedInTime = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finishedInTime) {
                process.destroy()
                val finishedAt = System.currentTimeMillis()
                CommandExecutionReport(
                    shellKind = shellKind,
                    requestedRoot = preferRoot,
                    command = command,
                    exitCode = null,
                    stdout = process.inputStream.bufferedReader().use { it.readText() },
                    stderr = process.errorStream.bufferedReader().use { it.readText() },
                    startedAtMillis = startedAt,
                    finishedAtMillis = finishedAt,
                    timedOut = true,
                    skipped = false,
                    summary = "command timed out",
                )
            } else {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exit = process.exitValue()
                val finishedAt = System.currentTimeMillis()
                CommandExecutionReport(
                    shellKind = shellKind,
                    requestedRoot = preferRoot,
                    command = command,
                    exitCode = exit,
                    stdout = stdout,
                    stderr = stderr,
                    startedAtMillis = startedAt,
                    finishedAtMillis = finishedAt,
                    timedOut = false,
                    skipped = false,
                    summary = if (exit == 0) "command completed" else "command failed with exit=$exit",
                )
            }
        } catch (t: Throwable) {
            val finishedAt = System.currentTimeMillis()
            CommandExecutionReport(
                shellKind = shellKind,
                requestedRoot = preferRoot,
                command = command,
                exitCode = null,
                stdout = "",
                stderr = t.stackTraceToString(),
                startedAtMillis = startedAt,
                finishedAtMillis = finishedAt,
                timedOut = false,
                skipped = false,
                summary = "command threw ${t.javaClass.simpleName}",
            )
        }
    }

    private fun findExecutable(vararg paths: String): String? {
        return paths.firstOrNull { File(it).canExecute() }
    }
}

internal fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}
