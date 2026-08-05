package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.presentationValue
import dev.obiente.nextcloudnative.nativeui.runtime.safeActionBindingValues

/**
 * One fail-closed, authenticated request for a compiler-proven record image preview.
 *
 * The request remains separate from decoding so platform image loaders can enforce their normal
 * sampled-decode and memory-cache limits. [expectedContentTypes] describes contract evidence only;
 * callers must still require an actual image media type before decoding any bytes.
 */
internal data class NativeRecordImageRequest(
    val request: NextcloudApiRequest,
    val cacheKey: String,
    val contentDescription: String,
    val expectedContentTypes: List<String>,
)

/**
 * Resolves a nested binary record preview exclusively from a trusted compiled capability.
 *
 * Route identities come from the record and its authoritative binding context. Ambient runtime
 * values may confirm those identities, but conflicts, missing parameters, ambiguous provenance,
 * untrusted acquisition, or a descriptor/schema mismatch all leave the ordinary semantic icon in
 * place without issuing a request.
 */
internal fun nativeRecordImageRequest(
    discovery: DynamicDescriptorDiscovery,
    resource: ResourceSpec,
    record: NativeRecord,
    runtimeContext: Map<String, String> = emptyMap(),
): NativeRecordImageRequest? {
    if (!discovery.acquisition.usesAppStoreContract()) return null
    val descriptorResource = discovery.descriptor.resources.singleOrNull { candidate ->
        candidate.id == resource.id
    } ?: return null
    val descriptorPreview = descriptorResource.recordImagePreview ?: return null
    val nativePreview = resource.recordImagePreview ?: return null
    if (
        nativePreview.actionId != descriptorPreview.actionId ||
        nativePreview.declaredContentTypes != descriptorPreview.declaredContentTypes
    ) {
        return null
    }
    val action = discovery.descriptor.actions.singleOrNull { candidate ->
        candidate.id == descriptorPreview.actionId
    }?.takeIf { candidate -> candidate.isTrustedRecordImageAction(resource.id) } ?: return null
    val recordValues = record.safeActionBindingValues() ?: return null
    val contextualValues = runtimeContext.filterKeys { key ->
        key.lowercase().filter(Char::isLetterOrDigit) != "id"
    }
    val bindingValues = safeActionBindingValues(recordValues, contextualValues) ?: return null
    val request = runCatching {
        buildDynamicApiRequest(
            descriptor = discovery.descriptor,
            action = action,
            values = bindingValues,
            runtimeContext = runtimeContext,
        )
    }.getOrNull()?.copy(
        maximumResponseBytes = MAX_DYNAMIC_RECORD_IMAGE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.PreferCache,
    ) ?: return null
    if (request.method != NextcloudApiMethod.GET || request.body != null || request.multipartBody != null) return null
    return NativeRecordImageRequest(
        request = request,
        cacheKey = stableNativeRecordImageCacheKey(
            appId = discovery.descriptor.app.id,
            actionId = action.id,
            recordId = record.id,
            recordGeneration = nativeRecordImageGeneration(record),
            request = request,
        ),
        contentDescription = resource.recordImageContentDescription(record),
        expectedContentTypes = descriptorPreview.declaredContentTypes,
    )
}

private fun DynamicAction.isTrustedRecordImageAction(resourceId: String): Boolean =
    this.resourceId == resourceId &&
        binding.method == HttpMethod.GET &&
        intent == ActionIntent.read &&
        risk == ActionRisk.readOnly &&
        binding.pathParameters.isNotEmpty() &&
        binding.body == null &&
        binding.queryParameters.none { parameter -> parameter.required } &&
        !fallbackOnly &&
        provenance.any { evidence ->
            evidence.kind == ProvenanceKind.verifiedAppPackage ||
                evidence.kind == ProvenanceKind.appStoreLinkedSourceTag
        }

private fun ResourceSpec.recordImageContentDescription(record: NativeRecord): String {
    val preferred = fields
        .mapNotNull { field ->
            val priority = when (field.id.lowercase().filter(Char::isLetterOrDigit)) {
                "caption" -> 0
                "title" -> 1
                "name" -> 2
                "label" -> 3
                "description" -> 4
                else -> return@mapNotNull null
            }
            field to priority
        }
        .sortedBy { (_, priority) -> priority }
        .firstNotNullOfOrNull { (field, _) ->
            record.presentationValue(field.id)?.safeRecordImageDescription()
        }
    return preferred ?: name.safeRecordImageDescription() ?: "Image preview"
}

private fun String.safeRecordImageDescription(): String? = trim()
    .filterNot(Char::isISOControl)
    .take(MAX_DYNAMIC_RECORD_IMAGE_DESCRIPTION_CHARS)
    .takeIf(String::isNotBlank)

private fun stableNativeRecordImageCacheKey(
    appId: String,
    actionId: String,
    recordId: String,
    recordGeneration: String,
    request: NextcloudApiRequest,
): String = listOf(appId, actionId, recordId, recordGeneration)
    .joinToString(":") { value ->
        value.filter { character -> character.isLetterOrDigit() || character in setOf('-', '_', '.') }
            .take(MAX_DYNAMIC_RECORD_IMAGE_CACHE_PART_CHARS)
            .ifBlank { "unknown" }
    } + ":" + request.dynamicReadCacheIdentity()

internal fun nativeRecordImageGeneration(record: NativeRecord): String {
    val explicitGeneration = record.values.entries.firstNotNullOfOrNull { (key, value) ->
        value?.takeIf {
            key.lowercase().filter(Char::isLetterOrDigit) in NATIVE_RECORD_IMAGE_GENERATION_FIELDS
        }
    }
    if (explicitGeneration != null) return explicitGeneration
    val authoritativeFingerprint = record.values.entries
        .sortedBy(Map.Entry<String, String?>::key)
        .joinToString("\u001f") { (key, value) -> "$key\u001e${value.orEmpty()}" }
        .hashCode()
        .toUInt()
        .toString(16)
    return "record-$authoritativeFingerprint"
}

internal fun String?.isSupportedDynamicRecordImageContentType(): Boolean =
    this?.substringBefore(';')?.trim()?.lowercase()?.let { type ->
        type.startsWith("image/") && type.length <= MAX_DYNAMIC_RECORD_IMAGE_CONTENT_TYPE_CHARS
    } == true

internal fun NextcloudApiResponse.acceptedDynamicRecordImageBytes(): ByteArray? =
    body.takeIf {
        status in 200..299 &&
            contentType.isSupportedDynamicRecordImageContentType() &&
            it.isNotEmpty() &&
            it.size.toLong() <= MAX_DYNAMIC_RECORD_IMAGE_BYTES
    }

internal const val MAX_DYNAMIC_RECORD_IMAGE_BYTES: Long = DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES
private const val MAX_DYNAMIC_RECORD_IMAGE_DESCRIPTION_CHARS = 512
private const val MAX_DYNAMIC_RECORD_IMAGE_CACHE_PART_CHARS = 128
private const val MAX_DYNAMIC_RECORD_IMAGE_CONTENT_TYPE_CHARS = 128
private val NATIVE_RECORD_IMAGE_GENERATION_FIELDS = setOf(
    "etag",
    "generation",
    "version",
    "mtime",
    "modified",
    "lastmodified",
    "updatedat",
)
