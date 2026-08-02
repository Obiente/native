package dev.obiente.nextcloudnative.app

internal data class AppWorkspaceNavigationMemory<T>(
    val activeAppId: String? = null,
    val lastStateByApp: Map<String, T> = emptyMap(),
) {
    fun retainCurrent(
        currentState: T,
        maximumRememberedApps: Int = MAX_REMEMBERED_APP_WORKSPACES,
    ): AppWorkspaceNavigationMemory<T> {
        val appId = activeAppId ?: return this
        val remembered = ((lastStateByApp - appId) + (appId to currentState))
            .entries
            .toList()
            .takeLast(maximumRememberedApps.coerceAtLeast(1))
            .associate { it.toPair() }
        return copy(lastStateByApp = remembered)
    }

    fun switchTo(
        appId: String,
        initialState: T,
        maximumRememberedApps: Int = MAX_REMEMBERED_APP_WORKSPACES,
    ): AppWorkspaceNavigationSwitch<T> {
        require(appId.isNotBlank()) { "An app workspace ID is required." }
        val restoredState = lastStateByApp[appId] ?: initialState
        val remembered = ((lastStateByApp - appId) + (appId to restoredState))
            .entries
            .toList()
            .takeLast(maximumRememberedApps.coerceAtLeast(1))
            .associate { it.toPair() }
        return AppWorkspaceNavigationSwitch(
            memory = copy(activeAppId = appId, lastStateByApp = remembered),
            restoredState = restoredState,
        )
    }

    fun leave(): AppWorkspaceNavigationMemory<T> = copy(activeAppId = null)
}

internal data class AppWorkspaceNavigationSwitch<T>(
    val memory: AppWorkspaceNavigationMemory<T>,
    val restoredState: T,
)

internal const val MAX_REMEMBERED_APP_WORKSPACES = 32
