package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Production host for the native Deck workspace.
 *
 * UI callbacks receive typed board, stack, card, label, user, comment, and attachment context.
 * The host never asks for resource ids and refreshes authoritative server state after every write.
 */
@Composable
fun NativeDeckScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    currentUserId: String = session.loginName,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val retainedWorkspace = remember(session) { DeckWorkspaceMemoryCache.get(session) }
    var state by remember(session) {
        mutableStateOf<DeckWorkspaceState>(retainedWorkspace?.state ?: DeckWorkspaceState.Loading)
    }
    var loadedBoards by remember(session) {
        mutableStateOf(retainedWorkspace?.loadedBoards.orEmpty())
    }
    var capabilities by remember(session) { mutableStateOf(retainedWorkspace?.capabilities) }
    var activeRoute by remember(session) { mutableStateOf(retainedWorkspace?.activeRoute) }
    var requestedBoard by remember(session) { mutableStateOf(retainedWorkspace?.requestedBoard) }
    var requestedBoardId by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf(retainedWorkspace?.requestedBoardId)
    }
    var requestedCardId by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf(retainedWorkspace?.requestedCardId)
    }
    var loadAttempt by remember(session) { mutableStateOf(0) }
    var interaction by remember(session) { mutableStateOf<DeckUiInteraction?>(null) }
    val cardDraftRecovery = remember(services, session) {
        DeckCardDraftRecoveryCoordinator(services, session)
    }
    var mutationBusy by remember(session) { mutableStateOf(false) }
    var mutationOutcomeUnknown by remember(session) { mutableStateOf(false) }
    var mutationError by remember(session) { mutableStateOf<String?>(null) }
    var confirmedCommentComposerRevision by remember(session) { mutableStateOf(0L) }
    var confirmedLabelEditorRevision by remember(session) { mutableStateOf(0L) }
    var boardRecovery by remember(session) { mutableStateOf<DeckUiBoardRecovery?>(null) }
    var commentsState by remember(session) {
        mutableStateOf(DeckUiCommentsState(comments = emptyList()))
    }
    var commentRecords by remember(session) { mutableStateOf<Map<String, DeckComment>>(emptyMap()) }
    var attachmentsState by remember(session) {
        mutableStateOf(DeckUiAttachmentsState(attachments = emptyList()))
    }
    var attachmentRecords by remember(session) {
        mutableStateOf<Map<String, DeckAttachment>>(emptyMap())
    }
    val cardSelectionLoadGate = remember(session) { DeckCardLoadGate() }
    val authoritativeActionLoadGate = remember(session) { DeckCardLoadGate() }
    val commentsLoadGate = remember(session) { DeckCardLoadGate() }
    val attachmentsLoadGate = remember(session) { DeckCardLoadGate() }

    LaunchedEffect(
        session,
        state,
        loadedBoards,
        capabilities,
        activeRoute,
        requestedBoard,
        requestedBoardId,
        requestedCardId,
    ) {
        val cacheProducer = DeckWorkspaceMemoryCache.producer(session)
        DeckWorkspaceMemoryCache.store(
            session,
            DeckWorkspaceMemorySnapshot(
                state = state,
                loadedBoards = loadedBoards,
                capabilities = capabilities,
                activeRoute = activeRoute,
                requestedBoard = requestedBoard,
                requestedBoardId = requestedBoardId,
                requestedCardId = requestedCardId,
            ),
            cacheProducer,
        )
    }

    fun boardState(): DeckWorkspaceState.Board? = state as? DeckWorkspaceState.Board

    fun stackFor(card: DeckCard): DeckStack =
        boardState()?.stacks?.firstOrNull { it.id == card.stackId }
            ?: error("The Deck card list is unavailable.")

    fun selectedRoute(): DeckReadRoutePlan =
        checkNotNull(activeRoute) { "The verified Deck API route is unavailable." }

    fun writePlan(): DeckWriteRoutePlan = DeckWriteRoutePlan(selectedRoute().version)

    fun boardAccess(board: DeckBoard): DeckBoardAccess = DeckBoardAccess.from(board)

    fun stackContext(stack: DeckStack): DeckStackContext =
        DeckStackContext(DeckBoardId(stack.boardId), stack.id)

    fun cardContext(card: DeckCard): DeckCardContext =
        DeckCardContext(
            stack = DeckStackContext(DeckBoardId(card.boardId), card.stackId),
            cardId = card.id,
        )

    fun cardDraftKey(editor: DeckUiInteraction.CardEditor): DeckCardDraftKey =
        DeckCardDraftKey(
            boardId = editor.card?.boardId ?: editor.stack.boardId,
            stackId = editor.stack.id,
            cardId = editor.card?.id,
        )

    suspend fun fetchAuthoritativeCard(card: DeckCard): DeckCard {
        val route = selectedRoute()
        val response = services.executeNextcloudApi(
            session,
            route.authoritativeCard(card.boardId, card.stackId, card.id),
        )
        return parseDeckResponseOffUi {
            parseDeckCard(card.boardId, card.stackId, response)
        }
    }

    suspend fun fetchAuthoritativeBoard(board: DeckBoard): DeckBoard {
        val route = selectedRoute()
        val response = services.executeNextcloudApi(session, route.authoritativeBoard(board.id))
        return parseDeckResponseOffUi {
            parseDeckBoard(response)
        }
    }

    fun replaceCard(card: DeckCard, select: Boolean = true) {
        val board = boardState() ?: return
        state = board.copy(
            stacks = board.stacks.map { stack ->
                if (stack.id != card.stackId) {
                    stack
                } else {
                    stack.copy(
                        cards = stack.cards.map { current ->
                            if (current.id == card.id) card else current
                        },
                    )
                }
            },
            selectedCardId = if (select) card.id else board.selectedCardId,
        )
    }

    fun refreshBoard() {
        loadAttempt += 1
    }

    fun runMutation(
        request: () -> NextcloudApiRequest,
        returnToBoards: Boolean = false,
        dismissInteractionOnSuccess: Boolean = true,
        outcomeUnknownBoardRecovery: DeckBoard? = null,
        afterSuccess: suspend () -> Unit = {},
        onResponse: (NextcloudApiResponse) -> Unit = { response ->
            parseDeckMutationReceipt(response)
        },
    ) {
        if (mutationBusy || mutationOutcomeUnknown) return
        scope.launch {
            mutationBusy = true
            mutationError = null
            var responseStatus: Int? = null
            runCatching {
                services.executeNextcloudApi(session, request()).also {
                    responseStatus = it.status
                    onResponse(it)
                }
            }.onSuccess {
                if (dismissInteractionOnSuccess) interaction = null
                afterSuccess()
                if (returnToBoards) {
                    requestedBoard = null
                    requestedBoardId = null
                    requestedCardId = null
                    loadedBoards = emptyList()
                    state = DeckWorkspaceState.Loading
                }
                refreshBoard()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (isAmbiguousDeckMutationFailure(responseStatus)) {
                    mutationOutcomeUnknown = true
                    mutationError = "Deck may have applied this action. Refreshing before another change."
                    outcomeUnknownBoardRecovery?.let { board ->
                        boardRecovery = DeckUiBoardRecovery(
                            board = board,
                            errorMessage = "Deck may have deleted this board. Checking the board list.",
                            verification = DeckBoardRecoveryVerification.DeleteOutcome,
                        )
                    }
                    if (returnToBoards) {
                        requestedBoard = null
                        requestedBoardId = null
                        requestedCardId = null
                        state = DeckWorkspaceState.Loading
                    }
                    refreshBoard()
                } else {
                    mutationError = error.deckMessage()
                }
            }
            mutationBusy = false
        }
    }

    fun restoreDeletedBoard(board: DeckBoard) {
        if (mutationBusy || mutationOutcomeUnknown) return
        scope.launch {
            mutationBusy = true
            mutationError = null
            boardRecovery = DeckUiBoardRecovery(board = board, restoring = true)
            var responseStatus: Int? = null
            runCatching {
                services.executeNextcloudApi(
                    session,
                    writePlan().restoreBoard(boardAccess(board)),
                ).also {
                    responseStatus = it.status
                    parseDeckMutationReceipt(it, expectedId = board.id)
                }
            }.onSuccess {
                boardRecovery = null
                loadedBoards = emptyList()
                refreshBoard()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                boardRecovery = if (isAmbiguousDeckMutationFailure(responseStatus)) {
                    mutationOutcomeUnknown = true
                    DeckUiBoardRecovery(
                        board = board,
                        restoring = false,
                        errorMessage = "Deck may have restored this board. Checking the board list.",
                        verification = DeckBoardRecoveryVerification.RestoreOutcome,
                    ).also {
                        loadedBoards = emptyList()
                        refreshBoard()
                    }
                } else {
                    DeckUiBoardRecovery(
                        board = board,
                        restoring = false,
                        errorMessage = error.deckMessage(),
                    )
                }
            }
            mutationBusy = false
        }
    }

    fun runRevalidatedCardMutation(
        original: DeckCard,
        conflictInteraction: (DeckCard, DeckStack) -> DeckUiInteraction,
        update: (DeckCard) -> DeckCardUpdate,
        afterSuccess: suspend () -> Unit = {},
    ) {
        if (mutationBusy || mutationOutcomeUnknown) return
        scope.launch {
            mutationBusy = true
            mutationError = null
            runCatching { fetchAuthoritativeCard(original) }
                .onSuccess { current ->
                    replaceCard(current)
                    if (!original.hasSameAuthoritativeRevision(current)) {
                        interaction = conflictInteraction(current, stackFor(current))
                        mutationError = "This card changed elsewhere. Review the latest version before saving."
                    } else {
                        var responseStatus: Int? = null
                        runCatching {
                            services.executeNextcloudApi(
                                session,
                                writePlan().updateCard(
                                    boardAccess(checkNotNull(boardState()?.board)),
                                    cardContext(current),
                                    update(current),
                                ),
                            ).also {
                                responseStatus = it.status
                                parseDeckMutationReceipt(it)
                            }
                        }.onSuccess {
                            interaction = null
                            afterSuccess()
                            refreshBoard()
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            if (isAmbiguousDeckMutationFailure(responseStatus)) {
                                mutationOutcomeUnknown = true
                                mutationError =
                                    "Deck may have applied this card update. Refreshing before another change."
                                refreshBoard()
                            } else {
                                mutationError = error.deckMessage()
                            }
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutationError = error.deckMessage()
                }
            mutationBusy = false
        }
    }

    fun runRevalidatedBoardMutation(
        original: DeckBoard,
        update: (DeckBoard) -> DeckBoardUpdate,
    ) {
        if (mutationBusy || mutationOutcomeUnknown) return
        scope.launch {
            mutationBusy = true
            mutationError = null
            runCatching { fetchAuthoritativeBoard(original) }
                .onSuccess { current ->
                    requestedBoard = current
                    loadedBoards = loadedBoards.map { board ->
                        if (board.id == current.id) current else board
                    }
                    state = boardState()?.copy(board = current) ?: state
                    if (!original.hasSameAuthoritativeRevision(current)) {
                        interaction = DeckUiInteraction.BoardEditor(current)
                        mutationError =
                            "This board changed elsewhere. Review the latest version before saving."
                    } else {
                        var responseStatus: Int? = null
                        runCatching {
                            services.executeNextcloudApi(
                                session,
                                writePlan().updateBoard(
                                    boardAccess(current),
                                    update(current),
                                ),
                            ).also {
                                responseStatus = it.status
                                parseDeckMutationReceipt(it)
                            }
                        }.onSuccess {
                            interaction = null
                            requestedBoard = null
                            requestedBoardId = null
                            requestedCardId = null
                            loadedBoards = emptyList()
                            state = DeckWorkspaceState.Loading
                            refreshBoard()
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            if (isAmbiguousDeckMutationFailure(responseStatus)) {
                                mutationOutcomeUnknown = true
                                mutationError =
                                    "Deck may have applied this board update. Refreshing before another change."
                                requestedBoard = null
                                requestedBoardId = null
                                requestedCardId = null
                                state = DeckWorkspaceState.Loading
                                refreshBoard()
                            } else {
                                mutationError = error.deckMessage()
                            }
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutationError = error.deckMessage()
                }
            mutationBusy = false
        }
    }

    fun runMutations(
        card: DeckCard,
        requests: () -> List<NextcloudApiRequest>,
        reconciledInteraction: (DeckCard) -> DeckUiInteraction,
    ) {
        if (mutationBusy || mutationOutcomeUnknown) return
        scope.launch {
            mutationBusy = true
            mutationError = null
            var confirmedWrites = 0
            var failedResponseStatus: Int? = null
            runCatching {
                requests().forEach { request ->
                    val response = services.executeNextcloudApi(session, request)
                    failedResponseStatus = response.status
                    require(response.status in 200..299) {
                        "The Deck action failed (HTTP ${response.status})."
                    }
                    confirmedWrites += 1
                    failedResponseStatus = null
                }
            }.onSuccess {
                interaction = null
                refreshBoard()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutationError = error.deckMessage()
                val requiresReconciliation = requiresDeckBatchMutationReconciliation(
                    confirmedWrites = confirmedWrites,
                    failedResponseStatus = failedResponseStatus,
                )
                val reconciliation = runCatching { fetchAuthoritativeCard(card) }
                reconciliation
                    .onSuccess { current ->
                        replaceCard(current)
                        interaction = reconciledInteraction(current)
                    }
                if (requiresReconciliation && reconciliation.isFailure) {
                    mutationOutcomeUnknown = true
                    mutationError =
                        "Deck may have applied part of this change. Refreshing before another change."
                }
                refreshBoard()
            }
            mutationBusy = false
        }
    }

    fun moveCardToIndex(
        card: DeckCard,
        destinationStack: DeckStack,
        insertionIndex: Int,
    ) {
        val board = boardState()?.board ?: return
        runCatching {
            planDeckCardInsertion(
                source = card,
                destination = destinationStack,
                insertionIndex = insertionIndex,
            )
        }.onSuccess { plan ->
            when (plan) {
                is DeckCardInsertionPlan.MoveReady -> runMutation(
                    request = {
                        writePlan().moveCard(boardAccess(board), plan.move)
                    },
                    onResponse = { response ->
                        parseDeckCardMove(plan.move, response)
                    },
                )
                DeckCardInsertionPlan.Unchanged -> {
                    interaction = null
                    mutationError = null
                }
            }
        }.onFailure { error ->
            mutationError = error.deckMessage()
            interaction = DeckUiInteraction.MoveCard(card)
            refreshBoard()
        }
    }

    fun withAuthoritativeCard(
        card: DeckCard,
        action: (DeckCard, DeckStack) -> Unit,
    ) {
        val loadToken = authoritativeActionLoadGate.begin(card.id)
        scope.launch {
            mutationError = null
            runCatching { fetchAuthoritativeCard(card) }.onSuccess { current ->
                if (
                    authoritativeActionLoadGate.accepts(loadToken, card.id) &&
                    interaction == null
                ) {
                    replaceCard(current)
                    action(current, stackFor(current))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (
                    authoritativeActionLoadGate.accepts(loadToken, card.id) &&
                    interaction == null
                ) {
                    mutationError = error.deckMessage()
                }
            }
        }
    }

    fun showAuthoritativeCard(
        card: DeckCard,
        target: (DeckCard, DeckStack) -> DeckUiInteraction,
    ) = withAuthoritativeCard(card) { current, stack ->
        interaction = target(current, stack)
    }

    fun activeCommentsCardId(): Long? =
        (interaction as? DeckUiInteraction.Comments)?.card?.id

    fun activeAttachmentsCardId(): Long? =
        (interaction as? DeckUiInteraction.Attachments)?.card?.id

    fun loadComments(card: DeckCard, append: Boolean) {
        val board = boardState()?.board ?: return
        val loadToken = commentsLoadGate.begin(card.id)
        val offset = if (append) commentsState.comments.size else 0
        commentsState = commentsState.copy(
            loading = !append,
            loadingMore = append,
            errorMessage = null,
        )
        scope.launch {
            runCatching {
                val response = services.executeNextcloudApi(
                    session,
                    writePlan().comments(
                        access = boardAccess(board),
                        context = cardContext(card),
                        limit = DECK_COMMENT_PAGE_SIZE,
                        offset = offset,
                    ),
                )
                parseDeckResponseOffUi {
                    parseDeckComments(cardContext(card), response)
                }
            }.onSuccess { page ->
                if (!commentsLoadGate.accepts(loadToken, activeCommentsCardId())) {
                    return@onSuccess
                }
                val combined = if (append) {
                    (commentRecords.values + page).distinctBy(DeckComment::id)
                } else {
                    page
                }
                commentRecords = combined.associateBy { it.id.toString() }
                val canWriteComments = boardState()?.board?.canWriteDeckComments == true
                commentsState = DeckUiCommentsState(
                    comments = combined.map {
                        it.toDeckUiComment(currentUserId, canWriteComments)
                    },
                    hasMore = page.size == DECK_COMMENT_PAGE_SIZE,
                    canComment = canWriteComments,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!commentsLoadGate.accepts(loadToken, activeCommentsCardId())) {
                    return@onFailure
                }
                commentsState = commentsState.copy(
                    loading = false,
                    loadingMore = false,
                    errorMessage = error.deckMessage(),
                )
            }
        }
    }

    fun showComments(card: DeckCard) {
        commentsState = DeckUiCommentsState(
            comments = emptyList(),
            loading = true,
            canComment = boardState()?.board?.canWriteDeckComments == true,
        )
        commentRecords = emptyMap()
        interaction = DeckUiInteraction.Comments(card)
        loadComments(card, append = false)
    }

    fun runCommentMutation(
        card: DeckCard,
        request: () -> NextcloudApiRequest,
        onResponse: suspend (NextcloudApiResponse) -> Unit = { response ->
            parseDeckResponseOffUi {
                parseDeckCommentMutationReceipt(response)
            }
        },
        afterConfirmedSuccess: () -> Unit = {},
    ) {
        if (commentsState.submitting || mutationOutcomeUnknown) return
        val plannedRequest = runCatching(request).getOrElse { error ->
            commentsState = commentsState.copy(errorMessage = error.deckMessage())
            return
        }
        commentsState = commentsState.copy(submitting = true, errorMessage = null)
        scope.launch {
            var responseStatus: Int? = null
            runCatching {
                services.executeNextcloudApi(session, plannedRequest).also {
                    responseStatus = it.status
                    onResponse(it)
                }
            }.onSuccess {
                if (activeCommentsCardId() == card.id) {
                    afterConfirmedSuccess()
                    commentsState = commentsState.copy(submitting = false)
                    loadComments(card, append = false)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (activeCommentsCardId() != card.id) {
                    return@onFailure
                }
                if (isAmbiguousDeckMutationFailure(responseStatus)) {
                    mutationOutcomeUnknown = true
                    commentsState = commentsState.copy(
                        submitting = false,
                        errorMessage = "Deck may have applied this comment action. Checking the card first.",
                    )
                    loadComments(card, append = false)
                    refreshBoard()
                } else {
                    commentsState = commentsState.copy(
                        submitting = false,
                        errorMessage = error.deckMessage(),
                    )
                }
            }
        }
    }

    fun attachmentUploadScope(card: DeckCard): DurableUploadScope =
        DurableUploadScope(feature = "deck-attachment", resourceId = card.id.toString())

    fun loadAttachments(card: DeckCard) {
        val board = boardState()?.board ?: return
        val loadToken = attachmentsLoadGate.begin(card.id)
        attachmentsState = attachmentsState.copy(loading = true, errorMessage = null)
        scope.launch {
            val durableUploads = runCatching {
                services.durableMultipartUploadStatuses(session, attachmentUploadScope(card))
            }.getOrDefault(emptyList())
            runCatching {
                val response = services.executeNextcloudApi(
                    session,
                    writePlan().attachments(boardAccess(board), cardContext(card)),
                )
                parseDeckResponseOffUi {
                    parseDeckAttachments(cardContext(card), response)
                }
            }.onSuccess { values ->
                if (!attachmentsLoadGate.accepts(loadToken, activeAttachmentsCardId())) {
                    return@onSuccess
                }
                val handoffCapability =
                    (services.externalFileHandoffSupport as? ExternalFileHandoffSupport.Available)
                        ?.capability
                attachmentRecords = values.associateBy(DeckAttachment::deckUiKey)
                attachmentsState = DeckUiAttachmentsState(
                    attachments = values.map { attachment ->
                        attachment.toDeckUiAttachment(
                            canEdit = board.permissions.canEdit,
                            handoffCapability = handoffCapability,
                        )
                    },
                    uploads = durableUploads,
                    canAdd = board.permissions.canEdit,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!attachmentsLoadGate.accepts(loadToken, activeAttachmentsCardId())) {
                    return@onFailure
                }
                attachmentsState = attachmentsState.copy(
                    loading = false,
                    errorMessage = error.deckMessage(),
                )
            }
        }
    }

    fun showAttachments(card: DeckCard) {
        attachmentsState = DeckUiAttachmentsState(
            attachments = emptyList(),
            loading = true,
            canAdd = boardState()?.board?.permissions?.canEdit == true,
        )
        attachmentRecords = emptyMap()
        interaction = DeckUiInteraction.Attachments(card)
        loadAttachments(card)
    }

    fun addAttachment(card: DeckCard) {
        val board = boardState()?.board ?: return
        if (attachmentsState.adding) return
        attachmentsState = attachmentsState.copy(adding = true, errorMessage = null)
        scope.launch {
            val selection = runCatching {
                services.chooseLocalUploadFile(
                    acceptedMimeTypes = listOf("*/*"),
                    maximumBytes = DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
                )
            }.getOrElse { error ->
                attachmentsState = attachmentsState.copy(
                    adding = false,
                    errorMessage = error.deckMessage(),
                )
                return@launch
            }
            when (selection) {
                LocalUploadSelectionResult.Cancelled -> {
                    attachmentsState = attachmentsState.copy(adding = false)
                }
                is LocalUploadSelectionResult.Rejected -> {
                    attachmentsState = attachmentsState.copy(
                        adding = false,
                        errorMessage = selection.reason,
                    )
                }
                is LocalUploadSelectionResult.Unavailable -> {
                    attachmentsState = attachmentsState.copy(
                        adding = false,
                        errorMessage = selection.reason,
                    )
                }
                is LocalUploadSelectionResult.Selected -> {
                    val file = selection.file
                    runCatching {
                        val type = if (selectedRoute().version.value == "1.1") {
                            DeckAttachmentType.File
                        } else {
                            DeckAttachmentType.DeckFile
                        }
                        val target = writePlan().attachmentUploadTarget(
                            access = boardAccess(board),
                            context = cardContext(card),
                            type = type,
                        )
                        services.enqueueDurableMultipartUpload(
                            session,
                            attachmentUploadScope(card),
                            NextcloudMultipartUploadRequest(
                                method = target.method,
                                relativePath = target.relativePath,
                                file = file,
                                fileFieldName = target.fileFieldName,
                                textFields = listOf(
                                    MultipartTextField(
                                        target.typeFieldName,
                                        target.attachmentType.serverValue,
                                    ),
                                ),
                                ocsApiRequest = true,
                                maximumFileBytes = DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
                                maximumResponseBytes = DECK_ATTACHMENT_MUTATION_RESPONSE_BYTES,
                            ),
                        ).also { result ->
                            require(result !is DurableUploadEnqueueResult.Rejected) {
                                (result as DurableUploadEnqueueResult.Rejected).reason
                            }
                        }
                    }.onSuccess { result ->
                        if (result is DurableUploadEnqueueResult.Completed) {
                            services.releaseLocalUploadFile(file)
                        }
                        if (activeAttachmentsCardId() == card.id) {
                            val uploadStatuses = runCatching {
                                services.durableMultipartUploadStatuses(
                                    session,
                                    attachmentUploadScope(card),
                                )
                            }.getOrDefault(attachmentsState.uploads)
                            attachmentsState = attachmentsState.copy(
                                adding = false,
                                uploads = uploadStatuses,
                            )
                            loadAttachments(card)
                        }
                        refreshBoard()
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (activeAttachmentsCardId() == card.id) {
                            attachmentsState = attachmentsState.copy(
                                adding = false,
                                errorMessage = error.deckMessage(),
                            )
                        }
                        services.releaseLocalUploadFile(file)
                    }
                }
            }
        }
    }

    fun deleteAttachment(card: DeckCard, attachment: DeckAttachment) {
        val board = boardState()?.board ?: return
        attachmentsState = attachmentsState.copy(loading = true, errorMessage = null)
        scope.launch {
            runCatching {
                val response = services.executeNextcloudApi(
                    session,
                    writePlan().deleteAttachment(
                        boardAccess(board),
                        DeckAttachmentReference(
                            card = cardContext(card),
                            type = attachment.type,
                            attachmentId = attachment.id,
                        ),
                    ),
                )
                parseDeckMutationReceipt(response)
            }.onSuccess {
                if (activeAttachmentsCardId() == card.id) {
                    loadAttachments(card)
                }
                refreshBoard()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (activeAttachmentsCardId() == card.id) {
                    attachmentsState = attachmentsState.copy(
                        loading = false,
                        errorMessage = error.deckMessage(),
                    )
                }
            }
        }
    }

    LaunchedEffect(interaction) {
        val editor = interaction as? DeckUiInteraction.CardEditor
        if (editor == null) {
            cardDraftRecovery.clearForClosedEditor()
            return@LaunchedEffect
        }
        val key = cardDraftKey(editor)
        mutationError = cardDraftRecovery.load(key, editor.card.toDeckUiDraft())
    }

    LaunchedEffect(session, requestedBoard?.id, loadAttempt) {
        val board = requestedBoard
        if (board == null) {
            if (loadedBoards.isEmpty()) state = DeckWorkspaceState.Loading
            runCatching {
                loadDeckBoards(services, session)
            }.onSuccess { loaded ->
                val reconciledUnknownMutation = mutationOutcomeUnknown
                loadedBoards = loaded.boards
                capabilities = loaded.capabilities
                activeRoute = loaded.route
                if (reconciledUnknownMutation) {
                    boardRecovery = boardRecovery?.let { recovery ->
                        reconcileDeckBoardRecovery(recovery, loaded.boards)
                    }
                }
                val restoredBoard = requestedBoardId?.let { boardId ->
                    loaded.boards.firstOrNull { it.id == boardId }
                }
                if (restoredBoard != null) {
                    requestedBoard = restoredBoard
                    state = DeckWorkspaceState.Loading
                } else {
                    requestedBoardId = null
                    requestedCardId = null
                    state = if (loaded.boards.isEmpty()) {
                        DeckWorkspaceState.Empty(
                            title = "No boards",
                            message = "Create a board to start organizing cards.",
                            canCreateBoards = loaded.capabilities?.canCreateBoards == true,
                        )
                    } else {
                        DeckWorkspaceState.BoardPicker(
                            boards = loaded.boards,
                            canCreateBoards = loaded.capabilities?.canCreateBoards == true,
                        )
                    }
                }
                if (reconciledUnknownMutation) {
                    mutationOutcomeUnknown = false
                    interaction = null
                    mutationError = null
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = DeckWorkspaceState.Error(
                    title = "Deck could not load",
                    message = error.deckMessage(),
                    cachedState = loadedBoards.takeIf(List<DeckBoard>::isNotEmpty)?.let {
                        DeckWorkspaceState.BoardPicker(
                            boards = it,
                            canCreateBoards = capabilities?.canCreateBoards == true,
                        )
                    },
                )
            }
        } else {
            val selectedCardId = requestedCardId
            if (boardState()?.board?.id != board.id) state = DeckWorkspaceState.Loading
            runCatching {
                loadDeckStacks(
                    services = services,
                    session = session,
                    board = board,
                    preferredRoute = activeRoute,
                )
            }.onSuccess { loaded ->
                val reconciledUnknownMutation = mutationOutcomeUnknown
                activeRoute = loaded.route
                requestedBoard = loaded.board
                loadedBoards = loadedBoards.map { current ->
                    if (current.id == loaded.board.id) loaded.board else current
                }
                val restoredCardId = selectedCardId?.takeIf { cardId ->
                    loaded.stacks.any { stack -> stack.cards.any { it.id == cardId } }
                }
                requestedCardId = restoredCardId
                state = DeckWorkspaceState.Board(
                    board = loaded.board,
                    stacks = loaded.stacks,
                    selectedCardId = restoredCardId,
                )
                if (reconciledUnknownMutation) {
                    mutationOutcomeUnknown = false
                    interaction = null
                    mutationError = null
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = DeckWorkspaceState.Error(
                    title = "Board could not load",
                    message = error.deckMessage(),
                    cachedState = boardState()
                        ?: DeckWorkspaceState.BoardPicker(
                            loadedBoards,
                            capabilities?.canCreateBoards == true,
                        ),
                )
            }
        }
    }

    fun backToBoards() {
        cardSelectionLoadGate.invalidate()
        authoritativeActionLoadGate.invalidate()
        interaction = null
        mutationError = null
        requestedBoard = null
        requestedBoardId = null
        requestedCardId = null
        state = if (loadedBoards.isEmpty()) {
            DeckWorkspaceState.Empty(
                title = "No boards",
                message = "Create a board to start organizing cards.",
                canCreateBoards = capabilities?.canCreateBoards == true,
            )
        } else {
            DeckWorkspaceState.BoardPicker(
                loadedBoards,
                capabilities?.canCreateBoards == true,
            )
        }
    }

    fun dismissSelectedCard() {
        cardSelectionLoadGate.invalidate()
        authoritativeActionLoadGate.invalidate()
        requestedCardId = null
        state = boardState()?.copy(selectedCardId = null) ?: state
    }

    val currentBoard = boardState()?.board
    PlatformBackHandler(
        enabled = requestedBoard != null,
        onBack = {
            if (requestedCardId != null) dismissSelectedCard() else backToBoards()
        },
    )
    NativeDeckBoardSurface(
        state = state,
        boardContext = requestedBoard,
        onExit = onBack,
        onSelectBoard = { board ->
            cardSelectionLoadGate.invalidate()
            authoritativeActionLoadGate.invalidate()
            interaction = null
            mutationError = null
            requestedBoard = board
            requestedBoardId = board.id
            requestedCardId = null
        },
        onBackToBoards = ::backToBoards,
        onOpenCard = {},
        onSelectCard = { card ->
            authoritativeActionLoadGate.invalidate()
            val loadToken = cardSelectionLoadGate.begin(card.id)
            requestedCardId = card.id
            state = boardState()?.copy(selectedCardId = card.id) ?: state
            scope.launch {
                runCatching { fetchAuthoritativeCard(card) }
                    .onSuccess { current ->
                        if (cardSelectionLoadGate.accepts(loadToken, requestedCardId)) {
                            replaceCard(current)
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        if (cardSelectionLoadGate.accepts(loadToken, requestedCardId)) {
                            mutationError = error.deckMessage()
                        }
                    }
            }
        },
        onDismissCard = ::dismissSelectedCard,
        onRetry = { refreshBoard() },
        onCreateBoard = capabilities?.takeIf(DeckCapabilities::canCreateBoards)?.let {
            { interaction = DeckUiInteraction.BoardEditor(null) }
        },
        onCreateStack = currentBoard?.takeIf { it.permissions.canManage }?.let { board ->
            { interaction = DeckUiInteraction.StackEditor(board, null) }
        },
        onCreateCard = currentBoard?.takeIf { it.permissions.canEdit }?.let {
            { stack -> interaction = DeckUiInteraction.CardEditor(stack, null) }
        },
        boardActions = { board ->
            buildList {
                if (board.permissions.canManage) {
                    if (!board.archived) {
                        add(
                            NextcloudCardAction("Manage labels") {
                                mutationError = null
                                interaction = DeckUiInteraction.ManageLabels(board)
                            },
                        )
                    }
                    add(
                        NextcloudCardAction("Edit board") {
                            interaction = DeckUiInteraction.BoardEditor(board)
                        },
                    )
                    add(
                        NextcloudCardAction("Delete board", destructive = true) {
                            interaction = DeckUiInteraction.DeleteBoard(board)
                        },
                    )
                }
            }
        },
        stackActions = { stack ->
            val board = boardState()?.board
            if (board?.permissions?.canManage != true) {
                emptyList()
            } else {
                listOf(
                    NextcloudCardAction("Rename list") {
                        interaction = DeckUiInteraction.StackEditor(board, stack)
                    },
                    NextcloudCardAction("Delete list", destructive = true) {
                        interaction = DeckUiInteraction.DeleteStack(stack)
                    },
                )
            }
        },
        cardActions = { card ->
            val board = boardState()?.board
            buildList {
                if (board?.permissions?.canEdit == true) {
                    add(
                        NextcloudCardAction("Edit card") {
                            showAuthoritativeCard(card) { current, stack ->
                                DeckUiInteraction.CardEditor(stack, current)
                            }
                        },
                    )
                    add(
                        NextcloudCardAction("Move") {
                            showAuthoritativeCard(card) { current, _ ->
                                DeckUiInteraction.MoveCard(current)
                            }
                        },
                    )
                    add(
                        NextcloudCardAction("Labels") {
                            showAuthoritativeCard(card) { current, _ ->
                                DeckUiInteraction.Labels(current)
                            }
                        },
                    )
                    add(
                        NextcloudCardAction("Assignees") {
                            showAuthoritativeCard(card) { current, _ ->
                                DeckUiInteraction.Assignees(current)
                            }
                        },
                    )
                    add(
                        NextcloudCardAction("Due date") {
                            showAuthoritativeCard(card) { current, _ ->
                                DeckUiInteraction.DueDate(current)
                            }
                        },
                    )
                    add(
                        NextcloudCardAction(
                            if (card.completedAt == null) "Mark done" else "Mark not done",
                        ) {
                            withAuthoritativeCard(card) { current, _ ->
                                runMutation(
                                    request = {
                                        writePlan().updateCard(
                                            boardAccess(checkNotNull(board)),
                                            cardContext(current),
                                            current.toUpdate(
                                                completedAt = if (current.completedAt == null) {
                                                    Clock.System.now().toString()
                                                } else {
                                                    null
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        },
                    )
                    add(
                        NextcloudCardAction("Delete card", destructive = true) {
                            interaction = DeckUiInteraction.DeleteCard(card)
                        },
                    )
                }
                if (board?.permissions?.canRead == true) {
                    add(NextcloudCardAction("Comments") { showComments(card) })
                    add(NextcloudCardAction("Attachments") { showAttachments(card) })
                }
            }
        },
        onMoveCard = currentBoard?.takeIf {
            it.permissions.canEdit && !mutationBusy
        }?.let {
            { card, destinationStack, insertionIndex ->
                moveCardToIndex(card, destinationStack, insertionIndex)
            }
        },
        boardRecovery = boardRecovery.takeIf { requestedBoard == null },
        onRestoreBoard = boardRecovery
            ?.takeIf { requestedBoard == null }
            ?.takeUnless { it.restoring || it.verifying }
            ?.let {
            ::restoreDeletedBoard
        },
        onDismissBoardRecovery = boardRecovery
            ?.takeIf { requestedBoard == null }
            ?.takeUnless { it.restoring || it.verifying }
            ?.let {
            { boardRecovery = null }
        },
        modifier = modifier,
    )

    when (val overlay = interaction?.takeIf { it.isAvailableFor(state) }) {
        null -> Unit
        is DeckUiInteraction.BoardEditor -> DeckUiBoardEditorDialog(
            board = overlay.board,
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onSubmit = { draft ->
                if (overlay.board == null) {
                    runMutation(
                        request = {
                            writePlan().createBoard(
                                capabilities = checkNotNull(capabilities),
                                draft = DeckBoardDraft(draft.title, draft.color),
                            )
                        },
                    )
                } else {
                    runRevalidatedBoardMutation(overlay.board) { current ->
                        DeckBoardUpdate(
                            title = draft.title,
                            color = draft.color,
                            archived = current.archived,
                        )
                    }
                }
            },
        )
        is DeckUiInteraction.StackEditor -> DeckUiStackEditorDialog(
            stack = overlay.stack,
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onSubmit = { draft ->
                val access = boardAccess(overlay.board)
                if (overlay.stack == null) {
                    runMutation(
                        request = {
                            writePlan().createStack(
                                access,
                                DeckStackDraft(
                                    title = draft.title,
                                    order = boardState()?.stacks?.maxOfOrNull(DeckStack::order)
                                        ?.plus(DECK_STACK_ORDER_STEP) ?: DECK_STACK_ORDER_STEP,
                                ),
                            )
                        },
                    )
                } else {
                    runMutation(
                        request = {
                            writePlan().updateStack(
                                access,
                                stackContext(overlay.stack),
                                DeckStackDraft(draft.title, overlay.stack.order),
                            )
                        },
                    )
                }
            },
        )
        is DeckUiInteraction.CardEditor -> if (cardDraftRecovery.loadedKey == cardDraftKey(overlay)) {
            val cardDraftBusy = mutationBusy || cardDraftRecovery.recoveryBusy
            DeckUiCardEditorDialog(
            stack = overlay.stack,
            card = overlay.card,
            initialDraft = cardDraftRecovery.restoredDraft
                ?.takeIf { it.key == cardDraftKey(overlay) }
                ?.draft,
            recoveredDraft = cardDraftRecovery.recoveredNotice,
            draftRecoveryFailed = cardDraftRecovery.unreadableKey == cardDraftKey(overlay),
            draftRecoveryResetRequired = cardDraftRecovery.resetRequired,
            busy = cardDraftBusy,
            errorMessage = mutationError,
            quickDueDates = deckQuickDueDates(),
            onDismiss = {
                if (!cardDraftBusy) {
                    val key = cardDraftKey(overlay)
                    cardDraftRecovery.clearForClosedEditor()
                    interaction = null
                    if (cardDraftRecovery.canClearOnClose(key)) {
                        val pendingPersistence = cardDraftRecovery.persistenceJob
                        cardDraftRecovery.persistenceJob = scope.launch {
                            pendingPersistence?.cancelAndJoin()
                            runCatching { services.clearDeckCardDraft(session, key) }
                        }
                    }
                }
            },
            onDiscardRecoveredDraft = { draft ->
                if (!mutationBusy) {
                    val key = cardDraftKey(overlay)
                    val original = overlay.card.toDeckUiDraft()
                    cardDraftRecovery.launchRecovery(scope) {
                        mutationError = cardDraftRecovery.discardAndPersistReplacement(key, draft, original)
                    }
                }
            },
            onResetDraftRecovery = { draft ->
                if (!mutationBusy) {
                    val key = cardDraftKey(overlay)
                    val original = overlay.card.toDeckUiDraft()
                    cardDraftRecovery.launchRecovery(scope) {
                        mutationError = cardDraftRecovery.resetAndPersistReplacement(key, draft, original)
                    }
                }
            },
            onDraftChange = draftChange@ { draft ->
                val key = cardDraftKey(overlay)
                if (cardDraftRecovery.blocksPersistence(key)) return@draftChange
                val original = overlay.card.toDeckUiDraft()
                val pendingPersistence = cardDraftRecovery.persistenceJob
                cardDraftRecovery.persistenceJob = scope.launch {
                    pendingPersistence?.cancelAndJoin()
                    val persistenceError = cardDraftRecovery.persist(key, draft, original)
                    if (
                        persistenceError != null ||
                        mutationError == DeckCardDraftRecoveryCoordinator.DRAFT_SAVE_FAILURE
                    ) {
                        mutationError = persistenceError
                    }
                }
            },
            onSubmit = submit@ { draft ->
                val draftKey = cardDraftKey(overlay)
                if (cardDraftRecovery.blocksPersistence(draftKey)) return@submit
                val board = checkNotNull(boardState()?.board)
                if (overlay.card == null) {
                    runMutation(
                        request = {
                            writePlan().createCard(
                                boardAccess(board),
                                stackContext(overlay.stack),
                                DeckCardDraft(
                                    title = draft.title,
                                    order = overlay.stack.cards.maxOfOrNull(DeckCard::order)
                                        ?.plus(1L) ?: 0L,
                                    descriptionMarkdown = draft.descriptionMarkdown,
                                    dueAt = draft.resolvedDueAt(
                                        deckUiInstant(draft.dueDate, draft.dueTime),
                                    ),
                                ),
                            )
                        },
                        afterSuccess = {
                            cardDraftRecovery.persistenceJob?.cancelAndJoin()
                            cardDraftRecovery.quarantineSubmitted(draftKey)?.let { mutationError = it }
                            cardDraftRecovery.persistenceJob = null
                        },
                    )
                } else {
                    runRevalidatedCardMutation(
                        original = overlay.card,
                        conflictInteraction = { current, stack ->
                            if (!draft.dueFieldsEdited) {
                                val authoritativeDue = current.toDeckUiDraft()
                                val reconciledDraft = draft.reconcileUntouchedDueDate(authoritativeDue)
                                val persisted = PersistedDeckCardDraft(draftKey, reconciledDraft)
                                cardDraftRecovery.markRestored(persisted)
                                val pendingPersistence = cardDraftRecovery.persistenceJob
                                cardDraftRecovery.persistenceJob = scope.launch {
                                    pendingPersistence?.cancelAndJoin()
                                    runCatching { services.saveDeckCardDraft(session, persisted) }
                                        .onFailure {
                                            mutationError =
                                                "The unsaved card draft could not be stored safely."
                                        }
                                }
                            }
                            DeckUiInteraction.CardEditor(stack, current)
                        },
                        update = { current ->
                            current.toUpdate(
                                title = draft.title,
                                description = draft.descriptionMarkdown,
                                dueAt = draft.resolvedDueAt(
                                    deckUiInstant(draft.dueDate, draft.dueTime),
                                ),
                            )
                        },
                        afterSuccess = {
                            cardDraftRecovery.persistenceJob?.cancelAndJoin()
                            cardDraftRecovery.quarantineSubmitted(draftKey)?.let { mutationError = it }
                            cardDraftRecovery.persistenceJob = null
                        },
                    )
                }
            },
            )
        }
        is DeckUiInteraction.MoveCard -> DeckUiMoveCardDialog(
            card = overlay.card,
            stacks = boardState()?.stacks.orEmpty(),
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onMove = { target, placement ->
                val remainingCardCount = target.cards.count { card ->
                    card.id != overlay.card.id
                }
                moveCardToIndex(
                    card = overlay.card,
                    destinationStack = target,
                    insertionIndex = when (placement) {
                        DeckUiCardPlacement.Top -> 0
                        DeckUiCardPlacement.Bottom -> remainingCardCount
                    },
                )
            },
        )
        is DeckUiInteraction.Labels -> DeckUiLabelPickerDialog(
            card = overlay.card,
            availableLabels = boardState()?.board?.labels.orEmpty(),
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onSubmit = { selected ->
                val board = checkNotNull(boardState()?.board)
                val access = boardAccess(board)
                val currentIds = overlay.card.labels.mapTo(mutableSetOf(), DeckLabel::id)
                val selectedIds = selected.mapTo(mutableSetOf(), DeckLabel::id)
                runMutations(
                    card = overlay.card,
                    requests = {
                        (selectedIds - currentIds).map { labelId ->
                            writePlan().assignLabel(access, cardContext(overlay.card), labelId)
                        } + (currentIds - selectedIds).map { labelId ->
                            writePlan().removeLabel(access, cardContext(overlay.card), labelId)
                        }
                    },
                    reconciledInteraction = DeckUiInteraction::Labels,
                )
            },
            onManageLabels = boardState()?.board
                ?.takeIf { it.permissions.canManage && !it.archived }
                ?.let { board ->
                    {
                        mutationError = null
                        interaction = DeckUiInteraction.ManageLabels(board)
                    }
                },
        )
        is DeckUiInteraction.ManageLabels -> {
            val board = boardState()?.board ?: overlay.board
            DeckUiLabelManagerDialog(
                board = board,
                availableLabels = board.labels,
                busy = mutationBusy,
                errorMessage = mutationError,
                confirmedEditorMutationRevision = confirmedLabelEditorRevision,
                onDismiss = { if (!mutationBusy) interaction = null },
                onCreate = { draft ->
                    runMutation(
                        request = {
                            writePlan().createLabel(
                                boardAccess(board),
                                DeckLabelDraft(draft.title, draft.color),
                            )
                        },
                        dismissInteractionOnSuccess = false,
                        afterSuccess = { confirmedLabelEditorRevision += 1L },
                    )
                },
                onUpdate = { label, draft ->
                    runMutation(
                        request = {
                            writePlan().updateLabel(
                                boardAccess(board),
                                label.id,
                                DeckLabelDraft(draft.title, draft.color),
                            )
                        },
                        dismissInteractionOnSuccess = false,
                        afterSuccess = { confirmedLabelEditorRevision += 1L },
                    )
                },
                onDelete = { label ->
                    runMutation(
                        request = {
                            writePlan().deleteLabel(boardAccess(board), label.id)
                        },
                        dismissInteractionOnSuccess = false,
                    )
                },
            )
        }
        is DeckUiInteraction.Assignees -> DeckUiAssigneePickerDialog(
            card = overlay.card,
            availableUsers = boardState()?.board?.users.orEmpty(),
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onSubmit = { selected ->
                val board = checkNotNull(boardState()?.board)
                val access = boardAccess(board)
                val currentIds = overlay.card.assignees.mapTo(mutableSetOf(), DeckUser::id)
                val selectedIds = selected.mapTo(mutableSetOf(), DeckUser::id)
                runMutations(
                    card = overlay.card,
                    requests = {
                        (selectedIds - currentIds).map { userId ->
                            writePlan().assignUser(access, cardContext(overlay.card), userId)
                        } + (currentIds - selectedIds).map { userId ->
                            writePlan().unassignUser(access, cardContext(overlay.card), userId)
                        }
                    },
                    reconciledInteraction = DeckUiInteraction::Assignees,
                )
            },
        )
        is DeckUiInteraction.DueDate -> DeckUiDueDateDialog(
            card = overlay.card,
            busy = mutationBusy,
            errorMessage = mutationError,
            quickDueDates = deckQuickDueDates(),
            onDismiss = { if (!mutationBusy) interaction = null },
            onSubmit = { date, time ->
                runRevalidatedCardMutation(
                    original = overlay.card,
                    conflictInteraction = { current, _ -> DeckUiInteraction.DueDate(current) },
                    update = { current ->
                        current.toUpdate(dueAt = deckUiInstant(date, time))
                    },
                )
            },
            onClear = {
                runRevalidatedCardMutation(
                    original = overlay.card,
                    conflictInteraction = { current, _ -> DeckUiInteraction.DueDate(current) },
                    update = { current ->
                        current.toUpdate(dueAt = null)
                    },
                )
            },
        )
        is DeckUiInteraction.Comments -> DeckUiCommentsDialog(
            card = overlay.card,
            state = commentsState,
            confirmedComposerMutationRevision = confirmedCommentComposerRevision,
            onDismiss = { if (!commentsState.submitting) interaction = null },
            onRefresh = { loadComments(overlay.card, append = false) },
            onLoadMore = { loadComments(overlay.card, append = true) },
            onSubmit = { message, replyTo ->
                val board = checkNotNull(boardState()?.board)
                runCommentMutation(
                    card = overlay.card,
                    request = {
                        writePlan().createComment(
                            boardAccess(board),
                            cardContext(overlay.card),
                            DeckCommentDraft(
                                message = message,
                                parentId = replyTo?.key?.toLongOrNull(),
                            ),
                        )
                    },
                    onResponse = { response ->
                        parseDeckResponseOffUi {
                            parseDeckComment(cardContext(overlay.card), response)
                        }
                    },
                    afterConfirmedSuccess = {
                        confirmedCommentComposerRevision += 1L
                    },
                )
            },
            onEdit = { comment, message ->
                val board = checkNotNull(boardState()?.board)
                val record = checkNotNull(commentRecords[comment.key])
                require(record.cardId == overlay.card.id) {
                    "The Deck comment belongs to another card."
                }
                runCommentMutation(
                    card = overlay.card,
                    request = {
                        writePlan().updateComment(
                            boardAccess(board),
                            cardContext(overlay.card),
                            record,
                            currentUserId,
                            DeckCommentUpdate(message),
                        )
                    },
                    onResponse = { response ->
                        parseDeckResponseOffUi {
                            parseDeckComment(cardContext(overlay.card), response)
                        }
                    },
                    afterConfirmedSuccess = {
                        confirmedCommentComposerRevision += 1L
                    },
                )
            },
            onDelete = { comment ->
                val board = checkNotNull(boardState()?.board)
                val record = checkNotNull(commentRecords[comment.key])
                require(record.cardId == overlay.card.id) {
                    "The Deck comment belongs to another card."
                }
                runCommentMutation(
                    card = overlay.card,
                    request = {
                        writePlan().deleteComment(
                            boardAccess(board),
                            cardContext(overlay.card),
                            record,
                            currentUserId,
                        )
                    },
                )
            },
        )
        is DeckUiInteraction.Attachments -> DeckUiAttachmentsDialog(
            card = overlay.card,
            state = attachmentsState,
            onDismiss = { if (!attachmentsState.adding) interaction = null },
            onRefresh = { loadAttachments(overlay.card) },
            onLoadMore = {},
            onAdd = { addAttachment(overlay.card) },
            onDismissUpload = { upload ->
                scope.launch {
                    runCatching {
                        services.dismissDurableMultipartUpload(
                            session,
                            attachmentUploadScope(overlay.card),
                            upload.id,
                        )
                    }.onSuccess {
                        loadAttachments(overlay.card)
                    }.onFailure { error ->
                        attachmentsState = attachmentsState.copy(
                            errorMessage = error.deckMessage(),
                        )
                    }
                }
            },
            onOpen = { attachment ->
                val board = checkNotNull(boardState()?.board)
                val record = checkNotNull(attachmentRecords[attachment.key])
                require(record.cardId == overlay.card.id) {
                    "The Deck attachment belongs to another card."
                }
                scope.launch {
                    attachmentsState = attachmentsState.copy(errorMessage = null)
                    runCatching {
                        services.handoffDeckAttachmentToExternalApp(
                            session = session,
                            target = writePlan().openAttachment(
                                boardAccess(board),
                                DeckAttachmentReference(
                                    card = cardContext(overlay.card),
                                    type = record.type,
                                    attachmentId = record.id,
                                ),
                            ),
                            attachment = record,
                        )
                    }.onSuccess { result ->
                        if (activeAttachmentsCardId() == overlay.card.id) {
                            attachmentsState = attachmentsState.copy(
                                errorMessage = result.deckAttachmentHandoffMessage(),
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (activeAttachmentsCardId() == overlay.card.id) {
                            attachmentsState = attachmentsState.copy(
                                errorMessage = error.deckMessage(),
                            )
                        }
                    }
                }
            },
            onDelete = { attachment ->
                val record = checkNotNull(attachmentRecords[attachment.key])
                require(record.cardId == overlay.card.id) {
                    "The Deck attachment belongs to another card."
                }
                deleteAttachment(overlay.card, record)
            },
        )
        is DeckUiInteraction.DeleteBoard -> DeckUiDestructiveConfirmationDialog(
            title = "Delete ${overlay.board.title}?",
            message = "This removes the board and its lists from Deck.",
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onConfirm = {
                runMutation(
                    request = { writePlan().deleteBoard(boardAccess(overlay.board)) },
                    returnToBoards = true,
                    outcomeUnknownBoardRecovery = overlay.board,
                    afterSuccess = {
                        boardRecovery = DeckUiBoardRecovery(overlay.board)
                    },
                )
            },
        )
        is DeckUiInteraction.DeleteStack -> DeckUiDestructiveConfirmationDialog(
            title = "Delete ${overlay.stack.title}?",
            message = "This removes the list and its cards from this board.",
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onConfirm = {
                val board = checkNotNull(boardState()?.board)
                runMutation(
                    request = {
                        writePlan().deleteStack(boardAccess(board), stackContext(overlay.stack))
                    },
                )
            },
        )
        is DeckUiInteraction.DeleteCard -> DeckUiDestructiveConfirmationDialog(
            title = "Delete ${overlay.card.title}?",
            message = "This removes the card from this board.",
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onConfirm = {
                val board = checkNotNull(boardState()?.board)
                runMutation(
                    request = {
                        writePlan().deleteCard(boardAccess(board), cardContext(overlay.card))
                    },
                )
            },
        )
    }
}


private data class LoadedDeckBoards(
    val route: DeckReadRoutePlan,
    val boards: List<DeckBoard>,
    val capabilities: DeckCapabilities?,
)

private data class LoadedDeckStacks(
    val route: DeckReadRoutePlan,
    val board: DeckBoard,
    val stacks: List<DeckStack>,
)

private suspend fun loadDeckBoards(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): LoadedDeckBoards {
    val capabilityResponse = services.executeNextcloudApi(
        session,
        NextcloudApiRequest(
            method = NextcloudApiMethod.GET,
            relativePath = "/ocs/v2.php/cloud/capabilities",
            queryParameters = mapOf("format" to "json"),
            ocsApiRequest = true,
            maximumResponseBytes = DECK_CAPABILITY_RESPONSE_BYTES,
        ),
    )
    val capabilities = capabilityResponse.takeIf { it.status in 200..299 }
        ?.let { response ->
            parseDeckResponseOffUi {
                parseDeckCapabilities(response)
            }
        }
    val negotiation = negotiateDeckReadRoutes(capabilities)
    var route = negotiation.candidates.first()
    while (true) {
        val response = services.executeNextcloudApi(session, route.boards(details = true))
        if (response.status in 200..299) {
            return LoadedDeckBoards(
                route = route,
                boards = parseDeckResponseOffUi {
                    parseDeckBoards(response)
                },
                capabilities = capabilities,
            )
        }
        route = negotiation.nextAfter(route, response.status)
            ?: return LoadedDeckBoards(
                route = route,
                boards = parseDeckResponseOffUi {
                    parseDeckBoards(response)
                },
                capabilities = capabilities,
            )
    }
}

private suspend fun loadDeckStacks(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    board: DeckBoard,
    preferredRoute: DeckReadRoutePlan?,
): LoadedDeckStacks {
    val negotiation = negotiateDeckReadRoutes(null)
    var route = preferredRoute ?: negotiation.candidates.first()
    while (true) {
        val boardResponse = services.executeNextcloudApi(
            session,
            route.workspaceBoardMetadata(board.id),
        )
        if (boardResponse.status !in 200..299) {
            route = negotiation.nextAfter(route, boardResponse.status)
                ?: return LoadedDeckStacks(
                    route = route,
                    board = parseDeckResponseOffUi {
                        parseDeckBoard(boardResponse)
                    },
                    stacks = emptyList(),
                )
            continue
        }
        val authoritativeBoard = parseDeckResponseOffUi {
            parseDeckBoard(boardResponse)
        }
        val stacksResponse = services.executeNextcloudApi(session, route.stacks(board.id))
        if (stacksResponse.status in 200..299) {
            return LoadedDeckStacks(
                route = route,
                board = authoritativeBoard,
                stacks = parseDeckResponseOffUi {
                    parseDeckStacks(board.id, stacksResponse)
                },
            )
        }
        route = negotiation.nextAfter(route, stacksResponse.status)
            ?: return LoadedDeckStacks(
                route = route,
                board = authoritativeBoard,
                stacks = parseDeckResponseOffUi {
                    parseDeckStacks(board.id, stacksResponse)
                },
            )
    }
}

private fun DeckCard.toUpdate(
    title: String = this.title,
    description: String = descriptionMarkdown.orEmpty(),
    dueAt: String? = this.dueAt,
    completedAt: String? = this.completedAt,
): DeckCardUpdate = DeckCardUpdate(
    original = this,
    title = title,
    order = order,
    descriptionMarkdown = description,
    dueAt = dueAt,
    startAt = startAt,
    archived = archived,
    completedAt = completedAt,
)

private fun deckUiInstant(date: String, time: String): String? {
    val normalizedDate = date.trim()
    if (normalizedDate.isEmpty()) return null
    val normalizedTime = time.trim().ifEmpty { "23:59" }
    return deckLocalDateTimeToInstant(normalizedDate, normalizedTime)
}

private fun deckQuickDueDates(): List<DeckUiDueDateOption> = listOf(
    DeckUiDueDateOption("Today", deckLocalDatePlusDays(0)),
    DeckUiDueDateOption("Tomorrow", deckLocalDatePlusDays(1)),
    DeckUiDueDateOption("Next week", deckLocalDatePlusDays(7)),
)

private val DeckBoard.canWriteDeckComments: Boolean
    get() = permissions.canRead && !archived

private fun DeckComment.toDeckUiComment(
    currentUserId: String,
    canWriteComments: Boolean,
): DeckUiComment =
    DeckUiComment(
        key = id.toString(),
        author = authorId?.let { DeckUser(it, authorDisplayName) },
        messageMarkdown = message,
        createdLabel = createdAt ?: "Unknown time",
        edited = false,
        canEdit = canWriteComments && authorId == currentUserId,
        canDelete = canWriteComments && authorId == currentUserId,
        replyToLabel = replyToId?.let { "Reply to comment $it" },
    )

private fun DeckAttachment.deckUiKey(): String = "${type.serverValue}:$id"

private fun DeckAttachment.toDeckUiAttachment(
    canEdit: Boolean,
    handoffCapability: ExternalFileHandoffCapability?,
): DeckUiAttachment =
    DeckUiAttachment(
        key = deckUiKey(),
        fileName = name,
        supportingText = listOfNotNull(
            mimeType,
        byteCount?.let(::deckByteCountLabel),
        createdBy,
    ).joinToString(" - ").ifBlank { null },
        canOpen = handoffCapability != null &&
            ExternalFileHandoffAction.OpenWith in handoffCapability.supportedActions,
        canDelete = canEdit,
    )

private fun ExternalFileHandoffResult.deckAttachmentHandoffMessage(): String? = when (this) {
    is ExternalFileHandoffResult.Launched -> null
    is ExternalFileHandoffResult.Cancelled -> null
    is ExternalFileHandoffResult.NoCompatibleApplication ->
        "No installed app can open this attachment."
    is ExternalFileHandoffResult.Rejected -> message
    is ExternalFileHandoffResult.Unsupported -> reason
}

private fun deckByteCountLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private fun Throwable.deckMessage(): String =
    message?.takeIf(String::isNotBlank) ?: "Deck could not complete the action."

private const val DECK_CAPABILITY_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val DECK_COMMENT_PAGE_SIZE = 20
private const val DECK_STACK_ORDER_STEP = 1_000L
private const val DECK_ATTACHMENT_MUTATION_RESPONSE_BYTES = 2L * 1024L * 1024L
