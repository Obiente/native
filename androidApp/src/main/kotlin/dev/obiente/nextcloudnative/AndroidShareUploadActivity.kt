package dev.obiente.nextcloudnative

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.obiente.nextcloudnative.app.IncomingShareUploadFilePresentation
import dev.obiente.nextcloudnative.app.IncomingShareUploadFileStatus
import dev.obiente.nextcloudnative.app.IncomingShareUploadPresentation
import dev.obiente.nextcloudnative.app.IncomingShareUploadScreen
import dev.obiente.nextcloudnative.app.IncomingShareUploadState
import dev.obiente.nextcloudnative.app.NextcloudServerInfo
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.RemoteFolderPickerOperations
import dev.obiente.nextcloudnative.app.RemoteFolderSelectionAccess
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.remoteFolderPickerOperations
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Android share-sheet entry point. URI grants are staged before the user chooses a DAV folder. */
class AndroidShareUploadActivity : ComponentActivity() {
    private lateinit var store: AndroidIncomingShareStore
    private lateinit var uploads: AndroidIncomingShareUploads
    private lateinit var services: AndroidNextcloudServices
    private lateinit var permissionWebDav: NextcloudDocumentWebDav
    private var request by mutableStateOf<AndroidIncomingShareRequest?>(null)
    private var session by mutableStateOf<NextcloudSession?>(null)
    private var serverInfo by mutableStateOf<NextcloudServerInfo?>(null)
    private var loading by mutableStateOf(true)
    private var queueing by mutableStateOf(false)
    private var folderPickerVisible by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var corruptRecoveryRequestId by mutableStateOf<String?>(null)
    private var corruptRecoveryAccountId: String? = null
    private var corruptRemovalConfirmationVisible by mutableStateOf(false)
    private var discardConfirmationVisible by mutableStateOf(false)
    private var restoreJob: Job? = null
    private var queueJob: Job? = null
    private var restoreGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidIncomingShareStore(applicationContext)
        uploads = AndroidIncomingShareUploads(applicationContext)
        services = AndroidNextcloudServices(applicationContext)
        permissionWebDav = NextcloudDocumentWebDav(
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .useAndroidNextcloudCertificateTrust(applicationContext)
                .build(),
            applicationContext.cloudMutationGate(),
        )
        folderPickerVisible = savedInstanceState?.getBoolean(KEY_FOLDER_PICKER_VISIBLE) == true
        setContent {
            NextcloudNativeTheme {
                NextcloudAppBackground {
                    BackHandler(
                        enabled = !discardConfirmationVisible && !corruptRemovalConfirmationVisible,
                    ) {
                        finishKeepingRecovery()
                    }
                    val activeSession = session
                    val activeUserId = serverInfo?.userId
                    val folderOperations = remember(activeSession, activeUserId) {
                        if (activeSession != null && activeUserId != null) {
                            incomingShareFolderPickerOperations(
                                services,
                                activeSession,
                                activeUserId,
                                permissionWebDav,
                            )
                        } else {
                            null
                        }
                    }
                    IncomingShareUploadScreen(
                        request = request?.toPresentation(),
                        accountLabel = serverInfo?.let { "${it.displayName} (${it.serverUrl})" },
                        loading = loading,
                        queueing = queueing,
                        error = error,
                        corruptRecoveryAvailable = corruptRecoveryRequestId != null,
                        corruptRemovalConfirmationVisible = corruptRemovalConfirmationVisible,
                        discardConfirmationVisible = discardConfirmationVisible,
                        destinationReady = folderOperations != null,
                        folderPickerOperations = folderOperations,
                        folderPickerVisible = folderPickerVisible,
                        onChooseDestination = {
                            if (folderOperations == null) {
                                request?.id?.let { restoreOrStage(intent, it) }
                            } else {
                                folderPickerVisible = true
                            }
                        },
                        onDestinationSelected = { path ->
                            folderPickerVisible = false
                            enqueue(path)
                        },
                        onFolderPickerDismissed = { folderPickerVisible = false },
                        onCancel = ::cancelOrRequestDiscard,
                        onDone = ::finishOrReleaseReviewedRequest,
                        onVerifyOutcome = ::verifyUnknownOutcome,
                        onConfirmDiscard = ::confirmDiscardAndFinish,
                        onDismissDiscard = { discardConfirmationVisible = false },
                        onRemoveCorruptRecovery = { corruptRemovalConfirmationVisible = true },
                        onConfirmRemoveCorruptRecovery = ::removeCorruptRecoveryAndFinish,
                        onDismissRemoveCorruptRecovery = { corruptRemovalConfirmationVisible = false },
                    )
                    LaunchedEffect(request?.state) {
                        while (request?.state in ACTIVE_SHARE_STATES) {
                            delay(500)
                            request?.id?.let(store::load)?.let { request = it }
                        }
                    }
                }
            }
        }
        restoreOrStage(
            sourceIntent = intent,
            restoredRequestId = savedInstanceState?.getString(KEY_REQUEST_ID)
                ?: intent.getStringExtra(KEY_REQUEST_ID),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestId = intent.getStringExtra(KEY_REQUEST_ID)
        if (requestId != null || intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            releaseSafeDisplayedRequestForReplacement()
            loading = true
            error = null
            restoreOrStage(intent, requestId)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        request?.id?.let { outState.putString(KEY_REQUEST_ID, it) }
        outState.putBoolean(KEY_FOLDER_PICKER_VISIBLE, folderPickerVisible)
        super.onSaveInstanceState(outState)
    }

    private fun restoreOrStage(sourceIntent: Intent, restoredRequestId: String?) {
        restoreJob?.cancel()
        val generation = ++restoreGeneration
        val validatedRequestId = restoredRequestId?.takeIf(::isValidIncomingShareRequestId)
        if (restoredRequestId != null && validatedRequestId == null) {
            error = "This shared upload reference is invalid."
            loading = false
            return
        }
        corruptRecoveryRequestId = null
        corruptRecoveryAccountId = null
        corruptRemovalConfirmationVisible = false
        discardConfirmationVisible = false
        restoreJob = lifecycleScope.launch {
            var unclaimedStagedRequestId: String? = null
            var activeAccountId: String? = null
            try {
                val activeSession = services.loadSession()
                    ?: error("Sign in to nati.ve before sharing files to it.")
                activeAccountId = NextcloudDocumentIds.accountKey(activeSession)
                val staged = withContext(Dispatchers.IO) {
                    ANDROID_ACCOUNT_OPERATION_GUARD.withExactAccountSession(
                        expectedSession = activeSession,
                        resolveSession = { services.loadSession(activeSession.accountId) },
                        unavailable = { error("The account changed before the shared files could be prepared.") },
                    ) {
                        val restored = validatedRequestId?.let { requestId ->
                            store.requireAvailable(requestId)
                        } ?: store.stage(
                            sourceIntent,
                            NextcloudDocumentIds.accountKey(activeSession),
                        ).also { newlyStaged ->
                            unclaimedStagedRequestId = newlyStaged.id
                        }
                        require(restored.accountId == NextcloudDocumentIds.accountKey(activeSession)) {
                            "Switch back to the account that received this share before reviewing it."
                        }
                        uploads.ensureQueuedRequestScheduled(restored)
                        restored
                    }
                }
                ensureActive()
                if (generation != restoreGeneration) return@launch
                unclaimedStagedRequestId = null
                request = staged
                session = activeSession
                val info = services.loadServerInfo(activeSession)
                serverInfo = info
            } catch (_: CancellationException) {
                return@launch
            } catch (failure: Throwable) {
                val corrupt = failure as? CorruptIncomingShareManifestException
                corruptRecoveryRequestId = corrupt?.requestId
                    ?.takeIf { activeAccountId != null && corrupt.accountId == activeAccountId }
                corruptRecoveryAccountId = activeAccountId.takeIf { corruptRecoveryRequestId != null }
                error = failure.message ?: "The shared files could not be prepared."
            } finally {
                unclaimedStagedRequestId?.let { requestId ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        store.removeIfReleasable(requestId)
                    }
                }
            }
            if (generation == restoreGeneration) loading = false
        }
    }

