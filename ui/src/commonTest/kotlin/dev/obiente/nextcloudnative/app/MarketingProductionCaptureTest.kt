package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.runtime.formatNativeField
import dev.obiente.nextcloudnative.nativeui.runtime.nativeDatasetInsights
import dev.obiente.nextcloudnative.nativeui.runtime.shouldUseCompactTableRecordList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketingProductionCaptureTest {
    @Test
    fun `homepage captures cover every story in matched dark and light themes`() {
        val homepageCaptures = marketingCaptureScenarios.filter { it.feature == "Homepage" }
        val expectedPairs = setOf(
            "homepage-overview-desktop",
            "homepage-overview-mobile",
            "homepage-files-desktop",
            "homepage-photos-desktop",
            "homepage-conversations-desktop",
            "homepage-planning-desktop",
            "homepage-apps-desktop",
        )

        assertEquals(expectedPairs.size * 2, homepageCaptures.size)
        assertEquals(expectedPairs, homepageCaptures.map { it.id.removeSuffix("-dark").removeSuffix("-light") }.toSet())
        expectedPairs.forEach { baseId ->
            val pair = homepageCaptures.filter { capture -> capture.id.startsWith("$baseId-") }
            assertEquals(2, pair.size)
            assertEquals(setOf(true, false), pair.map(MarketingCaptureScenario::darkTheme).toSet())
            assertTrue(pair.all { it.purpose == MarketingCapturePurpose.Showcase })
        }
    }

    @Test
    fun `homepage files fixture exercises the production grid with useful synthetic content`() {
        assertTrue(marketingHomepageFiles.count(NextcloudFile::isDirectory) >= 3)
        assertTrue(marketingHomepageFiles.count { it.mimeType?.startsWith("image/") == true } >= 3)
        assertTrue(marketingHomepageFiles.any { it.mimeType == "text/markdown" })
        assertTrue(marketingHomepageFiles.any { it.mimeType == "application/pdf" })
        assertTrue(marketingHomepageFiles.filterNot(NextcloudFile::isDirectory).all { it.fileId != null })
        assertTrue(marketingHomepageFiles.filter { it.hasPreview }.all { !it.etag.isNullOrBlank() })
        assertEquals(NextcloudFileListingSource.Cache, marketingHomepageCachedFileListing.source)
        assertEquals(NextcloudFileListingSource.Network, marketingHomepageFileListing.source)
        assertTrue(marketingHomepageFileOfflineAvailability.values.all {
            it == FileOfflineAvailability.Available
        })
    }

    @Test
    fun `home fixture provides useful deterministic dashboard content`() {
        assertEquals(4, marketingDashboardSnapshot.widgets.size)
        assertTrue(marketingDashboardSnapshot.widgets.all { widget ->
            marketingDashboardSnapshot.itemsByWidget[widget.id].orEmpty().isNotEmpty()
        })
    }

    @Test
    fun `dynamic visual QA captures cover desktop and compact viewports`() {
        val desktop = MarketingCaptureScenario.AdaptiveApp
        val mobile = MarketingCaptureScenario.AdaptiveAppMobile
        val mobileCollection = MarketingCaptureScenario.AdaptiveAppCollectionMobile

        assertEquals("desktop", desktop.platform)
        assertEquals("wide", desktop.viewport)
        assertEquals(1_440, desktop.width)
        assertEquals(900, desktop.height)
        assertEquals("mobile", mobile.platform)
        assertEquals("phone-portrait", mobile.viewport)
        assertEquals(1_080, mobile.width)
        assertEquals(1_800, mobile.height)
        assertTrue(mobile.density > 1f)
        assertEquals("mobile", mobileCollection.platform)
        assertEquals("phone-portrait", mobileCollection.viewport)
        assertFalse(shouldUseCompactTableRecordList(widthDp = 1_440f))
        assertFalse(shouldUseCompactTableRecordList(widthDp = 900f))
        assertTrue(shouldUseCompactTableRecordList(widthDp = 412f))
        assertFalse(shouldUseCompactDynamicAppChrome(widthDp = 412f, heightDp = 686f))
        assertTrue(shouldUseCompactDynamicAppChrome(widthDp = 900f, heightDp = 420f))
    }

    @Test
    fun `dynamic table fixture preserves numeric semantics and native currency formatting`() {
        val resource = marketingAdaptiveSchema.resources.single()
        val amount = resource.fields.single { it.id == "amount" }
        val insights = assertNotNull(nativeDatasetInsights(resource, marketingAdaptiveRecords))

        assertEquals(FieldKind.currency, amount.kind)
        assertEquals("EUR", amount.format)
        assertEquals(5, marketingAdaptiveRecords.size)
        assertTrue(marketingAdaptiveRecords.all { record ->
            record.values.getValue("amount")?.toDoubleOrNull() != null
        })
        assertEquals("EUR 219.00", formatNativeField(amount, "219.00").displayValue)
        assertEquals("amount", insights.measure.id)
        assertEquals(5, insights.recordCount)
        assertEquals(663.45, insights.total, absoluteTolerance = 0.001)
        assertTrue(insights.points.isNotEmpty())
    }
}
