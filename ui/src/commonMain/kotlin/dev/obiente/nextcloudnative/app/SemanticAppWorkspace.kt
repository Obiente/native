package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NoteOperation
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticNotesContract
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticNotesContract

/**
 * A native surface chosen from the shape of an app's discovered contract.
 *
 * Selection never consults the app identifier or version: any app whose own contract has the
 * board shape gets the board workspace, and any app with the notes shape gets the notes
 * workspace. Everything else stays with the generic dynamic renderer.
 */
internal sealed interface SemanticAppWorkspace {
    val descriptor: DynamicAppDescriptor

    data class Board(val contract: SemanticBoardContract) : SemanticAppWorkspace {
        override val descriptor: DynamicAppDescriptor get() = contract.descriptor
    }

    data class Notes(val contract: SemanticNotesContract) : SemanticAppWorkspace {
        override val descriptor: DynamicAppDescriptor get() = contract.descriptor
    }
}

/** Board takes precedence because a board's cards can also carry a text body. */
internal fun selectSemanticAppWorkspace(discovery: DynamicDescriptorDiscovery): SemanticAppWorkspace? {
    val writesAuthorized = discovery.writeAuthority() != null
    val descriptor = discovery.descriptor
    resolveSemanticBoardContract(descriptor, writesAuthorized)?.let { return SemanticAppWorkspace.Board(it) }
    resolveSemanticNotesContract(descriptor, writesAuthorized)?.let { return SemanticAppWorkspace.Notes(it) }
    return null
}

/**
 * Sends one request, holding any write until the contract check confirms the same verified
 * contract the action was offered from. Reads never wait.
 */
internal fun gatedSemanticExecute(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    writeGate: DynamicContractWriteGate,
    descriptor: DynamicAppDescriptor,
): suspend (NextcloudApiRequest) -> NextcloudApiResponse = { request ->
    if (request.method != NextcloudApiMethod.GET) {
        check(writeGate.authorizesWrite(ActionRisk.mutating, descriptor)) {
            "The app's contract is being checked again. Try this change once it is confirmed."
        }
    }
    services.executeNextcloudApi(session, request)
}

/** The saved location of an open note: its id as the selected record of the notes collection. */
internal fun openNoteNavigation(noteId: Long): DynamicAppNavigationState = DynamicAppNavigationState(
    selectedRecord = NativeRecord(id = noteId.toString(), values = emptyMap(), actionSafeIdentity = false),
    selectedRecordResourceId = OPEN_NOTE_RESOURCE,
)

/** A saved open note, or null when the location names no valid note. */
internal fun DynamicAppNavigationState.openNoteId(): Long? =
    selectedRecord?.id?.toLongOrNull()?.takeIf { it >= 0L && selectedRecordResourceId == OPEN_NOTE_RESOURCE }

private const val OPEN_NOTE_RESOURCE = "notes"

@Composable
internal fun SemanticAppWorkspaceScreen(
    workspace: SemanticAppWorkspace,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    currentUserId: String,
    writeGate: DynamicContractWriteGate,
    filesAccess: NotesFilesAccess?,
    onMutationInProgressChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navigation: DynamicAppNavigationState = DynamicAppNavigationState(),
    onNavigationChanged: (DynamicAppNavigationState) -> Unit = {},
) {
    val execute = remember(services, session, writeGate, workspace.descriptor) {
        gatedSemanticExecute(services, session, writeGate, workspace.descriptor)
    }
    val settings = remember(workspace) {
        when (workspace) {
            is SemanticAppWorkspace.Board -> workspace.contract.settings
            is SemanticAppWorkspace.Notes -> workspace.contract.settings
        }?.let { contract -> SemanticSettings(workspace.descriptor, contract) }
    }
    when (workspace) {
        is SemanticAppWorkspace.Board -> NativeDeckScreen(
            services = services,
            session = session,
            routes = remember(workspace) { SemanticBoardRoutes(workspace.contract) },
            appSettings = settings,
            execute = execute,
            authorizeWrite = { writeGate.authorizesWrite(ActionRisk.mutating, workspace.descriptor) },
            currentUserId = currentUserId,
            onBack = onBack,
            modifier = modifier,
        )
        is SemanticAppWorkspace.Notes -> NextcloudNotesScreen(
            services = services,
            session = session,
            noteStore = remember(workspace, execute) { SemanticNotesRepository(execute, workspace.contract) },
            onBack = onBack,
            // Settings writes go through the same contract-gated execute as note writes.
            settings = remember(settings, execute) { settings?.let { NotesSettingsAccess(it, execute) } },
            // The open note is the app's saved location, so relaunch and older saved editors reopen it.
            initialOpenNoteId = remember(workspace) { navigation.openNoteId() },
            onOpenNoteChanged = { noteId -> onNavigationChanged(noteId?.let(::openNoteNavigation) ?: DynamicAppNavigationState()) },
            onMutationInProgressChanged = onMutationInProgressChanged,
            filesAccess = filesAccess,
        )
    }
}
