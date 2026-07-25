package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch

/**
 * Native provider-driven global search. Apps such as Mail appear automatically when their server
 * app registers a unified-search provider; no client adapter or app ID allow-list is involved.
 */
@Composable
internal fun NextcloudUnifiedSearchScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
    onOpenResult: (UnifiedSearchSelection) -> Unit,
    from: String = "",
) {
    val client = remember(services, session) { NextcloudUnifiedSearchClient(services, session) }
    var providers by remember(session, from) { mutableStateOf<List<UnifiedSearchProvider>?>(null) }
    var discoveryError by remember(session, from) { mutableStateOf<String?>(null) }
    var discoveryAttempt by remember(session, from) { mutableStateOf(0) }
    var query by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var submittedQuery by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var selectedProviderIds by remember(session) { mutableStateOf<Set<String>>(emptySet()) }
    var includeExternal by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf(false) }
    var groups by remember(session) { mutableStateOf<Map<String, UnifiedSearchGroup>>(emptyMap()) }
    var failures by remember(session) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var searching by remember(session) { mutableStateOf(false) }
    var loadingMore by remember(session) { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(client, from, discoveryAttempt) {
        discoveryError = null
        runCatching { client.discoverProviders(from) }
            .onSuccess { discovered ->
                providers = discovered
                selectedProviderIds = selectedProviderIds.intersect(discovered.mapTo(mutableSetOf(), UnifiedSearchProvider::id))
            }
            .onFailure {
                discoveryError = unifiedSearchFailureMessage(it, "Could not discover search providers.")
            }
    }

    val activeProviders = remember(providers, selectedProviderIds) {
        val available = providers.orEmpty()
        if (selectedProviderIds.isEmpty()) available else available.filter { it.id in selectedProviderIds }
    }
    LaunchedEffect(client, activeProviders, submittedQuery, includeExternal, from) {
        if (submittedQuery.isBlank() || providers == null) {
            groups = emptyMap()
            failures = emptyMap()
            searching = false
            return@LaunchedEffect
        }
        groups = emptyMap()
        failures = emptyMap()
        searching = true
        client.searchAll(
            providers = activeProviders,
            request = UnifiedSearchRequest(term = submittedQuery, from = from),
            includeExternalProviders = includeExternal,
        ) { outcome ->
            when (outcome) {
                is UnifiedSearchProviderOutcome.Results -> groups = groups + (outcome.provider.id to outcome.group)
                is UnifiedSearchProviderOutcome.Failure -> failures = failures + (outcome.provider.id to outcome.message)
            }
        }
        searching = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        UnifiedSearchHeader(onBack = onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.XLarge,
                vertical = NextcloudSpacing.Medium,
            ),
            leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = { submittedQuery = query.trim() },
                        enabled = query.isNotBlank(),
                    ) {
                        Icon(NextcloudIcons.Search, contentDescription = "Run search")
                    }
                }
            },
            label = { Text("Search across Nextcloud") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submittedQuery = query.trim() }),
        )

        providers?.takeIf(List<UnifiedSearchProvider>::isNotEmpty)?.let { available ->
            ProviderFilterRow(
                providers = available,
                selectedIds = selectedProviderIds,
                onSelectionChanged = { selectedProviderIds = it },
            )
            if (available.any(UnifiedSearchProvider::isExternal)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("External search providers", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Off by default because queries may leave your server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = includeExternal,
                        onCheckedChange = { includeExternal = it },
                    )
                }
            }
        }

        when {
            providers == null && discoveryError == null -> UnifiedSearchCentered { CircularProgressIndicator() }
            discoveryError != null -> UnifiedSearchMessage(
                title = "Search is unavailable",
                detail = requireNotNull(discoveryError),
                action = "Retry",
                onAction = { discoveryAttempt += 1 },
            )
            providers?.isEmpty() == true -> UnifiedSearchMessage(
                title = "No search providers",
                detail = "Installed apps can expose themselves through Nextcloud unified search.",
            )
            submittedQuery.isBlank() -> UnifiedSearchMessage(
                title = "Find anything",
                detail = "Search Files, Mail, Talk, Deck, and every other app that exposes a search provider.",
            )
            !searching && groups.values.all { it.entries.isEmpty() } && failures.isEmpty() -> UnifiedSearchMessage(
                title = "No results",
                detail = "Nothing matched \"$submittedQuery\".",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NextcloudSpacing.XLarge,
                    end = NextcloudSpacing.XLarge,
                    top = NextcloudSpacing.Medium,
                    bottom = NextcloudSpacing.XXLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                val orderedGroups = groups.values.sortedWith(compareBy({ it.provider.order }, { it.displayName }))
                orderedGroups.forEach { group ->
                    item(key = "header:${group.provider.id}") { UnifiedSearchGroupHeader(group) }
                    items(
                        items = group.entries,
                        key = { entry -> "${group.provider.id}:${entry.stableKey()}" },
                    ) { entry ->
                        UnifiedSearchResultCard(
                            provider = group.provider,
                            entry = entry,
                            onClick = { onOpenResult(UnifiedSearchSelection(group.provider, entry)) },
                        )
                    }
                    if (group.canLoadMore) {
                        item(key = "more:${group.provider.id}:${group.nextCursor?.value}") {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = group.provider.id !in loadingMore,
                                onClick = {
                                    loadingMore += group.provider.id
                                    scope.launch {
                                        runCatching {
                                            client.loadNextPage(
                                                group,
                                                UnifiedSearchRequest(term = submittedQuery, from = from),
                                            )
                                        }.onSuccess { updated ->
                                            groups = groups + (group.provider.id to updated)
                                        }.onFailure { failure ->
                                            failures = failures + (
                                                group.provider.id to unifiedSearchFailureMessage(
                                                    failure,
                                                    "Could not load more results.",
                                                )
                                            )
                                        }
                                        loadingMore -= group.provider.id
                                    }
                                },
                            ) {
                                if (group.provider.id in loadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Load more ${group.displayName}")
                                }
                            }
                        }
                    }
                }
                failures.forEach { (providerId, message) ->
                    val providerName = providers.orEmpty().firstOrNull { it.id == providerId }?.name ?: providerId
                    item(key = "error:$providerId") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(NextcloudRadii.Medium),
                        ) {
                            Text(
                                "$providerName: $message",
                                modifier = Modifier.padding(NextcloudSpacing.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedSearchHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(NextcloudIcons.Back, contentDescription = "Back") }
        Column(modifier = Modifier.weight(1f)) {
            Text("Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Every connected app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderFilterRow(
    providers: List<UnifiedSearchProvider>,
    selectedIds: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(
            horizontal = NextcloudSpacing.XLarge,
            vertical = NextcloudSpacing.Small,
        ),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        FilterChip(
            selected = selectedIds.isEmpty(),
            onClick = { onSelectionChanged(emptySet()) },
            label = { Text("All") },
        )
        providers.forEach { provider ->
            FilterChip(
                selected = provider.id in selectedIds,
                onClick = {
                    onSelectionChanged(
                        if (provider.id in selectedIds) selectedIds - provider.id else selectedIds + provider.id,
                    )
                },
                leadingIcon = {
                    Icon(
                        NextcloudIcons.app(provider.appId),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text(provider.name) },
            )
        }
    }
}

@Composable
private fun UnifiedSearchGroupHeader(group: UnifiedSearchGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
            Icon(NextcloudIcons.app(group.provider.appId), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(group.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            group.entries.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnifiedSearchResultCard(
    provider: UnifiedSearchProvider,
    entry: UnifiedSearchEntry,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = entry.resourceUrl != null, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    NextcloudIcons.app(provider.appId),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.subline?.let { subline ->
                    Text(
                        subline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            entry.resourceUrl?.let {
                Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${entry.title}")
            }
        }
    }
}

@Composable
private fun UnifiedSearchMessage(
    title: String,
    detail: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    UnifiedSearchCentered {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.XXLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun UnifiedSearchCentered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = { content() })
}
