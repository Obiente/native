package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MarketingCaptureRegistryTest {
    @Test
    fun `production capture registry is valid`() {
        validateMarketingCaptureRegistry(marketingCaptureVariants.map(MarketingCaptureVariant::registryEntry))
    }

    @Test
    fun `registry rejects duplicate identities and unsafe output names`() {
        val entry = validEntry()
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(
                listOf(entry, entry.copy(fileName = "other.png"), lightEntry(entry)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(
                listOf(entry, lightEntry(entry), lightEntry(entry).copy(id = "other")),
            )
        }
        listOf("../escape.png", "nested/file.png", "CAPTURE.png", "capture.jpg").forEach { fileName ->
            assertFailsWith<IllegalArgumentException> {
                validateMarketingCaptureRegistry(validPair(entry.copy(fileName = fileName)))
            }
        }
    }

    @Test
    fun `registry rejects invalid rendering and metadata contracts`() {
        val entry = validEntry()
        listOf(0, -1).forEach { width ->
            assertFailsWith<IllegalArgumentException> {
                validateMarketingCaptureRegistry(validPair(entry.copy(width = width)))
            }
        }
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { density ->
            assertFailsWith<IllegalArgumentException> {
                validateMarketingCaptureRegistry(validPair(entry.copy(density = density)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(validPair(entry.copy(feature = " ")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(validPair(entry.copy(state = " Ready")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(validPair(entry.copy(purpose = "unknown")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(validPair(entry.copy(platform = "../desktop")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(validPair(entry.copy(pullRequest = 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(validPair(entry.copy(issue = -1)))
        }
    }

    @Test
    fun `registry requires a complete matching theme pair`() {
        val entry = validEntry()
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(listOf(entry))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(
                listOf(entry, lightEntry(entry).copy(theme = "dark")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateMarketingCaptureRegistry(
                listOf(entry, lightEntry(entry).copy(width = entry.width + 1)),
            )
        }
    }

    private fun validPair(entry: MarketingCaptureRegistryEntry): List<MarketingCaptureRegistryEntry> =
        listOf(entry, lightEntry(entry))

    private fun lightEntry(entry: MarketingCaptureRegistryEntry): MarketingCaptureRegistryEntry =
        entry.copy(
            id = "${entry.id}-light",
            fileName = "${entry.fileName.removeSuffix(".png")}-light.png",
            theme = "light",
        )

    private fun validEntry() = MarketingCaptureRegistryEntry(
        id = "valid-capture",
        baseScenario = "valid-capture",
        fileName = "valid-capture.png",
        theme = "dark",
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
