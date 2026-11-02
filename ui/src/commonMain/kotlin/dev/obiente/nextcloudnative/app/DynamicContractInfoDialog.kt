package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Write safety as the contract information dialog shows it: typed rows, never request data. */
sealed interface DynamicWriteSafetyView {
    data object Loading : DynamicWriteSafetyView

    /** The device state could not be read; writes for this app are refused until it is cleared. */
    data object Unreadable : DynamicWriteSafetyView

    data class Ready(val entries: List<DynamicWriteSafetyEntry>) : DynamicWriteSafetyView
}

data class DynamicWriteSafetyEntry(
    val route: String,
    val status: DynamicWriteRuleStatus,
    val reason: DynamicWriteQuarantineReason?,
    val rule: String,
) {
    val label: String
        get() = when (status) {
            DynamicWriteRuleStatus.quarantined -> "Paused: " + when (reason) {
                DynamicWriteQuarantineReason.notAppliedAfterSuccess -> "the app reported success but did not apply a change"
                DynamicWriteQuarantineReason.untouchedFieldDrift -> "other values changed during a change"
                DynamicWriteQuarantineReason.encodingMismatch -> "a value read back differently than it was written"
                null -> "a change did not turn out as reported"
            }
            DynamicWriteRuleStatus.probation -> "In probation: the first change is checked by reading it back"
            DynamicWriteRuleStatus.confirmed -> "Confirmed on this device"
        }
}

internal fun List<DynamicWriteRuleRecord>.toWriteSafetyEntries(): List<DynamicWriteSafetyEntry> = map { record ->
    DynamicWriteSafetyEntry(
        route = record.key.route.take(MAX_ROUTE_LABEL),
        status = record.status,
        reason = record.reason,
        rule = "${record.key.ruleId} v${record.key.ruleVersion}",
    )
}

/**
 * Loads write safety for the dialog and owns the person's clear action. Clearing is the only way,
 * besides an app version change, that a quarantine ends.
 */
@Composable
internal fun DynamicContractInfoHost(
    info: DynamicContractInfo,
    store: DynamicWriteQuarantineStore?,
    accountDigest: String,
    appId: String,
    onDismiss: () -> Unit,
) {
    var view by remember(store, accountDigest, appId) { mutableStateOf<DynamicWriteSafetyView>(DynamicWriteSafetyView.Loading) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, accountDigest, appId, generation) {
        view = if (store == null) {
            DynamicWriteSafetyView.Ready(emptyList())
        } else {
            try {
                DynamicWriteSafetyView.Ready(store.records(accountDigest, appId).toWriteSafetyEntries())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistence fault boundary: show that the state is unreadable and offer the reset.
                DynamicWriteSafetyView.Unreadable
            }
        }
    }
    DynamicContractInfoDialog(
        info = info,
        writeSafety = view,
        onResumeWrites = store?.let { quarantine ->
            {
                scope.launch {
                    try {
                        quarantine.clearApp(accountDigest, appId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The reload below shows whether anything remains.
                    }
                    generation += 1
                }
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun DynamicContractInfoDialog(
    info: DynamicContractInfo,
    writeSafety: DynamicWriteSafetyView,
    onResumeWrites: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val clearable = onResumeWrites != null && when (writeSafety) {
        DynamicWriteSafetyView.Unreadable -> true
        is DynamicWriteSafetyView.Ready -> writeSafety.entries.isNotEmpty()
        DynamicWriteSafetyView.Loading -> false
    }
    // Ephemeral: the confirmation is asked again after recreation rather than resumed.
    var confirmingResume by remember(writeSafety) { mutableStateOf(false) }
    if (confirmingResume && clearable) {
        ResumeWritesConfirmation(
            writeSafety = writeSafety,
            onConfirm = {
                confirmingResume = false
                onResumeWrites?.invoke()
            },
            onDismiss = { confirmingResume = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contract info") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                DynamicWriteSafetySection(writeSafety)
                ContractInfoSection("Acquisition", info.acquisition)
                ContractInfoSection("App version", info.appVersion)
                ContractInfoSection("Source spec", info.sourceSpecFile)
                ContractInfoSection("Descriptor", info.countSummary())
                ContractInfoSection("Resources", info.resourceIds.safeContractList())
                ContractInfoSection("Layouts", info.layoutIds.safeContractList())
                ContractInfoSection("Links", info.linkIds.safeContractList())
                ContractInfoSection("Actions", info.actionIds.safeContractList())
                ContractInfoSection("Diagnostics", info.diagnosticCodes.ifEmpty { listOf("none") }.joinToString(", "))
                if (info.childCandidates.isNotEmpty()) {
                    Text("Current record navigation", style = MaterialTheme.typography.titleSmall)
                    info.childCandidates.forEach { child ->
                        val missing = child.missingContextParameters.takeIf(List<String>::isNotEmpty)
                            ?.joinToString(", ", prefix = " · needs ")
                            .orEmpty()
                        Text("${child.resourceId} · ${child.reasonLabel()}$missing", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    ContractInfoSection("Current record navigation", "No child collection candidates")
                }
            }
        },
        dismissButton = if (clearable) {
            { TextButton(onClick = { confirmingResume = true }) { Text("Resume changes") } }
        } else {
            null
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Names every route that resuming clears and why it was paused, so one tap never silently
 * re-enables a route whose last change the app contradicted.
 */
@Composable
private fun ResumeWritesConfirmation(
    writeSafety: DynamicWriteSafetyView,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = (writeSafety as? DynamicWriteSafetyView.Ready)?.entries.orEmpty()
    val paused = entries.filter { it.status == DynamicWriteRuleStatus.quarantined }
    val summary = when {
        writeSafety == DynamicWriteSafetyView.Unreadable ->
            "This device's write safety record for this app could not be read. Resuming resets it, " +
                "which also ends any pause it held, so check this app's recently changed items first."
        paused.isNotEmpty() ->
            "Changes through these routes were paused because the app did not do what it reported. " +
                "Check the affected items before resuming; the next change is checked by reading it back again."
        else -> "Resuming clears this device's record for these routes; their next change is checked by reading it back again."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume changes for this app?") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { contentDescription = summary })
                (paused + (entries - paused.toSet())).forEach { entry ->
                    val line = "${entry.route}: ${entry.label}"
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = line })
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Resume") } },
    )
}

@Composable
private fun DynamicWriteSafetySection(view: DynamicWriteSafetyView) {
    val summary = when (view) {
        DynamicWriteSafetyView.Loading -> "Checking..."
        DynamicWriteSafetyView.Unreadable ->
            "This device's write safety record for this app could not be read, so changes are paused. " +
                "Resume changes to reset it."
        is DynamicWriteSafetyView.Ready -> when {
            view.entries.isEmpty() -> "No paused or probationary changes on this device."
            view.entries.any { it.status == DynamicWriteRuleStatus.quarantined } ->
                "Some changes are paused on this device because an app did not do what it reported. " +
                    "Check the affected items, then resume changes. Updating the app also resumes them."
            else -> "Some kinds of change are checked by reading them back the first time."
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text("Write safety", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = summary })
        (view as? DynamicWriteSafetyView.Ready)?.entries?.forEach { entry ->
            val line = "${entry.route} · ${entry.label} · ${entry.rule}"
            Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = line })
        }
    }
}

@Composable
private fun ContractInfoSection(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun List<String>.safeContractList(): String = ifEmpty { listOf("none") }.joinToString(", ")

private const val MAX_ROUTE_LABEL = 160
