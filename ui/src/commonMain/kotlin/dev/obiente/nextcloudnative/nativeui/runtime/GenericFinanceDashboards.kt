package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

private enum class NativeBudgetProgressFilter(val label: String) {
    All("All"),
    OverBudget("Over budget"),
    Watch("Watch"),
    OnTrack("On track"),
}

private fun NativeBudgetCategoryProgress.isOverBudget(): Boolean {
    val normalizedStatus = status?.lowercase()?.filter(Char::isLetterOrDigit)
    return when (normalizedStatus) {
        "over", "overbudget", "overspent", "exceeded" -> true
        "watch", "warning", "ontrack", "fullyspent", "spent", "complete", "completed" -> false
        else -> percentage > 100.0
    }
}

@Composable
internal fun GenericBudgetPlanDashboard(plan: NativeBudgetPlanPresentation) {
    var filter by rememberSaveable { mutableStateOf(NativeBudgetProgressFilter.All) }
    val visibleCategories = remember(plan.categories, filter) {
        plan.categories.filter { category ->
            when (filter) {
                NativeBudgetProgressFilter.All -> true
                NativeBudgetProgressFilter.OverBudget -> category.isOverBudget()
                NativeBudgetProgressFilter.Watch -> !category.isOverBudget() && category.percentage >= 75.0
                NativeBudgetProgressFilter.OnTrack -> !category.isOverBudget() && category.percentage < 75.0
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        val currency = LocalNativeFinanceCurrency.current
        NativeBudgetPlanSummary(plan, currency)
        Text("Categories", modifier = Modifier.padding(horizontal = NextcloudSpacing.Large),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            NativeBudgetProgressFilter.entries.forEach { option ->
                val count = plan.categories.count { category ->
                    when (option) {
                        NativeBudgetProgressFilter.All -> true
                        NativeBudgetProgressFilter.OverBudget -> category.isOverBudget()
                        NativeBudgetProgressFilter.Watch -> !category.isOverBudget() && category.percentage >= 75.0
                        NativeBudgetProgressFilter.OnTrack -> !category.isOverBudget() && category.percentage < 75.0
                    }
                }
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text("${option.label} $count") },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.XXLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            if (visibleCategories.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(
                            if (plan.categories.isEmpty()) {
                                "No category budgets yet"
                            } else {
                                "No ${filter.label.lowercase()} categories"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (plan.categories.isEmpty()) {
                                "Add a recurring budget to start planning this period."
                            } else {
                                "Choose another progress filter to review category budgets."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            visibleCategories.forEach { category ->
                NativeBudgetCategoryCard(category, currency, category.isOverBudget())
            }
        }
    }
}

@Composable
internal fun GenericFinanceStatisticsDashboard(
    dashboard: NativeFinanceDashboardPresentation,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            Text("Spending overview", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Balances and spending patterns",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            DatasetMetricCard("Paid", formatNativeFinanceAmount(dashboard.totalPaid, null))
            DatasetMetricCard("Spent", formatNativeFinanceAmount(dashboard.totalSpent, null))
            DatasetMetricCard("Balance", formatNativeFinanceAmount(dashboard.balance, null))
            DatasetMetricCard("Members", dashboard.members.size.toString())
        }

        Text("Members", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        dashboard.members.forEach { member ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Text(member.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        FinanceStatisticValue("Paid", member.paid)
                        FinanceStatisticValue("Spent", member.spent)
                        FinanceStatisticValue("Balance", member.balance)
                    }
                }
            }
        }

        FinanceDashboardChart("Monthly spending", dashboard.monthlySpending)
        FinanceDashboardChart("Spending by category", dashboard.categories)
        FinanceDashboardChart("Payment methods", dashboard.paymentMethods)
    }
}

@Composable
private fun FinanceStatisticValue(label: String, value: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatNativeFinanceAmount(value, null),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FinanceDashboardChart(
    title: String,
    points: List<NativeChartPoint>,
) {
    if (points.isEmpty()) return
    val shown = points.take(8)
    val maximum = shown.maxOf { point -> kotlin.math.abs(point.value) }.takeIf { it > 0.0 } ?: 1.0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            shown.forEach { point ->
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            point.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatNativeFinanceAmount(point.value, null),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (kotlin.math.abs(point.value) / maximum).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                    )
                }
            }
        }
    }
}
