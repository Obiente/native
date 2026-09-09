package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.PlatformBackHandler

internal enum class NativeInlineEditorIntent { Navigate, Refresh }

internal data class NativeInlineEditorLeaveRequest(
    val proceed: () -> Unit,
    val cancel: () -> Unit = {},
    val intent: NativeInlineEditorIntent = NativeInlineEditorIntent.Navigate,
)

/** One account-scoped edit session guards both shell navigation and workspace navigation. */
internal class NativeInlineEditorNavigation {
    private var owner: Any? = null
    private var handler: ((NativeInlineEditorLeaveRequest) -> Unit)? = null
    var active by mutableStateOf(false)
        private set

    fun register(owner: Any, handler: (NativeInlineEditorLeaveRequest) -> Unit) {
        this.owner = owner
        this.handler = handler
        active = true
    }

    fun unregister(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        handler = null
        active = false
    }

    fun intercept(proceed: () -> Unit, cancel: () -> Unit = {}): Boolean {
        val current = handler ?: return false
        current(NativeInlineEditorLeaveRequest(proceed, cancel))
        return true
    }

    fun navigate(proceed: () -> Unit) {
        if (!intercept(proceed)) proceed()
    }

    fun refresh(proceed: () -> Unit) {
        val current = handler
        if (current == null) proceed()
        else current(NativeInlineEditorLeaveRequest(proceed = proceed, intent = NativeInlineEditorIntent.Refresh))
    }
}

internal val LocalNativeInlineEditorNavigation = compositionLocalOf<NativeInlineEditorNavigation?> { null }

internal enum class NativeInlineEditorLeaveDecision { Leave, ConfirmDiscard, Block }

internal fun nativeInlineEditorLeaveDecision(dirty: Boolean, submissionBlocked: Boolean) = when {
    submissionBlocked -> NativeInlineEditorLeaveDecision.Block
    dirty -> NativeInlineEditorLeaveDecision.ConfirmDiscard
    else -> NativeInlineEditorLeaveDecision.Leave
}

@Composable
internal fun rememberNativeInlineEditorCloseRequest(
    enabled: Boolean,
    dirty: Boolean,
    submissionBlocked: Boolean,
    onClose: () -> Unit,
    allowReconciliationRefresh: Boolean = false,
    discardTitle: String = "Discard unsaved changes?",
    discardMessage: String = "Your changes have not been saved. Leaving will discard this draft.",
    discardActionLabel: String = "Discard changes",
    navigation: NativeInlineEditorNavigation? = LocalNativeInlineEditorNavigation.current,
): () -> Unit {
    val owner = remember { Any() }
    var requestedLeave by remember { mutableStateOf<NativeInlineEditorLeaveRequest?>(null) }
    var requestedWhileBlocked by remember { mutableStateOf(false) }
    val currentDirty by rememberUpdatedState(dirty)
    val currentBlocked by rememberUpdatedState(submissionBlocked)
    val currentAllowReconciliationRefresh by rememberUpdatedState(allowReconciliationRefresh)
    val currentClose by rememberUpdatedState(onClose)
    fun leave(request: NativeInlineEditorLeaveRequest) {
        navigation?.unregister(owner)
        requestedLeave = null
        currentClose()
        request.proceed()
    }
    val requestLeave: (NativeInlineEditorLeaveRequest) -> Unit = { request ->
        requestedLeave?.cancel?.invoke()
        if (request.intent == NativeInlineEditorIntent.Refresh && currentAllowReconciliationRefresh) {
            requestedLeave = null
            request.proceed()
        } else {
            when (nativeInlineEditorLeaveDecision(currentDirty, currentBlocked)) {
                NativeInlineEditorLeaveDecision.Leave -> leave(request)
                else -> {
                    requestedWhileBlocked = currentBlocked
                    requestedLeave = request
                }
            }
        }
    }
    LaunchedEffect(submissionBlocked) {
        if (!submissionBlocked) {
            requestedWhileBlocked = false
        } else if (!requestedWhileBlocked) {
            val superseded = requestedLeave
            requestedLeave = null
            superseded?.cancel?.invoke()
        }
    }
    val currentRequestLeave by rememberUpdatedState(requestLeave)
    DisposableEffect(navigation, enabled, owner) {
        if (enabled) navigation?.register(owner) { request -> currentRequestLeave(request) }
        onDispose {
            navigation?.unregister(owner)
            requestedLeave?.cancel?.invoke()
        }
    }
    PlatformBackHandler(enabled = enabled) {
        currentRequestLeave(NativeInlineEditorLeaveRequest(proceed = {}))
    }
    requestedLeave?.let { request ->
        fun cancelLeave() {
            requestedLeave = null
            request.cancel()
        }
        AlertDialog(
            onDismissRequest = ::cancelLeave,
            title = { Text(if (currentBlocked) "Save not finished" else discardTitle) },
            text = {
                Text(if (currentBlocked) {
                    "Wait until the save finishes and its result has been checked before leaving this editor."
                } else {
                    discardMessage
                })
            },
            confirmButton = {
                if (!currentBlocked) TextButton(onClick = { leave(request) }) { Text(discardActionLabel) }
                else TextButton(onClick = ::cancelLeave) { Text("Stay here") }
            },
            dismissButton = {
                if (!currentBlocked) TextButton(onClick = ::cancelLeave) { Text("Keep editing") }
            },
        )
    }
    return if (enabled) {
        { currentRequestLeave(NativeInlineEditorLeaveRequest(proceed = {})) }
    } else onClose
}
