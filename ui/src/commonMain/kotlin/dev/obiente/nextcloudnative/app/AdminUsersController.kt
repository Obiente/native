package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Immutable
internal data class AdminUsersUiState(
    val query: String = "",
    val users: List<AdminUser> = emptyList(),
    val listLoaded: Boolean = false,
    val loadingList: Boolean = false,
    val mayHaveMore: Boolean = false,
    val listFailure: String? = null,
    val groups: List<AdminGroup> = emptyList(),
    val groupsLoaded: Boolean = false,
    val groupsFailure: String? = null,
    val selectedUserId: String? = null,
    val selectedUser: AdminUser? = null,
    val detailFailure: String? = null,
    val mutating: Boolean = false,
    val mutationMessage: String? = null,
    val mutationFailed: Boolean = false,
)

/**
 * Administrator users and groups for one session. Reads use the app password; every write is
 * password-confirmed, bound to the exact user or group ID, and verified by a fresh read.
 */
internal class AdminUsersController(
    private val scope: CoroutineScope,
    private val session: NextcloudSession,
    /** The server-reported ID of the signed-in account, which may differ from its login name. */
    private val signedInUserId: String,
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val transport: AccountPasswordConfirmedTransport?,
) {
    var state by mutableStateOf(AdminUsersUiState())
        private set

    val canWrite: Boolean get() = transport != null

    private var listJob: Job? = null
    private var detailJob: Job? = null

    fun search(query: String) {
        val trimmed = query.take(120)
        listJob?.cancel()
        state = state.copy(query = trimmed, loadingList = true, listFailure = null)
        listJob = scope.launch {
            val result = read { parseAdminUserPage(execute(AdminUserRequests.usersPage(trimmed, 0)), 0) }
            if (state.query != trimmed) return@launch
            state = when (result) {
                is AdminReadResult.Loaded -> state.copy(
                    users = result.value.users, listLoaded = true, loadingList = false, mayHaveMore = result.value.mayHaveMore,
                )
                else -> state.copy(loadingList = false, listLoaded = true, listFailure = result.failureMessage("accounts"))
            }
        }
    }

    fun loadMore() {
        if (state.loadingList || !state.mayHaveMore) return
        val query = state.query
        val offset = state.users.size
        state = state.copy(loadingList = true, listFailure = null)
        listJob = scope.launch {
            val result = read { parseAdminUserPage(execute(AdminUserRequests.usersPage(query, offset)), offset) }
            if (state.query != query) return@launch
            state = when (result) {
                is AdminReadResult.Loaded -> state.copy(
                    users = (state.users + result.value.users).distinctBy(AdminUser::id),
                    loadingList = false,
                    mayHaveMore = result.value.mayHaveMore,
                )
                else -> state.copy(loadingList = false, listFailure = result.failureMessage("accounts"))
            }
        }
    }

    fun loadGroups() {
        scope.launch {
            state = when (val result = read { parseAdminGroups(execute(AdminUserRequests.groups())) }) {
                is AdminReadResult.Loaded -> state.copy(groups = result.value, groupsLoaded = true, groupsFailure = null)
                else -> state.copy(groupsLoaded = true, groupsFailure = result.failureMessage("groups"))
            }
        }
    }

    fun select(userId: String?) {
        detailJob?.cancel()
        state = state.copy(
            selectedUserId = userId,
            selectedUser = state.users.firstOrNull { it.id == userId },
            detailFailure = null,
            mutationMessage = null,
            mutationFailed = false,
        )
        if (userId == null) return
        detailJob = scope.launch { refreshSelected(userId) }
    }

    fun dismissMessage() {
        state = state.copy(mutationMessage = null, mutationFailed = false)
    }

    fun apply(mutation: AdminUserMutation, secret: AccountPasswordConfirmationSecret, onDone: () -> Unit = {}) {
        val target = state.selectedUser
        if (target == null || target.id != mutation.userId || !adminUserActionsAllowed(target, signedInUserId)) {
            secret.clear()
            onDone()
            return
        }
        val removedUserId = mutation.userId.takeIf { mutation is AdminUserMutation.Delete }
        runWrite(secret, onDone, AdminUserRequests.mutation(mutation), mutation.successMessage(), removedUserId) {
            if (mutation is AdminUserMutation.Delete) {
                read { parseAdminUserDetail(execute(AdminUserRequests.user(mutation.userId))) } is AdminReadResult.NotFound
            } else {
                (refreshSelected(mutation.userId) as? AdminReadResult.Loaded)?.value?.reflects(mutation) == true
            }
        }
    }

    fun create(newUser: AdminNewUser, secret: AccountPasswordConfirmationSecret, onDone: () -> Unit = {}) {
        val request = try {
            AdminUserRequests.createUser(newUser)
        } catch (invalid: IllegalArgumentException) {
            secret.clear()
            state = state.copy(mutationMessage = invalid.message, mutationFailed = true)
            onDone()
            return
        }
        runWrite(secret, onDone, request, successMessage = "Account ${newUser.userId} was created.") {
            read { parseAdminUserDetail(execute(AdminUserRequests.user(newUser.userId))) } is AdminReadResult.Loaded
        }
    }

    fun mutateGroup(mutation: AdminGroupMutation, secret: AccountPasswordConfirmationSecret, onDone: () -> Unit = {}) {
        val request = try {
            AdminUserRequests.groupMutation(mutation)
        } catch (invalid: IllegalArgumentException) {
            secret.clear()
            state = state.copy(mutationMessage = invalid.message, mutationFailed = true)
            onDone()
            return
        }
        val message = when (mutation) {
            is AdminGroupMutation.Create -> "Group ${mutation.groupId} was created."
            is AdminGroupMutation.Delete -> "Group ${mutation.groupId} was deleted."
        }
        runWrite(secret, onDone, request, message) {
            val groups = (read { parseAdminGroups(execute(AdminUserRequests.groups(search = mutation.groupId))) } as? AdminReadResult.Loaded)
                ?.value ?: return@runWrite false
            val present = groups.any { it.id == mutation.groupId }
            if (mutation is AdminGroupMutation.Create) present else !present
        }
    }

    private fun runWrite(
        secret: AccountPasswordConfirmationSecret,
        onDone: () -> Unit,
        request: NextcloudApiRequest,
        successMessage: String,
        removedUserId: String? = null,
        verify: suspend () -> Boolean,
    ) {
        val activeTransport = transport
        if (activeTransport == null || state.mutating) {
            secret.clear()
            onDone()
            return
        }
        state = state.copy(mutating = true, mutationMessage = null, mutationFailed = false)
        scope.launch {
            val outcome = try {
                runPasswordConfirmedWrite(activeTransport, session, request, secret, verify)
            } finally {
                secret.clear()
            }
            val removed = removedUserId.takeIf { outcome == ConfirmedWriteOutcome.Applied }
            state = state.copy(
                mutating = false,
                mutationMessage = outcome.failureMessage() ?: successMessage,
                mutationFailed = outcome != ConfirmedWriteOutcome.Applied,
                users = if (removed != null) state.users.filterNot { it.id == removed } else state.users,
                selectedUserId = if (removed != null) null else state.selectedUserId,
                selectedUser = if (removed != null) null else state.selectedUser,
            )
            onDone()
            if (outcome != ConfirmedWriteOutcome.Applied || request.relativePath.contains("/groups")) loadGroups()
            if (request.method == NextcloudApiMethod.POST && request.relativePath.endsWith("/cloud/users")) search(state.query)
        }
    }

    private suspend fun refreshSelected(userId: String): AdminReadResult<AdminUser> {
        val result = read { parseAdminUserDetail(execute(AdminUserRequests.user(userId))) }
        if (state.selectedUserId != userId) return result
        state = when (result) {
            is AdminReadResult.Loaded -> state.copy(
                selectedUser = result.value,
                detailFailure = null,
                users = state.users.map { if (it.id == userId) result.value else it },
            )
            else -> state.copy(detailFailure = result.failureMessage("account"))
        }
        return result
    }

    private suspend fun <T> read(block: suspend () -> AdminReadResult<T>): AdminReadResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Transport fault boundary: exception text stays out of the UI.
        AdminReadResult.Unavailable
    }
}

