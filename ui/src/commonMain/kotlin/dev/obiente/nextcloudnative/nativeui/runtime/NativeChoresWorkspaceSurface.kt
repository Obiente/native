package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

@Composable
internal fun NativeChoresWorkspaceSurface(
    presentation: NativeChoresPresentation,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    recordActions: (NativeRecord) -> List<NextcloudCardAction>,
    navigationItems: List<NativeWorkspaceNavigationItem>,
    onNavigate: ((String) -> Unit)?,
    createLabel: String?,
    onCreate: (() -> Unit)?,
    roster: NativeRosterPresentation? = null,
) {
    val destinationStateHolder = rememberSaveableStateHolder()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        if (wide && navigationItems.size > 1 && onNavigate != null) {
            Row(modifier = Modifier.fillMaxSize()) {
                ChoresSidebar(navigationItems, onNavigate)
                ChoresContent(
                    presentation,
                    onSelectRecord,
                    recordActions,
                    Modifier.weight(1f),
                    showHeader = true,
                    createLabel = createLabel,
                    onCreate = onCreate,
                    roster = roster,
                    destinationStateHolder = destinationStateHolder,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (navigationItems.size > 1 && onNavigate != null) {
                    ChoresCompactNavigation(navigationItems, onNavigate)
                }
                ChoresContent(
                    presentation,
                    onSelectRecord,
                    recordActions,
                    Modifier.weight(1f),
                    showHeader = true,
                    createLabel = createLabel,
                    onCreate = onCreate,
                    roster = roster,
                    destinationStateHolder = destinationStateHolder,
                )
            }
        }
    }
}

@Composable
private fun ChoresSidebar(
    destinations: List<NativeWorkspaceNavigationItem>,
    onNavigate: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxHeight().width(240.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        Text(
            "Chores",
            modifier = Modifier.padding(NextcloudSpacing.Small),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        destinations.forEach { destination ->
            val color = if (destination.selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(color, RoundedCornerShape(NextcloudRadii.Small))
                    .selectable(
                        selected = destination.selected,
                        role = Role.Tab,
                        onClick = { if (!destination.selected) onNavigate(destination.id) },
                    )
                    .padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    destination.label.choresNavigationIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    destination.label,
                    modifier = Modifier.padding(start = NextcloudSpacing.Small),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ChoresCompactNavigation(
    destinations: List<NativeWorkspaceNavigationItem>,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        destinations.forEach { destination ->
            Surface(
                color = if (destination.selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
                contentColor = if (destination.selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = RoundedCornerShape(NextcloudRadii.Pill),
                modifier = Modifier.selectable(
                    selected = destination.selected,
                    role = Role.Tab,
                    onClick = { if (!destination.selected) onNavigate(destination.id) },
                ),
            ) {
                Text(
                    destination.label,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ChoresContent(
    presentation: NativeChoresPresentation,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    recordActions: (NativeRecord) -> List<NextcloudCardAction>,
    modifier: Modifier,
    showHeader: Boolean,
    createLabel: String?,
    onCreate: (() -> Unit)?,
    roster: NativeRosterPresentation?,
    destinationStateHolder: SaveableStateHolder,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    start = NextcloudSpacing.Large,
                    top = NextcloudSpacing.Medium,
                    end = NextcloudSpacing.Large,
                    bottom = NextcloudSpacing.Small,
                ),
            ) {
                Text(presentation.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    presentation.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (val content = presentation.content) {
            NativeChoresContent.Loading -> ChoresCenteredState {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                Text("Loading ${presentation.title.lowercase()}", style = MaterialTheme.typography.titleMedium)
            }
            is NativeChoresContent.Error -> ChoresCenteredState {
                Icon(NextcloudIcons.Error, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                Text("Chores are unavailable", style = MaterialTheme.typography.titleLarge)
                Text(content.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content.retry?.let { retry -> TextButton(onClick = retry) { Text(content.retryLabel) } }
            }
            is NativeChoresContent.Empty -> ChoresCenteredState {
                Surface(
                    color = NextcloudTheme.colors.appIconContainer,
                    shape = CircleShape,
                ) {
                    Icon(
                        NextcloudIcons.app("chores"),
                        null,
                        Modifier.padding(NextcloudSpacing.Large).size(42.dp),
                        tint = NextcloudTheme.colors.appIcon,
                    )
                }
                Text(content.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(content.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (createLabel != null && onCreate != null) {
                    Button(onClick = onCreate) { Text(createLabel) }
                }
            }
            is NativeChoresContent.Ready -> if (
                presentation.kind == NativeChoresWorkspaceKind.Team && roster != null
            ) {
                NativeRosterSurface(
                    roster = roster,
                    createLabel = createLabel,
                    onCreate = onCreate,
                )
            } else {
                destinationStateHolder.SaveableStateProvider(presentation.kind.name) {
                    ChoresList(content.items, onSelectRecord, recordActions)
                }
            }
        }
    }
}

@Composable
private fun ChoresList(
    chores: List<NativeChoresItem>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    recordActions: (NativeRecord) -> List<NextcloudCardAction>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Small,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(chores, key = { it.record.id }) { chore ->
            ChoreRow(chore, onSelectRecord, recordActions(chore.record))
        }
    }
}

@Composable
private fun ChoreRow(
    chore: NativeChoresItem,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    actions: List<NextcloudCardAction>,
) {
    var actionsExpanded by rememberSaveable(chore.record.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onSelectRecord?.let { callback -> { callback(chore.record) } },
            onShowActions = if (actions.isNotEmpty()) ({ actionsExpanded = true }) else null,
            openLabel = "Open ${chore.title}",
            actionsLabel = "Show actions for ${chore.title}",
        ),
        shape = RoundedCornerShape(NextcloudRadii.Card),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = CircleShape,
            ) {
                Icon(
                    if (chore.kind == NativeChoresWorkspaceKind.History) {
                        NextcloudIcons.CheckCircle
                    } else {
                        NextcloudIcons.app("chores")
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(NextcloudSpacing.Small).size(24.dp),
                    tint = NextcloudTheme.colors.appIcon,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    chore.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                chore.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chore.metrics.isNotEmpty()) {
                    Text(
                        chore.metrics.joinToString("  •  ") { metric -> "${metric.label}: ${metric.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onSelectRecord != null) {
                Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${chore.title}")
            }
            NextcloudCardOverflow(
                itemLabel = chore.title,
                actions = actions,
                expanded = actionsExpanded,
                onExpandedChange = { expanded -> actionsExpanded = expanded },
            )
        }
    }
}

@Composable
private fun ChoresCenteredState(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            content()
        }
    }
}

private fun String.choresNavigationIcon() = when (lowercase()) {
    "all chores" -> NextcloudIcons.app("chores")
    "history" -> NextcloudIcons.CheckCircle
    "team" -> NextcloudIcons.People
    "invitations" -> NextcloudIcons.Activity
    else -> NextcloudIcons.app("chores")
}
