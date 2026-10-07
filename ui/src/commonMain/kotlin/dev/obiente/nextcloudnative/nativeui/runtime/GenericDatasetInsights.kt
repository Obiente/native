package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.math.roundToInt

@Composable
internal fun GenericInsightCollection(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
) {
    val insights = remember(resource, records) { nativeDatasetInsights(resource, records) }
    val categoricalSummary = remember(resource, records) {
        nativeCategoricalSummary(resource, records)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactViewport = !datasetInsightsDefaultExpanded(maxWidth.value, maxHeight.value)
        Column(modifier = Modifier.fillMaxSize()) {
            if (insights != null) {
                DatasetInsightsDisclosure(
                    insights = insights,
                    compact = compactViewport,
                    initiallyExpanded = true,
                    stateKey = "insights:${resource.id}",
                )
            } else if (categoricalSummary != null) {
                CategoricalSummaryDisclosure(
                    summary = categoricalSummary,
                    initiallyExpanded = true,
                    stateKey = "summary:${resource.id}:${categoricalSummary.dimension.id}",
                )
            }
            GenericRecordList(resource, records, onSelectRecord, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CategoricalSummaryDisclosure(
    summary: NativeCategoricalSummary,
    initiallyExpanded: Boolean,
    stateKey: String,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Small,
                end = NextcloudSpacing.Small,
                bottom = NextcloudSpacing.XSmall,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.dimension.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${summary.recordCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            GenericCategoricalSummary(summary)
        } else {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun GenericCategoricalSummary(summary: NativeCategoricalSummary) {
    val maximum = summary.points.maxOfOrNull(NativeChartPoint::value)?.takeIf { it > 0.0 } ?: 1.0
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Small,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.Medium,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        summary.points.forEach { point ->
            val count = point.value.roundToInt()
            val percentage = ((point.value / summary.recordCount) * 100.0).roundToInt()
            Column(
                modifier = Modifier.semantics {
                    contentDescription = "${point.label}, $count of ${summary.recordCount}, $percentage percent"
                },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(point.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$count ($percentage%)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { (point.value / maximum).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

internal fun datasetInsightsDefaultExpanded(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 720f && heightDp >= 600f

internal fun shouldUseCompactTableRecordList(widthDp: Float): Boolean = widthDp < 720f

@Composable
internal fun DatasetInsightsDisclosure(
    insights: NativeDatasetInsights,
    compact: Boolean,
    initiallyExpanded: Boolean,
    stateKey: String,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.XSmall,
                end = NextcloudSpacing.Small,
                bottom = NextcloudSpacing.XSmall,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Insights",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${formatNativeMetric(insights.measure, insights.total)} total · ${insights.recordCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            GenericDatasetInsights(insights, compact)
        } else {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun GenericDatasetInsights(insights: NativeDatasetInsights, compact: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        "Total ${insights.measure.label.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatNativeMetric(insights.measure, insights.total),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "${insights.recordCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                DatasetMetricCard(
                    label = "Total ${insights.measure.label.lowercase()}",
                    value = formatNativeMetric(insights.measure, insights.total),
                )
                DatasetMetricCard(
                    label = "Average",
                    value = formatNativeMetric(insights.measure, insights.average),
                )
                DatasetMetricCard(label = "Items", value = insights.recordCount.toString())
            }
        }
        val displayedPoints = if (compact) insights.points.take(3) else insights.points
        if (displayedPoints.isNotEmpty()) {
            Text(
                "${insights.measure.label} by ${insights.dimension?.label.orEmpty().lowercase()}",
                style = MaterialTheme.typography.titleSmall,
            )
            val maximum = displayedPoints.maxOf { kotlin.math.abs(it.value) }.takeIf { it > 0.0 } ?: 1.0
            displayedPoints.forEach { point ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            point.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatNativeMetric(insights.measure, point.value),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (kotlin.math.abs(point.value) / maximum).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun DatasetMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier.width(148.dp),
) {
    Surface(
        modifier = modifier,
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
