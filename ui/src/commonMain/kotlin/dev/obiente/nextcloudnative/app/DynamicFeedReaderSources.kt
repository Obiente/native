package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.model.feedReaderSourceViews
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * Loads a feed reader's subscription and folder lists beside its articles, through the same bounded
 * relation cache that forms use. Only parameter-free verified collection reads qualify, so this
 * never supplies a request value the contract does not already resolve.
 */
internal fun dynamicFeedReaderSourceLoadRequests(
    schema: NativeAppSchema,
    view: ViewSpec,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadRequest> = schema.feedReaderSourceViews(view).map { (sourceView, action) ->
    DynamicFormRelationLoadRequest(
        plan = DynamicFormRelationLoadPlan(resourceId = sourceView.resourceId, actionId = action.id),
        cacheKey = DynamicFormRelationCacheKey(
            resourceId = sourceView.resourceId,
            actionId = action.id,
            bindingValues = dynamicFormRelationBindingValues(action, availableValues),
        ),
    )
}

/**
 * Loads [pending] feed source reads concurrently. A failed read yields null so the reader only
 * loses its sidebar; cancellation is rethrown. Callers apply the outcomes to the cache that is
 * current when the loads finish, so concurrent relation loads are not overwritten.
 */
internal suspend fun loadDynamicFeedReaderSources(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    pending: List<DynamicFormRelationLoadRequest>,
    values: Map<String, String>,
    cachePolicy: NextcloudApiCachePolicy,
): List<Pair<DynamicFormRelationLoadRequest, DynamicFormRelationLoadResult?>> {
    if (pending.isEmpty()) return emptyList()
    return coroutineScope {
        pending.map { request ->
            async {
                request to try {
                    loadInitialDynamicFormRelationRecords(services, session, descriptor, request, values, cachePolicy)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll()
    }
}

internal fun DynamicFormRelationCacheState.withDynamicRelationOutcomes(
    outcomes: List<Pair<DynamicFormRelationLoadRequest, DynamicFormRelationLoadResult?>>,
): DynamicFormRelationCacheState = outcomes.fold(this) { cache, (request, result) ->
    if (result == null) cache.loadFailed(request) else cache.loadSucceeded(request, result.records, result.pagination)
}
