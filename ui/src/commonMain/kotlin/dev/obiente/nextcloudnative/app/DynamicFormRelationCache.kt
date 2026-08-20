package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord

internal data class DynamicFormRelationCacheKey(
    val resourceId: String,
    val actionId: String,
    val bindingValues: Map<String, String>,
)

internal data class DynamicFormRelationLoadRequest(
    val plan: DynamicFormRelationLoadPlan,
    val cacheKey: DynamicFormRelationCacheKey,
)

internal data class DynamicFormRelationContinuation(
    val spec: DynamicPaginationSpec,
    val nextPageNumber: Int,
    val nextRequestValue: String,
    val loadedRecordCount: Int,
)

internal fun shouldOfferInitialDynamicRelationRetry(
    hasContinuation: Boolean,
    loading: Boolean,
    error: String?,
    discardedRecordCount: Int,
): Boolean = error != null && !hasContinuation && !loading && discardedRecordCount == 0

internal data class DynamicFormRelationLoadResult(
    val records: List<NativeRecord>,
    val pagination: DynamicPaginationSpec?,
    val partialFailureMessage: String? = null,
)

internal data class DynamicFormRelationCacheState(
    val recordsByKey: Map<DynamicFormRelationCacheKey, List<NativeRecord>> = emptyMap(),
    val continuationsByKey: Map<DynamicFormRelationCacheKey, DynamicFormRelationContinuation> = emptyMap(),
    val discardedRecordCountsByKey: Map<DynamicFormRelationCacheKey, Int> = emptyMap(),
    val failedKeys: Set<DynamicFormRelationCacheKey> = emptySet(),
) {
    fun pendingRequests(
        requests: List<DynamicFormRelationLoadRequest>,
    ): List<DynamicFormRelationLoadRequest> = requests.filter { request ->
        request.cacheKey !in recordsByKey && request.cacheKey !in failedKeys
    }

    fun relatedRecords(
        requests: List<DynamicFormRelationLoadRequest>,
    ): Map<String, List<NativeRecord>> = requests.mapNotNull { request ->
        recordsByKey[request.cacheKey]?.let { records -> request.plan.resourceId to records }
    }.toMap()

    fun datasetRelatedRecords(
        genericRecords: Map<String, List<NativeRecord>>,
        requests: List<DynamicFormRelationLoadRequest>,
    ): Map<String, List<NativeRecord>> {
        val scopedResourceIds = requests.mapTo(hashSetOf()) { request -> request.plan.resourceId }
        return genericRecords.filterKeys { resourceId -> resourceId !in scopedResourceIds } +
            relatedRecords(requests)
    }

    fun failedRequests(
        requests: List<DynamicFormRelationLoadRequest>,
    ): List<DynamicFormRelationLoadRequest> = requests.filter { request ->
        request.cacheKey in failedKeys
    }

    fun loadSucceeded(
        request: DynamicFormRelationLoadRequest,
        records: List<NativeRecord>,
        pagination: DynamicPaginationSpec? = null,
    ): DynamicFormRelationCacheState {
        val distinctRecords = records.distinctBy(NativeRecord::id)
        val discardedRecordCount =
            (distinctRecords.size - MAX_DYNAMIC_FORM_RELATION_RECORDS).coerceAtLeast(0)
        val boundedRecords = distinctRecords.takeLast(MAX_DYNAMIC_FORM_RELATION_RECORDS)
        val continuation = pagination?.nextDynamicFormRelationContinuation(
            lastPage = records,
            loadedRecordCount = records.size,
        )
        return copy(
            recordsByKey = recordsByKey.putBounded(request.cacheKey, boundedRecords),
            continuationsByKey = if (continuation == null) {
                continuationsByKey - request.cacheKey
            } else {
                continuationsByKey.putBounded(request.cacheKey, continuation)
            },
            discardedRecordCountsByKey = if (discardedRecordCount == 0) {
                discardedRecordCountsByKey - request.cacheKey
            } else {
                discardedRecordCountsByKey.putBounded(request.cacheKey, discardedRecordCount)
            },
            failedKeys = failedKeys - request.cacheKey,
        )
    }

    fun appendPageSucceeded(
        request: DynamicFormRelationLoadRequest,
        page: List<NativeRecord>,
    ): DynamicFormRelationCacheState {
        val current = recordsByKey[request.cacheKey].orEmpty()
        val activeContinuation = continuationsByKey[request.cacheKey] ?: return this
        val currentIds = current.mapTo(hashSetOf(), NativeRecord::id)
        val novelRecords = page.distinctBy(NativeRecord::id)
            .filterNot { record -> record.id in currentIds }
        val unboundedWindow = current + novelRecords
        val discardedFromWindow =
            (unboundedWindow.size - MAX_DYNAMIC_FORM_RELATION_RECORDS).coerceAtLeast(0)
        val merged = unboundedWindow.takeLast(MAX_DYNAMIC_FORM_RELATION_RECORDS)
        val nextContinuation = activeContinuation.spec.nextDynamicFormRelationContinuation(
            lastPage = page,
            loadedRecordCount = activeContinuation.loadedRecordCount + page.size,
            novelRecordCount = novelRecords.size,
            nextPageNumber = activeContinuation.nextPageNumber + 1,
        )
        val discardedRecordCount =
            ((discardedRecordCountsByKey[request.cacheKey] ?: 0).toLong() + discardedFromWindow)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        return copy(
            recordsByKey = recordsByKey.putBounded(request.cacheKey, merged),
            continuationsByKey = if (nextContinuation == null) {
                continuationsByKey - request.cacheKey
            } else {
                continuationsByKey.putBounded(request.cacheKey, nextContinuation)
            },
            discardedRecordCountsByKey = if (discardedRecordCount == 0) {
                discardedRecordCountsByKey - request.cacheKey
            } else {
                discardedRecordCountsByKey.putBounded(request.cacheKey, discardedRecordCount)
            },
        )
    }

    fun continuation(
        request: DynamicFormRelationLoadRequest,
    ): DynamicFormRelationContinuation? = continuationsByKey[request.cacheKey]

    fun discardedRecordCount(request: DynamicFormRelationLoadRequest): Int =
        discardedRecordCountsByKey[request.cacheKey] ?: 0

    fun loadFailed(
        request: DynamicFormRelationLoadRequest,
    ): DynamicFormRelationCacheState = copy(
        recordsByKey = recordsByKey - request.cacheKey,
        continuationsByKey = continuationsByKey - request.cacheKey,
        discardedRecordCountsByKey = discardedRecordCountsByKey - request.cacheKey,
        failedKeys = (failedKeys + request.cacheKey)
            .toList()
            .takeLast(MAX_DYNAMIC_FORM_RELATION_CACHE_SCOPES)
            .toSet(),
    )

    fun retry(
        requests: List<DynamicFormRelationLoadRequest>,
    ): DynamicFormRelationCacheState {
        val retryKeys = requests.mapTo(hashSetOf(), DynamicFormRelationLoadRequest::cacheKey)
        return copy(failedKeys = failedKeys - retryKeys)
    }
}

