package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Shared element transitions without composing either screen twice.
 *
 * Elements that show the same thing on two screens, such as a conversation's name in the room
 * list and in the chat header, use the same key with [nextcloudSharedElement]. When navigation
 * reveals an element whose key was just shown somewhere else, the arriving element flies from
 * where the other one was to its own place, drawn by [NextcloudSharedElementOverlay] above both
 * screens, and then settles into its screen. The other element may have left with the old screen
 * or may still be visible, as in a list beside its detail pane.
 *
 * Only arrivals shortly after a navigation fly, so a list item scrolling back into view never
 * does. An element that left because of a navigation is remembered a little longer, so a detail
 * whose title appears once its content has loaded still flies from the row that opened it.
 * Reduced motion turns flights off. Keys are in memory only and never saved.
 */
@Stable
class NextcloudSharedElements {
    private val present = mutableMapOf<String, MutableList<SharedElement>>()
    private val departed = mutableMapOf<String, Departure>()
    private val carried = mutableMapOf<String, Departure>()
    private var lastNavigation: TimeMark? = null
    internal var host: LayoutCoordinates? = null
    internal val flights = mutableStateListOf<SharedElementFlight>()

    /**
     * Called by entrances when the visible content changes because of navigation, including a
     * location that finishes loading. Elements removed by the same change are carried to it.
     */
    fun markNavigation() {
        lastNavigation = TimeSource.Monotonic.markNow()
        departed.forEach { (key, departure) ->
            if (departure.at.elapsedNow() < SameChangeWindow) carried[key] = Departure(departure.rect, TimeSource.Monotonic.markNow())
        }
        departed.clear()
        carried.entries.removeAll { (_, departure) -> departure.at.elapsedNow() >= CarryWindow }
    }

    internal fun place(element: SharedElement, coordinates: LayoutCoordinates) {
        val rect = boundsInHost(coordinates) ?: return
        element.coordinates = coordinates
        element.lastRect = rect
        if (!element.placed) {
            element.placed = true
            val source = sourceRectFor(element)
            present.getOrPut(element.key) { mutableListOf() } += element
            if (source != null && source != rect) {
                val flight = SharedElementFlight(element, source)
                element.flight = flight
                flights += flight
            }
        }
    }

    internal fun leave(element: SharedElement) {
        present[element.key]?.let { elements ->
            elements.remove(element)
            if (elements.isEmpty()) present.remove(element.key)
        }
        element.lastRect?.let { departed[element.key] = Departure(it, TimeSource.Monotonic.markNow()) }
        element.flight?.let(flights::remove)
        element.flight = null
        departed.entries.removeAll { (_, departure) -> departure.at.elapsedNow() >= SameChangeWindow }
    }

    internal suspend fun fly(flight: SharedElementFlight) {
        try {
            flight.progress.animateTo(1f, NextcloudMotion.content(false, NextcloudMotion.LongMillis))
        } finally {
            flights.remove(flight)
            if (flight.element.flight === flight) flight.element.flight = null
        }
    }

    internal fun currentRect(element: SharedElement): Rect? =
        element.coordinates?.takeIf { it.isAttached }?.let(::boundsInHost) ?: element.lastRect

    private fun sourceRectFor(element: SharedElement): Rect? {
        val navigated = lastNavigation?.let { it.elapsedNow() < FlightWindow } == true
        if (!navigated) return null
        present[element.key]?.lastOrNull { it !== element }?.let { other -> return currentRect(other) }
        val departure = carried.remove(element.key) ?: return null
        return departure.rect.takeIf { departure.at.elapsedNow() < CarryWindow }
    }

    private fun boundsInHost(coordinates: LayoutCoordinates): Rect? {
        val hostCoordinates = host?.takeIf { it.isAttached } ?: return null
        if (!coordinates.isAttached) return null
        // Elements in another window, such as a dialog or bottom sheet, cannot fly over this one.
        if (coordinates.findRootCoordinates() != hostCoordinates.findRootCoordinates()) return null
        return hostCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
    }

    private data class Departure(val rect: Rect, val at: TimeMark)
}

internal class SharedElement(val key: String, val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
    var lastRect: Rect? = null
    var placed = false
    var flight by mutableStateOf<SharedElementFlight?>(null)
}

internal class SharedElementFlight(val element: SharedElement, val from: Rect) {
    val progress = Animatable(0f)
}

/** The shared element registry for the screens below it; null where none is provided. */
val LocalNextcloudSharedElements: ProvidableCompositionLocal<NextcloudSharedElements?> =
    staticCompositionLocalOf { null }

/**
 * Marks content that also appears on another screen under the same [key], so navigating between
 * them carries it across. A null key, a missing registry, or reduced motion leaves it unchanged.
 */
@Composable
fun Modifier.nextcloudSharedElement(key: String?): Modifier {
    val registry = LocalNextcloudSharedElements.current
    if (key == null || registry == null || LocalNextcloudReducedMotion.current) return this
    val layer = rememberGraphicsLayer()
    val element = remember(registry, key, layer) { SharedElement(key, layer) }
    DisposableEffect(element) { onDispose { registry.leave(element) } }
    val flight = element.flight
    LaunchedEffect(flight) { flight?.let { registry.fly(it) } }
    return this
        .onGloballyPositioned { coordinates -> registry.place(element, coordinates) }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            // While flying, the overlay draws this element; its place stays empty until it lands.
            if (element.flight == null) drawLayer(layer)
        }
}

/**
 * Draws elements in flight above the screens. [NextcloudSharedElementHost] places it last in the
 * container of the screens whose elements can fly, so it covers them; it never takes input.
 */
@Composable
internal fun NextcloudSharedElementOverlay(registry: NextcloudSharedElements, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxSize()
            .drawWithContent {
                for (flight in registry.flights) {
                    val target = registry.currentRect(flight.element) ?: continue
                    if (target.height <= 0f || target.width <= 0f) continue
                    val rect = lerp(flight.from, target, flight.progress.value)
                    // Uniform scale keeps text and images undistorted while they travel.
                    val scale = rect.height / target.height
                    translate(rect.left, rect.top) {
                        scale(scale, scale, pivot = Offset.Zero) {
                            drawLayer(flight.element.layer)
                        }
                    }
                }
            },
    )
}

/**
 * Provides a registry to [content] and draws its flights above it. The container registers itself
 * as the flight space when it is placed, before its children report where they are.
 */
@Composable
fun NextcloudSharedElementHost(
    modifier: Modifier = Modifier,
    registry: NextcloudSharedElements = remember { NextcloudSharedElements() },
    content: @Composable () -> Unit,
) {
    Box(modifier.onPlaced { registry.host = it }) {
        CompositionLocalProvider(LocalNextcloudSharedElements provides registry) {
            content()
        }
        NextcloudSharedElementOverlay(registry)
    }
}

private val FlightWindow = 700.milliseconds

/** Removals and the navigation that caused them happen in the same frame. */
private val SameChangeWindow = 120.milliseconds

/** How long a navigated-away element waits for its counterpart, such as a loading detail. */
private val CarryWindow = 2_000.milliseconds
