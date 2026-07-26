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
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var state by remember(session) { mutableStateOf<DeckWorkspaceState>(DeckWorkspaceState.Loading) }
    var loadedBoards by remember(session) { mutableStateOf<List<DeckBoard>>(emptyList()) }
    var capabilities by remember(session) { mutableStateOf<DeckCapabilities?>(null) }
    var activeRoute by remember(session) { mutableStateOf<DeckReadRoutePlan?>(null) }
    var requestedBoard by remember(session) { mutableStateOf<DeckBoard?>(null) }
    var requestedBoardId by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf<Long?>(null)
    }
    var requestedCardId by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf<Long?>(null)
    }
    var loadAttempt by remember(session) { mutableStateOf(0) }
    var interaction by remember(session) { mutableStateOf<DeckUiInteraction?>(null) }
    var mutationBusy by remember(session) { mutableStateOf(false) }
    var mutationError by remember(session) { mutableStateOf<String?>(null) }
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

    suspend fun fetchAuthoritativeCard(card: DeckCard): DeckCard {
        val route = selectedRoute()
        return parseDeckCard(
            card.boardId,
            card.stackId,
            services.executeNextcloudApi(
                session,
                route.card(card.boardId, card.stackId, card.id),
            ),
        )
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
        onResponse: (NextcloudApiResponse) -> Unit = { response ->
            parseDeckMutationReceipt(response)
        },
    ) {
        if (mutationBusy) return
        scope.launch {
            mutationBusy = true
            mutationError = null
            runCatching {
                services.executeNextcloudApi(session, request()).also(onResponse)
            }.onSuccess {
                interaction = null
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
        if (mutationBusy) return
        scope.launch {
            mutationBusy = true
            mutationError = null
            runCatching {
                requests().forEach { request ->
                    val response = services.executeNextcloudApi(session, request)
                    require(response.status in 200..299) {
                        "The Deck action failed (HTTP ${response.status})."
                    }
                }
            }.onSuccess {
                interaction = null
                refreshBoard()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutationError = error.deckMessage()
                runCatching { fetchAuthoritativeCard(card) }
                    .onSuccess { current ->
                        replaceCard(current)
                        interaction = reconciledInteraction(current)
                    }
                refreshBoard()
            }
            mutationBusy = false
        }
    }

    fun withAuthoritativeCard(
        card: DeckCard,
        action: (DeckCard, DeckStack) -> Unit,
    ) {
        scope.launch {
            mutationError = null
            runCatching { fetchAuthoritativeCard(card) }.onSuccess { current ->
                replaceCard(current)
                action(current, stackFor(current))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutationError = error.deckMessage()
            }
        }
    }

    fun showAuthoritativeCard(
        card: DeckCard,
        target: (DeckCard, DeckStack) -> DeckUiInteraction,
    ) = withAuthoritativeCard(card) { current, stack ->
        interaction = target(current, stack)
    }

    fun loadComments(card: DeckCard, append: Boolean) {
        val board = boardState()?.board ?: return
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
                parseDeckComments(cardContext(card), response)
            }.onSuccess { page ->
                val combined = if (append) {
                    (commentRecords.values + page).distinctBy(DeckComment::id)
                } else {
                    page
                }
                commentRecords = combined.associateBy { it.id.toString() }
                commentsState = DeckUiCommentsState(
                    comments = combined.map { it.toDeckUiComment(currentUserId) },
                    hasMore = page.size == DECK_COMMENT_PAGE_SIZE,
                    canComment = board.permissions.canRead,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
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
            canComment = boardState()?.board?.permissions?.canRead == true,
        )
        commentRecords = emptyMap()
        interaction = DeckUiInteraction.Comments(card)
        loadComments(card, append = false)
    }

    fun runCommentMutation(
        card: DeckCard,
        request: () -> NextcloudApiRequest,
        onResponse: (NextcloudApiResponse) -> Unit = { response ->
            parseDeckCommentMutationReceipt(response)
        },
    ) {
        if (commentsState.submitting) return
        commentsState = commentsState.copy(submitting = true, errorMessage = null)
        scope.launch {
            runCatching {
                services.executeNextcloudApi(session, request()).also(onResponse)
            }.onSuccess {
                commentsState = commentsState.copy(submitting = false)
                loadComments(card, append = false)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                commentsState = commentsState.copy(
                    submitting = false,
                    errorMessage = error.deckMessage(),
                )
            }
        }
    }

    fun loadAttachments(card: DeckCard) {
        val board = boardState()?.board ?: return
        attachmentsState = attachmentsState.copy(loading = true, errorMessage = null)
        scope.launch {
            runCatching {
                val response = services.executeNextcloudApi(
                    session,
                    writePlan().attachments(boardAccess(board), cardContext(card)),
                )
                parseDeckAttachments(cardContext(card), response)
            }.onSuccess { values ->
                attachmentRecords = values.associateBy(DeckAttachment::deckUiKey)
                attachmentsState = DeckUiAttachmentsState(
                    attachments = values.map { attachment ->
                        attachment.toDeckUiAttachment(board.permissions.canEdit)
                    },
                    canAdd = board.permissions.canEdit,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
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
                    try {
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
                            val response = services.executeNextcloudMultipartUpload(
                                session,
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
                            )
                            require(response.status in 200..299) {
                                "The Deck attachment upload failed (HTTP ${response.status})."
                            }
                        }.onSuccess {
                            attachmentsState = attachmentsState.copy(adding = false)
                            loadAttachments(card)
                            refreshBoard()
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            attachmentsState = attachmentsState.copy(
                                adding = false,
                                errorMessage = error.deckMessage(),
                            )
                        }
                    } finally {
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
                loadAttachments(card)
                refreshBoard()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                attachmentsState = attachmentsState.copy(
                    loading = false,
                    errorMessage = error.deckMessage(),
                )
            }
        }
    }

    LaunchedEffect(session, requestedBoard?.id, loadAttempt) {
        val board = requestedBoard
        if (board == null) {
            if (loadedBoards.isEmpty()) state = DeckWorkspaceState.Loading
            runCatching {
                loadDeckBoards(services, session)
            }.onSuccess { loaded ->
                loadedBoards = loaded.boards
                capabilities = loaded.capabilities
                activeRoute = loaded.route
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
                activeRoute = loaded.route
                val restoredCardId = selectedCardId?.takeIf { cardId ->
                    loaded.stacks.any { stack -> stack.cards.any { it.id == cardId } }
                }
                requestedCardId = restoredCardId
                state = DeckWorkspaceState.Board(
                    board = board,
                    stacks = loaded.stacks,
                    selectedCardId = restoredCardId,
                )
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

    val currentBoard = boardState()?.board
    NativeDeckBoardSurface(
        state = state,
        onSelectBoard = { board ->
            interaction = null
            mutationError = null
            requestedBoard = board
            requestedBoardId = board.id
            requestedCardId = null
        },
        onBackToBoards = {
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
        },
        onOpenCard = {},
        onSelectCard = { card ->
            requestedCardId = card.id
            state = boardState()?.copy(selectedCardId = card.id) ?: state
            scope.launch {
                runCatching { fetchAuthoritativeCard(card) }
                    .onSuccess(::replaceCard)
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        mutationError = error.deckMessage()
                    }
            }
        },
        onDismissCard = {
            requestedCardId = null
            state = boardState()?.copy(selectedCardId = null) ?: state
        },
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
        modifier = modifier,
    )

    when (val overlay = interaction) {
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
                    runMutation(
                        request = {
                            writePlan().updateBoard(
                                boardAccess(overlay.board),
                                DeckBoardUpdate(draft.title, draft.color, overlay.board.archived),
                            )
                        },
                        returnToBoards = true,
                    )
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
        is DeckUiInteraction.CardEditor -> DeckUiCardEditorDialog(
            stack = overlay.stack,
            card = overlay.card,
            busy = mutationBusy,
            errorMessage = mutationError,
            quickDueDates = deckQuickDueDates(),
            onDismiss = { if (!mutationBusy) interaction = null },
            onSubmit = { draft ->
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
                                    dueAt = deckUiInstant(draft.dueDate, draft.dueTime),
                                ),
                            )
                        },
                    )
                } else {
                    runMutation(
                        request = {
                            writePlan().updateCard(
                                boardAccess(board),
                                cardContext(overlay.card),
                                overlay.card.toUpdate(
                                    title = draft.title,
                                    description = draft.descriptionMarkdown,
                                    dueAt = deckUiInstant(draft.dueDate, draft.dueTime),
                                ),
                            )
                        },
                    )
                }
            },
        )
        is DeckUiInteraction.MoveCard -> DeckUiMoveCardDialog(
            card = overlay.card,
            stacks = boardState()?.stacks.orEmpty(),
            busy = mutationBusy,
            errorMessage = mutationError,
            onDismiss = { if (!mutationBusy) interaction = null },
            onMove = { target, placement ->
                val board = checkNotNull(boardState()?.board)
                val order = when (placement) {
                    DeckUiCardPlacement.Top -> 0L
                    DeckUiCardPlacement.Bottom ->
                        target.cards.maxOfOrNull(DeckCard::order)?.plus(1L) ?: 0L
                }
                runMutation(
                    request = {
                        writePlan().moveCard(
                            boardAccess(board),
                            DeckCardMove(
                                source = cardContext(overlay.card),
                                destinationStack = stackContext(target),
                                order = order,
                            ),
                        )
                    },
                    onResponse = { response ->
                        parseDeckCardMove(
                            DeckCardMove(
                                source = cardContext(overlay.card),
                                destinationStack = stackContext(target),
                                order = order,
                            ),
                            response,
                        )
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
        )
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
                val board = checkNotNull(boardState()?.board)
                runMutation(
                    request = {
                        writePlan().updateCard(
                            boardAccess(board),
                            cardContext(overlay.card),
                            overlay.card.toUpdate(dueAt = deckUiInstant(date, time)),
                        )
                    },
                )
            },
            onClear = {
                val board = checkNotNull(boardState()?.board)
                runMutation(
                    request = {
                        writePlan().updateCard(
                            boardAccess(board),
                            cardContext(overlay.card),
                            overlay.card.toUpdate(dueAt = null),
                        )
                    },
                )
            },
        )
        is DeckUiInteraction.Comments -> DeckUiCommentsDialog(
            card = overlay.card,
            state = commentsState,
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
                        parseDeckComment(cardContext(overlay.card), response)
                    },
                )
            },
            onEdit = { comment, message ->
                val board = checkNotNull(boardState()?.board)
                val record = checkNotNull(commentRecords[comment.key])
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
                        parseDeckComment(cardContext(overlay.card), response)
                    },
                )
            },
            onDelete = { comment ->
                val board = checkNotNull(boardState()?.board)
                val record = checkNotNull(commentRecords[comment.key])
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
            onOpen = {
                attachmentsState = attachmentsState.copy(
                    errorMessage = "Native attachment opening is not available yet.",
                )
            },
            onDelete = { attachment ->
                val record = checkNotNull(attachmentRecords[attachment.key])
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
        ?.let(::parseDeckCapabilities)
    val negotiation = negotiateDeckReadRoutes(capabilities)
    var route = negotiation.candidates.first()
    while (true) {
        val response = services.executeNextcloudApi(session, route.boards(details = true))
        if (response.status in 200..299) {
            return LoadedDeckBoards(
                route = route,
                boards = parseDeckBoards(response),
                capabilities = capabilities,
            )
        }
        route = negotiation.nextAfter(route, response.status)
            ?: return LoadedDeckBoards(
                route = route,
                boards = parseDeckBoards(response),
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
        val response = services.executeNextcloudApi(session, route.stacks(board.id))
        if (response.status in 200..299) {
            return LoadedDeckStacks(route, parseDeckStacks(board.id, response))
        }
        route = negotiation.nextAfter(route, response.status)
            ?: return LoadedDeckStacks(route, parseDeckStacks(board.id, response))
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

private fun DeckComment.toDeckUiComment(currentUserId: String): DeckUiComment =
    DeckUiComment(
        key = id.toString(),
        author = authorId?.let { DeckUser(it, authorDisplayName) },
        messageMarkdown = message,
        createdLabel = createdAt ?: "Unknown time",
        edited = false,
        canEdit = authorId == currentUserId,
        canDelete = authorId == currentUserId,
        replyToLabel = replyToId?.let { "Reply to comment $it" },
    )

private fun DeckAttachment.deckUiKey(): String = "${type.serverValue}:$id"

private fun DeckAttachment.toDeckUiAttachment(canEdit: Boolean): DeckUiAttachment =
    DeckUiAttachment(
        key = deckUiKey(),
        fileName = name,
        supportingText = listOfNotNull(
            mimeType,
        byteCount?.let(::deckByteCountLabel),
        createdBy,
    ).joinToString(" - ").ifBlank { null },
        canOpen = false,
        canDelete = canEdit,
    )

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