    private fun releaseSafeDisplayedRequestForReplacement() {
        restoreGeneration += 1
        restoreJob?.cancel()
        queueJob?.cancel()
        request?.takeIf(AndroidIncomingShareRequest::canReleaseForIncomingShareReplacement)?.let { current ->
            scheduleIncomingSharePresentedRelease(applicationContext, current)
        }
        request = null
        session = null
        serverInfo = null
        corruptRecoveryRequestId = null
        corruptRecoveryAccountId = null
        corruptRemovalConfirmationVisible = false
        discardConfirmationVisible = false
        queueing = false
        folderPickerVisible = false
    }

    private fun enqueue(destinationPath: String) {
        val activeSession = session ?: return
        val info = serverInfo ?: return
        val staged = request ?: return
        val generation = restoreGeneration
        queueing = true
        error = null
        queueJob?.cancel()
        queueJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ANDROID_ACCOUNT_OPERATION_GUARD.withExactAccountSession(
                        expectedSession = activeSession,
                        resolveSession = { services.loadSession(activeSession.accountId) },
                        unavailable = { error("The account changed before the upload could be queued.") },
                    ) { current ->
                        uploads.enqueue(current, info.userId, staged.id, destinationPath)
                    }
                }
            }
            if (!isCurrentIncomingShareEnqueue(generation, restoreGeneration, staged.id, request?.id)) return@launch
            result.onSuccess { queued -> request = queued }
                .onFailure { failure -> error = failure.message ?: "The upload could not be queued." }
            queueing = false
        }
    }

    private fun cancelOrRequestDiscard() {
        val current = request
        if (current != null && current.state in ACTIVE_SHARE_STATES) {
            uploads.cancel(current.id)
            request = store.load(current.id)
        } else if (
            current?.state in setOf(
                AndroidIncomingShareState.Staged,
                AndroidIncomingShareState.Failed,
                AndroidIncomingShareState.OutcomeUnknown,
                AndroidIncomingShareState.Canceled,
            )
        ) {
            discardConfirmationVisible = true
        } else {
            finishKeepingRecovery()
        }
    }

    private fun finishOrReleaseReviewedRequest() {
        if (request?.state == AndroidIncomingShareState.Completed) {
            finishAndRelease()
        } else {
            finishKeepingRecovery()
        }
    }

    private fun confirmDiscardAndFinish() {
        discardConfirmationVisible = false
        request?.let { scheduleIncomingSharePresentedDiscard(applicationContext, it) }
        finishKeepingRecovery()
    }

    private fun verifyUnknownOutcome() {
        val current = request ?: return
        val activeSession = session ?: return
        val info = serverInfo ?: return
        val targetName = current.visibleMutationTargetName ?: current.chunkSession?.targetName
        if (targetName == null || current.destinationPath == null) {
            error = "This older recovery does not identify the exact remote target. Review it in Files."
            return
        }
        val generation = restoreGeneration
        queueing = true
        error = null
        queueJob?.cancel()
        queueJob = lifecycleScope.launch {
            try {
                val verified = withContext(Dispatchers.IO) {
                    require(current.accountId == NextcloudDocumentIds.accountKey(activeSession)) {
                        "Switch back to the upload account before verifying this result."
                    }
                    require(current.userId == info.userId) {
                        "The active Nextcloud user does not match this recovery."
                    }
                    val cancellation = CoroutineDocumentRequestCancellation(
                        requireNotNull(kotlin.coroutines.coroutineContext[Job]),
                    )
                    cancellation.use {
                        val remote = AndroidFileSyncRemoteTree(
                            activeSession,
                            info.userId,
                            current.destinationPath,
                            permissionWebDav,
                        )
                        store.recordUnknownOutcomeVerification(
                            current.id,
                            remote.resourceExists(targetName, cancellation),
                        )
                    }
                }
                if (isCurrentIncomingShareEnqueue(generation, restoreGeneration, current.id, request?.id)) {
                    request = verified
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (failure: Throwable) {
                if (isCurrentIncomingShareEnqueue(generation, restoreGeneration, current.id, request?.id)) {
                    error = failure.message ?: "Nextcloud could not verify the upload result."
                }
            } finally {
                if (isCurrentIncomingShareEnqueue(generation, restoreGeneration, current.id, request?.id)) {
                    queueing = false
                }
            }
        }
    }

    private fun finishKeepingRecovery() {
        restoreGeneration += 1
        restoreJob?.cancel()
        queueJob?.cancel()
        finish()
    }

    private fun finishAndRelease() {
        restoreGeneration += 1
        restoreJob?.cancel()
        queueJob?.cancel()
        val presented = request
        if (presented?.canReleaseIncomingShareRequest() == true) {
            scheduleIncomingSharePresentedRelease(applicationContext, presented)
        }
        finish()
    }

    private fun removeCorruptRecoveryAndFinish() {
        val requestId = corruptRecoveryRequestId ?: return
        val accountId = corruptRecoveryAccountId ?: return
        corruptRemovalConfirmationVisible = false
        scheduleCorruptIncomingShareRemoval(applicationContext, requestId, accountId)
        finish()
    }

    internal companion object {
        const val KEY_REQUEST_ID = "incoming_share_request_id"
        const val KEY_FOLDER_PICKER_VISIBLE = "incoming_share_folder_picker_visible"
        val ACTIVE_SHARE_STATES = setOf(
            AndroidIncomingShareState.Queued,
            AndroidIncomingShareState.Uploading,
        )
    }
}

internal fun isCurrentIncomingShareEnqueue(
    enqueueGeneration: Long,
    currentGeneration: Long,
    queuedRequestId: String,
    currentRequestId: String?,
): Boolean = enqueueGeneration == currentGeneration && queuedRequestId == currentRequestId

internal fun isValidIncomingShareRequestId(value: String): Boolean =
    runCatching { UUID.fromString(value) }.isSuccess

internal fun AndroidIncomingShareRequest.canReleaseForIncomingShareReplacement(): Boolean =
    chunkSession == null && state == AndroidIncomingShareState.Completed

private fun AndroidShareUploadActivity.incomingShareFolderPickerOperations(
    services: AndroidNextcloudServices,
    session: NextcloudSession,
    userId: String,
    webDav: NextcloudDocumentWebDav,
): RemoteFolderPickerOperations {
    val base = remoteFolderPickerOperations(services, session, userId)
    return RemoteFolderPickerOperations(
        identity = base.identity + "|incoming-share",
        listCached = base.listCached,
        listNetwork = base.listNetwork,
        createDirectoryIfAbsent = { path ->
            val parent = path.substringBeforeLast('/', "")
            val access = inspectIncomingShareDirectoryAccess(session, userId, parent, webDav)
            require(access.canCreateDirectories) {
                "This Nextcloud folder does not allow creating subfolders."
            }
            base.createDirectoryIfAbsent(path)
        },
        selectionAccess = { path ->
            incomingShareFolderSelectionAccess(
                inspectIncomingShareDirectoryAccess(session, userId, path, webDav),
            )
        },
    )
}

internal fun incomingShareFolderSelectionAccess(access: DocumentDirectoryAccess): RemoteFolderSelectionAccess = when {
    access.canCreateFiles -> RemoteFolderSelectionAccess.Allowed
    access.canCreateDirectories -> RemoteFolderSelectionAccess.DirectoryCreationOnly
    else -> RemoteFolderSelectionAccess.Denied("This Nextcloud folder is read-only for this account.")
}

private suspend fun inspectIncomingShareDirectoryAccess(
    session: NextcloudSession,
    userId: String,
    path: String,
    webDav: NextcloudDocumentWebDav,
): DocumentDirectoryAccess = withContext(Dispatchers.IO) {
    val job = requireNotNull(coroutineContext[Job])
    CoroutineDocumentRequestCancellation(job).use { cancellation ->
        AndroidFileSyncRemoteTree(session, userId, path, webDav).directoryAccess(cancellation)
    }
}

internal fun AndroidIncomingShareRequest.toPresentation(): IncomingShareUploadPresentation =
    IncomingShareUploadPresentation(
        id = id,
        files = files.mapIndexed { index, file ->
            val status = when {
                index < completedFiles -> IncomingShareUploadFileStatus.Uploaded
                index == completedFiles && state == AndroidIncomingShareState.Failed ->
                    IncomingShareUploadFileStatus.Failed
                index == completedFiles && state == AndroidIncomingShareState.OutcomeUnknown ->
                    IncomingShareUploadFileStatus.OutcomeUnknown
                index == completedFiles && state == AndroidIncomingShareState.Canceled &&
                    message == CANCELED_INCOMING_SHARE_MUTATION_WARNING ->
                    IncomingShareUploadFileStatus.OutcomeUnknown
                else -> IncomingShareUploadFileStatus.Pending
            }
            IncomingShareUploadFilePresentation(
                id = file.id,
                displayName = file.displayName,
                sizeBytes = file.sizeBytes,
                status = status,
                uploadedName = uploadedNames.getOrNull(index),
            )
        },
        state = IncomingShareUploadState.valueOf(state.name),
        destinationPath = destinationPath,
        completedFiles = completedFiles,
        message = message,
        canVerifyOutcome = (visibleMutationTargetName != null || chunkSession?.targetName != null) && (
            state == AndroidIncomingShareState.OutcomeUnknown ||
                state == AndroidIncomingShareState.Canceled && message == CANCELED_INCOMING_SHARE_MUTATION_WARNING
            ),
    )
