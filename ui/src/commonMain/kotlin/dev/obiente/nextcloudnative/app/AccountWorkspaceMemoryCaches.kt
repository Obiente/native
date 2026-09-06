package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf

internal class PhotoTimelineUiState {
    val timeline = mutableStateOf(PhotoTimelineState(pageSize = MAX_PHOTO_TIMELINE_PAGE_SIZE))
    val backupStatuses = mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())
    val initialLoadCompleted = mutableStateOf(false)
}

internal object PhotoTimelineUiStateRepository {
    private const val MAXIMUM_ACCOUNT_STATES = 4
    private val accountStates = linkedMapOf<String, PhotoTimelineUiState>()

    fun stateFor(session: NextcloudSession): PhotoTimelineUiState {
        val accountKey = previewCacheDigest(session)
        accountStates.remove(accountKey)?.let { existing ->
            accountStates[accountKey] = existing
            return existing
        }
        val created = PhotoTimelineUiState()
        accountStates[accountKey] = created
        while (accountStates.size > MAXIMUM_ACCOUNT_STATES) accountStates.remove(accountStates.keys.first())
        return created
    }

    fun removeAccount(accountStorageKey: String) {
        accountStates.remove(accountStorageKey)
    }
}

internal sealed interface CalendarLoadState {
    data object Loading : CalendarLoadState
    data class Ready(
        val month: CalendarMonth,
        val timeWindow: GroupwareDavTimeWindow,
        val calendars: List<GroupwareCalendar>,
        val events: List<GroupwareCalendarEvent>,
    ) : CalendarLoadState
    data class Error(val message: String) : CalendarLoadState
}

internal object CalendarWorkspaceMemoryCache {
    private val gate = sharedAccountPrivateMemoryGate
    private val entries = linkedMapOf<Pair<NextcloudAccountId, String>, CalendarLoadState.Ready>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? =
        gate.producer(session.accountId.storageKey)

    fun get(
        session: NextcloudSession,
        userId: String,
        month: CalendarMonth,
        timeWindow: GroupwareDavTimeWindow,
    ): CalendarLoadState.Ready? = gate.read(session.accountId.storageKey, null) {
        val key = key(session, userId, month, timeWindow)
        entries.remove(key)?.also { entries[key] = it }
    }

    fun store(
        session: NextcloudSession,
        userId: String,
        value: CalendarLoadState.Ready,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(session.accountId.storageKey, producer) {
            val key = key(session, userId, value.month, value.timeWindow)
            entries.remove(key)
            entries[key] = value
            while (entries.size > MAXIMUM_RETAINED_CALENDAR_MONTHS) entries.remove(entries.keys.first())
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.keys.removeAll { (account) -> account.storageKey == accountStorageKey }
    }

    private fun key(
        session: NextcloudSession,
        userId: String,
        month: CalendarMonth,
        timeWindow: GroupwareDavTimeWindow,
    ): Pair<NextcloudAccountId, String> = session.accountId to
        "$userId\n${month.year}-${month.month}\n${timeWindow.startUtc}-${timeWindow.endUtc}"
}

internal sealed interface UserStatusSurfaceState {
    data object Loading : UserStatusSurfaceState
    data class Available(
        val capabilities: NativeUserStatusCapabilities,
        val status: NativeUserStatus,
        val predefined: List<NativePredefinedStatus>,
    ) : UserStatusSurfaceState
    data class Failed(val message: String) : UserStatusSurfaceState
}

internal object UserStatusWorkspaceMemoryCache {
    private val entries = linkedMapOf<NextcloudAccountId, UserStatusSurfaceState.Available>()

    fun get(session: NextcloudSession): UserStatusSurfaceState.Available? {
        val key = session.accountId
        return entries.remove(key)?.also { entries[key] = it }
    }

    fun store(session: NextcloudSession, value: UserStatusSurfaceState.Available) {
        val key = session.accountId
        entries.remove(key)
        entries[key] = value
        while (entries.size > MAXIMUM_RETAINED_STATUS_ACCOUNTS) entries.remove(entries.keys.first())
    }

