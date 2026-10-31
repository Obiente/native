package dev.obiente.nextcloudnative.nativeui.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** What a container record says the signed-in account may do with the records it contains. */
internal data class NativeWritePermissions(val create: Boolean, val update: Boolean, val delete: Boolean) {
    val any: Boolean get() = create || update || delete

    companion object {
        val All = NativeWritePermissions(create = true, update = true, delete = true)
        val None = NativeWritePermissions(create = false, update = false, delete = false)
    }
}

/**
 * Reads permission evidence from the data a container record exposes, without knowing its app:
 * - an owner field (`owner`, `ownerId`, `ownership`) naming the signed-in account grants every write;
 * - `isShared: false` means the container is the account's own and grants every write;
 * - a permission object (any field whose name contains `permission`) or top-level `can...` flags
 *   grant exactly the operations they declare as `true`;
 * - a shared container without readable flags grants nothing.
 *
 * Returns null when the record states nothing, so callers keep the collection read-only instead
 * of guessing. The server still enforces its own authorization for every request.
 */
internal fun NativeRecord.nativeWritePermissions(currentUserId: String?): NativeWritePermissions? {
    val byKey = values.entries.associateBy { it.key.contractKey() }
    fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key -> byKey[key]?.value?.trim()?.takeIf(String::isNotEmpty) }
    val owner = text("owner", "ownerid", "ownership", "owneruserid")
    if (currentUserId != null && owner != null && owner == currentUserId) return NativeWritePermissions.All
    val shared = text("isshared")?.toBooleanStrictOrNull()
    if (shared == false) return NativeWritePermissions.All
    val declared = declaredPermissionFlags(byKey)
    return declared ?: NativeWritePermissions.None.takeIf { shared == true }
}

private fun declaredPermissionFlags(byKey: Map<String, Map.Entry<String, String?>>): NativeWritePermissions? {
    val flags = linkedMapOf<String, Boolean>()
    byKey.filterKeys { "permission" in it }.values.forEach { entry ->
        when (val parsed = entry.value.contractJsonOrNull()) {
            is JsonObject -> parsed.forEach { (key, value) ->
                (value as? JsonPrimitive)?.booleanOrNull?.let { flags[key.contractKey()] = it }
            }
            // A list of granted operation names, for example ["read", "update"].
            is JsonArray -> parsed.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.contractKey() }
                .forEach { flags[it] = true }
            else -> Unit
        }
    }
    byKey.forEach { (key, entry) ->
        if (key.startsWith("can") && key.length > 3) {
            entry.value?.trim()?.toBooleanStrictOrNull()?.let { flags[key.removePrefix("can")] = it }
        }
    }
    val known = flags.filterKeys { it in CREATE_FLAGS || it in UPDATE_FLAGS || it in DELETE_FLAGS }
    if (known.isEmpty()) return null
    fun granted(names: Set<String>) = names.any { known[it] == true }
    return NativeWritePermissions(
        create = granted(CREATE_FLAGS),
        update = granted(UPDATE_FLAGS),
        delete = granted(DELETE_FLAGS),
    )
}

private val CREATE_FLAGS = setOf("create", "add", "insert", "write", "manage")
private val UPDATE_FLAGS = setOf("update", "edit", "write", "manage")
private val DELETE_FLAGS = setOf("delete", "remove", "manage")
