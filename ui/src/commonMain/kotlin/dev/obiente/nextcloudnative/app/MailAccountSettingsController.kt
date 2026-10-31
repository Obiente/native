package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class MailAccountSettingsUiState(
    val saving: Boolean = false,
    val notice: String? = null,
    val failed: Boolean = false,
)

/**
 * Edits the settings Mail exposes per account: the signature, whether it goes above quoted text,
 * the default editor, and sending aliases with their own signatures.
 *
 * Every change is followed by re-reading the account list, and success is reported only when the
 * re-read value matches what was requested. Removing an alias is destructive and is confirmed by
 * the caller. Account connection settings (servers and passwords) stay in the Mail web settings,
 * because changing them needs the account password.
 */
internal class MailAccountSettingsController(
    private val scope: CoroutineScope,
    private val transport: MailTransport,
    private val onAccountsReloaded: (List<MailAccount>) -> Unit,
) {
    var state by mutableStateOf(MailAccountSettingsUiState())
        private set

    fun saveSignature(account: MailAccount, signature: String) = run(
        request = { mailAccountSignatureRequest(account.id, signature) },
        verify = { accounts -> accounts.byId(account.id)?.signature.orEmpty().trim() == signature.trim() },
        done = "Signature saved.",
    )

    fun saveSignaturePlacement(account: MailAccount, aboveQuote: Boolean) = run(
        request = { mailAccountPreferencesRequest(account.id, signatureAboveQuote = aboveQuote) },
        verify = { accounts -> accounts.byId(account.id)?.signatureAboveQuote == aboveQuote },
        done = "Signature placement saved.",
    )

    fun saveEditorMode(account: MailAccount, mode: MailEditorMode) = run(
        request = { mailAccountPreferencesRequest(account.id, editorMode = mode) },
        verify = { accounts -> accounts.byId(account.id)?.editorMode == mode },
        done = "Default editor saved.",
    )

    fun createAlias(account: MailAccount, email: String, name: String) = run(
        request = { mailCreateAliasRequest(account.id, email, name) },
        verify = { accounts -> accounts.byId(account.id)?.aliases?.any { it.email.equals(email.trim(), ignoreCase = true) } == true },
        done = "Alias added.",
    )

    fun updateAlias(account: MailAccount, alias: MailAlias, email: String, name: String) = run(
        request = { mailUpdateAliasRequest(account.id, alias.id, email, name) },
        verify = { accounts ->
            accounts.byId(account.id)?.aliases?.firstOrNull { it.id == alias.id }?.let { updated ->
                updated.email.equals(email.trim(), ignoreCase = true) && updated.name.orEmpty() == name.trim()
            } == true
        },
        done = "Alias saved.",
    )

    fun saveAliasSignature(account: MailAccount, alias: MailAlias, signature: String) = run(
        request = { mailAliasSignatureRequest(account.id, alias.id, signature) },
        verify = { accounts ->
            accounts.byId(account.id)?.aliases?.firstOrNull { it.id == alias.id }?.signature.orEmpty().trim() == signature.trim()
        },
        done = "Alias signature saved.",
    )

    fun deleteAlias(account: MailAccount, alias: MailAlias) = run(
        request = { mailDeleteAliasRequest(account.id, alias.id) },
        verify = { accounts -> accounts.byId(account.id)?.aliases?.none { it.id == alias.id } == true },
        done = "Alias removed.",
    )

    fun dismissNotice() {
        state = state.copy(notice = null, failed = false)
    }

    private fun run(
        request: () -> NextcloudApiRequest,
        verify: (List<MailAccount>) -> Boolean,
        done: String,
    ) {
        if (state.saving) return
        val built = try {
            request()
        } catch (invalid: IllegalArgumentException) {
            state = state.copy(notice = invalid.message ?: "Check the value and try again.", failed = true)
            return
        }
        state = state.copy(saving = true, notice = null, failed = false)
        scope.launch {
            val outcome = transport.write(built, ignoreMailBody)
            val reloaded = transport.read(mailAccountsRequest(), ::parseMailAccounts)
            val accounts = (reloaded as? MailReadOutcome.Loaded)?.value
            accounts?.let(onAccountsReloaded)
            val verified = accounts?.let(verify)
            state = when {
                verified == true -> state.copy(saving = false, notice = done, failed = false)
                outcome is MailWriteOutcome.Rejected ->
                    state.copy(saving = false, notice = "${outcome.failure.title}. ${outcome.failure.message}", failed = true)
                verified == false -> state.copy(saving = false, notice = "The change did not take effect. Try again.", failed = true)
                else -> state.copy(saving = false, notice = "Could not confirm the change. Refresh before trying again.", failed = true)
            }
        }
    }
}

private fun List<MailAccount>.byId(id: Long): MailAccount? = firstOrNull { it.id == id }
