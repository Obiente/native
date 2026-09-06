package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal fun homeWorkspaceAccountPersistenceKeys(
    accountScopeDigest: String,
    legacyAccountScopeDigest: String? = null,
): Set<String> = buildSet {
    setOfNotNull(accountScopeDigest, legacyAccountScopeDigest).forEach { digest ->
        require(digest.isCanonicalSha256Digest()) {
            "The home workspace account scope must be a canonical SHA-256 digest."
        }
        add("apps:pins:1:$digest")
        HomeFormFactor.entries.forEach { formFactor ->
            add(HomeWorkspaceScope(digest, formFactor).persistenceKey)
        }
    }
}

internal data class HomeWorkspaceLayoutLoad(
    val layout: HomeWorkspaceLayout,
    val storageAuthoritative: Boolean = true,
    val legacyMigrationRequired: Boolean = false,
)

internal enum class PersistencePromotionResult {
    Saved,
    CanonicalAlreadyPresent,
    Failed,
}

internal class HomeWorkspaceLayoutLoadCoordinator(
    private val loadLayout: () -> HomeWorkspaceLayoutLoad,
) {
    var state: HomeWorkspaceLayoutLoad? = null
        private set

    suspend fun load(dispatcher: CoroutineDispatcher = Dispatchers.Default): HomeWorkspaceLayoutLoad {
        val loaded = withContext(dispatcher) { loadLayout() }
        state = loaded
        return loaded
    }
}

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
            return HomeWorkspaceLayoutLoad(
                defaultHomeWorkspaceLayout(scope),
                storageAuthoritative = false,
            )
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
            return HomeWorkspaceLayoutLoad(
                defaultHomeWorkspaceLayout(scope),
                storageAuthoritative = false,
            )
        } ?: return HomeWorkspaceLayoutLoad(defaultHomeWorkspaceLayout(scope))
        val legacyLayout = decodeHomeWorkspaceLayoutSnapshot(legacyScope, legacyEncoded)
        val migrated = HomeWorkspaceLayout(scope, legacyLayout.sections)
        return HomeWorkspaceLayoutLoad(
            layout = migrated,
            storageAuthoritative = false,
            legacyMigrationRequired = true,
        )
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
    fun promoteIfAbsent(layout: HomeWorkspaceLayout): PersistencePromotionResult {
        return try {
            val encoded = encodeSnapshot(layout)
            if (storage.writeIfAbsent(layout.scope.persistenceKey, encoded)) {
                PersistencePromotionResult.Saved
            } else {
                PersistencePromotionResult.CanonicalAlreadyPresent
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            PersistencePromotionResult.Failed
        }
    }

    fun saveIfAbsent(layout: HomeWorkspaceLayout): Boolean =
        promoteIfAbsent(layout) == PersistencePromotionResult.Saved

    fun resolveLegacyMigration(loaded: HomeWorkspaceLayoutLoad): HomeWorkspaceLayoutLoad {
        if (!loaded.legacyMigrationRequired) return loaded
        return when (promoteIfAbsent(loaded.layout)) {
            PersistencePromotionResult.Saved -> loaded.copy(
                storageAuthoritative = true,
                legacyMigrationRequired = false,
            )
            PersistencePromotionResult.CanonicalAlreadyPresent -> loadWithMigration(loaded.layout.scope)
            PersistencePromotionResult.Failed -> loaded.copy(storageAuthoritative = false)
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
