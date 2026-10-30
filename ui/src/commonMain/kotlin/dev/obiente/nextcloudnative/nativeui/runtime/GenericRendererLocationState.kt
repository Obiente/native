package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/**
 * Gives each dynamic app location its own saved presentation state, such as list and grid scroll
 * positions. A location is a view together with the record it was opened for, matching the
 * entries the host pushes onto its navigation history. Opening a record or child collection
 * therefore starts at the top, and Back returns to the parent's position, including after
 * process restoration of the history.
 *
 * Only presentation state lives here: records, drafts, and pending work stay with their owners.
 */
@Composable
internal fun GenericRendererLocationScope(locationKey: String, content: @Composable () -> Unit) {
    val holder = rememberSaveableStateHolder()
    val retained = remember { GenericRendererRetainedLocations() }
    SideEffect {
        retained.visit(locationKey).forEach(holder::removeState)
    }
    holder.SaveableStateProvider(locationKey) { content() }
}

/**
 * Saveable key for a location. It is a plain string so the platform can save it, and the record
 * part is a digest so record ids never enter saved instance state. A digest collision can only
 * share a scroll position between two locations, never content.
 */
internal fun genericRendererLocationKey(viewId: String, recordResourceId: String?, recordId: String?): String {
    if (recordId == null) return "view:$viewId"
    val record = "${recordResourceId.orEmpty()}\u0000$recordId".hashCode().toUInt().toString(16)
    return "view:$viewId|record:$record"
}

/**
 * Recently visited locations, newest last. Older locations beyond what navigation history can
 * return to are evicted so their saved state does not accumulate.
 */
internal class GenericRendererRetainedLocations(private val capacity: Int = MaxRetainedLocations) {
    private val order = ArrayDeque<String>()

    /** Records a visit and returns the locations whose saved state should be dropped. */
    fun visit(locationKey: String): List<String> {
        if (order.lastOrNull() == locationKey) return emptyList()
        order.remove(locationKey)
        order.addLast(locationKey)
        val evicted = mutableListOf<String>()
        while (order.size > capacity) evicted += order.removeFirst()
        return evicted
    }
}

/** The host keeps up to 16 history entries; one more covers the current location. */
private const val MaxRetainedLocations = 17
