package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FilesToolbarInteractionTest {
    @Test
    fun searchCanBeClearedAtLargeTextSizeOnPhone() {
        var query by mutableStateOf("report")
        nativeSceneTest(390, 500, fontScale = 1.5f, content = {
            Surface(modifier = Modifier.fillMaxSize()) {
                FilesCommandBar(
                    path = "", query = query, onQueryChanged = { query = it },
                    searchScope = FileSearchScope.CurrentFolder, onSearchScopeChanged = {},
                    refreshing = false, searchLoading = false, layout = FileLayout.List,
                    onLayoutChanged = {}, onCreate = {}, onRefresh = {}, onOpenPath = {},
                    desktop = false,
                )
            }
        }) {
            click("Clear file search")
            assertEquals("", query)
            assertFalse(has("Clear file search"))
            capture("files-toolbar-large-text")
        }
    }
}
