package dev.obiente.nextcloudnative

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.durableMutationAccountScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

internal fun installAndroidAccountRemovalCleanupRecovery(
    context: Context,
): SharedPreferences.OnSharedPreferenceChangeListener {
    val appContext = context.applicationContext
    val preferences = appContext.getSharedPreferences(ANDROID_ACCOUNT_PREFERENCES, Context.MODE_PRIVATE)
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (
            key == ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY ||
            key == ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP_KEY
        ) {
            AndroidAccountRemovalCleanupRecoveryWork.schedule(appContext, preferences)
        }
    }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    AndroidAccountRemovalCleanupRecoveryWork.schedule(appContext, preferences)
    return listener
}

internal object AndroidAccountRemovalCleanupRecoveryWork {
    private const val UNIQUE_WORK = "nextcloud-native-account-removal-cleanup"

    fun schedule(context: Context, preferences: SharedPreferences) {
        if (
            !preferences.contains(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY) &&
            !hasPendingAndroidExternalHandoffCleanup(preferences)
        ) return
        val request = OneTimeWorkRequestBuilder<AndroidAccountRemovalCleanupRecoveryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ANDROID_ACCOUNT_REMOVAL_CLEANUP_WORK_POLICY,
            request,
        )
    }
}

internal class AndroidAccountRemovalCleanupRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
        val preferences = applicationContext.getSharedPreferences(
            ANDROID_ACCOUNT_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val journal = AndroidAccountRemovalCleanupJournal(
            preferences = preferences,
            commit = { editor -> ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
                requireCommittedAndroidAccountCredentialEdit(editor)
            } },
            recordMalformed = { Log.w(LOG_TAG, "Malformed account-removal cleanup journal retained") },
        )
        val handoffCleanup = AndroidExternalFileHandoffCleanup(
            context = applicationContext,
            preferences = preferences,
            commit = { editor -> ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
                requireCommittedAndroidAccountCredentialEdit(editor)
            } },
        )
        val handoffCompleted = retryPendingAndroidExternalHandoffCleanup(
            pending = handoffCleanup.pending(),
            clearHandoffs = handoffCleanup::clearHandoffs,
            clearJournal = handoffCleanup::clearJournal,
            recordFailure = {
                logAndroidAccountRemovalCleanupRecoveryDeferred { message -> Log.w(LOG_TAG, message) }
            },
        )
        val registry = preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null)
            ?.let(::restoreAndroidCredentialFreeRegistry)
            ?.registry
        val cleanup = AndroidAccountOwnedStateCleanup(applicationContext)
        val pending = readPendingAndroidAccountRemovalCleanups(
            readPending = journal::pending,
            recordFailure = {
                logAndroidAccountRemovalCleanupRecoveryDeferred { message -> Log.w(LOG_TAG, message) }
            },
        ) ?: return@withLock Result.retry()
        val completed = recoverPendingAndroidAccountRemovalCleanups(
            pending = pending,
            accountOwnedByRegistry = { pendingCleanup ->
                androidAccountRemovalCleanupOwnedByRegistry(pendingCleanup, registry?.accounts)
            },
            removeAccountOwnedWork = { pending ->
                ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(pending.workIdentity) {
                    cleanup.retryWithoutCredentials(
                        pending.workIdentity,
                        pending.previewCacheIdentity,
                        pending.durableMutationIdentity,
                    )
                }
            },
            clearCleanup = journal::clear,
            recordFailure = {
                logAndroidAccountRemovalCleanupRecoveryDeferred { message -> Log.w(LOG_TAG, message) }
            },
        )
        if (completed && handoffCompleted) Result.success() else Result.retry()
    }
}

internal suspend fun recoverPendingAndroidAccountRemovalCleanups(
    pending: Collection<AndroidPendingAccountRemovalCleanup>,
    accountOwnedByRegistry: (AndroidPendingAccountRemovalCleanup) -> Boolean?,
    removeAccountOwnedWork: suspend (AndroidPendingAccountRemovalCleanup) -> Unit,
    clearCleanup: suspend (String) -> Unit,
    recordFailure: () -> Unit,
): Boolean {
    var completed = true
    pending.forEach { cleanup ->
        try {
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = accountOwnedByRegistry(cleanup),
                removeAccountOwnedWork = { removeAccountOwnedWork(cleanup) },
                clearCleanup = { clearCleanup(cleanup.accountStorageKey) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            completed = false
            recordFailure()
        }
    }
    return completed
}

internal fun androidAccountRemovalCleanupOwnedByRegistry(
    cleanup: AndroidPendingAccountRemovalCleanup,
    retainedAccounts: List<NextcloudAccountRecord>?,
): Boolean? {
    retainedAccounts ?: return null
    val storageOwner = retainedAccounts.firstOrNull { account ->
        account.id.storageKey == cleanup.accountStorageKey
    }
    if (storageOwner != null) {
        val storageOwnerWorkIdentity = NextcloudDocumentIds.accountKey(
            storageOwner.serverUrl,
            storageOwner.loginName,
        )
        val storageOwnerPreviewIdentity = NextcloudDocumentIds.cacheAccountId(
            NextcloudSession(storageOwner.serverUrl, storageOwner.loginName, appPassword = ""),
        )
        check(storageOwnerWorkIdentity == cleanup.workIdentity) {
            "The account-removal cleanup identities do not match."
        }
        check(cleanup.previewCacheIdentity == null || storageOwnerPreviewIdentity == cleanup.previewCacheIdentity) {
            "The account-removal preview identity does not match."
        }
        check(
            cleanup.durableMutationIdentity == null ||
                durableMutationAccountScope(
                    NextcloudSession(storageOwner.serverUrl, storageOwner.loginName, appPassword = ""),
                ) == cleanup.durableMutationIdentity,
        ) {
            "The account-removal mutation identity does not match."
        }
        return true
    }
    check(retainedAccounts.none { account ->
        NextcloudDocumentIds.accountKey(account.serverUrl, account.loginName) == cleanup.workIdentity
    }) {
        "The account-removal cleanup identity belongs to a retained account."
    }
    check(cleanup.durableMutationIdentity == null || retainedAccounts.none { account ->
        durableMutationAccountScope(
            NextcloudSession(account.serverUrl, account.loginName, appPassword = ""),
        ) == cleanup.durableMutationIdentity
    }) {
        "The account-removal mutation identity belongs to a retained account."
    }
    return false
}

internal fun readPendingAndroidAccountRemovalCleanups(
    readPending: () -> Collection<AndroidPendingAccountRemovalCleanup>,
    recordFailure: () -> Unit,
): Collection<AndroidPendingAccountRemovalCleanup>? = try {
    readPending()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    recordFailure()
    null
}

internal fun logAndroidAccountRemovalCleanupRecoveryDeferred(
    logWarning: (String) -> Unit,
) {
    logWarning("Account-removal cleanup recovery deferred")
}

private const val ANDROID_ACCOUNT_PREFERENCES = "nextcloud_native"
private const val LOG_TAG = "AccountCleanupRecovery"
internal val ANDROID_ACCOUNT_REMOVAL_CLEANUP_WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE
