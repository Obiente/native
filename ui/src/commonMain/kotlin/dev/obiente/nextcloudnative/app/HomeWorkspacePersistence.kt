package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface HomeWorkspaceLayoutStorage {
    fun read(persistenceKey: String): String?

    fun write(persistenceKey: String, encodedSnapshot: String)
}

internal class HomeWorkspaceLayoutRepository(
    private val storage: HomeWorkspaceLayoutStorage,
    private val encodeSnapshot: (HomeWorkspaceLayout) -> String =
        ::encodeHomeWorkspaceLayoutSnapshot,
) {
    fun load(scope: HomeWorkspaceScope, legacyAccountScopeDigest: String? = null): HomeWorkspaceLayout {
        val encoded = try {
            storage.read(scope.persistenceKey)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return defaultHomeWorkspaceLayout(scope)
        }
        if (encoded != null) return decodeHomeWorkspaceLayoutSnapshot(scope, encoded)
        val legacyScope = legacyAccountScopeDigest?.let { digest ->
            HomeWorkspaceScope(digest, scope.formFactor)
        } ?: return defaultHomeWorkspaceLayout(scope)
        val legacyEncoded = runCatching { storage.read(legacyScope.persistenceKey) }.getOrNull()
            ?: return defaultHomeWorkspaceLayout(scope)
        val legacyLayout = decodeHomeWorkspaceLayoutSnapshot(legacyScope, legacyEncoded)
        val migrated = HomeWorkspaceLayout(scope, legacyLayout.sections)
        save(migrated)
        return migrated
    }

    /**
     * Returns false when platform persistence fails. The caller may keep the in-memory layout
     * without crashing or pretending it was durably saved.
     */
    fun save(layout: HomeWorkspaceLayout): Boolean {
        return runCatching {
            val encoded = encodeSnapshot(layout)
            storage.write(layout.scope.persistenceKey, encoded)
        }.isSuccess
    }
}

internal fun encodeHomeWorkspaceLayoutSnapshot(layout: HomeWorkspaceLayout): String =
    homeWorkspaceJson.encodeToString(layout.snapshot()).also { encoded ->
        require(encoded.length <= MAX_HOME_WORKSPACE_SNAPSHOT_CHARACTERS) {
            "The home workspace snapshot is too large."
        }
    }

internal fun decodeHomeWorkspaceLayoutSnapshot(
    scope: HomeWorkspaceScope,
    encoded: String,
): HomeWorkspaceLayout {
    if (encoded.length !in 1..MAX_HOME_WORKSPACE_SNAPSHOT_CHARACTERS) {
        return defaultHomeWorkspaceLayout(scope)
    }
    val snapshot = runCatching {
        homeWorkspaceJson.decodeFromString<HomeWorkspaceLayoutSnapshot>(encoded)
    }.getOrNull()
    return restoreHomeWorkspaceLayout(scope, snapshot)
}

@Composable
internal expect fun rememberHomeWorkspaceLayoutStorage(): HomeWorkspaceLayoutStorage

@Composable
internal expect fun rememberHomeFormFactor(): HomeFormFactor

private val homeWorkspaceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal const val MAX_HOME_WORKSPACE_SNAPSHOT_CHARACTERS = 8 * 1024
