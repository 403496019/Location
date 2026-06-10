package dev.lerist.fakelocation.app.ui

import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import dev.lerist.fakelocation.app.appGraph
import dev.lerist.fakelocation.app.runtime.RuntimeSnapshot
import dev.lerist.fakelocation.app.service.ForegroundControlService
import dev.lerist.fakelocation.app.service.MockCoordinatorService
import dev.lerist.fakelocation.app.service.MockServiceProviderService

class MainActivity : ComponentActivity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(24, 24, 24, 24)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(24, 24, 24, 24)
            addView(actionButton("Prepare Runtime") {
                startProvider(MockServiceProviderService.ACTION_REGISTER_LOCAL_SERVICES)
                refreshSnapshot()
            })
            addView(actionButton("Activate AppHook") {
                startCoordinator(MockCoordinatorService.ACTION_ACTIVATE_APP_HOOK)
                refreshSnapshot()
            })
            addView(actionButton("Start Session") {
                startCoordinator(MockCoordinatorService.ACTION_START_SESSION)
                startForegroundRuntime()
                refreshSnapshot()
            })
            addView(actionButton("Stop Session") {
                startCoordinator(MockCoordinatorService.ACTION_STOP_SESSION)
                stopForegroundRuntime()
                refreshSnapshot()
            })
            addView(actionButton("Update Demo Location") {
                startCoordinator(MockCoordinatorService.ACTION_UPDATE_DEMO_LOCATION)
                refreshSnapshot()
            })
            addView(actionButton("Update Demo Wi-Fi") {
                startCoordinator(MockCoordinatorService.ACTION_UPDATE_DEMO_WIFI)
                refreshSnapshot()
            })
            addView(actionButton("Run Preflight Checks") {
                startCoordinator(MockCoordinatorService.ACTION_RUN_PREFLIGHT_CHECKS)
                refreshSnapshot()
            })
            addView(actionButton("Probe Root Shell") {
                startCoordinator(MockCoordinatorService.ACTION_PROBE_ROOT)
                refreshSnapshot()
            })
            addView(actionButton("Sync /data/fl Mirror") {
                startCoordinator(MockCoordinatorService.ACTION_SYNC_RUNTIME_MIRROR)
                refreshSnapshot()
            })
            addView(actionButton("Execute Dry-Run Tasks") {
                startCoordinator(MockCoordinatorService.ACTION_EXECUTE_DRY_RUN_TASKS)
                refreshSnapshot()
            })
            addView(actionButton("Refresh Snapshot") {
                refreshSnapshot()
            })
            addView(statusView)
        }

        setContentView(
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                addView(content)
            },
        )

        startProvider(MockServiceProviderService.ACTION_REGISTER_LOCAL_SERVICES)
        refreshSnapshot()
    }

    override fun onResume() {
        super.onResume()
        refreshSnapshot()
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            text = label
            setOnClickListener { onClick() }
        }
    }

    private fun startCoordinator(action: String) {
        startService(Intent(this, MockCoordinatorService::class.java).setAction(action))
    }

    private fun startProvider(action: String) {
        startService(Intent(this, MockServiceProviderService::class.java).setAction(action))
    }

    private fun startForegroundRuntime() {
        val intent = Intent(this, ForegroundControlService::class.java)
            .setAction(ForegroundControlService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundRuntime() {
        startService(
            Intent(this, ForegroundControlService::class.java)
                .setAction(ForegroundControlService.ACTION_STOP),
        )
    }

    private fun refreshSnapshot() {
        statusView.text = renderSnapshot(appGraph.runtimeController.snapshot())
    }

    private fun renderSnapshot(snapshot: RuntimeSnapshot): String {
        val state = snapshot.sessionState
        val locationText = state.currentLocation?.let {
            "${it.latitude}, ${it.longitude}, alt=${it.altitudeMeters}, acc=${it.accuracyMeters}, provider=${it.provider}"
        } ?: "none"
        val wifiText = state.currentWifiProfile?.let {
            "${it.ssid} / ${it.bssid} / ${it.frequencyMhz}MHz / ${it.rssiDbm}dBm"
        } ?: "none"
        val planLines = snapshot.injectionPlans.joinToString("\n") { plan ->
            "- ${plan.processName} | ${plan.role} | ${plan.stage} | ${plan.nativeLoaderName} | ${plan.javaEntrypoint}"
        }
        val taskLines = snapshot.injectionTasks.joinToString("\n") { task ->
            "- ${task.plan.processName}: ${task.dryRunCommand}"
        }.ifBlank { "- none" }
        val executionLines = snapshot.taskExecutions.joinToString("\n\n") { execution ->
            buildString {
                append("- ${execution.task.plan.processName}: ${execution.report.summary}")
                append("\n  shell=${execution.report.shellKind} root=${execution.report.requestedRoot} exit=${execution.report.exitCode}")
                append("\n  stdout=${execution.report.stdout.ifBlank { "<empty>" }}")
                append("\n  stderr=${execution.report.stderr.ifBlank { "<empty>" }}")
            }
        }.ifBlank { "- none" }
        val artifactLines = snapshot.stagedArtifacts.joinToString("\n") { artifact ->
            "- ${artifact.descriptor.logicalName} | ${artifact.descriptor.kind} | ${artifact.sharedPathHint} | sha256=${artifact.sha256.take(16)}..."
        }.ifBlank { "- none" }
        val integrityText = snapshot.runtimeIntegrityReport?.let { report ->
            buildString {
                append("manifestExists=${report.manifestExists}, allPresent=${report.allPresent}, ")
                append("allNonEmpty=${report.allNonEmpty}, allHashesMatch=${report.allHashesMatch}, ")
                append("placeholderArtifactCount=${report.placeholderArtifactCount}")
            }
        } ?: "none"
        val integrityArtifactLines = snapshot.runtimeIntegrityReport?.artifactReports?.joinToString("\n") { artifact ->
            "- ${artifact.logicalName}: ${artifact.status} size=${artifact.actualSizeBytes ?: "missing"} sha=${artifact.actualSha256?.take(16) ?: "none"}"
        } ?: "- none"
        val preflightText = snapshot.injectionPreflightReport?.let { report ->
            buildString {
                append("taskCount=${report.taskCount}, rootRequired=${report.rootRequired}, rootAvailable=${report.rootAvailable}, ")
                append("placeholderArtifactsPresent=${report.placeholderArtifactsPresent}, ")
                append("canAttemptMirrorSync=${report.canAttemptMirrorSync}, canAttemptInjection=${report.canAttemptInjection}")
            }
        } ?: "none"
        val blockingLines = snapshot.injectionPreflightReport?.blockingIssues?.joinToString("\n") { "- $it" }
            ?.ifBlank { "- none" } ?: "- none"
        val warningLines = snapshot.injectionPreflightReport?.warnings?.joinToString("\n") { "- $it" }
            ?.ifBlank { "- none" } ?: "- none"
        val recommendationLines = snapshot.injectionPreflightReport?.recommendations?.joinToString("\n") { "- $it" }
            ?.ifBlank { "- none" } ?: "- none"
        val rootEnvText = snapshot.injectorEnvironment?.let {
            "hasSuBinary=${it.hasSuBinary}, suBinaryPath=${it.suBinaryPath ?: "none"}, hasUserShell=${it.hasUserShell}"
        } ?: "none"
        val rootProbeText = snapshot.rootProbeReport?.let { report ->
            "summary=${report.summary}, shell=${report.shellKind}, exit=${report.exitCode}, stdout=${report.stdout.ifBlank { "<empty>" }}, stderr=${report.stderr.ifBlank { "<empty>" }}"
        } ?: "none"
        val mirrorSyncText = snapshot.runtimeMirrorSyncResult?.let { result ->
            "summary=${result.report.summary}, exit=${result.report.exitCode}, stdout=${result.report.stdout.ifBlank { "<empty>" }}, stderr=${result.report.stderr.ifBlank { "<empty>" }}"
        } ?: "none"
        val reportLines = snapshot.payloadReports.joinToString("\n") { report ->
            "- ${report.stageName}: hooks=${report.installedHooks}, services=${report.registeredServices.joinToString()}"
        }.ifBlank { "- none" }
        val hookLines = snapshot.hookHistory.joinToString("\n") { result ->
            "- ${result.bridgeName}: ${result.spec.targetClassName}.${result.spec.targetMethodName} -> installed=${result.installed} (${result.message})"
        }.ifBlank { "- none" }

        return buildString {
            appendLine("FakeLocation Repro v1")
            appendLine("Phase 1 runtime dashboard")
            appendLine()
            appendLine("Runtime")
            appendLine("runtimePrepared=${snapshot.runtimePrepared}")
            appendLine("hiddenApiAttempted=${snapshot.hiddenApiAttempted}")
            appendLine("hiddenApiApplied=${snapshot.hiddenApiApplied}")
            appendLine("initStageActivated=${snapshot.initStageActivated}")
            appendLine("appHookStageActivated=${snapshot.appHookStageActivated}")
            appendLine("sessionRunning=${snapshot.sessionRunning}")
            appendLine("nativeHookReady=${appGraph.runtimeController.isNativeHookReady()}")
            appendLine("manifestVersion=${snapshot.runtimeManifestVersion ?: "none"}")
            appendLine("manifestFile=${snapshot.runtimeManifestFile?.absolutePath ?: "none"}")
            appendLine("runtimeDirs=${snapshot.runtimeDirectories.joinToString { it.absolutePath }}")
            appendLine("injectorEnvironment=$rootEnvText")
            appendLine("rootProbe=$rootProbeText")
            appendLine("mirrorSync=$mirrorSyncText")
            appendLine("integrity=$integrityText")
            appendLine("preflight=$preflightText")
            appendLine()
            appendLine("Staged Artifacts")
            appendLine(artifactLines)
            appendLine()
            appendLine("Integrity Artifacts")
            appendLine(integrityArtifactLines)
            appendLine()
            appendLine("Services")
            appendLine(snapshot.registeredServices.joinToString(prefix = "[", postfix = "]"))
            appendLine()
            appendLine("State")
            appendLine("locationEnabled=${state.toggles.locationEnabled}")
            appendLine("wifiEnabled=${state.toggles.wifiEnabled}")
            appendLine("cellsEnabled=${state.toggles.cellsEnabled}")
            appendLine("location=$locationText")
            appendLine("wifi=$wifiText")
            appendLine("lastUpdatedAtMillis=${state.lastUpdatedAtMillis}")
            appendLine()
            appendLine("Injection Plans")
            appendLine(planLines)
            appendLine()
            appendLine("Injection Tasks")
            appendLine(taskLines)
            appendLine()
            appendLine("Task Executions")
            appendLine(executionLines)
            appendLine()
            appendLine("Blocking Issues")
            appendLine(blockingLines)
            appendLine()
            appendLine("Warnings")
            appendLine(warningLines)
            appendLine()
            appendLine("Recommendations")
            appendLine(recommendationLines)
            appendLine()
            appendLine("Payload Reports")
            appendLine(reportLines)
            appendLine()
            appendLine("Hook History")
            appendLine(hookLines)
        }
    }
}
