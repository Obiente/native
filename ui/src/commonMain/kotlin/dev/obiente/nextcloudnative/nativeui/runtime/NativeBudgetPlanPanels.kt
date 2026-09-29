package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun NativeBudgetPlanSummary(plan: NativeBudgetPlanPresentation, currency: String?) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large,
        vertical = NextcloudSpacing.Small),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
            Text("Budget progress", style = MaterialTheme.typography.titleMedium)
            listOfNotNull(plan.startDate, plan.endDate).joinToString(" - ")
                .takeIf(String::isNotBlank)?.let { period ->
                    Text(period, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            Text(nativeBudgetRemainingLabel(plan.remaining, currency),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                color = if (plan.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("${formatNativeFinanceAmount(plan.spent, currency)} spent of ${formatNativeFinanceAmount(plan.budgeted, currency)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (plan.percentage / 100).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (plan.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${plan.percentage.roundToInt()}% used", style = MaterialTheme.typography.labelLarge)
                plan.overallStatus?.takeIf(String::isNotBlank)?.let {
                    Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun NativeBudgetCategoryCard(category: NativeBudgetCategoryProgress, currency: String?, overBudget: Boolean) {
    val attention = overBudget || category.remaining < 0
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(category.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Limit ${formatNativeFinanceAmount(category.budgeted, currency)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${category.percentage.roundToInt()}%", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            LinearProgressIndicator(progress = { (category.percentage / 100).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${formatNativeFinanceAmount(category.spent, currency)} spent",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(nativeBudgetRemainingLabel(category.remaining, currency),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (abs(category.carried) >= 0.005) Text(
                "Includes ${formatNativeFinanceAmount(category.carried, currency)} carryover",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
