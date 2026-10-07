package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/**
 * The balance counted in summary totals, or null when it would need a currency conversion.
 * Without one shared currency, raw balances in different currencies are never added together.
 */
internal fun nativeFinancialSummaryBalance(
    account: NativeFinancialAccountPresentation,
    summaryCurrency: String?,
    accounts: List<NativeFinancialAccountPresentation>,
): Double? {
    account.convertedBalance?.let { return it }
    val noCommonCurrency = summaryCurrency == null && accounts.any { it.currency != null }
    return account.balance.takeIf {
        !noCommonCurrency && (account.currency == null || account.currency == summaryCurrency)
    }
}

@Composable
internal fun GenericFinancialAccountCollection(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    rows: List<Pair<NativeRecord, NativeFinancialAccountPresentation>>,
    navigationContext: Map<String, String>,
    authorityContext: NativeRecordAuthorityContext?,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onEditRecord: (NativeRecord, NativeRecordFormActionPlan) -> Unit,
    onDeleteRecord: (NativeRecord, NativeRecordDeleteActionPlan) -> Unit,
    onCommandRecord: (NativeRecord, NativeRecordCommandActionPlan) -> Unit,
    onCommandFormRecord: (NativeRecord, NativeRecordCommandFormActionPlan) -> Unit,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val contextualCurrency = LocalNativeFinanceCurrency.current
    val accounts = remember(rows) { rows.map { (_, account) -> account } }
    val currency = remember(accounts, contextualCurrency) {
        accounts.mapNotNull(NativeFinancialAccountPresentation::baseCurrency).distinct().singleOrNull()
            ?: accounts.mapNotNull(NativeFinancialAccountPresentation::currency).distinct().singleOrNull()
            ?: contextualCurrency
    }
    fun convertedBalance(account: NativeFinancialAccountPresentation): Double? =
        nativeFinancialSummaryBalance(account, currency, accounts)
    val assets = remember(rows) { rows.filter { (_, account) -> account.kind == NativeFinancialAccountKind.Asset } }
    val liabilities = remember(rows) {
        rows.filter { (_, account) -> account.kind == NativeFinancialAccountKind.Liability }
    }
    val other = remember(rows) { rows.filter { (_, account) -> account.kind == NativeFinancialAccountKind.Other } }
    val includedAccounts = accounts.filterNot(NativeFinancialAccountPresentation::excludedFromReports)
    val assetTotal = includedAccounts.filter { it.kind == NativeFinancialAccountKind.Asset }
        .mapNotNull(::convertedBalance).sum()
    val liabilityBalance = includedAccounts.filter { it.kind == NativeFinancialAccountKind.Liability }
        .mapNotNull(::convertedBalance).sum()
    val liabilityTotal = nativeFinanceLiabilityTotal(liabilityBalance)
    val netWorth = assetTotal + liabilityBalance
    val unconvertedCount = accounts.count { convertedBalance(it) == null }
    val totalsQualifier = if (onLoadMore != null || loadingMore || loadMoreError != null) " (loaded)" else ""
    val listState = rememberLazyListState()
    NativeCollectionAutoPager(listState, rows.size, onLoadMore, loadingMore, loadMoreError)

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Medium,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth < 600.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            ) {
                                FinancialAccountMetric(
                                    label = "Assets$totalsQualifier",
                                    value = formatNativeFinanceAmount(assetTotal, currency),
                                    modifier = Modifier.weight(1f),
                                )
                                FinancialAccountMetric(
                                    label = "Liabilities$totalsQualifier",
                                    value = formatNativeFinanceAmount(liabilityTotal, currency),
                                    negative = liabilityTotal > 0.0,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            FinancialAccountMetric(
                                label = "Net worth$totalsQualifier",
                                value = formatNativeFinanceAmount(netWorth, currency),
                                negative = netWorth < 0.0,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            FinancialAccountMetric(
                                label = "Assets$totalsQualifier",
                                value = formatNativeFinanceAmount(assetTotal, currency),
                                modifier = Modifier.weight(1f),
                            )
                            FinancialAccountMetric(
                                label = "Liabilities$totalsQualifier",
                                value = formatNativeFinanceAmount(liabilityTotal, currency),
                                negative = liabilityTotal > 0.0,
                                modifier = Modifier.weight(1f),
                            )
                            FinancialAccountMetric(
                                label = "Net worth$totalsQualifier",
                                value = formatNativeFinanceAmount(netWorth, currency),
                                negative = netWorth < 0.0,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (unconvertedCount > 0) {
                    Text(
                        "$unconvertedCount ${if (unconvertedCount == 1) "account is" else "accounts are"} excluded from totals because no exchange rate is available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
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
            fun section(
                key: String,
                label: String,
                sectionRows: List<Pair<NativeRecord, NativeFinancialAccountPresentation>>,
            ) {
                if (sectionRows.isEmpty()) return
                item(key = "$key-header") {
                    val subtotal = sectionRows.filterNot { (_, account) -> account.excludedFromReports }
                        .mapNotNull { (_, account) -> convertedBalance(account) }
                        .sum()
                        .let { amount -> if (key == "liabilities") nativeFinanceLiabilityTotal(amount) else amount }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.XSmall),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            formatNativeFinanceAmount(subtotal, currency),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(sectionRows, key = { (record, _) -> record.id }) { (record, account) ->
                    val actions = remember(schema, resource, record, navigationContext, authorityContext) {
                        nativeRecordActions(schema, resource, record, navigationContext, authorityContext)
                    }
                    FinancialAccountCard(
                        resource = resource,
                        account = account,
                        onClick = onSelectRecord?.let { callback -> { callback(record) } },
                        secondaryActions = nativeRecordCardActions(
                            capabilities = actions,
                            record = record,
                            onEditRecord = onEditRecord,
                            onDeleteRecord = onDeleteRecord,
                            onCommandRecord = onCommandRecord,
                            onCommandFormRecord = onCommandFormRecord,
                        ),
                    )
                }
            }
            section("assets", "Assets", assets)
            section("liabilities", "Liabilities", liabilities)
            section("other", "Other accounts", other)
            NativeCollectionPagingFooter(loadingMore, loadMoreError, onLoadMore)
        }
    }
}

internal fun nativeFinanceLiabilityTotal(signedLiabilityBalance: Double): Double =
    (-signedLiabilityBalance).coerceAtLeast(0.0)

@Composable
private fun FinancialAccountMetric(
    label: String,
    value: String,
    negative: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FinancialAccountCard(
    resource: ResourceSpec,
    account: NativeFinancialAccountPresentation,
    onClick: (() -> Unit)?,
    secondaryActions: List<NextcloudCardAction>,
) {
    var actionsExpanded by rememberSaveable(account.name) { mutableStateOf(false) }
    val liability = account.kind == NativeFinancialAccountKind.Liability
    val displayedBalance = if (liability) kotlin.math.abs(account.balance) else account.balance
    val balanceLabel = when {
        liability && account.balance < 0.0 -> "Owed"
        liability && account.balance > 0.0 -> "Credit"
        else -> "Balance"
    }
    Card(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onClick,
            onShowActions = if (secondaryActions.isNotEmpty()) ({ actionsExpanded = true }) else null,
            openLabel = "Open ${account.name}",
            actionsLabel = "Show actions for ${account.name}",
        ),
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
                GenericResourceIcon(resource, account.type?.replace('_', '-'))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metadata = listOfNotNull(
                        account.type?.replace('_', ' ')?.replaceFirstChar(Char::uppercase),
                        account.institution,
                    ).distinct().joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Text(
                            metadata,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        balanceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatNativeFinanceAmount(displayedBalance, account.currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if ((liability && account.balance < 0.0) || (!liability && account.balance < 0.0)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color(0xFF3F8F50)
                        },
                        maxLines = 1,
                    )
                }
                if (secondaryActions.isNotEmpty()) {
                    NextcloudCardOverflow(
                        itemLabel = account.name,
                        actions = secondaryActions,
                        expanded = actionsExpanded,
                        onExpandedChange = { actionsExpanded = it },
                    )
                }
            }
            val footer = listOfNotNull(
                account.accountNumber,
                account.lastReconciled?.let { "Reconciled $it" },
            ).joinToString(" · ")
            if (footer.isNotBlank() || account.excludedFromReports || account.convertedBalance != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        footer,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val trailing = when {
                        account.excludedFromReports -> "Excluded"
                        account.convertedBalance != null -> "≈ ${formatNativeFinanceAmount(account.convertedBalance, account.baseCurrency)}"
                        else -> null
                    }
                    trailing?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
