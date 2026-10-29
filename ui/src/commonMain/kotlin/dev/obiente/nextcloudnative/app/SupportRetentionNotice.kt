package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-time Home announcement that Obiente Support now keeps reports until they are deleted.
 *
 * Builds before this change deleted reports and their device receipts after 30 days, so nothing on
 * the device identifies the people affected. The notice is therefore shown once per install, but
 * only to installs that had already run a build with direct Obiente Support. The decision is made
 * when the app starts, before the support service creates its private storage for the first time.
 */
internal enum class SupportRetentionNoticeState(val storedValue: String) {
    Pending("pending"),
    Dismissed("dismissed"),
    NotApplicable("not-applicable"),
    ;

    companion object {
        /** An undecided or unreadable value never shows the notice. */
        fun fromStoredValue(value: String?): SupportRetentionNoticeState =
            entries.firstOrNull { it.storedValue == value } ?: NotApplicable
    }
}

/** Small non-secret per-install flag kept in the platform preference storage, never per account. */
internal interface SupportRetentionNoticeStore {
    fun read(): String?

    fun write(value: String): Boolean
}

/**
 * Records the install history once. [supportStorageExistedAtLaunch] is true when the private
 * support-submission directory already existed before this process created it, which means an
 * earlier build with direct Obiente Support and 30-day retention ran on this install.
 */
internal fun SupportRetentionNoticeStore.recordSupportRetentionInstallHistory(supportStorageExistedAtLaunch: Boolean) {
    if (read() != null) return
    val state = if (supportStorageExistedAtLaunch) {
        SupportRetentionNoticeState.Pending
    } else {
        SupportRetentionNoticeState.NotApplicable
    }
    write(state.storedValue)
}

/** One-shot request to open Settings > Support > New report, consumed by the Support screen. */
internal object SupportNewReportRequests {
    private val pending = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = pending.asStateFlow()

    fun request() {
        pending.value = true
    }

    fun consume(): Boolean = pending.compareAndSet(expect = true, update = false)
}

@Stable
internal class SupportRetentionNoticeController(
    private val store: SupportRetentionNoticeStore,
    private val requests: SupportNewReportRequests = SupportNewReportRequests,
) {
    var visible by mutableStateOf(
        SupportRetentionNoticeState.fromStoredValue(store.read()) == SupportRetentionNoticeState.Pending,
    )
        private set

    /** Opens a fresh report. Old report data is gone, so nothing from an earlier report is reused. */
    fun sendNewReport() {
        dismiss()
        requests.request()
    }

    /** Hides the notice for good. A failed write still hides it for this session. */
    fun dismiss() {
        visible = false
        store.write(SupportRetentionNoticeState.Dismissed.storedValue)
    }
}

@Composable
internal fun rememberSupportRetentionNoticeController(): SupportRetentionNoticeController {
    val store = rememberSupportRetentionNoticeStore()
    return remember(store) { SupportRetentionNoticeController(store) }
}

/** Returns the Home notice slot, or null when the notice is not due or Settings cannot be opened. */
@Composable
internal fun rememberHomeSupportRetentionNotice(
    onOpenSettings: (() -> Unit)?,
    controller: SupportRetentionNoticeController = rememberSupportRetentionNoticeController(),
): (@Composable () -> Unit)? {
    val openSettings by rememberUpdatedState(onOpenSettings)
    val notice: @Composable () -> Unit = remember(controller) {
        {
            SupportRetentionHomeNotice(
                onSendNewReport = {
                    controller.sendNewReport()
                    openSettings?.invoke()
                },
                onDismiss = controller::dismiss,
            )
        }
    }
    return notice.takeIf { onOpenSettings != null && controller.visible }
}

@Composable
internal fun SupportRetentionHomeNotice(onSendNewReport: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text(
                SUPPORT_RETENTION_NOTICE_TITLE,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(SUPPORT_RETENTION_NOTICE_BODY, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Button(onClick = onSendNewReport) { Text(SUPPORT_RETENTION_NOTICE_SEND) }
                TextButton(onClick = onDismiss) { Text(SUPPORT_RETENTION_NOTICE_DISMISS) }
            }
        }
    }
}

@Composable
internal expect fun rememberSupportRetentionNoticeStore(): SupportRetentionNoticeStore

internal const val SUPPORT_RETENTION_NOTICE_KEY = "support-retention-notice-v1"
internal const val SUPPORT_RETENTION_NOTICE_TITLE = "Support reports are now kept longer"
internal const val SUPPORT_RETENTION_NOTICE_BODY =
    "We used to delete support reports after 30 days to protect your privacy, but that did not leave us " +
        "enough time to act on them. Reports are now kept until you or Obiente Support delete them. If you " +
        "sent a report and have not heard back, please send it again."
internal const val SUPPORT_RETENTION_NOTICE_SEND = "Send a new report"
internal const val SUPPORT_RETENTION_NOTICE_DISMISS = "Dismiss"
