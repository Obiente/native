package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class NextcloudCollectionNavigationMode {
    Hidden,
    Drawer,
    Rail,
    Sidebar,
}

enum class NextcloudCollectionNavigationHost {
    AdaptiveAndroid,
    Desktop,
}

enum class NextcloudCollectionLeadingControl {
    Back,
    Menu,
}

enum class NextcloudCollectionNavigationMove {
    Previous,
    Next,
    First,
    Last,
}

@Immutable
data class NextcloudCollectionDestination(
    val id: String,
    val label: String,
    val count: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "Collection destination IDs must not be blank." }
        require(label.isNotBlank()) { "Collection destination labels must not be blank." }
        require(count == null || count >= 0) { "Collection destination counts must not be negative." }
    }
}

/**
 * Immutable collection navigation state. Selection callbacks remain in the host instead of the
 * destination data so restored route state cannot retain stale lambdas.
 */
@Immutable
class NextcloudCollectionNavigationModel private constructor(
    destinations: List<NextcloudCollectionDestination>,
    val selectedDestinationId: String?,
) {
    val destinations: List<NextcloudCollectionDestination> = destinations.toList()

    val selectedDestination: NextcloudCollectionDestination?
        get() = selectedDestinationId?.let { selectedId ->
            this.destinations.first { destination -> destination.id == selectedId }
        }

    init {
        require(this.destinations.map(NextcloudCollectionDestination::id).distinct().size == this.destinations.size) {
            "Collection destination IDs must be unique."
        }
        if (selectedDestinationId != null) {
            require(this.destinations.any { destination -> destination.id == selectedDestinationId }) {
                "The selected collection destination must exist in the model."
            }
        }
    }

    companion object {
        fun create(
            destinations: List<NextcloudCollectionDestination>,
            selectedDestinationId: String?,
        ): NextcloudCollectionNavigationModel = NextcloudCollectionNavigationModel(
            destinations = destinations,
            selectedDestinationId = selectedDestinationId,
        )
    }
}

/**
 * Resolves keyboard focus movement without changing the active destination.
 *
 * Navigation can legitimately have no selected primary destination while a contextual or
 * secondary view is active. In that state, forward movement begins at the first destination and
 * backward movement begins at the last destination without falsely selecting either one.
 */
fun resolveNextcloudCollectionKeyboardDestination(
    model: NextcloudCollectionNavigationModel,
    focusedDestinationId: String?,
    move: NextcloudCollectionNavigationMove,
): NextcloudCollectionDestination? {
    if (model.destinations.isEmpty()) return null

    val currentIndex = focusedDestinationId
        ?.let { destinationId ->
            model.destinations.indexOfFirst { destination -> destination.id == destinationId }
        }
        ?.takeIf { index -> index >= 0 }
        ?: model.selectedDestination
            ?.let { selected -> model.destinations.indexOf(selected) }
            ?.takeIf { index -> index >= 0 }

    val targetIndex = when (move) {
        NextcloudCollectionNavigationMove.First -> 0
        NextcloudCollectionNavigationMove.Last -> model.destinations.lastIndex
        NextcloudCollectionNavigationMove.Previous ->
            currentIndex?.let { index -> (index - 1).floorMod(model.destinations.size) }
                ?: model.destinations.lastIndex

        NextcloudCollectionNavigationMove.Next ->
            currentIndex?.let { index -> (index + 1).floorMod(model.destinations.size) }
                ?: 0
    }
    return model.destinations[targetIndex]
}

fun resolveNextcloudCollectionNavigationMode(
    host: NextcloudCollectionNavigationHost,
    availableWidthDp: Int,
    destinationCount: Int,
): NextcloudCollectionNavigationMode {
    require(availableWidthDp >= 0) { "Available width must not be negative." }
    require(destinationCount >= 0) { "Destination count must not be negative." }
    if (destinationCount <= 1) return NextcloudCollectionNavigationMode.Hidden

    return when (host) {
        NextcloudCollectionNavigationHost.AdaptiveAndroid ->
            if (availableWidthDp < NextcloudWorkspaceBreakpoints.AdaptiveRailDp) {
                NextcloudCollectionNavigationMode.Drawer
            } else {
                NextcloudCollectionNavigationMode.Rail
            }

        NextcloudCollectionNavigationHost.Desktop ->
            if (availableWidthDp < NextcloudWorkspaceBreakpoints.DesktopSidebarDp) {
                NextcloudCollectionNavigationMode.Rail
            } else {
                NextcloudCollectionNavigationMode.Sidebar
            }
    }
}

