package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceChromeRenderTest {
    @Test
    fun refreshedSectionCountsDoNotCloseTheChooserOrDiscardItsSearch() {
        val revision = mutableStateOf(0)
        var openedSection: String? = null
        nativeSceneTest(390, 844, content = {
            val model = NextcloudCollectionNavigationModel.create(
                (1..8).map { NextcloudCollectionDestination("section-$it", "Section $it", count = revision.value) },
                "section-1",
            )
            NextcloudCollectionWorkspaceScaffold(
                model = model,
                mode = NextcloudCollectionNavigationMode.Sheet,
                workspaceLabel = "Synthetic workspace",
                contentTitle = "Section 1",
                contentSubtitle = null,
                onBack = {}, hasHierarchyBack = false,
                onDestinationSelected = { openedSection = it.id },
            ) { Box(Modifier.fillMaxSize()) }
        }) {
            click("Section 1. Open sections for Synthetic workspace")
            settle()
            settle()
            replaceText("", "Section 2")
            revision.value = 1
            settle()
            replaceText("Section 2", "Section 3")
            click("Open destination Section 3")
            assertEquals("section-3", openedSection)
        }
    }

    @Test
    fun filePaneButtonsEmitOnlyEnabledActions() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val hasSelection = mutableStateOf(false)
                var navigationClicks = 0
                var inspectorClicks = 0
                val scene = ImageComposeScene(400, 200, Density(1f), coroutineContext = coroutineContext) {
                    NextcloudNativeTheme(darkTheme = false) {
                        FilesPaneControls(
                            panes = FilesWorkspacePanes(true, true, false),
                            hasSelection = hasSelection.value,
                            onToggleNavigation = { navigationClicks++ },
                            onToggleInspector = { inspectorClicks++ },
                        )
                    }
                }
                try {
                    val driver = NativeSceneTestDriver(scene)
                    warmUp(scene)
                    driver.click("Hide library")
                    driver.click("Show file details")
                    assertEquals(1, navigationClicks)
                    assertEquals(0, inspectorClicks, "No selection must keep details unavailable")
                    hasSelection.value = true
                    warmUp(scene)
                    driver.click("Show file details")
                    assertEquals(1, inspectorClicks)
                } finally { scene.close() }
            }
        }
    }

    @Test
    fun activityFiltersExpandWithoutChangingTheQueryAndClearUsesTheHostCallback() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val query = mutableStateOf("report")
                var toolbarBounds = Rect.Zero
                val scene = ImageComposeScene(600, 400, Density(1f), coroutineContext = coroutineContext) {
                    NextcloudNativeTheme(darkTheme = false) {
                        Column(Modifier.onGloballyPositioned { toolbarBounds = it.boundsInRoot() }) {
                            ActivityFilterToolbar(
                                query = query.value, selectedSemantic = null, selectedApp = null, selectedType = null,
                                serverFilters = listOf(NextcloudActivityFilterOption("all", "All activities", 0)),
                                selectedServerFilterId = "all",
                                feed = ActivityFeedPresentation(emptyList(), emptyList(), emptyList(), emptyMap(), 0),
                                onQueryChanged = { query.value = it }, onSemanticSelected = {}, onAppSelected = {},
                                onTypeSelected = {}, onServerFilterSelected = {}, onClearFilters = { query.value = "" },
                            )
                        }
                    }
                }
                try {
                    val driver = NativeSceneTestDriver(scene)
                    warmUp(scene)
                    val compactHeight = toolbarBounds.height
                    driver.click("Filters")
                    warmUp(scene)
                    assertTrue(toolbarBounds.height > compactHeight, "Filters should expand only when requested")
                    assertEquals("report", query.value, "Expanding filters must retain the current search")
                    driver.click("Filters")
                    warmUp(scene)
                    assertEquals(compactHeight, toolbarBounds.height)
                    driver.click("Clear filters and search")
                    warmUp(scene)
                    assertEquals("", query.value)
                } finally { scene.close() }
            }
        }
    }

    @Test
    fun sectionSheetUsesOneHeaderAndDesktopCollapsePreservesTheRoute() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                for (mode in listOf(NextcloudCollectionNavigationMode.Sheet, NextcloudCollectionNavigationMode.Sidebar)) {
                    val width = if (mode == NextcloudCollectionNavigationMode.Sheet) 390 else 1280
                    var bounds = Rect.Zero
                    var navigationEvents = 0
                    var openedSection: String? = null
                    val model = NextcloudCollectionNavigationModel.create(
                        (1..6).map { NextcloudCollectionDestination("section-$it", "Section $it") },
                        "section-1",
                    )
                    val scene = ImageComposeScene(width, 844, Density(1f), coroutineContext = coroutineContext) {
                        NextcloudNativeTheme(darkTheme = false) {
                            NextcloudCollectionWorkspaceScaffold(
                                model = model,
                                mode = mode,
                                workspaceLabel = "Synthetic workspace",
                                contentTitle = "Section 1",
                                contentSubtitle = null,
                                onBack = { navigationEvents++ },
                                hasHierarchyBack = false,
                                onDestinationSelected = { navigationEvents++; openedSection = it.id },
                                destinationIcon = { NextcloudIcons.ListView },
                            ) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() })
                            }
                        }
                    }
                    try {
                        val driver = NativeSceneTestDriver(scene)
                        repeat(8) { scene.render(System.nanoTime()).close(); delay(16) }
                        assertTrue(bounds.top in 48f..80f, "Only one compact header should precede workspace content")
                        if (mode == NextcloudCollectionNavigationMode.Sheet) {
                            driver.click("Section 1. Open sections for Synthetic workspace")
                            repeat(30) { scene.render(System.nanoTime()).close(); delay(16) }
                            driver.click("Open destination Section 2")
                            warmUp(scene)
                            assertTrue(openedSection != null, "Opening the header chooser must expose selectable sections")
                            assertEquals(1, navigationEvents)
                        }
                        if (mode == NextcloudCollectionNavigationMode.Sidebar) {
                            val expandedLeft = bounds.left
                            driver.click("Collapse sections")
                            repeat(8) { scene.render(System.nanoTime()).close(); delay(16) }
                            assertTrue(expandedLeft - bounds.left > 100f, "Collapse must return horizontal space to content")
                            assertEquals(0, navigationEvents, "Collapsing sections must not navigate away from a draft")
                            assertEquals("section-1", model.selectedDestinationId)
                        }
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }

    private suspend fun warmUp(scene: ImageComposeScene) {
        repeat(8) { scene.render(System.nanoTime()).close(); delay(16) }
    }

}