private fun DynamicPaginationSpec.nextDynamicFormRelationContinuation(
    lastPage: List<NativeRecord>,
    loadedRecordCount: Int,
    novelRecordCount: Int = lastPage.size,
    nextPageNumber: Int? = null,
): DynamicFormRelationContinuation? {
    if (!canContinue(lastPage.size, novelRecordCount)) return null
    val continuationPageNumber = nextPageNumber ?: (initialPageNumber + 1)
    val nextValue = nextValue(continuationPageNumber, loadedRecordCount, lastPage) ?: return null
    return DynamicFormRelationContinuation(this, continuationPageNumber, nextValue, loadedRecordCount)
}

internal suspend fun loadInitialDynamicFormRelationRecords(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    request: DynamicFormRelationLoadRequest,
    values: Map<String, String>,
    cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
): DynamicFormRelationLoadResult {
    val action = descriptor.actions.singleOrNull { action -> action.id == request.plan.actionId }
        ?: error("This relation has no declared load action.")
    val boundValues = dynamicFormRelationRuntimeValues(request, values)
    val outcome = loadDynamicRecordsWithOutcome(
        services = services,
        session = session,
        descriptor = descriptor,
        actionId = action.id,
        values = boundValues,
        runtimeContext = boundValues,
        cachePolicy = cachePolicy,
    )
    return DynamicFormRelationLoadResult(
        records = outcome.records,
        pagination = descriptor.resolvedDynamicPaginationSpec(action.id, boundValues)
            .takeIf { outcome.partialFailureMessage == null },
        partialFailureMessage = outcome.partialFailureMessage,
    )
}

internal fun dynamicFormRelationRuntimeValues(
    request: DynamicFormRelationLoadRequest,
    availableValues: Map<String, String>,
    additionalValues: Map<String, String> = emptyMap(),
): Map<String, String> =
    availableValues + request.cacheKey.bindingValues + additionalValues

internal fun dynamicFormRelationLoadRequests(
    schema: NativeAppSchema,
    formView: ViewSpec,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadRequest> = dynamicRelationLoadRequests(
    schema = schema,
    plans = dynamicFormRelationLoadPlans(
        schema = schema,
        formView = formView,
        availableValues = availableValues,
    ),
    availableValues = availableValues,
)

internal fun dynamicCollectionBatchRelationLoadRequests(
    schema: NativeAppSchema,
    childResourceId: String,
    relatedFieldIds: Set<String>,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadRequest> = dynamicRelationLoadRequests(
    schema = schema,
    plans = dynamicRelationLoadPlans(
        schema = schema,
        childResourceId = childResourceId,
        editableFieldIds = relatedFieldIds,
        availableValues = availableValues,
    ),
    availableValues = availableValues,
)

private fun dynamicRelationLoadRequests(
    schema: NativeAppSchema,
    plans: List<DynamicFormRelationLoadPlan>,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadRequest> = plans.mapNotNull { plan ->
    val action = schema.action(plan.actionId) ?: return@mapNotNull null
    val bindingNames = (
        action.binding.pathParameterNames +
            action.binding.requiredPathParameterNames +
            action.binding.queryParameterNames +
            action.binding.requiredQueryParameterNames
        ).distinct()
    if (bindingNames.size > MAX_DYNAMIC_FORM_RELATION_BINDINGS) return@mapNotNull null
    val bindingValues = dynamicFormRelationBindingValues(action, availableValues)
    DynamicFormRelationLoadRequest(
        plan = plan,
        cacheKey = DynamicFormRelationCacheKey(
            resourceId = plan.resourceId,
            actionId = plan.actionId,
            bindingValues = bindingValues,
        ),
    )
}

internal fun <K, V> Map<K, V>.putBounded(key: K, value: V): Map<K, V> =
    ((this - key) + (key to value))
        .entries
        .toList()
        .takeLast(MAX_DYNAMIC_FORM_RELATION_CACHE_SCOPES)
        .associate(Map.Entry<K, V>::toPair)

private const val MAX_DYNAMIC_FORM_RELATION_BINDINGS = 32
private const val MAX_DYNAMIC_FORM_RELATION_CACHE_SCOPES = 16
internal const val MAX_DYNAMIC_FORM_RELATION_RECORDS = 500
