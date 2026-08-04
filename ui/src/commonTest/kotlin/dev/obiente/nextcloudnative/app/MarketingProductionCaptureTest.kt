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
        val homepageCaptures = marketingCaptureVariants.filter { it.scenario.feature == "Homepage" }
        val expectedPairs = setOf(
            "homepage-overview-desktop",
            "homepage-overview-mobile",
            "homepage-files-desktop",
            "homepage-files-mobile",
            "homepage-photos-desktop",
            "homepage-conversations-desktop",
            "homepage-planning-desktop",
            "homepage-apps-desktop",
        )

        assertEquals(expectedPairs.size * 2, homepageCaptures.size)
        assertEquals(expectedPairs, homepageCaptures.map(MarketingCaptureVariant::baseScenario).toSet())
        expectedPairs.forEach { baseId ->
            val pair = homepageCaptures.filter { capture -> capture.baseScenario == baseId }
            assertEquals(2, pair.size)
            assertEquals(setOf(true, false), pair.map { it.theme.darkTheme }.toSet())
            assertTrue(pair.all { it.scenario.purpose == MarketingCapturePurpose.Showcase })
        }
    }

    @Test
    fun `every marketing capture is generated in both themes`() {
        val pairs = marketingCaptureVariants.groupBy(MarketingCaptureVariant::baseScenario)

        assertTrue(pairs.isNotEmpty())
        pairs.forEach { (_, pair) ->
            assertEquals(2, pair.size)
            assertEquals(setOf(MarketingCaptureTheme.Dark, MarketingCaptureTheme.Light), pair.map { it.theme }.toSet())
            assertEquals(1, pair.map { it.width to it.height }.toSet().size)
        }
    }

    @Test
    fun `homepage files fixture exercises the production workspace with useful synthetic content`() {
        assertTrue(marketingHomepageFiles.size >= 12)
        assertTrue(marketingHomepageFiles.count(NextcloudFile::isDirectory) >= 5)
        assertTrue(marketingHomepageFiles.count { it.mimeType?.startsWith("image/") == true } >= 3)
        assertTrue(marketingHomepageFiles.any { it.mimeType == "text/markdown" })
        assertTrue(marketingHomepageFiles.any { it.mimeType == "application/pdf" })
        assertTrue(marketingHomepageFiles.any { it.mimeType?.startsWith("video/") == true })
        assertTrue(marketingHomepageFiles.any { it.mimeType?.startsWith("audio/") == true })
        assertTrue(marketingHomepageFiles.filterNot(NextcloudFile::isDirectory).all { it.fileId != null })
        assertTrue(marketingHomepageFiles.filter { it.hasPreview }.all { !it.etag.isNullOrBlank() })
        assertTrue(marketingHomepageFiles.count(NextcloudFile::favorite) >= 4)
        assertTrue(marketingHomepageFiles.any { it.unreadComments > 0 })
        assertTrue(marketingHomepageFiles.all { !it.ownerDisplayName.isNullOrBlank() })
        assertEquals(NextcloudFileListingSource.Cache, marketingHomepageCachedFileListing.source)
        assertEquals(NextcloudFileListingSource.Network, marketingHomepageFileListing.source)
        assertTrue(marketingHomepageFileOfflineAvailability.values.all {
            it == FileOfflineAvailability.Available
        })
    }

    @Test
    fun `home fixture provides useful deterministic dashboard content`() {
        assertEquals(8, marketingDashboardSnapshot.widgets.size)
        assertTrue(marketingDashboardSnapshot.widgets.all { widget ->
            marketingDashboardSnapshot.itemsByWidget[widget.id].orEmpty().size >= 2
        })
        assertTrue(marketingDashboardSnapshot.itemsByWidget.values.sumOf { it.size } >= 25)
        assertEquals(6, marketingHomepageTalkPage.messages.size)
    }

    @Test
    fun `apps command center capture covers both desktop themes`() {
        val captures = marketingCaptureVariants.filter { variant ->
            variant.baseScenario == "apps-workspace-desktop"
        }

        assertEquals(2, captures.size)
        assertEquals(setOf(MarketingCaptureTheme.Dark, MarketingCaptureTheme.Light), captures.map { it.theme }.toSet())
        assertTrue(captures.all { it.scenario.purpose == MarketingCapturePurpose.Showcase })
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
