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
import androidx.core.app.NotificationManagerCompat
import dev.obiente.nextcloudnative.app.IncomingShareUploadFilePresentation
import dev.obiente.nextcloudnative.app.IncomingShareUploadFileStatus
import dev.obiente.nextcloudnative.app.IncomingShareUploadPresentation
import dev.obiente.nextcloudnative.app.IncomingShareUploadScreen
import dev.obiente.nextcloudnative.app.IncomingShareUploadState
import dev.obiente.nextcloudnative.app.NextcloudServerInfo
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.remoteFolderPickerOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android share-sheet entry point. URI grants are staged before the user chooses a DAV folder. */
class AndroidShareUploadActivity : ComponentActivity() {
    private lateinit var store: AndroidIncomingShareStore
    private lateinit var uploads: AndroidIncomingShareUploads
    private lateinit var services: AndroidNextcloudServices
    private var request by mutableStateOf<AndroidIncomingShareRequest?>(null)
    private var session by mutableStateOf<NextcloudSession?>(null)
    private var serverInfo by mutableStateOf<NextcloudServerInfo?>(null)
    private var loading by mutableStateOf(true)
    private var queueing by mutableStateOf(false)
    private var folderPickerVisible by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidIncomingShareStore(applicationContext)
        uploads = AndroidIncomingShareUploads(applicationContext)
        services = AndroidNextcloudServices(applicationContext)
        setContent {
            NextcloudNativeTheme {
                NextcloudAppBackground {
                    BackHandler {
                        if (request?.state in ACTIVE_SHARE_STATES) finishAndRelease() else cancelOrDismiss()
                    }
                    val activeSession = session
                    val activeUserId = serverInfo?.userId
                    val folderOperations = remember(activeSession, activeUserId) {
                        if (activeSession != null && activeUserId != null) {
                            remoteFolderPickerOperations(services, activeSession, activeUserId)
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
                        folderPickerOperations = folderOperations,
                        folderPickerVisible = folderPickerVisible,
                        onChooseDestination = { folderPickerVisible = true },
                        onDestinationSelected = { path ->
                            folderPickerVisible = false
                            enqueue(path)
                        },
                        onFolderPickerDismissed = { folderPickerVisible = false },
                        onCancel = ::cancelOrDismiss,
                        onDone = ::finishAndRelease,
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
        restoreOrStage(savedInstanceState?.getString(KEY_REQUEST_ID) ?: intent.getStringExtra(KEY_REQUEST_ID))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(KEY_REQUEST_ID)?.let { requestId ->
            loading = true
            error = null
            restoreOrStage(requestId)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        request?.id?.let { outState.putString(KEY_REQUEST_ID, it) }
        super.onSaveInstanceState(outState)
    }

    private fun restoreOrStage(restoredRequestId: String?) {
        lifecycleScope.launch {
            runCatching {
                val activeSession = services.loadSession()
                    ?: error("Sign in to Nextcloud Native before sharing files to it.")
                val staged = withContext(Dispatchers.IO) {
                    restoredRequestId?.let { requestId ->
                        requireNotNull(store.load(requestId)) { "This shared upload is no longer available." }
                    } ?: store.stage(intent)
                }
                val info = services.loadServerInfo(activeSession)
                Triple(activeSession, info, staged)
            }.onSuccess { (activeSession, info, staged) ->
                session = activeSession
                serverInfo = info
                request = staged
            }.onFailure { failure ->
                error = failure.message ?: "The shared files could not be prepared."
            }
            loading = false
        }
    }

    private fun enqueue(destinationPath: String) {
        val activeSession = session ?: return
        val info = serverInfo ?: return
        val staged = request ?: return
        queueing = true
        error = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    uploads.enqueue(activeSession, info.userId, staged.id, destinationPath)
                }
            }.onSuccess { queued -> request = queued }
                .onFailure { failure -> error = failure.message ?: "The upload could not be queued." }
            queueing = false
        }
    }

    private fun cancelOrDismiss() {
        val current = request
        if (current != null && current.state in ACTIVE_SHARE_STATES) {
            uploads.cancel(current.id)
            request = store.load(current.id)
        } else {
            finishAndRelease()
        }
    }

    private fun finishAndRelease() {
        request?.takeIf { it.state !in ACTIVE_SHARE_STATES }?.let {
            store.remove(it.id)
            NotificationManagerCompat.from(this).cancel(incomingShareNotificationId(it.id))
        }
        finish()
    }

    internal companion object {
        const val KEY_REQUEST_ID = "incoming_share_request_id"
        val ACTIVE_SHARE_STATES = setOf(
            AndroidIncomingShareState.Queued,
            AndroidIncomingShareState.Uploading,
        )
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
    )
