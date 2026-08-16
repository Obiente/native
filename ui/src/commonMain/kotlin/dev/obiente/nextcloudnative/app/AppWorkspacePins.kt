package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AppWorkspacePinsSnapshot(
    val schemaVersion: Int = APP_WORKSPACE_PINS_SCHEMA_VERSION,
    val appIds: List<String>,
)

internal class AppWorkspacePinsRepository(
    private val storage: HomeWorkspaceLayoutStorage,
) {
    fun load(accountScopeDigest: String): List<String> {
        val encoded = runCatching { storage.read(persistenceKey(accountScopeDigest)) }.getOrNull()
            ?: return defaultAppWorkspacePinnedIds()
        if (encoded.length !in 1..MAX_APP_WORKSPACE_PINS_CHARACTERS) return defaultAppWorkspacePinnedIds()
        val snapshot = runCatching {
            appWorkspacePinsJson.decodeFromString<AppWorkspacePinsSnapshot>(encoded)
        }.getOrNull() ?: return defaultAppWorkspacePinnedIds()
        if (snapshot.schemaVersion != APP_WORKSPACE_PINS_SCHEMA_VERSION) return defaultAppWorkspacePinnedIds()
        return validatedAppWorkspacePinnedIds(snapshot.appIds) ?: defaultAppWorkspacePinnedIds()
    }

    fun save(accountScopeDigest: String, appIds: List<String>): Boolean {
        val validated = validatedAppWorkspacePinnedIds(appIds) ?: return false
        return runCatching {
            val encoded = appWorkspacePinsJson.encodeToString(AppWorkspacePinsSnapshot(appIds = validated))
            check(encoded.length <= MAX_APP_WORKSPACE_PINS_CHARACTERS)
            storage.write(persistenceKey(accountScopeDigest), encoded)
        }.isSuccess
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
internal const val MAX_APP_WORKSPACE_PINS = 8
private const val MAX_APP_WORKSPACE_PINS_CHARACTERS = 1024
