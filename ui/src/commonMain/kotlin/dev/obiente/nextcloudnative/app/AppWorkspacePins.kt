package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AppWorkspacePinsSnapshot(
    val schemaVersion: Int = APP_WORKSPACE_PINS_SCHEMA_VERSION,
    val appIds: List<String>,
)

internal data class AppWorkspacePinsLoad(
    val appIds: List<String>,
    val storageAuthoritative: Boolean,
    val legacyMigrationRequired: Boolean = false,
)

internal class AppWorkspacePinsLoadCoordinator(
    private val loadPins: () -> AppWorkspacePinsLoad,
) {
    var state: AppWorkspacePinsLoad? = null
        private set

    suspend fun load(dispatcher: CoroutineDispatcher = Dispatchers.Default): AppWorkspacePinsLoad {
        val loaded = withContext(dispatcher) { loadPins() }
        state = loaded
        return loaded
    }
}

internal class AppWorkspacePinsRepository(
    private val storage: HomeWorkspaceLayoutStorage,
) {
    fun load(accountScopeDigest: String, legacyAccountScopeDigest: String? = null): List<String> {
        return loadWithProvenance(accountScopeDigest, legacyAccountScopeDigest).appIds
    }

    fun loadWithProvenance(
        accountScopeDigest: String,
        legacyAccountScopeDigest: String? = null,
    ): AppWorkspacePinsLoad {
        val encoded = try {
            storage.read(persistenceKey(accountScopeDigest))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AppWorkspacePinsLoad(defaultAppWorkspacePinnedIds(), storageAuthoritative = false)
        }
        var legacyMigrationRequired = false
        val persisted = encoded ?: legacyAccountScopeDigest?.let { legacyScope ->
            try {
                storage.read(persistenceKey(legacyScope))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return AppWorkspacePinsLoad(defaultAppWorkspacePinnedIds(), storageAuthoritative = false)
            }?.also { legacyMigrationRequired = true }
        }
            ?: return AppWorkspacePinsLoad(defaultAppWorkspacePinnedIds(), storageAuthoritative = true)
        if (persisted.length !in 1..MAX_APP_WORKSPACE_PINS_CHARACTERS) {
            return AppWorkspacePinsLoad(defaultAppWorkspacePinnedIds(), storageAuthoritative = true)
        }
        val snapshot = runCatching {
            appWorkspacePinsJson.decodeFromString<AppWorkspacePinsSnapshot>(persisted)
        }.getOrNull() ?: return AppWorkspacePinsLoad(defaultAppWorkspacePinnedIds(), storageAuthoritative = true)
        if (snapshot.schemaVersion != APP_WORKSPACE_PINS_SCHEMA_VERSION) {
            return AppWorkspacePinsLoad(defaultAppWorkspacePinnedIds(), storageAuthoritative = true)
        }
        val appIds = validatedAppWorkspacePinnedIds(snapshot.appIds) ?: defaultAppWorkspacePinnedIds()
        return AppWorkspacePinsLoad(
            appIds = appIds,
            storageAuthoritative = !legacyMigrationRequired,
            legacyMigrationRequired = legacyMigrationRequired,
        )
    }

    fun save(accountScopeDigest: String, appIds: List<String>): Boolean {
        val validated = validatedAppWorkspacePinnedIds(appIds) ?: return false
        return try {
            val encoded = appWorkspacePinsJson.encodeToString(AppWorkspacePinsSnapshot(appIds = validated))
            check(encoded.length <= MAX_APP_WORKSPACE_PINS_CHARACTERS)
            storage.write(persistenceKey(accountScopeDigest), encoded)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun persistenceKey(accountScopeDigest: String): String {
        require(accountScopeDigest.length == 64 && accountScopeDigest.all { it in '0'..'9' || it in 'a'..'f' })
        return "apps:pins:$APP_WORKSPACE_PINS_SCHEMA_VERSION:$accountScopeDigest"
    }
}

internal fun toggleAppWorkspacePin(appIds: List<String>, appId: String): List<String> {
    val canonicalId = canonicalAppWorkspaceId(appId)
    require(canonicalId.isSafePinnedAppId())
    val current = validatedAppWorkspacePinnedIds(appIds) ?: error("The pinned app list is invalid.")
    return if (canonicalId in current) {
        current - canonicalId
    } else {
        require(current.size < MAX_APP_WORKSPACE_PINS) { "No more apps can be pinned." }
        current + canonicalId
    }
}

internal fun defaultAppWorkspacePinnedIds(): List<String> =
    listOf("files", "photos", "spreed", "calendar")

internal fun reconcileAppWorkspacePinnedIds(
    appIds: List<String>,
    installedAppIds: Collection<String>,
): List<String> {
    val installed = installedAppIds.mapTo(mutableSetOf(), ::canonicalAppWorkspaceId)
    return (validatedAppWorkspacePinnedIds(appIds) ?: defaultAppWorkspacePinnedIds())
        .filter { it in installed }
}

internal fun reconciledAppWorkspacePinsForDiscovery(
    appIds: List<String>,
    installedAppIds: Collection<String>,
    appsAuthoritative: Boolean,
): List<String>? = if (appsAuthoritative) {
    reconcileAppWorkspacePinnedIds(appIds, installedAppIds)
} else {
    null
}

private fun validatedAppWorkspacePinnedIds(appIds: List<String>): List<String>? {
    if (appIds.size > MAX_APP_WORKSPACE_PINS) return null
    val canonical = appIds.map(::canonicalAppWorkspaceId)
    if (canonical.any { !it.isSafePinnedAppId() } || canonical.distinct().size != canonical.size) return null
    return canonical
}

private fun String.isSafePinnedAppId(): Boolean =
    length in 1..64 && all {
        it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '.'
    }

private val appWorkspacePinsJson = Json { ignoreUnknownKeys = true }
private const val APP_WORKSPACE_PINS_SCHEMA_VERSION = 1
internal const val MAX_APP_WORKSPACE_PINS = 6
private const val MAX_APP_WORKSPACE_PINS_CHARACTERS = 1024