    fun removeAccount(accountStorageKey: String) {
        entries.keys.removeAll { account -> account.storageKey == accountStorageKey }
    }
}

internal object ActivityWorkspaceMemoryCache {
    private val gate = sharedAccountPrivateMemoryGate
    private val entries = linkedMapOf<Pair<NextcloudAccountId, String>, ActivityTimelineState>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? =
        gate.producer(session.accountId.storageKey)

    fun get(session: NextcloudSession, filterId: String): ActivityTimelineState? =
        gate.read(session.accountId.storageKey, null) {
            val key = session.accountId to filterId
            entries.remove(key)?.also { entries[key] = it }
        }

    fun store(
        session: NextcloudSession,
        filterId: String,
        value: ActivityTimelineState,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(session.accountId.storageKey, producer) {
            val key = session.accountId to filterId
            entries.remove(key)
            entries[key] = value
            while (entries.size > MAXIMUM_RETAINED_ACTIVITY_ACCOUNTS) entries.remove(entries.keys.first())
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.keys.removeAll { (account) -> account.storageKey == accountStorageKey }
    }
}

internal object TalkWorkspaceMemoryCache {
    private val gate = sharedAccountPrivateMemoryGate
    private val rooms = linkedMapOf<NextcloudAccountId, List<TalkRoom>>()
    private val messages = linkedMapOf<Pair<NextcloudAccountId, String>, List<TalkMessage>>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? =
        gate.producer(session.accountId.storageKey)

    fun rooms(session: NextcloudSession): List<TalkRoom>? = gate.read(session.accountId.storageKey, null) {
        touch(rooms, session.accountId)
    }

    fun storeRooms(
        session: NextcloudSession,
        value: List<TalkRoom>,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(session.accountId.storageKey, producer) {
            store(rooms, session.accountId, value, MAXIMUM_RETAINED_TALK_ACCOUNTS)
        }
    }

    fun messages(session: NextcloudSession, roomToken: String): List<TalkMessage>? =
        gate.read(session.accountId.storageKey, null) { touch(messages, session.accountId to roomToken) }

    fun storeMessages(
        session: NextcloudSession,
        roomToken: String,
        value: List<TalkMessage>,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(session.accountId.storageKey, producer) {
            store(messages, session.accountId to roomToken, value, MAXIMUM_RETAINED_TALK_ROOMS)
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        rooms.keys.removeAll { account -> account.storageKey == accountStorageKey }
        messages.keys.removeAll { (account) -> account.storageKey == accountStorageKey }
    }

    private fun <Key, T> touch(entries: LinkedHashMap<Key, T>, key: Key): T? =
        entries.remove(key)?.also { entries[key] = it }

    private fun <Key, T> store(entries: LinkedHashMap<Key, T>, key: Key, value: T, maximum: Int) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maximum) entries.remove(entries.keys.first())
    }
}

internal fun removeCalendarWorkspaceMemory(accountStorageKey: String) =
    CalendarWorkspaceMemoryCache.purgeRetiredAccount(accountStorageKey)

internal fun removeUserStatusWorkspaceMemory(accountStorageKey: String) =
    UserStatusWorkspaceMemoryCache.removeAccount(accountStorageKey)

internal fun removeNextcloudNativeWorkspaceMemory(accountStorageKey: String) {
    PhotoTimelineUiStateRepository.removeAccount(accountStorageKey)
    ActivityWorkspaceMemoryCache.purgeRetiredAccount(accountStorageKey)
    TalkWorkspaceMemoryCache.purgeRetiredAccount(accountStorageKey)
}

private const val MAXIMUM_RETAINED_CALENDAR_MONTHS = 24
private const val MAXIMUM_RETAINED_STATUS_ACCOUNTS = 4
private const val MAXIMUM_RETAINED_ACTIVITY_ACCOUNTS = 4
private const val MAXIMUM_RETAINED_TALK_ACCOUNTS = 4
private const val MAXIMUM_RETAINED_TALK_ROOMS = 16
