package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Immutable
internal data class AccountProfileUiState(
    val profile: AccountProfile? = null,
    val loading: Boolean = true,
    val loadFailure: String? = null,
    val saving: Boolean = false,
    /** Fields whose change was confirmed by a fresh read during the last save. */
    val savedFields: Set<AccountProfileField> = emptySet(),
    val saveFailure: String? = null,
    /** Bumped after every save so editors reset their drafts to server values. */
    val revision: Int = 0,
)

/** Result of applying a batch of profile edits. Partial success is reported per edit. */
internal data class AccountProfileSaveReport(
    val applied: List<AccountProfileEdit>,
    val failed: List<Pair<AccountProfileEdit, ConfirmedWriteOutcome>>,
    val skipped: List<AccountProfileEdit>,
)

/**
 * Owns the signed-in account's profile for one session. Reads use the stored app password;
 * edits require a password-confirmed transport and are verified by a fresh read.
 */
internal class AccountProfileController(
    private val scope: CoroutineScope,
    private val session: NextcloudSession,
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val transport: AccountPasswordConfirmedTransport?,
) {
    var state by mutableStateOf(AccountProfileUiState())
        private set

    private var loadJob: Job? = null

    val canEdit: Boolean get() = transport != null

    fun refresh() {
        loadJob?.cancel()
        state = state.copy(loading = true, loadFailure = null)
        loadJob = scope.launch {
            state = when (val result = readProfile()) {
                is AccountProfileLoadResult.Loaded -> state.copy(profile = result.profile, loading = false)
                else -> state.copy(loading = false, loadFailure = result.loadFailureMessage())
            }
        }
    }

    fun dismissSaveFailure() {
        state = state.copy(saveFailure = null)
    }

    /** Applies [edits] in order with one password confirmation, stopping when the password fails. */
    fun save(edits: List<AccountProfileEdit>, secret: AccountPasswordConfirmationSecret, onFinished: (AccountProfileSaveReport) -> Unit = {}) {
        val profile = state.profile
        val activeTransport = transport
        if (edits.isEmpty() || profile == null || activeTransport == null || state.saving) {
            secret.clear()
            return
        }
        state = state.copy(saving = true, saveFailure = null, savedFields = emptySet())
        scope.launch {
            val report = try {
                applyAccountProfileEdits(activeTransport, profile.userId, edits, secret)
            } finally {
                secret.clear()
            }
            val refreshed = (readProfile() as? AccountProfileLoadResult.Loaded)?.profile
            state = state.copy(
                profile = refreshed ?: state.profile,
                saving = false,
                savedFields = report.applied.mapNotNull { (it as? AccountProfileEdit.Value)?.field ?: (it as? AccountProfileEdit.Scope)?.field }.toSet(),
                saveFailure = report.failureSummary(),
                revision = state.revision + 1,
            )
            onFinished(report)
        }
    }

    internal suspend fun applyAccountProfileEdits(
        transport: AccountPasswordConfirmedTransport,
        userId: String,
        edits: List<AccountProfileEdit>,
        secret: AccountPasswordConfirmationSecret,
    ): AccountProfileSaveReport {
        val applied = mutableListOf<AccountProfileEdit>()
        val failed = mutableListOf<Pair<AccountProfileEdit, ConfirmedWriteOutcome>>()
        edits.forEachIndexed { index, edit ->
            val outcome = runPasswordConfirmedWrite(
                transport = transport,
                session = session,
                request = AccountProfileRequests.edit(userId, edit),
                secret = secret,
                verify = { (readProfile() as? AccountProfileLoadResult.Loaded)?.profile?.reflects(edit) == true },
            )
            if (outcome == ConfirmedWriteOutcome.Applied) {
                applied += edit
            } else {
                failed += edit to outcome
                val reason = (outcome as? ConfirmedWriteOutcome.Rejected)?.reason
                // A rejected password, throttle, or offline device fails every remaining edit the same way.
                if (reason in STOPPING_REJECTIONS || outcome == ConfirmedWriteOutcome.Unverified) {
                    return AccountProfileSaveReport(applied, failed, edits.drop(index + 1))
                }
            }
        }
        return AccountProfileSaveReport(applied, failed, emptyList())
    }

    private suspend fun readProfile(): AccountProfileLoadResult = try {
        coroutineScope {
            val profile = async { execute(AccountProfileRequests.currentUser()) }
            val fields = async { execute(AccountProfileRequests.editableFields()) }
            parseAccountProfile(profile.await(), fields.await().takeIf { it.status in 200..299 })
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Transport fault boundary: no exception text reaches the UI.
        AccountProfileLoadResult.Unavailable
    }
}

internal fun AccountProfileLoadResult.loadFailureMessage(): String = when (this) {
    is AccountProfileLoadResult.Loaded -> ""
    AccountProfileLoadResult.Unauthorized -> "Sign in to this account again to load your profile."
    AccountProfileLoadResult.Unavailable -> "Your profile could not be loaded. Check your connection and try again."
    AccountProfileLoadResult.Malformed -> "The server sent a profile this app cannot read."
}

internal fun AccountProfileSaveReport.failureSummary(): String? {
    if (failed.isEmpty()) return null
    val first = failed.first().second.failureMessage().orEmpty()
    val notTried = if (skipped.isEmpty()) "" else " ${skipped.size} other change(s) were not sent."
    val savedPart = if (applied.isEmpty()) "" else " ${applied.size} change(s) were saved."
    return first + savedPart + notTried
}

private val STOPPING_REJECTIONS = setOf(
    ConfirmedWriteRejection.PasswordNotAccepted,
    ConfirmedWriteRejection.Throttled,
    ConfirmedWriteRejection.NotSent,
    ConfirmedWriteRejection.TransportMissing,
)
