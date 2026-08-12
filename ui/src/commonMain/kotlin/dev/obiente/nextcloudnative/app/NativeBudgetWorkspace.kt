package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import kotlin.math.abs
import kotlin.math.round

internal data class NativeBudgetMetric(
    val label: String,
    val value: Double,
    val supportingText: String? = null,
    val tone: NativeBudgetMetricTone = NativeBudgetMetricTone.Neutral,
)

internal enum class NativeBudgetMetricTone { Neutral, Positive, Negative }

internal data class NativeBudgetAccountRow(
    val id: String,
    val name: String,
    val balance: Double?,
)

internal data class NativeBudgetTrendPoint(
    val label: String,
    val income: Double,
    val expenses: Double,
)

internal data class NativeBudgetTransactionRow(
    val id: String,
    val title: String,
    val detail: String?,
    val amount: Double?,
    val isCredit: Boolean,
)

internal data class NativeBudgetBillRow(
    val id: String,
    val title: String,
    val dueDate: String?,
    val amount: Double?,
)

internal data class NativeBudgetDashboardModel(
    val currency: String?,
    val netWorth: NativeBudgetMetric?,
    val income: NativeBudgetMetric?,
    val expenses: NativeBudgetMetric?,
    val savings: NativeBudgetMetric?,
    val pensionWorth: NativeBudgetMetric?,
    val accounts: List<NativeBudgetAccountRow>,
    val trends: List<NativeBudgetTrendPoint>,
    val recentTransactions: List<NativeBudgetTransactionRow>,
    val upcomingBills: List<NativeBudgetBillRow>,
    val savingsGoalCount: Int?,
    val budgetCategoryCount: Int?,
    val billCount: Int?,
    val alertCount: Int?,
    val debtTotal: Double?,
    val assetTotal: Double?,
    val loadedSectionCount: Int,
)

