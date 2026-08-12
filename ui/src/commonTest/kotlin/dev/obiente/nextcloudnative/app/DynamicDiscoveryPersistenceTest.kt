package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DynamicDiscoveryPersistenceTest {
    @Test
    fun restoredVerifiedContractIsReadOnlyUntilLiveRevalidation() {
        val discovery = verifiedDiscovery()

        val encoded = assertNotNull(encodePersistedDynamicDiscovery(discovery))
        val restored = assertNotNull(decodePersistedDynamicDiscovery(encoded, "tables"))

        assertEquals(discovery.descriptor, restored.descriptor)
        assertEquals(DynamicContractVersionStatus.LastKnownReadOnly, restored.versionStatus)
    }

    @Test
    fun metadataFallbackAndAlreadyStaleContractsAreNotPersisted() {
        assertNull(
            encodePersistedDynamicDiscovery(
                verifiedDiscovery().copy(acquisition = DynamicDescriptorAcquisition.MetadataFallback),
            ),
        )
        assertNull(
            encodePersistedDynamicDiscovery(
                verifiedDiscovery().copy(versionStatus = DynamicContractVersionStatus.LastKnownReadOnly),
            ),
        )
    }

    @Test
    fun cacheEntryCannotBeReusedForAnotherApp() {
        val encoded = assertNotNull(encodePersistedDynamicDiscovery(verifiedDiscovery()))

        assertNull(decodePersistedDynamicDiscovery(encoded, "mail"))
    }

    private fun verifiedDiscovery() = DynamicDescriptorDiscovery(
        descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("tables", "Tables", "1.0.9"),
            endpointPolicy = EndpointPolicy(
                serverOrigin = "https://cloud.example.test",
                approvedApiPrefixes = listOf("/apps/tables"),
            ),
        ),
        sourcePath = "https://apps.nextcloud.com/api/v1/apps/releases/tables",
        acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
        diagnostics = listOf("Verified contract"),
        versionStatus = DynamicContractVersionStatus.VerifiedCurrent,
    )
}