fun resolveNextcloudCollectionLeadingControl(
    mode: NextcloudCollectionNavigationMode,
    hasHierarchyBack: Boolean,
): NextcloudCollectionLeadingControl = when {
    mode == NextcloudCollectionNavigationMode.Drawer && !hasHierarchyBack ->
        NextcloudCollectionLeadingControl.Menu

    else -> NextcloudCollectionLeadingControl.Back
}

fun shouldShowNextcloudCollectionTrailingNavigation(
    mode: NextcloudCollectionNavigationMode,
    hasHierarchyBack: Boolean,
): Boolean = mode == NextcloudCollectionNavigationMode.Drawer && hasHierarchyBack

internal fun resolveNextcloudCollectionDestinationLabelMaxLines(
    mode: NextcloudCollectionNavigationMode,
): Int = when (mode) {
    NextcloudCollectionNavigationMode.Drawer,
    NextcloudCollectionNavigationMode.Sidebar -> 2

    NextcloudCollectionNavigationMode.Hidden,
    NextcloudCollectionNavigationMode.Rail -> 1
}

/**
 * Owns collection navigation and the single contextual header for a native app workspace.
 *
 * Compact Android uses a drawer. Large Android and narrow desktop windows use a rail. Wide
 * desktop windows use a persistent 252 dp sidebar.
 */
@Composable
fun NextcloudCollectionWorkspaceScaffold(
    model: NextcloudCollectionNavigationModel,
    mode: NextcloudCollectionNavigationMode,
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    hasHierarchyBack: Boolean,
    onDestinationSelected: (NextcloudCollectionDestination) -> Unit,
    modifier: Modifier = Modifier,
    compactHeader: Boolean = false,
    destinationIcon: (NextcloudCollectionDestination) -> ImageVector? = { null },
    headerActions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    when (mode) {
        NextcloudCollectionNavigationMode.Drawer -> NextcloudCollectionDrawerScaffold(
            model = model,
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            hasHierarchyBack = hasHierarchyBack,
            onDestinationSelected = onDestinationSelected,
            destinationIcon = destinationIcon,
            compactHeader = compactHeader,
            headerActions = headerActions,
            modifier = modifier,
            content = content,
        )

        NextcloudCollectionNavigationMode.Hidden -> NextcloudCollectionMainPane(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            leadingControl = resolveNextcloudCollectionLeadingControl(mode, hasHierarchyBack),
            onOpenNavigation = null,
            compactHeader = compactHeader,
            headerActions = headerActions,
            modifier = modifier,
            content = content,
        )

        NextcloudCollectionNavigationMode.Rail -> Row(modifier.fillMaxSize()) {
            NextcloudCollectionNavigationRail(
                model = model,
                onDestinationSelected = onDestinationSelected,
                destinationIcon = destinationIcon,
            )
            NextcloudCollectionMainPane(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                leadingControl = resolveNextcloudCollectionLeadingControl(mode, hasHierarchyBack),
                onOpenNavigation = null,
                compactHeader = compactHeader,
                headerActions = headerActions,
                modifier = Modifier.weight(1f),
                content = content,
            )
        }

        NextcloudCollectionNavigationMode.Sidebar -> Row(modifier.fillMaxSize()) {
            NextcloudCollectionNavigationSidebar(
                model = model,
                label = title,
                onDestinationSelected = onDestinationSelected,
                destinationIcon = destinationIcon,
            )
            NextcloudCollectionMainPane(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                leadingControl = resolveNextcloudCollectionLeadingControl(mode, hasHierarchyBack),
                onOpenNavigation = null,
                compactHeader = compactHeader,
                headerActions = headerActions,
                modifier = Modifier.weight(1f),
                content = content,
            )
        }
    }
}

@Composable
private fun NextcloudCollectionDrawerScaffold(
    model: NextcloudCollectionNavigationModel,
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    hasHierarchyBack: Boolean,
    onDestinationSelected: (NextcloudCollectionDestination) -> Unit,
    destinationIcon: (NextcloudCollectionDestination) -> ImageVector?,
    compactHeader: Boolean,
    headerActions: @Composable () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val leadingControl = resolveNextcloudCollectionLeadingControl(
        mode = NextcloudCollectionNavigationMode.Drawer,
        hasHierarchyBack = hasHierarchyBack,
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(NextcloudCollectionDrawerWidthDp.dp)) {
                Text(
                    text = title,
                    modifier = Modifier
                        .padding(
                            horizontal = NextcloudSpacing.Large,
                            vertical = NextcloudSpacing.Medium,
                        )
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NextcloudCollectionDestinationList(
                    model = model,
                    onDestinationSelected = { destination ->
                        onDestinationSelected(destination)
                        coroutineScope.launch { drawerState.close() }
                    },
                    destinationIcon = destinationIcon,
                    labelMaxLines = resolveNextcloudCollectionDestinationLabelMaxLines(
                        NextcloudCollectionNavigationMode.Drawer,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(NextcloudSpacing.Small),
                )
            }
        },
        modifier = modifier,
    ) {
        NextcloudCollectionMainPane(
            title = title,
            subtitle = subtitle,
            onBack = {
                if (drawerState.isOpen) {
                    coroutineScope.launch { drawerState.close() }
                } else {
                    onBack()
                }
            },
            leadingControl = leadingControl,
            onOpenNavigation = { coroutineScope.launch { drawerState.open() } },
            compactHeader = compactHeader,
            headerActions = headerActions,
            content = content,
        )
    }
}