internal fun buildNativeBudgetDashboardModel(
    dashboardReads: List<NativeBudgetDashboardRead>,
    dashboardRecordsByActionId: Map<String, List<NativeRecord>>,
): NativeBudgetDashboardModel {
    fun records(kind: NativeBudgetDashboardDataKind): List<NativeRecord> = dashboardReads
        .firstOrNull { it.kind == kind }
        ?.action
        ?.id
        ?.let(dashboardRecordsByActionId::get)
        .orEmpty()

    val allRecords = dashboardRecordsByActionId.values.flatten()
    val currency = allRecords.firstSemanticText("currency", "currencycode", "defaultcurrency", "basecurrency")
        ?.uppercase()
        ?.takeIf { it.length in 3..4 && it.all(Char::isLetter) }
    val accountSummary = records(NativeBudgetDashboardDataKind.AccountSummary)
    val reportSummary = records(NativeBudgetDashboardDataKind.ReportSummary)
    val accountRecords = records(NativeBudgetDashboardDataKind.Accounts)
    val accountCurrencies = accountRecords.mapNotNull { record ->
        record.semanticText("currency", "currencycode")?.uppercase()
    }.distinct()
    val accountBalanceFallback = accountRecords
        .takeIf { accountCurrencies.size <= 1 }
        ?.mapNotNull { it.semanticNumber("balance", "currentbalance", "value") }
        ?.takeIf(List<Double>::isNotEmpty)
        ?.sum()
    val netWorth = accountSummary.firstSemanticNumber(
        "networth", "totalnetworth", "totalbalance", "balance",
    ) ?: reportSummary.firstSemanticNumber("networth", "totalnetworth", "currentbalance") ?: accountBalanceFallback
    val income = reportSummary.firstSemanticNumber(
        "income", "totalincome", "monthlyincome", "incomethismonth",
    ) ?: records(NativeBudgetDashboardDataKind.IncomeSummary).firstSemanticNumber(
        "income", "total", "totalincome", "monthlyincome",
    )
    val expenses = reportSummary.firstSemanticNumber(
        "expenses", "expense", "totalexpenses", "monthlyexpenses", "expensesthismonth",
    )
    val reportedSavings = reportSummary.firstSemanticNumber(
        "netsavings", "savings", "totalsavings",
    )
    val savings = reportedSavings ?: if (income != null && expenses != null) income - abs(expenses) else null
    val savingsRate = if (income != null && income != 0.0 && savings != null) savings / income * 100.0 else null
    val pensions = records(NativeBudgetDashboardDataKind.PensionSummary)
        .firstSemanticNumber("pensionworth", "totalworth", "total", "balance")
    val billSummary = records(NativeBudgetDashboardDataKind.BillSummary)
    val alertSummary = records(NativeBudgetDashboardDataKind.AlertSummary)
    val debtSummary = records(NativeBudgetDashboardDataKind.DebtSummary)
    val assetSummary = records(NativeBudgetDashboardDataKind.AssetSummary)
    val trendSource = reportSummary.firstNotNullOfOrNull(NativeRecord::nativeBudgetTrendSeries)
    val recentTransactions = records(NativeBudgetDashboardDataKind.RecentTransactions)
    val savingsGoals = records(NativeBudgetDashboardDataKind.SavingsGoals)
    val budgetProgress = records(NativeBudgetDashboardDataKind.BudgetProgress)
    val upcomingBills = records(NativeBudgetDashboardDataKind.UpcomingBills)

    return NativeBudgetDashboardModel(
        currency = currency ?: accountCurrencies.singleOrNull(),
        netWorth = netWorth?.let { NativeBudgetMetric("Net worth", it) },
        income = income?.let { NativeBudgetMetric("Income this month", it, tone = NativeBudgetMetricTone.Positive) },
        expenses = expenses?.let { NativeBudgetMetric("Expenses this month", abs(it), tone = NativeBudgetMetricTone.Negative) },
        savings = savings?.let {
            NativeBudgetMetric(
                "Net savings",
                it,
                savingsRate?.let { rate -> "${formatNativeBudgetNumber(rate)}% savings rate" },
                if (it >= 0.0) NativeBudgetMetricTone.Positive else NativeBudgetMetricTone.Negative,
            )
        },
        pensionWorth = pensions?.let { NativeBudgetMetric("Pension worth", it) },
        accounts = accountRecords.map { record ->
            NativeBudgetAccountRow(
                id = record.id,
                name = record.semanticText("name", "accountname", "title") ?: "Account",
                balance = record.semanticNumber("balance", "currentbalance", "value"),
            )
        }.take(5),
        trends = trendSource.orEmpty(),
        recentTransactions = recentTransactions.map { record ->
            NativeBudgetTransactionRow(
                id = record.id,
                title = record.semanticText("description", "merchant", "name", "title") ?: "Transaction",
                detail = record.semanticText("categoryname", "category", "date", "bookingdate"),
                amount = record.semanticNumber("amount", "value", "total"),
                isCredit = record.semanticText("type", "transactiontype")
                    ?.equals("credit", ignoreCase = true) == true,
            )
        }.take(5),
        upcomingBills = upcomingBills.map { record ->
            NativeBudgetBillRow(
                id = record.id,
                title = record.semanticText("name", "title", "description", "vendor") ?: "Bill",
                dueDate = record.semanticText("duedate", "nextduedate", "date"),
                amount = record.semanticNumber("amount", "expectedamount", "total"),
            )
        }.take(3),
        savingsGoalCount = savingsGoals.nativeBudgetNestedOrRecordCount("goals", "savingsgoals"),
        budgetCategoryCount = budgetProgress.nativeBudgetNestedOrRecordCount("categories", "budgets"),
        billCount = billSummary.firstSemanticInt("count", "total", "upcomingcount", "billcount"),
        alertCount = alertSummary.firstSemanticInt("count", "total", "alertcount", "activecount")
            ?: records(NativeBudgetDashboardDataKind.Alerts).size,
        debtTotal = debtSummary.firstSemanticNumber("totaldebt", "total", "balance", "remainingbalance"),
        assetTotal = assetSummary.firstSemanticNumber("totalassets", "total", "value", "valuation"),
        loadedSectionCount = dashboardRecordsByActionId.count { (_, value) -> value.isNotEmpty() },
    )
}

