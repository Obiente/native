package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface HomeWorkspaceLayoutStorage {
    fun read(persistenceKey: String): String?

    fun write(persistenceKey: String, encodedSnapshot: String)

    fun writeIfAbsent(persistenceKey: String, encodedSnapshot: String): Boolean {
        if (read(persistenceKey) != null) return false
        write(persistenceKey, encodedSnapshot)
        return true
    }
}

internal data class HomeWorkspaceLayoutLoad(
    val layout: HomeWorkspaceLayout,
    val legacyMigrationRequired: Boolean = false,
)

internal class HomeWorkspaceLayoutRepository(
    private val storage: HomeWorkspaceLayoutStorage,
    private val encodeSnapshot: (HomeWorkspaceLayout) -> String =
        ::encodeHomeWorkspaceLayoutSnapshot,
) {
    fun load(scope: HomeWorkspaceScope, legacyAccountScopeDigest: String? = null): HomeWorkspaceLayout {
        return loadWithMigration(scope, legacyAccountScopeDigest).layout
    }

    fun loadWithMigration(
        scope: HomeWorkspaceScope,
        legacyAccountScopeDigest: String? = null,
    ): HomeWorkspaceLayoutLoad {
        val encoded = try {
            storage.read(scope.persistenceKey)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return HomeWorkspaceLayoutLoad(defaultHomeWorkspaceLayout(scope))
        }
        if (encoded != null) return HomeWorkspaceLayoutLoad(decodeHomeWorkspaceLayoutSnapshot(scope, encoded))
        val legacyScope = legacyAccountScopeDigest?.let { digest ->
            HomeWorkspaceScope(digest, scope.formFactor)
        } ?: return HomeWorkspaceLayoutLoad(defaultHomeWorkspaceLayout(scope))
        val legacyEncoded = try {
            storage.read(legacyScope.persistenceKey)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        } ?: return HomeWorkspaceLayoutLoad(defaultHomeWorkspaceLayout(scope))
        val legacyLayout = decodeHomeWorkspaceLayoutSnapshot(legacyScope, legacyEncoded)
        val migrated = HomeWorkspaceLayout(scope, legacyLayout.sections)
        return HomeWorkspaceLayoutLoad(migrated, legacyMigrationRequired = true)
    }

    /**
     * Returns false when platform persistence fails. The caller may keep the in-memory layout
     * without crashing or pretending it was durably saved.
     */
    fun save(layout: HomeWorkspaceLayout): Boolean {
        return try {
            val encoded = encodeSnapshot(layout)
            storage.write(layout.scope.persistenceKey, encoded)
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }

    /** Promotes a legacy layout only while the canonical key is still absent. */
    fun saveIfAbsent(layout: HomeWorkspaceLayout): Boolean {
        return try {
            val encoded = encodeSnapshot(layout)
            storage.writeIfAbsent(layout.scope.persistenceKey, encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
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
