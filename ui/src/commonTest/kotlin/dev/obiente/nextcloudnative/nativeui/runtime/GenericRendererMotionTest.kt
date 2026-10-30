package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.app.design.NextcloudSkeletonLayout
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GenericRendererMotionTest {
    @Test
    fun selectingARecordInAListIsNotANewScreenButOpeningItsDetailIs() {
        val list = genericRendererEntranceKey("tasks", GenericNativeSurface.List, "first", inlineForm = false)
        val otherSelection = genericRendererEntranceKey("tasks", GenericNativeSurface.List, "second", inlineForm = false)
        assertEquals(list, otherSelection, "List selection must not replay the entrance")

        val firstDetail = genericRendererEntranceKey("tasks", GenericNativeSurface.Detail, "first", inlineForm = false)
        val secondDetail = genericRendererEntranceKey("tasks", GenericNativeSurface.Detail, "second", inlineForm = false)
        assertNotEquals(list, firstDetail)
        assertNotEquals(firstDetail, secondDetail, "Another record's detail is new content")
    }

    @Test
    fun anotherViewAndTheInlineEditorAreNewLocations() {
        val ready = genericRendererEntranceKey("tasks", GenericNativeSurface.List, null, inlineForm = false)
        assertNotEquals(ready, genericRendererEntranceKey("notes", GenericNativeSurface.List, null, inlineForm = false))
        assertNotEquals(ready, genericRendererEntranceKey("tasks", GenericNativeSurface.List, null, inlineForm = true))
        assertEquals(ready, genericRendererEntranceKey("tasks", GenericNativeSurface.List, null, inlineForm = false))
    }

    @Test
    fun loadingPlaceholdersFollowTheShapeOfTheIncomingSurface() {
        assertEquals(NextcloudSkeletonLayout.Grid, GenericNativeSurface.Grid.loadingSkeleton())
        assertEquals(NextcloudSkeletonLayout.Grid, GenericNativeSurface.MediaLibrary.loadingSkeleton())
        for (surface in listOf(GenericNativeSurface.List, GenericNativeSurface.Table, GenericNativeSurface.Mailbox, GenericNativeSurface.Board)) {
            assertEquals(NextcloudSkeletonLayout.List, surface.loadingSkeleton(), "$surface")
        }
        for (surface in listOf(GenericNativeSurface.Detail, GenericNativeSurface.Form, GenericNativeSurface.Insights)) {
            assertNull(surface.loadingSkeleton(), "$surface has no list shape, so it keeps the progress indicator")
        }
    }

    @Test
    fun aRowIsKeyedByTheResourceItsSelectionNames() {
        val bills = ResourceSpec("bills", "Bills", Confidence.high)
        val plain = NativeRecord("7", mapOf("name" to "Groceries"))
        assertEquals(genericRecordTitleSharedKey("bills", "7"), genericRecordTitleKey(bills, plain))
        val nested = NativeRecord("7", mapOf(NATIVE_SYNTHETIC_RESOURCE_FIELD to "projects"))
        assertEquals(
            genericRecordTitleSharedKey("projects", "7"),
            genericRecordTitleKey(bills, nested),
            "A record shown inside another resource keeps the identity selection gives it",
        )
        assertNotEquals(genericRecordTitleKey(bills, plain), genericRecordTitleKey(ResourceSpec("projects", "Projects", Confidence.high), plain))
    }

    @Test
    fun theHeaderLineThatNamesTheOpenedRecordTakesItsKey() {
        val key = genericRecordTitleSharedKey("projects", "7")
        assertEquals(key to null, genericHeaderSharedKeys("7", "projects", "Trip", "Trip", "Bills", detailShowsRecord = false))
        assertEquals(null to key, genericHeaderSharedKeys("7", "projects", "Trip", "Bills", "Trip", detailShowsRecord = false))
        assertEquals(
            null to null,
            genericHeaderSharedKeys("7", "projects", "Trip", "Trip", null, detailShowsRecord = true),
            "A detail names the record in its own headline, so the header must not compete for the key",
        )
        assertEquals(null to null, genericHeaderSharedKeys(null, null, null, "Bills", null, detailShowsRecord = false))
        assertEquals(null to null, genericHeaderSharedKeys("7", "projects", "Trip", "Bills", "All bills", detailShowsRecord = false))
    }
}