@Composable
internal fun NativeBudgetDashboard(
    schema: NativeAppSchema,
    state: NativeScreenState,
    recordsByResourceId: Map<String, List<NativeRecord>>,
    dashboardReads: List<NativeBudgetDashboardRead> = emptyList(),
    dashboardRecordsByActionId: Map<String, List<NativeRecord>> = emptyMap(),
    dashboardErrorsByActionId: Map<String, String> = emptyMap(),
    onRetryDashboardReads: () -> Unit = {},
    onOpenSection: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val primaryResourceId = schema.views.firstOrNull { it.id == NATIVE_BUDGET_DASHBOARD_VIEW_ID }?.resourceId
    val primaryRecords = (state as? NativeScreenState.Ready)?.records.orEmpty()
    val effectiveDashboardRecords = remember(
        dashboardReads,
        dashboardRecordsByActionId,
        primaryResourceId,
        primaryRecords,
        recordsByResourceId,
    ) {
        if (dashboardReads.isEmpty()) {
            // Deterministic capture fixtures predate action-specific summary loading.
            dashboardRecordsByActionId + recordsByResourceId.mapKeys { (resourceId, _) -> "fixture:$resourceId" }
        } else {
            val primaryActionId = dashboardReads.firstOrNull { it.kind == NativeBudgetDashboardDataKind.Accounts }
                ?.action?.id
            if (primaryActionId != null && primaryRecords.isNotEmpty()) {
                dashboardRecordsByActionId + (primaryActionId to primaryRecords)
            } else dashboardRecordsByActionId
        }
    }
    val effectiveReads = remember(dashboardReads, recordsByResourceId) {
        if (dashboardReads.isNotEmpty()) dashboardReads else emptyList()
    }
    val model = remember(effectiveReads, effectiveDashboardRecords) {
        buildNativeBudgetDashboardModel(effectiveReads, effectiveDashboardRecords)
    }
    val initialLoading = state is NativeScreenState.Loading && model.loadedSectionCount == 0
    val availableSectionIds = remember(schema.views) {
        schema.views.mapTo(hashSetOf()) { view -> view.resourceId.normalizedBudgetResourceId() }
    }

    if (initialLoading) {
        NativeBudgetDashboardLoading(modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        item("metrics") {
            NativeBudgetMetricGrid(model)
        }
        item("cash-flow") {
            NativeBudgetCashFlowCard(model, onOpenSection)
        }
        if (model.accounts.isNotEmpty()) {
            item("accounts") {
                NativeBudgetAccountsCard(model, onOpenSection)
            }
        }
        if (model.recentTransactions.isNotEmpty()) {
            item("recent-transactions") {
                NativeBudgetTransactionsCard(model, onOpenSection)
            }
        }
        if (model.upcomingBills.isNotEmpty()) {
            item("upcoming-bills") {
                NativeBudgetBillsCard(model, onOpenSection)
            }
        }
        item("planning") {
            NativeBudgetPlanningRow(model, availableSectionIds, onOpenSection)
        }
        if (dashboardErrorsByActionId.isNotEmpty()) {
            item("partial-errors") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Some dashboard data could not be loaded", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${dashboardErrorsByActionId.size} section(s) may be incomplete.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = onRetryDashboardReads) { Text("Retry") }
                    }
                }
            }
        }
        if (state is NativeScreenState.Error && model.loadedSectionCount == 0) {
            item("error") {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NativeBudgetBillsCard(
    model: NativeBudgetDashboardModel,
    onOpenSection: (String) -> Unit,
) {
    NativeBudgetSectionCard("Upcoming bills", "View all", { onOpenSection("bills") }) {
        model.upcomingBills.forEachIndexed { index, bill ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(bill.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    bill.dueDate?.let { dueDate ->
                        Text("Due $dueDate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                bill.amount?.let { amount ->
                    Text(formatNativeBudgetMoney(abs(amount), model.currency), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun NativeBudgetTransactionsCard(
    model: NativeBudgetDashboardModel,
    onOpenSection: (String) -> Unit,
) {
    NativeBudgetSectionCard("Recent transactions", "View all", { onOpenSection("transactions") }) {
        model.recentTransactions.forEachIndexed { index, transaction ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(transaction.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    transaction.detail?.let { detail ->
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                transaction.amount?.let { amount ->
                    val signedAmount = if (transaction.isCredit) abs(amount) else -abs(amount)
                    Text(
                        "${if (transaction.isCredit) "+" else "-"}${formatNativeBudgetMoney(abs(amount), model.currency)}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (signedAmount < 0.0) MaterialTheme.colorScheme.error else Color(0xFF3F8F50),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeBudgetDashboardLoading(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text("Loading your finance overview", style = MaterialTheme.typography.titleMedium)
        Text(
            "Summary sections appear as soon as each verified Budget read completes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NativeBudgetMetricGrid(model: NativeBudgetDashboardModel) {
    val metrics = listOfNotNull(model.netWorth, model.income, model.expenses, model.savings, model.pensionWorth)
    if (metrics.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 1_000.dp -> 4
            maxWidth >= 560.dp -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            metrics.chunked(columns).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    rowMetrics.forEach { metric ->
                        NativeBudgetMetricCard(
                            metric = metric,
                            currency = model.currency,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowMetrics.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun NativeBudgetMetricCard(
    metric: NativeBudgetMetric,
    currency: String?,
    modifier: Modifier = Modifier,
) {
    val accent = when (metric.tone) {
        NativeBudgetMetricTone.Neutral -> MaterialTheme.colorScheme.primary
        NativeBudgetMetricTone.Positive -> Color(0xFF3F8F50)
        NativeBudgetMetricTone.Negative -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = modifier.heightIn(min = 104.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(
                metric.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatNativeBudgetMoney(metric.value, currency),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            metric.supportingText?.let { supporting ->
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NativeBudgetCashFlowCard(
    model: NativeBudgetDashboardModel,
    onOpenSection: (String) -> Unit,
) {
    val income = model.income?.value
    val expenses = model.expenses?.value
    val maximum = maxOf(income ?: 0.0, expenses ?: 0.0).takeIf { it > 0.0 } ?: 1.0
    NativeBudgetSectionCard(
        title = "Cash-flow trend",
        actionLabel = "View",
        onAction = { onOpenSection("transactions") },
    ) {
        if (model.trends.size >= 2) {
            NativeBudgetTrendChart(model.trends, model.currency)
        } else if (income == null && expenses == null) {
            Text(
                "Cash-flow totals are unavailable from this server response.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NativeBudgetComparisonBar("Income", income ?: 0.0, maximum, model.currency, Color(0xFF3F8F50))
            NativeBudgetComparisonBar("Expenses", expenses ?: 0.0, maximum, model.currency, MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NativeBudgetTrendChart(
    points: List<NativeBudgetTrendPoint>,
    currency: String?,
) {
    val incomeColor = Color(0xFF3F8F50)
    val expenseColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maximum = points.maxOfOrNull { maxOf(abs(it.income), abs(it.expenses)) }
        ?.takeIf { it > 0.0 } ?: 1.0
    val description = points.joinToString(prefix = "Six month cash flow. ", separator = ". ") { point ->
        "${point.label}: income ${formatNativeBudgetMoney(point.income, currency)}, expenses ${formatNativeBudgetMoney(point.expenses, currency)}"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        NativeBudgetLegendItem("Income", incomeColor)
        NativeBudgetLegendItem("Expenses", expenseColor)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .semantics { contentDescription = description },
    ) {
        val left = 4.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        repeat(3) { index ->
            val y = top + (bottom - top) * index / 2f
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(left, y), end = androidx.compose.ui.geometry.Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        fun pathFor(selector: (NativeBudgetTrendPoint) -> Double): Path {
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = if (points.size == 1) left else left + (right - left) * index / (points.size - 1)
                val y = bottom - (bottom - top) * (abs(selector(point)) / maximum).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        drawPath(pathFor(NativeBudgetTrendPoint::income), incomeColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(pathFor(NativeBudgetTrendPoint::expenses), expenseColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
    val labelIndexes = listOf(0, points.lastIndex / 2, points.lastIndex).distinct()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labelIndexes.forEach { index ->
            val point = points[index]
            Text(point.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NativeBudgetLegendItem(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(8.dp), color = color, shape = RoundedCornerShape(50)) {}
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NativeBudgetComparisonBar(
    label: String,
    value: Double,
    maximum: Double,
    currency: String?,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(formatNativeBudgetMoney(value, currency), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { (abs(value) / maximum).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = color,
        )
    }
}

@Composable
private fun NativeBudgetAccountsCard(
    model: NativeBudgetDashboardModel,
    onOpenSection: (String) -> Unit,
) {
    NativeBudgetSectionCard("Accounts", "Manage", { onOpenSection("accounts") }) {
        model.accounts.forEachIndexed { index, account ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(NextcloudRadii.Small),
                    ) {
                        Icon(
                            NextcloudIcons.app("budget"),
                            contentDescription = null,
                            modifier = Modifier.padding(6.dp).size(18.dp),
                        )
                    }
                    Text(account.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                account.balance?.let { balance ->
                    Text(
                        formatNativeBudgetMoney(balance, model.currency),
                        modifier = Modifier.padding(start = NextcloudSpacing.Medium),
                        fontWeight = FontWeight.SemiBold,
                        color = if (balance < 0.0) MaterialTheme.colorScheme.error else Color(0xFF3F8F50),
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeBudgetPlanningRow(
    model: NativeBudgetDashboardModel,
    availableSectionIds: Set<String>,
    onOpenSection: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 700.dp) 3 else 1
        val width = (maxWidth - NextcloudSpacing.Small * (columns - 1)) / columns
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            maxItemsInEachRow = columns,
        ) {
            if ((model.alertCount ?: 0) > 0 && "alerts" in availableSectionIds) {
                NativeBudgetStatusCard(
                    width, "Budget alerts", "${model.alertCount} need attention",
                    "alerts", onOpenSection,
                )
            }
            if (model.upcomingBills.isEmpty() && "bills" in availableSectionIds) {
                NativeBudgetStatusCard(
                    width, "Upcoming bills", model.billCount?.let { "$it scheduled" } ?: "No summary available",
                    "bills", onOpenSection,
                )
            }
            val planningDestination = listOf("savings-goals", "budget", "debts", "assets", "trends")
                .firstOrNull { destination -> destination in availableSectionIds }
            if (planningDestination != null) NativeBudgetStatusCard(
                width, "Planning", listOfNotNull(
                    model.budgetCategoryCount?.let { "$it budget categories" },
                    model.savingsGoalCount?.let { "$it savings goals" },
                    model.debtTotal?.let { "Debt ${formatNativeBudgetMoney(it, model.currency)}" },
                    model.assetTotal?.takeIf { abs(it) >= 0.005 }
                        ?.let { "Assets ${formatNativeBudgetMoney(it, model.currency)}" },
                ).joinToString(" · ").ifBlank { "Goals, debt and forecast" },
                planningDestination, onOpenSection,
            )
        }
    }
}

@Composable
private fun NativeBudgetStatusCard(
    width: Dp,
    title: String,
    summary: String,
    resourceId: String,
    onOpenSection: (String) -> Unit,
) {
    Card(
        onClick = { onOpenSection(resourceId) },
        modifier = Modifier.width(width).heightIn(min = 72.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NativeBudgetSectionCard(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
            content()
        }
    }
}

private fun NativeRecord.semanticText(vararg aliases: String): String? {
    val normalizedAliases = aliases.mapTo(hashSetOf(), String::budgetSemanticKey)
    val scalar = (displayValues.mapValues { it.value as String? } + values).entries.firstNotNullOfOrNull { (key, value) ->
        value?.trim()?.takeIf { key.budgetSemanticKey() in normalizedAliases && it.isNotBlank() }
    }
    return scalar ?: structuredValues.entries.firstNotNullOfOrNull { (key, value) ->
        value.findNativeBudgetStructuredText(key, normalizedAliases)
    }
}

private fun NativeStructuredValue.findNativeBudgetStructuredText(
    key: String,
    aliases: Set<String>,
): String? = when (this) {
    is NativeStructuredValue.Scalar -> value
        ?.trim()
        ?.takeIf { key.budgetSemanticKey() in aliases && it.isNotBlank() }

    is NativeStructuredValue.ListValue -> items.firstNotNullOfOrNull { item ->
        item.findNativeBudgetStructuredText(key, aliases)
    }

    is NativeStructuredValue.ObjectValue -> entries.firstNotNullOfOrNull { entry ->
        entry.value.findNativeBudgetStructuredText(entry.key, aliases)
    }
}

private fun NativeRecord.nativeBudgetTrendSeries(): List<NativeBudgetTrendPoint>? {
    val trends = structuredValues.entries
        .firstNotNullOfOrNull { (key, value) ->
            (value as? NativeStructuredValue.ObjectValue)
                ?.takeIf { key.budgetSemanticKey() == "trends" }
                ?: value.findNativeBudgetObject("trends")
        }
        ?: return null
    val labels = trends.nativeBudgetScalarList("labels")
    val income = trends.nativeBudgetNumberList("income")
    val expenses = trends.nativeBudgetNumberList("expenses")
    val size = minOf(labels.size, income.size, expenses.size)
    if (size < 2) return null
    return (0 until size).map { index ->
        NativeBudgetTrendPoint(labels[index], income[index], abs(expenses[index]))
    }
}

private fun NativeStructuredValue.findNativeBudgetObject(alias: String): NativeStructuredValue.ObjectValue? = when (this) {
    is NativeStructuredValue.Scalar -> null
    is NativeStructuredValue.ListValue -> items.firstNotNullOfOrNull { it.findNativeBudgetObject(alias) }
    is NativeStructuredValue.ObjectValue -> entries.firstNotNullOfOrNull { entry ->
        (entry.value as? NativeStructuredValue.ObjectValue)
            ?.takeIf { entry.key.budgetSemanticKey() == alias.budgetSemanticKey() }
            ?: entry.value.findNativeBudgetObject(alias)
    }
}

private fun NativeStructuredValue.ObjectValue.nativeBudgetScalarList(alias: String): List<String> =
    entries.firstOrNull { it.key.budgetSemanticKey() == alias.budgetSemanticKey() }
        ?.value
        ?.let { it as? NativeStructuredValue.ListValue }
        ?.items
        ?.mapNotNull { (it as? NativeStructuredValue.Scalar)?.value }
        .orEmpty()

private fun NativeStructuredValue.ObjectValue.nativeBudgetNumberList(alias: String): List<Double> =
    nativeBudgetScalarList(alias).mapNotNull(String::nativeBudgetNumberOrNull)

private fun List<NativeRecord>.firstSemanticText(vararg aliases: String): String? =
    firstNotNullOfOrNull { record -> record.semanticText(*aliases) }

private fun NativeRecord.semanticNumber(vararg aliases: String): Double? =
    semanticText(*aliases)?.nativeBudgetNumberOrNull()

private fun List<NativeRecord>.firstSemanticNumber(vararg aliases: String): Double? =
    firstNotNullOfOrNull { record -> record.semanticNumber(*aliases) }

private fun List<NativeRecord>.firstSemanticInt(vararg aliases: String): Int? =
    firstSemanticNumber(*aliases)?.toInt()

private fun List<NativeRecord>.nativeBudgetNestedOrRecordCount(vararg aliases: String): Int? {
    if (isEmpty()) return null
    val wanted = aliases.mapTo(hashSetOf(), String::budgetSemanticKey)
    val nested = firstNotNullOfOrNull { record ->
        record.structuredValues.entries.firstNotNullOfOrNull { (key, value) ->
            (value as? NativeStructuredValue.ListValue)
                ?.takeIf { key.budgetSemanticKey() in wanted }
                ?.items
                ?.size
        }
    }
    return nested ?: size
}

private fun String.budgetSemanticKey(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.nativeBudgetNumberOrNull(): Double? {
    val normalized = trim().replace(" ", "").replace(",", "")
    return normalized.toDoubleOrNull()
        ?: Regex("-?[0-9]+(?:\\.[0-9]+)?").find(normalized)?.value?.toDoubleOrNull()
}

internal fun formatNativeBudgetMoney(value: Double, currency: String?): String {
    val amount = formatNativeBudgetNumber(value)
    return currency?.let { "$it $amount" } ?: amount
}

internal fun formatNativeBudgetNumber(value: Double): String {
    val rounded = round(value * 100.0) / 100.0
    val sign = if (rounded < 0.0) "-" else ""
    val absolute = abs(rounded)
    val integer = absolute.toLong()
    val grouped = integer.toString().reversed().chunked(3).joinToString(",").reversed()
    val cents = round((absolute - integer) * 100.0).toInt()
    return if (cents == 0) "$sign$grouped" else "$sign$grouped.${cents.toString().padStart(2, '0')}"
}
