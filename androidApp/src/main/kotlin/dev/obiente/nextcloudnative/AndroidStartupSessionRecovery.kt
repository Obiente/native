package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.NextcloudSessionStorageUnavailableException

internal fun loadAndroidStartupSession(
    registry: NextcloudAccountRegistry?,
    hasPersistedCredentials: () -> Boolean,
    load: (NextcloudAccountId) -> NextcloudSession?,
): NextcloudSession? {
    if (registry == null && hasPersistedCredentials()) {
        throw NextcloudSessionStorageUnavailableException("Saved account credentials need recovery before signing in again.")
    }
    val active = registry?.activeAccountId ?: return null
    return load(active) ?: throw NextcloudSessionStorageUnavailableException("The saved account cannot be opened. Retry or reset its sign-in.")
}
