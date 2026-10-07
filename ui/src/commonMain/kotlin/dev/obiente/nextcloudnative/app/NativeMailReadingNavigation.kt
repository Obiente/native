package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.hasNativeMailWorkspaceSemantics

/** Presentation only: technical reads remain in the verified descriptor, outside reading sections. */
internal fun nativeMailReadingDestinations(
    descriptor: DynamicAppDescriptor,
    destinations: List<Pair<DynamicNavigationDestination, ViewSpec>>,
): List<Pair<DynamicNavigationDestination, ViewSpec>> {
    if (!descriptor.hasNativeMailWorkspaceSemantics()) return destinations
    return destinations.filter { (destination, _) ->
        nativeMailReadingSection(descriptor, destination) !in setOf("dkim", "raw", "source", "smartreply", "itineraries")
    }.sortedBy { (destination, _) ->
        when (nativeMailReadingSection(descriptor, destination)) { "body" -> 0; "thread" -> 1; else -> 2 }
    }.map { (destination, view) ->
        when (nativeMailReadingSection(descriptor, destination)) {
            "body" -> destination.copy(label = "Message") to view.copy(title = "Message")
            "thread" -> destination.copy(label = "Conversation") to view.copy(title = "Conversation")
            else -> destination to view
        }
    }
}

private fun nativeMailReadingSection(descriptor: DynamicAppDescriptor, destination: DynamicNavigationDestination): String? {
        if (!descriptor.hasNativeMailWorkspaceSemantics()) return null
        val action = descriptor.actions.singleOrNull { it.id == destination.actionId } ?: return null
        if (action.binding.method != HttpMethod.GET || action.risk != ActionRisk.readOnly ||
            action.confidence !in setOf(Confidence.high, Confidence.verified) ||
            !action.binding.path.isApproved(descriptor.endpointPolicy) ||
            action.provenance.none { it.kind in setOf(ProvenanceKind.advertisedOpenApi,
                ProvenanceKind.verifiedAppPackage, ProvenanceKind.verifiedAdapter) }) return null
        val parts = action.binding.path.split('/').filter(String::isNotBlank)
        val parentIndex = parts.indexOfLast { it in setOf("message", "messages") }
        if (parentIndex < 0 || parentIndex + 2 != parts.lastIndex) return null
        val parameter = action.binding.pathParameters.singleOrNull() ?: return null
        if (!parameter.required || parts[parentIndex + 1] != "{${parameter.name}}") return null
        return parts.last()
    }

internal fun nativeMailReadingOrder(descriptor: DynamicAppDescriptor, destination: DynamicNavigationDestination): Int? =
    when (nativeMailReadingSection(descriptor, destination)) { "body" -> 0; "thread" -> 1; else -> null }

internal fun nativeMailReadingSupportingText(descriptor: DynamicAppDescriptor, destination: DynamicNavigationDestination): String? =
    when (nativeMailReadingSection(descriptor, destination)) {
        "body" -> "Read the message and attachment details"
        "thread" -> "Read the messages in this conversation"
        else -> null
    }