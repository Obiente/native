package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs

internal fun Map<String, String>.resourceQualifiedValuesForGenericPathIdentity(
    path: String,
    parameterName: String,
): List<String> {
    if (parameterName.nativeCollectionSemanticId() != "id") return emptyList()
    val parentResourceId = path.literalParentResourceOf(parameterName) ?: return emptyList()
    return entries
        .mapNotNull { (name, value) ->
            val semanticName = name.nativeCollectionSemanticId()
            value.takeIf {
                semanticName.length > 2 &&
                    semanticName.endsWith("id") &&
                    semanticName.dropLast(2).sameDynamicResourceAs(parentResourceId)
            }
        }
        .distinct()
}

internal fun ApiBinding.isProvenSingleCollectionParentIdentityAlias(
    parameterName: String,
): Boolean {
    if (!parameterName.endsWith("Id", ignoreCase = true) || parameterName.length <= 2) return false
    val parentResourceId = parameterName.dropLast(2)
    val segments = path.substringBefore('?').split('/').filter(String::isNotBlank)
    val placeholder = "{$parameterName}"
    return segments.indices.any { index ->
        segments[index] == placeholder &&
            index > 0 &&
            segments[index - 1].sameDynamicResourceAs(parentResourceId)
    }
}

internal fun ApiBinding.hasNativeCollectionSelfResourcePathIdentity(
    resource: ResourceSpec,
): Boolean = (pathParameterNames + requiredPathParameterNames)
    .distinct()
    .any { name ->
        if (name.nativeCollectionSemanticId() == "id") {
            path.literalParentResourceOf(name)?.sameDynamicResourceAs(resource.id) == true
        } else {
            name.isSelfResourceIdentityField(resource)
        }
    }

private fun String.literalParentResourceOf(parameterName: String): String? {
    val segments = substringBefore('?').split('/').filter(String::isNotBlank)
    val placeholder = "{$parameterName}"
    val index = segments.indices.singleOrNull { candidate -> segments[candidate] == placeholder }
        ?: return null
    return segments.getOrNull(index - 1)?.takeIf { parent ->
        '{' !in parent && '}' !in parent && parent.isNotBlank()
    }
}
