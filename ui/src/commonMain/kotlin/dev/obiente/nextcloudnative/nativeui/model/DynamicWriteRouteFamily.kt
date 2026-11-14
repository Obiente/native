package dev.obiente.nextcloudnative.nativeui.model

/*
 * Route families. A write and a read belong to one family when the read addresses the write's
 * target by route position: the same route, the item route below a terminal command segment
 * (`/items/{itemId}/read` -> `/items/{itemId}`), or the collection the target is exactly one
 * placeholder below (`/items/{itemId}[/command]` -> `/items`). Placeholders match by position, not
 * by name, so a renamed placeholder never changes which value binds where.
 */

enum class DynamicRouteFamilyRelation {
    /** The read uses exactly the write's route. */
    sameRoute,

    /** The read is the item route the write's terminal command segment acts on. */
    itemCommand,

    /** The read is the collection; the write's target is one placeholder below it. */
    collectionItem,
}

/** How [readPath] relates to [writePath], or null when it is not in the write's family. */
fun dynamicRouteFamilyRelation(writePath: String, readPath: String): DynamicRouteFamilyRelation? {
    val write = writePath.familySegments()
    val read = readPath.familySegments()
    if (write.isEmpty() || read.isEmpty()) return null
    if (write.sameShapeAs(read)) return DynamicRouteFamilyRelation.sameRoute
    val withoutCommand = write.takeIf { it.last().isFamilyLiteral() }?.dropLast(1)
    if (withoutCommand != null && read.last().isFamilyPlaceholder() && withoutCommand.sameShapeAs(read)) {
        return DynamicRouteFamilyRelation.itemCommand
    }
    val item = listOfNotNull(write, withoutCommand).firstOrNull { candidate -> candidate.last().isFamilyPlaceholder() }
        ?: return null
    return DynamicRouteFamilyRelation.collectionItem.takeIf {
        read.last().isFamilyLiteral() && item.dropLast(1).sameShapeAs(read)
    }
}

/**
 * The single verified read in [write]'s route family among [candidates], preferring the target's
 * own item read over its collection. Only reads declared by the app's own contract (E2 or E3)
 * qualify; two equally close reads are ambiguous and nothing is chosen.
 */
internal fun routeFamilyRead(write: DynamicAction, candidates: Collection<DynamicAction>): DynamicAction? {
    if (write.binding.method == HttpMethod.GET) return null
    val related = candidates.mapNotNull { read ->
        if (!read.isVerifiedFamilyRead()) return@mapNotNull null
        dynamicRouteFamilyRelation(write.binding.path, read.binding.path)?.let { relation -> relation to read }
    }
    listOf(
        DynamicRouteFamilyRelation.sameRoute,
        DynamicRouteFamilyRelation.itemCommand,
        DynamicRouteFamilyRelation.collectionItem,
    ).forEach { relation ->
        val matches = related.filter { it.first == relation }
        if (matches.isNotEmpty()) return matches.singleOrNull()?.second
    }
    return null
}

/** A declared, visible, body-free GET that needs no additional query input. */
internal fun DynamicAction.isVerifiedFamilyRead(): Boolean =
    binding.method == HttpMethod.GET &&
        binding.body == null &&
        binding.queryParameters.none(HttpParameter::required) &&
        intent in setOf(ActionIntent.read, ActionIntent.list) &&
        risk == ActionRisk.readOnly &&
        !fallbackOnly &&
        evidenceTier?.declaresOperation == true

/**
 * The declared boolean-like field of [read] that states whether the current actor may share.
 * Names are compared without case or punctuation, so `canShare` and `can_share` are one field.
 */
internal fun sharePermissionField(read: DynamicAction): String? =
    read.responseFieldIds.filter { field -> field.lowercase().filter(Char::isLetterOrDigit) in SHARE_PERMISSION_FIELDS }
        .singleOrNull()

/** True when this names the standard Idempotency-Key request header. */
fun String.isIdempotencyKeyHeader(): Boolean = equals(IDEMPOTENCY_KEY_HEADER, ignoreCase = true)

const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"

private val SHARE_PERMISSION_FIELDS = setOf(
    "canshare",
    "mayshare",
    "shareallowed",
    "sharingallowed",
    "permissionshare",
    "permissionsshare",
    "sharepermission",
)

private fun String.familySegments(): List<String> = substringBefore('?').split('/').filter(String::isNotEmpty)

private fun String.isFamilyPlaceholder(): Boolean = length >= 3 && startsWith('{') && endsWith('}')

private fun String.isFamilyLiteral(): Boolean = !isFamilyPlaceholder()

private fun List<String>.sameShapeAs(other: List<String>): Boolean =
    size == other.size && zip(other).all { (left, right) ->
        if (left.isFamilyPlaceholder()) right.isFamilyPlaceholder() else left == right
    }

/**
 * Completes the write policy of every compiled action once all reads are known: the route-family
 * read used for identity binding and read-back, and for sharing, the declared field proving that
 * the actor may share. A sharing operation reads back through the read that declares that field,
 * and always asks for a separate confirmation.
 */
internal fun completeDynamicWritePolicies(actions: Map<String, DynamicAction>): Map<String, DynamicAction> {
    val reads = actions.values.filter(DynamicAction::isVerifiedFamilyRead)
    return actions.mapValues { (_, action) ->
        if (action.binding.method == HttpMethod.GET) return@mapValues action
        if (action.operationRiskClass != DynamicOperationRiskClass.sharing) {
            return@mapValues action.copy(readBackActionId = routeFamilyRead(action, reads)?.id)
        }
        val permissionRead = reads.filter { read ->
            dynamicRouteFamilyRelation(action.binding.path, read.binding.path) != null && sharePermissionField(read) != null
        }.singleOrNull()
        action.copy(
            readBackActionId = permissionRead?.id ?: routeFamilyRead(action, reads)?.id,
            sharePermissionFieldId = permissionRead?.let(::sharePermissionField),
            requiresConfirmation = true,
        )
    }
}
