package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MarketingCaptureRegistryTest {
    @Test
    fun `production capture registry is valid`() {
        validateMarketingCaptureRegistry(marketingCaptureScenarios.map(MarketingCaptureScenario::registryEntry))
    }

    @Test
    fun `registry rejects duplicate identities and unsafe output names`() {
        val entry = validEntry()
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry, entry.copy(fileName = "other.png")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry, entry.copy(id = "other")))
        }
        listOf("../escape.png", "nested/file.png", "CAPTURE.png", "capture.jpg").forEach { fileName ->
            assertFailsWith<IllegalArgumentException> {
                validateMarketingCaptureRegistry(listOf(entry.copy(fileName = fileName)))
            }
        }
    }

    @Test
    fun `registry rejects invalid rendering and metadata contracts`() {
        val entry = validEntry()
        listOf(0, -1).forEach { width ->
            assertFailsWith<IllegalArgumentException> {
                validateMarketingCaptureRegistry(listOf(entry.copy(width = width)))
            }
        }
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { density ->
            assertFailsWith<IllegalArgumentException> {
                validateMarketingCaptureRegistry(listOf(entry.copy(density = density)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry.copy(feature = " ")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry.copy(state = " Ready")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry.copy(purpose = "unknown")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry.copy(platform = "../desktop")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry.copy(pullRequest = 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry.copy(issue = -1)))
        }
    }

    private fun validEntry() = MarketingCaptureRegistryEntry(
        id = "valid-capture",
        fileName = "valid-capture.png",
        feature = "Files",
        surface = "Browser",
        state = "Ready",
        purpose = "showcase",
        platform = "desktop",
        viewport = "wide",
        pullRequest = 1,
        issue = 2,
        width = 100,
        height = 80,
        density = 1f,
    )
}
