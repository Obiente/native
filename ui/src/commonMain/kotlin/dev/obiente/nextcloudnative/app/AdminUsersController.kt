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
)

/**
 * Administrator accounts and groups for one session, read with the app password.
 * Provisioning writes need a password confirmation that sign-in cannot provide, so none are sent.
 */
internal class AdminUsersController(
    private val scope: CoroutineScope,
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
) {
    var state by mutableStateOf(AdminUsersUiState())
        private set

    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var groupsJob: Job? = null

    fun search(query: String) {
        val trimmed = query.take(120)
        listJob?.cancel()
        state = state.copy(query = trimmed, loadingList = true, listFailure = null)
        listJob = scope.launch {
            val result = read { parseAdminUserPage(execute(AdminUserRequests.usersPage(trimmed, 0)), 0) }
            // A newer search owns the list; this completion is stale.
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
        groupsJob?.cancel()
        groupsJob = scope.launch {
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
        )
        if (userId == null) return
        detailJob = scope.launch {
            val result = read { parseAdminUserDetail(execute(AdminUserRequests.user(userId))) }
            if (state.selectedUserId != userId) return@launch
            state = when (result) {
                is AdminReadResult.Loaded -> state.copy(
                    selectedUser = result.value,
                    detailFailure = null,
                    users = state.users.map { if (it.id == userId) result.value else it },
                )
                else -> state.copy(detailFailure = result.failureMessage("account"))
            }
        }
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
    AdminReadResult.Forbidden -> "This account is not allowed to view $subject."
    AdminReadResult.NotFound -> "The $subject could not be found."
    AdminReadResult.Unavailable -> "The $subject could not be loaded. Check your connection and try again."
    AdminReadResult.Malformed -> "The server sent $subject data this app cannot read."
}
