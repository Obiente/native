package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

@Composable
internal fun GenericRecordTable(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
    modifier: Modifier = Modifier,
) {
    val composite = view.compositeDataGrid
    val columnResource = composite?.let { schema.resource(it.columnResourceId) }
    val columnRecords = composite?.let { datasetContext.relatedRecords[it.columnResourceId].orEmpty() }.orEmpty()
    val projection = remember(resource, records, columnResource, columnRecords, composite) {
        nativeTableProjection(resource, records, columnResource, columnRecords, composite)
    }
    val projectedResource = projection.resource
    val projectedRecords = projection.records
    val fields = remember(projection) {
        if (projection.composite) {
            projectedResource.fields.filter { it.id in projection.projectedFieldIds } +
                listOfNotNull(projectedResource.fields.firstOrNull { it.id == projection.frozenFieldId })
        } else {
            nativeTableFields(projectedResource, projectedRecords)
        }
    }.distinctBy(FieldSpec::id)
    if (fields.isEmpty()) {
        GenericRecordList(projectedResource, projectedRecords, onSelectRecord, modifier)
        return
    }
    val cellEdits = remember(schema, projection) { NativeCellEditSession() }
    LaunchedEffect(cellEdits, records) { cellEdits.acceptAuthoritativeRecords() }
    val actionWidth = if (onSelectRecord == null) 0.dp else 48.dp
    val frozenField = fields.firstOrNull { it.id == projection.frozenFieldId }
    val scrollingFields = fields.filterNot { it.id == frozenField?.id }
    val horizontalState = rememberScrollState()
    val verticalState = rememberLazyListState()
    NativeCollectionAutoPager(
        listState = verticalState,
        itemCount = projectedRecords.size,
        onLoadMore = onLoadMore,
        loadingMore = loadingMore,
        loadMoreError = loadMoreError,
    )
    Column(
        modifier = modifier.fillMaxSize().padding(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            frozenField?.let { field -> GenericTableHeaderCell(field, field.nativeTableColumnWidth()) }
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(horizontalState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scrollingFields.forEach { field -> GenericTableHeaderCell(field, field.nativeTableColumnWidth()) }
                if (actionWidth > 0.dp) Box(Modifier.width(actionWidth))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            state = verticalState,
            modifier = Modifier.weight(1f),
        ) {
            items(projectedRecords, key = NativeRecord::id) { record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            onSelectRecord?.let { callback -> Modifier.clickable { callback(record) } } ?: Modifier,
                        )
                        .heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    frozenField?.let { field ->
                        val plan = nativeCellEditPlan(schema, resource, projection, record, field)
                        GenericTableValueCell(
                            field = field,
                            rawValue = cellEdits.savedValue(record.id, field.id) ?: record.presentationValue(field.id),
                            editPlan = plan,
                            width = field.nativeTableColumnWidth(),
                            emphasized = true,
                            onEdit = cellEdits::begin,
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(horizontalState),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        scrollingFields.forEachIndexed { index, field ->
                            val plan = nativeCellEditPlan(schema, resource, projection, record, field)
                            GenericTableValueCell(
                                field = field,
                                rawValue = cellEdits.savedValue(record.id, field.id) ?: record.presentationValue(field.id),
                                editPlan = plan,
                                width = field.nativeTableColumnWidth(),
                                emphasized = frozenField == null && index == 0,
                                onEdit = cellEdits::begin,
                            )
                        }
                        if (onSelectRecord != null) {
                            Icon(
                                NextcloudIcons.ChevronRight,
                                contentDescription = "Open ${nativeRecordPresentation(projectedResource, record).title}",
                                modifier = Modifier.width(actionWidth).padding(NextcloudSpacing.Small),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            NativeCollectionPagingFooter(
                loadingMore = loadingMore,
                loadMoreError = loadMoreError,
                onRetry = onLoadMore,
            )
        }
    }
    NativeCellEditDialog(cellEdits, actionExecutor, onInlineActionSucceeded)
}


@Composable
private fun GenericTableHeaderCell(field: FieldSpec, width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = 44.dp)
            .background(NextcloudTheme.colors.appTile)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = NextcloudSpacing.Small, vertical = NextcloudSpacing.XSmall),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            field.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun FieldSpec.nativeTableColumnWidth(): androidx.compose.ui.unit.Dp {
    val semanticId = id.lowercase().filter(Char::isLetterOrDigit)
    return when {
        semanticId in setOf("id", "rowid", "recordid", "index", "position") -> 76.dp
        kind == FieldKind.boolean -> 96.dp
        kind == FieldKind.integer || kind == FieldKind.decimal -> 112.dp
        kind == FieldKind.date || kind == FieldKind.dateTime -> 148.dp
        kind == FieldKind.longText || semanticId in setOf("description", "content", "message", "notes") -> 240.dp
        else -> 184.dp
    }
}

@Composable
private fun GenericTableValueCell(
    field: FieldSpec,
    rawValue: String?,
    editPlan: NativeCellEditPlan?,
    width: androidx.compose.ui.unit.Dp,
    emphasized: Boolean,
    onEdit: (NativeCellEditPlan) -> Unit,
) {
    val value = rawValue?.takeIf(String::isNotBlank)?.let { formatNativeField(field, it).displayValue } ?: "-"
    Row(
        modifier = Modifier.width(width)
            .heightIn(min = 50.dp)
            .background(
                if (emphasized) NextcloudTheme.colors.appTile.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.background,
            )
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(editPlan?.let { plan -> Modifier.clickable { onEdit(plan) } } ?: Modifier)
            .padding(horizontal = NextcloudSpacing.Small, vertical = NextcloudSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = if (emphasized) {
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (value == "-") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (editPlan != null) {
            Icon(
                NextcloudIcons.Edit,
                contentDescription = "Edit ${field.label}",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

