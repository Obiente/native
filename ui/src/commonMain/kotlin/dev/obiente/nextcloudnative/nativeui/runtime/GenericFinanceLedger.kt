package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

@Composable
internal fun GenericFinanceCollection(
    resource: ResourceSpec,
    rows: List<Pair<NativeRecord, NativeFinancePresentation?>>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val contextualCurrency = LocalNativeFinanceCurrency.current
    var filter by rememberSaveable(resource.id) { mutableStateOf(NativeFinanceLedgerFilter.All) }
    var categoryFilter by rememberSaveable(resource.id) { mutableStateOf<String?>(null) }
    var accountFilter by rememberSaveable(resource.id) { mutableStateOf<String?>(null) }
    var filtersExpanded by rememberSaveable(resource.id) { mutableStateOf(false) }
    val presentations = remember(rows) { rows.mapNotNull { (_, transaction) -> transaction } }
    // A selected facet keeps its chip even when refresh or paging drops it, so it stays removable.
    val categories = remember(presentations, categoryFilter) {
        (presentations.mapNotNull(NativeFinancePresentation::category).distinct().sorted().take(12) +
            listOfNotNull(categoryFilter)).distinct()
    }
    val accounts = remember(presentations, accountFilter) {
        (presentations.mapNotNull(NativeFinancePresentation::paymentMethod).distinct().sorted().take(12) +
            listOfNotNull(accountFilter)).distinct()
    }
    val facetFiltersAvailable = categories.size > 1 || accounts.size > 1 ||
        categoryFilter != null || accountFilter != null
    val presentedRows = remember(rows, filter, categoryFilter, accountFilter) {
        rows.filter { (_, transaction) ->
            val directionMatches = when (filter) {
                NativeFinanceLedgerFilter.All -> true
                NativeFinanceLedgerFilter.Income -> transaction?.direction == NativeFinanceDirection.Credit
                NativeFinanceLedgerFilter.Expenses -> transaction?.direction == NativeFinanceDirection.Debit
            }
            directionMatches &&
                (categoryFilter == null || transaction?.category == categoryFilter) &&
                (accountFilter == null || transaction?.paymentMethod == accountFilter)
        }
    }
    val loadedCurrencies = remember(presentations) {
        presentations.mapNotNull(NativeFinancePresentation::currency).distinct()
    }
    val currency = remember(loadedCurrencies, contextualCurrency) {
        loadedCurrencies.singleOrNull() ?: contextualCurrency.takeIf { loadedCurrencies.isEmpty() }
    }
    val netFlow = remember(presentations, loadedCurrencies) {
        presentations.sumOf(NativeFinancePresentation::amount).takeIf { loadedCurrencies.size <= 1 }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${presentations.size} loaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            netFlow?.let { "Loaded net ${formatNativeFinanceAmount(it, currency)}" }
                                ?: "Multiple currencies",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                netFlow == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                netFlow < 0 -> MaterialTheme.colorScheme.error
                                else -> Color(0xFF3F8F50)
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        val representedDirections = presentations.mapTo(hashSetOf(), NativeFinancePresentation::direction)
                        NativeFinanceLedgerFilter.entries.filter { option ->
                            option == NativeFinanceLedgerFilter.All ||
                                option == NativeFinanceLedgerFilter.Income && NativeFinanceDirection.Credit in representedDirections ||
                                option == NativeFinanceLedgerFilter.Expenses && NativeFinanceDirection.Debit in representedDirections
                        }.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(option.label) },
                            )
                        }
                        if (facetFiltersAvailable) {
                            TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                                val activeCount = listOfNotNull(categoryFilter, accountFilter).size
                                Text(if (activeCount == 0) "Filters" else "Filters ($activeCount)")
                            }
                        }
                    }
                    if (filtersExpanded && facetFiltersAvailable) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            categories.forEach { category ->
                                FilterChip(
                                    selected = categoryFilter == category,
                                    onClick = { categoryFilter = category.takeUnless { it == categoryFilter } },
                                    label = { Text(category, maxLines = 1) },
                                )
                            }
                            accounts.forEach { account ->
                                FilterChip(
                                    selected = accountFilter == account,
                                    onClick = { accountFilter = account.takeUnless { it == accountFilter } },
                                    label = { Text(account, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
            }
            val listState = rememberLazyListState()
            val localFiltersActive = filter != NativeFinanceLedgerFilter.All ||
                categoryFilter != null || accountFilter != null
            NativeCollectionAutoPager(
                listState,
                presentedRows.size,
                onLoadMore.takeUnless { localFiltersActive },
                loadingMore,
                loadMoreError,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = NextcloudSpacing.Large,
                    top = NextcloudSpacing.Medium,
                    end = NextcloudSpacing.Large,
                    bottom = NextcloudSpacing.XXLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                items(presentedRows, key = { (record, _) -> record.id }) { (record, transaction) ->
                    if (transaction == null) {
                        GenericCollectionCard(resource, record, onSelectRecord)
                        return@items
                    }
                    val interaction = onSelectRecord
                        ?.let { callback -> Modifier.clickable { callback(record) } }
                        ?: Modifier
                    Card(
                        modifier = interaction.fillMaxWidth().heightIn(min = 92.dp),
                        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val presentation = nativeRecordPresentation(resource, record)
                                GenericResourceIcon(
                                    resource,
                                    presentation.iconKey,
                                    presentation.colorArgb,
                                )
                                Text(
                                    transaction.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatNativeFinanceLedgerAmount(transaction, transaction.currency ?: currency),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (transaction.amount < 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                )
                            }
                            val split = financeSplitLabel(transaction)
                            if (transaction.participant != null || split != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                                    transaction.participant?.let { payer ->
                                        FinanceMetadataLine("Paid by", payer)
                                    }
                                    split?.let { value ->
                                        FinanceMetadataLine("Split", value)
                                    }
                                }
                            }
                            val context = listOfNotNull(transaction.category, transaction.paymentMethod)
                                .distinct().joinToString(" · ")
                            if (context.isNotBlank() || transaction.date != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                ) {
                                    Text(
                                        context,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    transaction.date?.let { date ->
                                        Text(
                                            date,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                NativeCollectionPagingFooter(loadingMore, loadMoreError, onLoadMore)
                // Filters only see loaded pages; offer one explicit page at a time instead of auto-paging.
                if (localFiltersActive && onLoadMore != null && !loadingMore && loadMoreError == null) {
                    item(key = "finance-filtered-load-more") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                        ) {
                            Text(
                                "Filters only cover the ${presentations.size} transactions loaded so far.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onLoadMore) { Text("Load more transactions") }
                        }
                    }
                }
            }
        }
    }
}

private enum class NativeFinanceLedgerFilter(val label: String) {
    All("All"),
    Income("Income"),
    Expenses("Expenses"),
}

private fun formatNativeFinanceLedgerAmount(
    transaction: NativeFinancePresentation,
    currency: String?,
): String {
    val formatted = formatNativeFinanceAmount(transaction.amount, currency)
    return if (transaction.direction == NativeFinanceDirection.Credit && transaction.amount > 0.0) {
        "+$formatted"
    } else formatted
}

@Composable
private fun FinanceMetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun financeSplitLabel(transaction: NativeFinancePresentation): String? =
    transaction.splitParticipants
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(", ")

@Composable
internal fun GenericFinanceDetailHeader(
    resource: ResourceSpec,
    transaction: NativeFinancePresentation,
) {
    val contextualCurrency = LocalNativeFinanceCurrency.current
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    val amount = formatNativeFinanceLedgerAmount(
        transaction,
        transaction.currency ?: contextualCurrency,
    )
    val amountColor = if (transaction.amount < 0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            if (desktop) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GenericResourceIcon(resource, large = true)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            transaction.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        transaction.date?.let { date ->
                            Text(
                                date,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        amount,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GenericResourceIcon(resource, large = true)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            transaction.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        transaction.date?.let { date ->
                            Text(
                                date,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    amount,
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
            }
            transaction.participant?.let { payer ->
                FinanceMetadataLine("Paid by", payer)
            }
            financeSplitLabel(transaction)?.let { split ->
                FinanceMetadataLine("Split", split)
            }
            listOfNotNull(transaction.category, transaction.paymentMethod)
                .distinct()
                .joinToString(" · ")
                .takeIf(String::isNotBlank)
                ?.let { metadata ->
                                    Text(
                                        metadata,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
        }
    }
}

/**
 * Compose effect keys use structural equality, while an authoritative refresh can legitimately
 * return records equal to the previous snapshot. This key treats a newly allocated record list as
 * a refresh even when its contents are unchanged.
 */
