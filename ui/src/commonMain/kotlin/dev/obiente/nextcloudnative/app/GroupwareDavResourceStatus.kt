package dev.obiente.nextcloudnative.app

/** A failed property is not proof that its containing DAV resource disappeared. */
internal fun String.groupwareDavResourceStatus(properties: List<String>): Int? {
    val statuses = properties.fold(this) { remainder, property -> remainder.replace(property, "") }
        .xmlElements("status")
    require(statuses.size <= 1 && (statuses.isEmpty() || properties.isEmpty())) {
        "The DAV response contained conflicting object statuses."
    }
    return statuses.singleOrNull()?.let { element ->
        requireNotNull(element.xmlText("status")?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()) {
            "The DAV response contained a malformed object status."
        }
    }
}
