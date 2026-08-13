package dev.obiente.nextcloudnative.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MAX_PERSISTED_DYNAMIC_DISCOVERY_BYTES = 2 * 1024 * 1024

private val persistedDynamicDiscoveryJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Encodes only the verified native contract. Screen records, credentials, request bodies, and
 * account identity are deliberately outside this cache entry.
 */
fun encodePersistedDynamicDiscovery(discovery: DynamicDescriptorDiscovery): String? {
    if (
        discovery.acquisition == DynamicDescriptorAcquisition.MetadataFallback ||
        discovery.versionStatus != DynamicContractVersionStatus.VerifiedCurrent
    ) {
        return null
    }
    val bounded = discovery.copy(
        descriptor = discovery.descriptor.copy(
            endpointPolicy = discovery.descriptor.endpointPolicy.copy(
                serverOrigin = PERSISTED_DYNAMIC_DISCOVERY_ORIGIN,
            ),
        ),
        diagnostics = discovery.diagnostics
            .map { diagnostic -> diagnostic.take(MAX_PERSISTED_DYNAMIC_DIAGNOSTIC_LENGTH) }
            .take(MAX_PERSISTED_DYNAMIC_DIAGNOSTICS),
    )
    val encoded = persistedDynamicDiscoveryJson.encodeToString(bounded)
    return encoded.takeIf { value -> value.encodeToByteArray().size <= MAX_PERSISTED_DYNAMIC_DISCOVERY_BYTES }
}

/**
 * A process-restored contract is always read-only until the live server and installed app version
 * have been checked again. This lets the workspace paint immediately without authorizing stale
 * mutations.
 */
fun decodePersistedDynamicDiscovery(
    encoded: String,
    expectedAppId: String,
    activeServerUrl: String,
): DynamicDescriptorDiscovery? {
    if (
        encoded.isBlank() ||
        encoded.encodeToByteArray().size > MAX_PERSISTED_DYNAMIC_DISCOVERY_BYTES
    ) {
        return null
    }
    return runCatching {
        persistedDynamicDiscoveryJson.decodeFromString<DynamicDescriptorDiscovery>(encoded)
    }.getOrNull()
        ?.takeIf { discovery ->
            discovery.descriptor.app.id == expectedAppId &&
                discovery.acquisition != DynamicDescriptorAcquisition.MetadataFallback
        }
        ?.let { discovery ->
            discovery.copy(
                descriptor = discovery.descriptor.copy(
                    endpointPolicy = discovery.descriptor.endpointPolicy.copy(
                        serverOrigin = activeServerUrl.httpOrigin(),
                    ),
                ),
                versionStatus = DynamicContractVersionStatus.LastKnownReadOnly,
            )
        }
}

internal fun cachedDynamicDiscoveryMatchesInstalledVersion(
    discovery: DynamicDescriptorDiscovery,
    expectedAppId: String,
    installedAppVersion: String?,
): Boolean = discovery.descriptor.app.id == expectedAppId &&
    discovery.acquisition != DynamicDescriptorAcquisition.MetadataFallback &&
    installedAppVersion?.trim()?.takeIf(String::isNotEmpty)?.let { installedVersion ->
        discovery.descriptor.app.version == installedVersion
    } != false

fun String.isSafeDynamicDiscoveryCacheAppId(): Boolean =
    matches(Regex("[A-Za-z0-9_.-]{1,128}"))

private const val MAX_PERSISTED_DYNAMIC_DIAGNOSTICS = 24
private const val MAX_PERSISTED_DYNAMIC_DIAGNOSTIC_LENGTH = 1_024
private const val PERSISTED_DYNAMIC_DISCOVERY_ORIGIN = "https://persisted.invalid"
