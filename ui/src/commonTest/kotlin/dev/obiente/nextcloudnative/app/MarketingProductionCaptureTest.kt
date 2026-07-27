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
    fun `home fixture provides useful deterministic dashboard content`() {
        assertEquals(4, marketingDashboardSnapshot.widgets.size)
        assertTrue(marketingDashboardSnapshot.widgets.all { widget ->
            marketingDashboardSnapshot.itemsByWidget[widget.id].orEmpty().isNotEmpty()
        })
    }

    @Test
    fun `dynamic table captures cover desktop and compact viewports`() {
        val desktop = MarketingCaptureScenario.AdaptiveApp
        val mobile = MarketingCaptureScenario.AdaptiveAppMobile

        assertEquals("desktop", desktop.platform)
        assertEquals("wide", desktop.viewport)
        assertEquals(1_440, desktop.width)
        assertEquals(900, desktop.height)
        assertEquals("mobile", mobile.platform)
        assertEquals("phone-portrait", mobile.viewport)
        assertEquals(1_080, mobile.width)
        assertEquals(1_800, mobile.height)
        assertTrue(mobile.density > 1f)
        assertFalse(shouldUseCompactTableRecordList(widthDp = 1_440f))
        assertFalse(shouldUseCompactTableRecordList(widthDp = 900f))
        assertTrue(shouldUseCompactTableRecordList(widthDp = 412f))
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
