package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudMotion
import dev.obiente.nextcloudnative.app.design.NextcloudSkeletonLayout
import dev.obiente.nextcloudnative.app.design.nextcloudFadeInOnLoad
import dev.obiente.nextcloudnative.app.design.nextcloudItemMotion
import dev.obiente.nextcloudnative.app.design.nextcloudSharedElement
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/**
 * Moves between dynamic app locations the way navigation reads: opening another view, a record's
 * detail, or the inline editor slides in from the end, and going back slides the previous one in
 * from the start. Content that finishes loading fades in where its placeholders were, without
 * travelling. Records refreshing in place, search, and paging change neither key.
 * Inside it, each location keeps its own saved presentation state; see
 * [GenericRendererLocationScope].
 */
@Composable
internal fun GenericRendererEntrance(
    viewId: String,
    surface: GenericNativeSurface,
    selectedRecordId: String?,
    inlineForm: Boolean,
    loading: Boolean,
    selectedRecordResourceId: String?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    NextcloudContentEntrance(
        key = genericRendererEntranceKey(viewId, surface, selectedRecordId, inlineForm),
        axis = NextcloudEntranceAxis.Horizontal,
        modifier = modifier,
        directional = true,
        distance = NextcloudMotion.RouteEntranceOffset,
    ) {
        // The entrance outlives each location's state, so it can animate the switch between them.
        GenericRendererLocationScope(genericRendererLocationKey(viewId, selectedRecordResourceId, selectedRecordId)) {
            val opened = selectedRecordId?.let { id -> selectedRecordResourceId?.let { GenericOpenedRecord(it, id) } }
            CompositionLocalProvider(LocalGenericOpenedRecord provides opened) {
                NextcloudContentEntrance(loading, NextcloudEntranceAxis.Fade, Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

internal data class GenericRendererEntranceKey(
    val viewId: String,
    val surface: GenericNativeSurface,
    val detailRecordId: String?,
    val inlineForm: Boolean,
)

/** Only a detail surface is identified by its record; list selection is not a new screen. */
internal fun genericRendererEntranceKey(
    viewId: String,
    surface: GenericNativeSurface,
    selectedRecordId: String?,
    inlineForm: Boolean,
): GenericRendererEntranceKey = GenericRendererEntranceKey(
    viewId = viewId,
    surface = surface,
    detailRecordId = selectedRecordId.takeIf { surface == GenericNativeSurface.Detail },
    inlineForm = inlineForm,
)

/** Loading placeholders take the shape of the surface that is coming. */
internal fun GenericNativeSurface.loadingSkeleton(): NextcloudSkeletonLayout? = when (this) {
    GenericNativeSurface.Grid, GenericNativeSurface.MediaLibrary -> NextcloudSkeletonLayout.Grid
    GenericNativeSurface.List, GenericNativeSurface.Table, GenericNativeSurface.Mailbox,
    GenericNativeSurface.Board -> NextcloudSkeletonLayout.List
    GenericNativeSurface.Insights, GenericNativeSurface.Detail, GenericNativeSurface.Form -> null
}

/**
 * Item motion for dynamic record lists. Removed records disappear at once instead of fading, so a
 * leaving row never reports stale drag geometry, and items stop gliding while a drag is active.
 */
@Composable
internal fun LazyItemScope.genericRecordItemMotion(dragActive: Boolean = false): Modifier =
    nextcloudItemMotion(fadeOut = false, placement = !dragActive)

@Composable
internal fun LazyGridItemScope.genericRecordItemMotion(): Modifier = nextcloudItemMotion(fadeOut = false)

/** Links a record's title in a collection with the same record's detail, so it carries across. */
internal fun genericRecordTitleSharedKey(resourceId: String?, recordId: String): String =
    "record:${resourceId.orEmpty()}:$recordId:title"

/** The key for a record's title where a collection lists it, matching how selection names it. */
internal fun genericRecordTitleKey(resource: ResourceSpec, record: NativeRecord): String =
    genericRecordTitleSharedKey(record.effectiveNativeResourceId(resource.id), record.id)

/** The record the person opened to reach this location, as the selection identified it. */
internal data class GenericOpenedRecord(val resourceId: String, val recordId: String)

internal val LocalGenericOpenedRecord = staticCompositionLocalOf<GenericOpenedRecord?> { null }

/**
 * A detail names the record it shows with the key of the row that opened it, because a detail
 * read often belongs to a different resource than the list. Other records keep their own key.
 */
@Composable
internal fun genericDetailTitleKey(resource: ResourceSpec, record: NativeRecord): String {
    val opened = LocalGenericOpenedRecord.current
    return if (opened?.recordId == record.id) {
        genericRecordTitleSharedKey(opened.resourceId, record.id)
    } else {
        genericRecordTitleKey(resource, record)
    }
}

/** Marks a record's title in a collection, so opening the record carries it to the next screen. */
@Composable
internal fun Modifier.genericRecordTitle(resource: ResourceSpec, record: NativeRecord): Modifier =
    nextcloudSharedElement(genericRecordTitleKey(resource, record))

/** The title key of the collection card being drawn, for custom card content to mark its title. */
internal val LocalGenericCardTitleKey = staticCompositionLocalOf<String?> { null }

/** Marks the title inside custom collection card content; see [LocalGenericCardTitleKey]. */
@Composable
internal fun Modifier.genericCardTitle(): Modifier = nextcloudSharedElement(LocalGenericCardTitleKey.current)

/** Marks a detail's headline for the record it shows; see [genericDetailTitleKey]. */
@Composable
internal fun Modifier.genericDetailTitle(resource: ResourceSpec, record: NativeRecord): Modifier =
    nextcloudSharedElement(genericDetailTitleKey(resource, record))

@Composable
internal fun Modifier.genericSharedElement(key: String?): Modifier = nextcloudSharedElement(key)

@Composable
internal fun Modifier.genericFadeInOnLoad(): Modifier = nextcloudFadeInOnLoad()

/** Shorthand for [dev.obiente.nextcloudnative.app.design.nextcloudAnimatedColor] in the renderer. */
@Composable
internal fun animatedColor(target: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    dev.obiente.nextcloudnative.app.design.nextcloudAnimatedColor(target)

/**
 * Which line of a dynamic app's header names the record that was opened, keyed like that
 * record's row, so opening a record into its children carries its name up into the header.
 * A detail names the record in its own headline, so its header text takes no key.
 */
internal fun genericHeaderSharedKeys(
    selectedRecordId: String?,
    selectedRecordResourceId: String?,
    contextLabel: String?,
    title: String,
    subtitle: String?,
    detailShowsRecord: Boolean,
): Pair<String?, String?> {
    if (selectedRecordId == null || selectedRecordResourceId == null || contextLabel.isNullOrBlank() || detailShowsRecord) {
        return null to null
    }
    val key = genericRecordTitleSharedKey(selectedRecordResourceId, selectedRecordId)
    return key.takeIf { title == contextLabel } to key.takeIf { subtitle == contextLabel && title != contextLabel }
}
