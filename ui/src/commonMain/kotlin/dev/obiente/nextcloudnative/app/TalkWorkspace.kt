package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRefreshBar
import dev.obiente.nextcloudnative.app.design.nextcloudItemMotion
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.runtime.runCatchingUnlessCancelled
import kotlinx.coroutines.launch

/**
 * Talk: the conversation list and the open conversation.
 *
 * Wide windows keep the list beside the conversation. Up and Down move between conversations
 * in the list, and Alt+Up or Alt+Down switch conversations from anywhere in the workspace.
 * Compact windows show one pane and use back to return to the list.
 */
@Composable
internal fun TalkWorkspaceScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    selectedRoom: TalkRoom?,
    onSelectRoom: (TalkRoom) -> Unit,
    onCloseRoom: () -> Unit,
    onExit: () -> Unit,
    onOpenAttachment: (NextcloudFile) -> Unit,
    execute: suspend (TalkTransportRequest) -> TalkTransportResponse = { services.executeTalkRequest(session, it) },
) {
    // Owned above the pane switch, so resizing across the two-pane width keeps the list position.
    val listState = rememberLazyListState()
    val rooms = rememberTalkRooms(services, session)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val twoPane = maxWidth >= TALK_TWO_PANE_MIN_WIDTH
        when {
            twoPane -> Row(
                modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isAltPressed) return@onPreviewKeyEvent false
                    val target = talkRoomKeyboardTarget(rooms.rooms.orEmpty(), selectedRoom?.token, event.key)
                        ?: return@onPreviewKeyEvent false
                    onSelectRoom(target)
                    true
                },
            ) {
                Column(modifier = Modifier.width(TALK_ROOM_PANE_WIDTH).fillMaxHeight()) {
                    ScreenHeader("Talk", "Conversations", onExit)
                    TalkRoomList(rooms, listState, selectedRoom?.token, keyboardSelection = true, onSelectRoom)
                }
                VerticalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedRoom != null) {
                        ChatScreen(services, session, userId, selectedRoom, onBack = null, onOpenAttachment, execute)
                    } else {
                        EmptyMessage("Choose a conversation to read and reply.")
                    }
                }
            }
            selectedRoom != null ->
                ChatScreen(services, session, userId, selectedRoom, onBack = onCloseRoom, onOpenAttachment, execute)
            else -> Column(modifier = Modifier.fillMaxSize()) {
                ScreenHeader("Talk", "Conversations", onExit)
                TalkRoomList(rooms, listState, selectedToken = null, keyboardSelection = false, onSelectRoom)
            }
        }
    }
}

/** The conversation list with its own header, for layouts that place the conversation elsewhere. */
@Composable
internal fun TalkScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
    onOpenRoom: (TalkRoom) -> Unit,
    selectedToken: String? = null,
) {
    val rooms = rememberTalkRooms(services, session)
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Talk", "Conversations", onBack)
        TalkRoomList(rooms, rememberLazyListState(), selectedToken, keyboardSelection = selectedToken != null, onOpenRoom)
    }
}

internal class TalkRoomsState {
    var rooms by mutableStateOf<List<TalkRoom>?>(null)
    var error by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
    var loadAttempt by mutableStateOf(0)
}

@Composable
private fun rememberTalkRooms(services: NextcloudPlatformServices, session: NextcloudSession): TalkRoomsState {
    val state = remember(session) { TalkRoomsState().apply { rooms = TalkWorkspaceMemoryCache.rooms(session) } }
    LaunchedEffect(state, state.loadAttempt) {
        val cacheProducer = TalkWorkspaceMemoryCache.producer(session)
        state.refreshing = state.rooms != null
        state.error = null
        runCatchingUnlessCancelled { services.listTalkRooms(session) }
            .onSuccess {
                state.rooms = it
                TalkWorkspaceMemoryCache.storeRooms(session, it, cacheProducer)
            }
            .onFailure { state.error = it.message ?: "Could not load Talk conversations." }
        state.refreshing = false
    }
    return state
}

@Composable
private fun TalkRoomList(
    state: TalkRoomsState,
    listState: LazyListState,
    selectedToken: String?,
    keyboardSelection: Boolean,
    onSelectRoom: (TalkRoom) -> Unit,
) {
    val rooms = state.rooms
    val scope = rememberCoroutineScope()
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    Column(modifier = Modifier.fillMaxSize()) {
        NextcloudRefreshBar(state.refreshing)
        val error = state.error
        if (error != null && rooms != null) {
            RetainedContentNotice(
                message = error,
                onRetry = { state.loadAttempt += 1 },
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            )
        }
        when {
            error != null && rooms == null -> ErrorMessage(error) { state.loadAttempt += 1 }
            rooms == null -> LoadingMessage("Loading conversations...")
            rooms.isEmpty() -> EmptyMessage("No Talk conversations yet.")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (!keyboardSelection || event.type != KeyEventType.KeyDown || event.isAltPressed) {
                        return@onPreviewKeyEvent false
                    }
                    val target = talkRoomKeyboardTarget(rooms, selectedToken, event.key) ?: return@onPreviewKeyEvent false
                    onSelectRoom(target)
                    scope.launch {
                        listState.scrollToItem(rooms.indexOf(target))
                        withFrameNanos { }
                        focusRequesters[target.token]?.requestFocus()
                    }
                    true
                },
                contentPadding = PaddingValues(bottom = NextcloudSpacing.XXLarge),
            ) {
                items(rooms, key = TalkRoom::token) { room ->
                    val focusRequester = remember(room.token) { FocusRequester() }
                    DisposableEffect(room.token, focusRequester) {
                        focusRequesters[room.token] = focusRequester
                        onDispose { focusRequesters.remove(room.token) }
                    }
                    TalkRoomRow(
                        room = room,
                        selected = room.token == selectedToken,
                        onOpen = { onSelectRoom(room) },
                        modifier = nextcloudItemMotion().focusRequester(focusRequester),
                    )
                }
            }
        }
    }
}

@Composable
private fun TalkRoomRow(room: TalkRoom, selected: Boolean, onOpen: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .clickable(onClickLabel = "Open ${room.displayName}", onClick = onOpen)
            .padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
            Icon(
                NextcloudIcons.app("talk"),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(10.dp).size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                room.displayName,
                Modifier.sharedElement(SharedElementKeys.talkRoomTitle(room.token)),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            room.lastMessage?.let {
                Text(
                    it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (room.unreadMessages > 0) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Text(
                    room.unreadMessages.toString(),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${room.displayName}")
    }
    HorizontalDivider(modifier = Modifier.padding(start = 84.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The header of the conversation pane beside the list, which has no back action of its own. */
@Composable
internal fun TalkPaneHeader(title: String, subtitle: String?, trailingContent: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The conversation a list key moves to, or null when the key is not a list move. */
internal fun talkRoomKeyboardTarget(rooms: List<TalkRoom>, selectedToken: String?, key: Key): TalkRoom? {
    if (rooms.isEmpty()) return null
    val current = rooms.indexOfFirst { it.token == selectedToken }
    val index = when (key) {
        Key.DirectionUp -> if (current < 0) rooms.lastIndex else (current - 1).coerceAtLeast(0)
        Key.DirectionDown -> if (current < 0) 0 else (current + 1).coerceAtMost(rooms.lastIndex)
        Key.MoveHome -> 0
        Key.MoveEnd -> rooms.lastIndex
        else -> return null
    }
    return rooms[index].takeIf { index != current }
}

private val TALK_TWO_PANE_MIN_WIDTH = 840.dp
private val TALK_ROOM_PANE_WIDTH = 340.dp
