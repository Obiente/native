package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicContractVersionTrustTest {
    @Test
    fun `patch compatible contracts remain read only after installed version observation`() {
        val compatible = AcquiredOpenApiContract(
            appId = "example",
            appVersion = "1.2.4",
            contractVersion = "1.2.3",
            specFile = "openapi.json",
            document = "{}",
            packageUrl = "https://apps.nextcloud.com/packages/example",
            sourceUrl = "https://apps.nextcloud.com/packages/example#openapi.json",
            sourceKind = AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage,
        )
        val exact = compatible.copy(
            contractVersion = compatible.appVersion,
            sourceKind = AcquiredOpenApiContractSourceKind.SignedAppPackage,
        )

        assertEquals(
            DynamicContractVersionStatus.LastKnownReadOnly,
            compatible.effectiveDynamicContractVersionStatus(DynamicContractVersionStatus.VerifiedCurrent),
        )
        assertEquals(
            DynamicContractVersionStatus.VerifiedCurrent,
            exact.effectiveDynamicContractVersionStatus(DynamicContractVersionStatus.VerifiedCurrent),
        )
    }
}
