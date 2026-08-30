package dev.obiente.nextcloudnative.nativeui.model

internal fun actionEffect(
    method: HttpMethod,
    path: String,
    operationId: String,
    label: String,
    collection: Boolean,
    fileUpload: Boolean,
): ActionEffect {
    if (method == HttpMethod.GET) {
        return when {
            path.endsWithIdentityPlaceholder() -> ActionEffect.read
            collection -> ActionEffect.list
            operationId.looksLikeCollectionReadOperation() -> ActionEffect.list
            path.contains('{') -> ActionEffect.read
            else -> ActionEffect.read
        }
    }

    val words = actionSemanticWords(path, operationId, label)
    return when {
        "empty" in words -> ActionEffect.empty
        words.any { it in PERMANENT_DELETE_WORDS } -> ActionEffect.permanentDelete
        words.any { it in REORDER_WORDS } -> ActionEffect.reorder
        "batch" in words -> ActionEffect.batch
        "restore" in words -> ActionEffect.restore
        "unarchive" in words -> ActionEffect.unarchive
        "archive" in words -> ActionEffect.archive
        words.any { it in COMPLETION_TRANSITION_WORDS } ||
            ("toggle" in words && words.any { it in COMPLETION_STATE_WORDS }) -> ActionEffect.toggle
        "move" in words -> ActionEffect.move
        "copy" in words || "duplicate" in words -> ActionEffect.copy
        fileUpload -> ActionEffect.upload
        "assign" in words || "replace" in words -> ActionEffect.assign
        "leave" in words -> ActionEffect.leave
        "clear" in words -> ActionEffect.clear
        method == HttpMethod.DELETE -> ActionEffect.delete
        words.any { it in CREATE_WORDS } -> ActionEffect.create
        method == HttpMethod.PUT || method == HttpMethod.PATCH -> ActionEffect.update
        else -> ActionEffect.execute
    }
}

internal fun actionRisk(
    method: HttpMethod,
    effect: ActionEffect,
    path: String,
    operationId: String,
    label: String,
): ActionRisk {
    if (method == HttpMethod.GET) return ActionRisk.readOnly
    if (
        effect in setOf(
            ActionEffect.delete,
            ActionEffect.permanentDelete,
            ActionEffect.empty,
            ActionEffect.leave,
            ActionEffect.clear,
        )
    ) {
        return ActionRisk.destructive
    }
    val words = actionSemanticWords(path, operationId, label)
    return if (words.any { it in DESTRUCTIVE_ACTION_WORDS }) {
        ActionRisk.destructive
    } else {
        ActionRisk.mutating
    }
}

internal fun ActionEffect.toActionIntent(): ActionIntent = when (this) {
    ActionEffect.list -> ActionIntent.list
    ActionEffect.read -> ActionIntent.read
    ActionEffect.create -> ActionIntent.create
    ActionEffect.update,
    ActionEffect.assign,
    -> ActionIntent.update
    ActionEffect.delete,
    ActionEffect.permanentDelete,
    ActionEffect.empty,
    ActionEffect.clear,
    -> ActionIntent.delete
    ActionEffect.unspecified,
    ActionEffect.toggle,
    ActionEffect.archive,
    ActionEffect.unarchive,
    ActionEffect.restore,
    ActionEffect.move,
    ActionEffect.copy,
    ActionEffect.reorder,
    ActionEffect.batch,
    ActionEffect.upload,
    ActionEffect.leave,
    ActionEffect.execute,
    -> ActionIntent.execute
}

internal fun actionSemanticWords(
    path: String,
    operationId: String,
    label: String,
): Set<String> = sequenceOf(path, operationId.humanize(), label)
    .flatMap { value -> value.stableId().split('-').asSequence() }
    .filter(String::isNotBlank)
    .toSet()

private fun String.endsWithIdentityPlaceholder(): Boolean {
    val segment = trimEnd('/').substringAfterLast('/')
    if (!segment.startsWith('{') || !segment.endsWith('}') || segment.length <= 2) return false
    val name = segment.substring(1, segment.lastIndex)
    return name.lowercase() in setOf("id", "uuid", "token") ||
        name.endsWith("Id") ||
        name.endsWith("ID") ||
        name.endsWith("_id") ||
        name.endsWith("-id")
}

private fun String.looksLikeCollectionReadOperation(): Boolean {
    val compact = lowercase().filter(Char::isLetterOrDigit)
    if ("list" in compact || "findall" in compact || "getall" in compact) return true
    val target = compact.substringAfterLast("get", missingDelimiterValue = compact)
    if (target.endsWith("history") || target.endsWith("log") || target.endsWith("feed")) return true
    if (!target.endsWith('s') || target.endsWith("ss")) return false
    return COLLECTION_SINGLETON_SUFFIXES.none(target::endsWith)
}

private val CREATE_WORDS = setOf("add", "create", "invite", "new")
internal val TOGGLE_WORDS = setOf("toggle", "complete", "reopen")
private val COMPLETION_TRANSITION_WORDS = setOf("complete", "reopen")
private val COMPLETION_STATE_WORDS = setOf("checked", "complete", "completed", "completion", "done")
internal val REORDER_WORDS = setOf("reorder", "reposition", "sort")
internal val PERMANENT_DELETE_WORDS = setOf("permanent", "permanently", "purge")
private val DESTRUCTIVE_ACTION_WORDS = setOf(
    "clear", "delete", "destroy", "empty", "permanent", "permanently", "purge", "remove",
)
private val COLLECTION_SINGLETON_SUFFIXES = setOf("capabilities", "preferences", "settings", "status")
