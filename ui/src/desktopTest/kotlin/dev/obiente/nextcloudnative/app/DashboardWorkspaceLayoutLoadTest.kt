package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

@OptIn(ExperimentalComposeUiApi::class)
class DashboardWorkspaceLayoutLoadTest {
    @Test
    fun loadedLayoutIsInstalledBeforeWidgetReconciliationCanPersist() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val scope = HomeWorkspaceScope("a".repeat(64), HomeFormFactor.Desktop)
                var layout by mutableStateOf(defaultHomeWorkspaceLayout(scope))
                var storageAuthoritative by mutableStateOf(false)
                val persisted = mutableListOf<HomeWorkspaceLayout>()
                val availableSections = buildList {
                    add(HomeSectionIds.QuickActions)
                    addAll(
                        homeDashboardWidgetBindings(marketingDashboardSnapshot.widgets)
                            .map(HomeDashboardWidgetBinding::sectionId),
                    )
                }
                val loadedLayout = HomeWorkspaceLayout(
                    scope = scope,
                    sections = availableSections.reversed().mapIndexed { index, sectionId ->
                        HomeWorkspaceSection(
                            id = sectionId,
                            visible = index != 1,
                            size = HomeSectionSize.Compact,
                        )
                    },
                )
                val scene = ImageComposeScene(
                    width = 1280,
                    height = 800,
                    density = Density(1f),
                    coroutineContext = coroutineContext,
                ) {
                    MaterialTheme {
                        NativeDashboardPresentation(
                            state = DashboardSurfaceState.Available(marketingDashboardSnapshot, status = null),
                            installedApps = emptyList(),
                            workspaceLayout = layout,
                            workspaceLayoutAuthoritative = storageAuthoritative,
                            onWorkspaceLayoutChanged = { updated ->
                                persisted += updated
                                true
                            },
                            onOpenApp = {},
                            onOpenStatus = null,
                            onOpenLink = {},
                            onBack = null,
                            onRefresh = {},
                        )
                    }
                }
                var frameTime = 0L
                suspend fun settle() {
                    repeat(8) {
                        frameTime += 100_000_000L
                        scene.render(frameTime).close()
                        yield()
                    }
                }

                try {
                    settle()
                    layout = loadedLayout
                    storageAuthoritative = true
                    settle()

                    assertTrue(persisted.isEmpty(), "Loading a current layout must not persist stale defaults: $persisted")
                } finally {
                    scene.close()
                }
            }
        }
    }
}
