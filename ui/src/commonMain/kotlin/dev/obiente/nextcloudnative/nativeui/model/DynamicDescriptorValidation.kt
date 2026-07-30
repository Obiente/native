package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.template.scanBracedTemplate

fun DynamicAppDescriptor.validationErrors(): List<String> = buildList {
    if (descriptorVersion != DYNAMIC_APP_DESCRIPTOR_VERSION) {
        add("Unsupported descriptor version: $descriptorVersion")
    }
    if (!endpointPolicy.serverOrigin.isSafeHttpOrigin()) {
        add("Invalid server origin")
    }
    if (endpointPolicy.approvedApiPrefixes.isEmpty()) {
        add("At least one approved API prefix is required")
    }
    endpointPolicy.approvedApiPrefixes.forEach { prefix ->
        if (!prefix.isSafeRelativePath()) add("Invalid approved API prefix: $prefix")
    }

    addDuplicateErrors("resource", resources.map(DynamicResource::id))
    addDuplicateErrors("layout", layouts.map(DynamicLayout::id))
    addDuplicateErrors("link", links.map(DynamicLink::id))
    addDuplicateErrors("form", forms.map(DynamicForm::id))
    addDuplicateErrors("action", actions.map(DynamicAction::id))
    addDuplicateErrors("permission", permissions.map(PermissionSpec::id))

    val resourcesById = resources.associateBy(DynamicResource::id)
    val actionsById = actions.associateBy(DynamicAction::id)
    val permissionIds = permissions.mapTo(mutableSetOf(), PermissionSpec::id)
    val capabilityIds = capabilities.mapTo(mutableSetOf(), CapabilityFact::id)
    resources.forEach { resource ->
        addDuplicateErrors("field in ${resource.id}", resource.fields.map(DynamicField::id))
    }
    layouts.forEach { layout ->
        val resource = resourcesById[layout.resourceId]
        if (resource == null) {
            add("Missing resource reference: ${layout.resourceId}")
        } else {
            val fieldIds = resource.fields.mapTo(mutableSetOf(), DynamicField::id)
            layout.fields.filter { it.fieldId !in fieldIds }.forEach {
                add("Missing field reference: ${resource.id}.${it.fieldId}")
            }
        }
        layout.sourceActionId?.takeIf { it !in actionsById }?.let {
            add("Missing action reference: $it")
        }
    }
    links.forEach { link ->
        val resource = resourcesById[link.resourceId]
        if (resource == null) {
            add("Missing resource reference: ${link.resourceId}")
        } else if (
            resource.fields.none { it.id == link.sourceFieldId } &&
            !(resource.collection && link.sourceFieldId == "id")
        ) {
            add("Missing field reference: ${resource.id}.${link.sourceFieldId}")
        }
        if (link.target is DynamicLinkTarget.Action && link.target.actionId !in actionsById) {
            add("Missing action reference: ${link.target.actionId}")
        }
    }
    forms.forEach { form ->
        val action = actionsById[form.actionId]
        if (action == null) add("Missing action reference: ${form.actionId}")
        else if (action.resourceId != form.resourceId) add("Form action resource does not match: ${form.id}")
        else if (action.binding.method == HttpMethod.GET) add("Form points to read action: ${form.id}")
        if (form.resourceId !in resourcesById) add("Missing resource reference: ${form.resourceId}")
    }
    resources.forEach { resource ->
        resource.capabilityIds.filter { it !in capabilityIds }.forEach {
            add("Missing capability reference: $it")
        }
        resource.permissionIds.filter { it !in permissionIds }.forEach {
            add("Missing permission reference: $it")
        }
    }
    actions.forEach { action ->
        val resource = resourcesById[action.resourceId]
        if (resource == null) {
            add("Missing resource reference: ${action.resourceId}")
        } else {
            val resourceFieldIds = resource.fields.mapTo(mutableSetOf(), DynamicField::id)
            action.responseFieldIds.filter { it !in resourceFieldIds }.forEach {
                add("Missing response field reference: ${action.resourceId}.$it")
            }
        }
        if (action.binding.method != HttpMethod.GET && action.responseFieldIds.isNotEmpty()) {
            add("Mutation action declares read-response fields: ${action.id}")
        }
        if (!action.binding.path.isSafeRelativePath()) {
            add("Invalid action endpoint: ${action.binding.path}")
        } else if (endpointPolicy.approvedApiPrefixes.none { action.binding.path.matchesPrefix(it) }) {
            add("Unapproved action endpoint: ${action.binding.path}")
        }
        action.permissionIds.filter { it !in permissionIds }.forEach {
            add("Missing permission reference: $it")
        }
        action.capabilityIds.filter { it !in capabilityIds }.forEach {
            add("Missing capability reference: $it")
        }
        action.fallbackActionIds.forEach { fallbackId ->
            val fallback = actionsById[fallbackId]
            when {
                fallback == null -> add("Missing fallback action reference: $fallbackId")
                action.binding.method != HttpMethod.GET || fallback.binding.method != HttpMethod.GET ->
                    add("Read fallback must connect GET actions: ${action.id} -> $fallbackId")
                !fallback.fallbackOnly -> add("Fallback action is not hidden: $fallbackId")
            }
        }
        if (action.fallbackOnly && action.binding.method != HttpMethod.GET) {
            add("Hidden fallback action is not read-only: ${action.id}")
        }
        val placeholders = action.binding.path.pathPlaceholders()
        val parameters = action.binding.pathParameters.mapTo(mutableSetOf(), HttpParameter::name)
        if (placeholders != parameters) add("Path parameters do not match placeholders: ${action.id}")
        if (action.binding.method != HttpMethod.GET && action.provenance.none {
                it.kind == ProvenanceKind.advertisedOpenApi ||
                    it.kind == ProvenanceKind.verifiedAdapter ||
                    it.kind == ProvenanceKind.verifiedAppPackage ||
                    it.kind == ProvenanceKind.appStoreLinkedSourceTag
            }
        ) {
            add("Mutating action lacks trusted provenance: ${action.id}")
        }
    }
}

fun DynamicAppDescriptor.requireValid(): DynamicAppDescriptor {
    val errors = validationErrors()
    require(errors.isEmpty()) { errors.joinToString(separator = "; ") }
    return this
}

private fun MutableList<String>.addDuplicateErrors(kind: String, ids: List<String>) {
    ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
        add("Duplicate $kind id: $it")
    }
}

internal fun String.isSafeHttpOrigin(): Boolean {
    val normalized = trimEnd('/')
    if (!(normalized.startsWith("https://") || normalized.startsWith("http://"))) return false
    val authority = normalized.substringAfter("://")
    return authority.isNotBlank() && authority.none {
        it == '/' || it == '?' || it == '#' || it == '\\' || it == '@' || it.isWhitespace()
    }
}

internal fun String.isSafeRelativePath(): Boolean =
    startsWith('/') &&
        !startsWith("//") &&
        none { it == '\\' || it == '?' || it == '#' } &&
        !lowercase().contains("%2f") &&
        !lowercase().contains("%5c") &&
        !lowercase().contains("%25") &&
        split('/').none { segment ->
            segment.replace("%2e", ".", ignoreCase = true) == "." ||
                segment.replace("%2e", ".", ignoreCase = true) == ".."
        }

internal fun String.matchesPrefix(prefix: String): Boolean {
    val normalized = prefix.trimEnd('/')
    return this == normalized || startsWith("$normalized/")
}

internal fun String.pathPlaceholders(): Set<String> = scanBracedTemplate().tokens
    .mapTo(linkedSetOf()) { token -> token.name }
