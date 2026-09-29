package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedChoresContractTest {
    @Test
    fun `only reviewed versions with the exact shared controller qualify`() {
        val digest = "146286dcb68bddd025e0a47e7edc134fbc94f0e9f594e9030663bb0f217f3cc6"
        for (version in listOf("0.1.0", "0.2.0")) {
            assertTrue(isVerifiedChoresControllerDigest(version, digest))
            assertFalse(isVerifiedChoresControllerDigest(version, "0".repeat(64)))
        }
        for (version in listOf("", "0.1.1", "0.2.1", "0.3.0", "0.2.0-beta")) {
            assertFalse(isVerifiedChoresControllerDigest(version, digest))
        }
    }
}
