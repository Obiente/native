package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun filterCollectionSections(
    destinations: List<NextcloudCollectionDestination>,
    query: String,
): List<NextcloudCollectionDestination> {
    val term = query.trim()
    return destinations.filter { destination ->
        term.isEmpty() || destination.label.contains(term, ignoreCase = true) ||
            destination.supportingText?.contains(term, ignoreCase = true) == true
    }
}

/** Compact presentation of the same destinations used by tablet rails and desktop sidebars. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NextcloudCollectionSheetScaffold(
    model: NextcloudCollectionNavigationModel,
    workspaceLabel: String,
    contentTitle: String,
    contentSubtitle: String?,
    onBack: () -> Unit,
    hasHierarchyBack: Boolean,
    onDestinationSelected: (NextcloudCollectionDestination) -> Unit,
    destinationIcon: (NextcloudCollectionDestination) -> ImageVector?,
    compactHeader: Boolean,
    headerActions: @Composable () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var sectionsOpen by remember(workspaceLabel) { mutableStateOf(false) }
    var query by remember(sectionsOpen) { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        NextcloudCollectionHeader(
            title = contentTitle,
            subtitle = contentSubtitle,
            onBack = onBack,
            leadingControl = resolveNextcloudCollectionLeadingControl(NextcloudCollectionNavigationMode.Sheet, hasHierarchyBack),
            onOpenNavigation = null,
            showHierarchyBack = false,
            compact = compactHeader,
            actions = headerActions,
            titleContent = {
                TextButton(
                    onClick = { sectionsOpen = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("collection-open-sections")
                        .semantics { contentDescription = "$contentTitle. Open sections for $workspaceLabel" },
                ) {
                    Text(
                        contentTitle,
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(NextcloudIcons.ExpandMore, null, Modifier.size(20.dp))
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
    if (sectionsOpen) {
        ModalBottomSheet(
            onDismissRequest = { sectionsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                workspaceLabel,
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            if (model.destinations.size > 7) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find a section") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val destinations = filterCollectionSections(model.destinations, query)
            if (destinations.isEmpty()) Text("No matching sections", Modifier.padding(16.dp))
            NextcloudCollectionDestinationList(
                model = NextcloudCollectionNavigationModel.create(
                    destinations,
                    model.selectedDestinationId?.takeIf { id -> destinations.any { it.id == id } },
                ),
                onDestinationSelected = {
                    sectionsOpen = false
                    onDestinationSelected(it)
                },
                destinationIcon = destinationIcon,
                labelMaxLines = 2,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 12.dp),
            )
        }
    }
}