internal fun AdminReadResult<*>.failureMessage(subject: String): String = when (this) {
    is AdminReadResult.Loaded -> ""
    AdminReadResult.Forbidden -> "This account is not allowed to manage $subject."
    AdminReadResult.NotFound -> "The $subject could not be found."
    AdminReadResult.Unavailable -> "The $subject could not be loaded. Check your connection and try again."
    AdminReadResult.Malformed -> "The server sent $subject data this app cannot read."
}

internal fun AdminUserMutation.successMessage(): String = when (this) {
    is AdminUserMutation.SetEnabled -> if (enabled) "$userId was enabled." else "$userId was disabled."
    is AdminUserMutation.AddToGroup -> "$userId was added to $groupId."
    is AdminUserMutation.RemoveFromGroup -> "$userId was removed from $groupId."
    is AdminUserMutation.AddSubadmin -> "$userId now administers $groupId."
    is AdminUserMutation.RemoveSubadmin -> "$userId no longer administers $groupId."
    is AdminUserMutation.SetQuota -> "The quota for $userId was changed."
    is AdminUserMutation.SetDisplayName -> "The name of $userId was changed."
    is AdminUserMutation.SetEmail -> "The email of $userId was changed."
    is AdminUserMutation.Delete -> "$userId was deleted."
}