@Composable
private fun NextcloudCollectionMainPane(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    leadingControl: NextcloudCollectionLeadingControl,
    onOpenNavigation: (() -> Unit)?,
    compactHeader: Boolean,
    headerActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        NextcloudCollectionHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            leadingControl = leadingControl,
            onOpenNavigation = onOpenNavigation,
            compact = compactHeader,
            actions = headerActions,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun NextcloudCollectionHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    leadingControl: NextcloudCollectionLeadingControl,
    onOpenNavigation: (() -> Unit)?,
    compact: Boolean,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 54.dp else 64.dp)
            .padding(horizontal = NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (leadingControl) {
            NextcloudCollectionLeadingControl.Back -> IconButton(
                onClick = onBack,
                modifier = Modifier.size(NextcloudCollectionMinimumTouchTargetDp.dp),
            ) {
                Icon(NextcloudIcons.Back, contentDescription = "Back")
            }

            NextcloudCollectionLeadingControl.Menu -> IconButton(
                onClick = requireNotNull(onOpenNavigation),
                modifier = Modifier.size(NextcloudCollectionMinimumTouchTargetDp.dp),
            ) {
                Icon(NextcloudIcons.Menu, contentDescription = "Open sections")
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = NextcloudSpacing.Small),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { supportingText ->
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
        if (
            onOpenNavigation != null &&
            shouldShowNextcloudCollectionTrailingNavigation(
                mode = NextcloudCollectionNavigationMode.Drawer,
                hasHierarchyBack = leadingControl == NextcloudCollectionLeadingControl.Back,
            )
        ) {
            IconButton(
                onClick = onOpenNavigation,
                modifier = Modifier.size(NextcloudCollectionMinimumTouchTargetDp.dp),
            ) {
                Icon(NextcloudIcons.Menu, contentDescription = "Open sections")
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun NextcloudCollectionNavigationRail(
    model: NextcloudCollectionNavigationModel,
    onDestinationSelected: (NextcloudCollectionDestination) -> Unit,
    destinationIcon: (NextcloudCollectionDestination) -> ImageVector?,
) {
    val focusRequesters = rememberNextcloudCollectionFocusRequesters(model)
    var focusedDestinationId by remember(model.destinations) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Row {
        NavigationRail(
            modifier = Modifier
                .width(NextcloudCollectionRailWidthDp.dp)
                .fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nextcloudCollectionKeyboardNavigation(
                        model = model,
                        focusedDestinationId = focusedDestinationId,
                        focusRequesters = focusRequesters,
                        listState = listState,
                        coroutineScope = coroutineScope,
                    )
                    .selectableGroup(),
                state = listState,
            ) {
                items(
                    items = model.destinations,
                    key = NextcloudCollectionDestination::id,
                ) { destination ->
                    val focusRequester = rememberNextcloudCollectionFocusRequester(
                        destinationId = destination.id,
                        registry = focusRequesters,
                    )
                    NavigationRailItem(
                        selected = destination.id == model.selectedDestinationId,
                        onClick = { onDestinationSelected(destination) },
                        modifier = Modifier
                            .heightIn(min = NextcloudCollectionMinimumTouchTargetDp.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) focusedDestinationId = destination.id
                            },
                        icon = {
                            destinationIcon(destination)?.let { icon ->
                                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                        },
                        label = {
                            Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        alwaysShowLabel = true,
                    )
                }
            }
        }
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun NextcloudCollectionNavigationSidebar(
    model: NextcloudCollectionNavigationModel,
    label: String,
    onDestinationSelected: (NextcloudCollectionDestination) -> Unit,
    destinationIcon: (NextcloudCollectionDestination) -> ImageVector?,
) {
    Row {
        Column(
            modifier = Modifier
                .width(NextcloudCollectionSidebarWidthDp.dp)
                .fillMaxHeight()
                .padding(NextcloudSpacing.Medium),
        ) {
            Text(
                text = label,
                modifier = Modifier
                    .padding(
                        horizontal = NextcloudSpacing.Small,
                        vertical = NextcloudSpacing.Medium,
                    )
                    .semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            NextcloudCollectionDestinationList(
                model = model,
                onDestinationSelected = onDestinationSelected,
                destinationIcon = destinationIcon,
                labelMaxLines = resolveNextcloudCollectionDestinationLabelMaxLines(
                    NextcloudCollectionNavigationMode.Sidebar,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun NextcloudCollectionDestinationList(
    model: NextcloudCollectionNavigationModel,
    onDestinationSelected: (NextcloudCollectionDestination) -> Unit,
    destinationIcon: (NextcloudCollectionDestination) -> ImageVector?,
    labelMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = rememberNextcloudCollectionFocusRequesters(model)
    var focusedDestinationId by remember(model.destinations) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .nextcloudCollectionKeyboardNavigation(
                model = model,
                focusedDestinationId = focusedDestinationId,
                focusRequesters = focusRequesters,
                listState = listState,
                coroutineScope = coroutineScope,
            )
            .selectableGroup(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        items(
            items = model.destinations,
            key = NextcloudCollectionDestination::id,
        ) { destination ->
            val focusRequester = rememberNextcloudCollectionFocusRequester(
                destinationId = destination.id,
                registry = focusRequesters,
            )
            NavigationDrawerItem(
                label = {
                    Text(
                        text = destination.label,
                        maxLines = labelMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                selected = destination.id == model.selectedDestinationId,
                onClick = { onDestinationSelected(destination) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NextcloudCollectionMinimumTouchTargetDp.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) focusedDestinationId = destination.id
                    },
                icon = destinationIcon(destination)?.let { imageVector ->
                    {
                        Icon(imageVector, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                },
                badge = destination.count?.let { count ->
                    {
                        Badge {
                            Text(count.toString())
                        }
                    }
                },
            )
        }
    }
}

internal class NextcloudCollectionComposedDestinationRegistry<T : Any> {
    private val values = mutableMapOf<String, T>()

    val retainedCount: Int
        get() = values.size

    operator fun get(destinationId: String): T? = values[destinationId]

    fun attach(destinationId: String, value: T) {
        values[destinationId] = value
    }

    fun detach(destinationId: String, value: T) {
        if (values[destinationId] === value) values.remove(destinationId)
    }
}

@Composable
private fun rememberNextcloudCollectionFocusRequesters(
    model: NextcloudCollectionNavigationModel,
): NextcloudCollectionComposedDestinationRegistry<FocusRequester> = remember(model.destinations) {
    NextcloudCollectionComposedDestinationRegistry()
}

@Composable
private fun rememberNextcloudCollectionFocusRequester(
    destinationId: String,
    registry: NextcloudCollectionComposedDestinationRegistry<FocusRequester>,
): FocusRequester {
    val focusRequester = remember(destinationId) { FocusRequester() }
    DisposableEffect(registry, destinationId, focusRequester) {
        registry.attach(destinationId, focusRequester)
        onDispose {
            registry.detach(destinationId, focusRequester)
        }
    }
    return focusRequester
}

private fun Modifier.nextcloudCollectionKeyboardNavigation(
    model: NextcloudCollectionNavigationModel,
    focusedDestinationId: String?,
    focusRequesters: NextcloudCollectionComposedDestinationRegistry<FocusRequester>,
    listState: LazyListState,
    coroutineScope: CoroutineScope,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

    val move = when (event.key) {
        Key.DirectionUp -> NextcloudCollectionNavigationMove.Previous
        Key.DirectionDown -> NextcloudCollectionNavigationMove.Next
        Key.MoveHome -> NextcloudCollectionNavigationMove.First
        Key.MoveEnd -> NextcloudCollectionNavigationMove.Last
        else -> return@onPreviewKeyEvent false
    }
    resolveNextcloudCollectionKeyboardDestination(
        model = model,
        focusedDestinationId = focusedDestinationId,
        move = move,
    )?.let { destination ->
        val targetIndex = model.destinations.indexOfFirst { candidate -> candidate.id == destination.id }
        if (targetIndex < 0) return@onPreviewKeyEvent false
        coroutineScope.launch {
            listState.scrollToItem(targetIndex)
            repeat(NextcloudCollectionFocusAttachmentFrameLimit) {
                withFrameNanos { }
                focusRequesters[destination.id]?.let { focusRequester ->
                    focusRequester.requestFocus()
                    return@launch
                }
            }
        }
        true
    } ?: false
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private const val NextcloudCollectionMinimumTouchTargetDp = 48
private const val NextcloudCollectionDrawerWidthDp = 320
private const val NextcloudCollectionRailWidthDp = 88
private const val NextcloudCollectionSidebarWidthDp = 252
private const val NextcloudCollectionFocusAttachmentFrameLimit = 2
