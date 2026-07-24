package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.AdaptiveNativeScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState

@Composable
fun NativeRuntimePreviewApp() {
    MaterialTheme {
        val schema = previewSchema()
        var selected by remember { mutableIntStateOf(0) }
        val view = schema.views[selected]

        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            PrimaryTabRow(selectedTabIndex = selected) {
                schema.views.forEachIndexed { index, candidate ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = { Text(candidate.title) },
                    )
                }
            }
            AdaptiveNativeScreen(
                schema = schema,
                view = view,
                state = NativeScreenState.Ready(previewRecords(view.resourceId)),
                onAction = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun previewSchema(): NativeAppSchema = NativeAppSchema(
    schemaVersion = "0.1",
    app = AppIdentity("adaptive-preview", "Adaptive Preview", "0.1"),
    confidence = Confidence.high,
    resources = listOf(
        ResourceSpec("media", "Media", Confidence.high),
        ResourceSpec("expenses", "Expenses", Confidence.high),
        ResourceSpec("messages", "Messages", Confidence.high),
    ),
    views = listOf(
        ViewSpec("media.grid", "Memories", "media", NativeComponent.mediaGrid, "", Confidence.high),
        ViewSpec("expenses.list", "Cospend", "expenses", NativeComponent.collectionList, "", Confidence.high),
        ViewSpec("messages.chat", "Talk", "messages", NativeComponent.chatThread, "", Confidence.high),
    ),
)

private fun previewRecords(resourceId: String): List<NativeRecord> = when (resourceId) {
    "media" -> listOf(
        NativeRecord("1", mapOf("name" to "Dunes at sunrise", "takenAt" to "21 July")),
        NativeRecord("2", mapOf("name" to "Canal ride", "takenAt" to "20 July")),
        NativeRecord("3", mapOf("name" to "Forest walk", "takenAt" to "18 July")),
    )
    "expenses" -> listOf(
        NativeRecord("1", mapOf("description" to "Groceries", "amount" to "EUR 42.30")),
        NativeRecord("2", mapOf("description" to "Train tickets", "amount" to "EUR 18.00")),
    )
    else -> listOf(
        NativeRecord("1", mapOf("actorDisplayName" to "Alex", "message" to "The adaptive schema is ready.")),
        NativeRecord("2", mapOf("actorDisplayName" to "Sam", "message" to "Let us build the renderer.")),
    )
}
