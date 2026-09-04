package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences

/**
 * Stores account metadata without exceeding the per-value limit of [Preferences].
 *
 * Small registries retain the original single-value format. Larger registries are written to an
 * inactive chunk generation before one pointer switches readers to the complete new value.
 */
internal class DesktopAccountRegistryPreferenceStore(
    private val preferences: Preferences,
    private val flushPreferences: () -> Unit = preferences::flush,
) {
    @Synchronized
    fun read(): String? {
        val generation = preferences.get(KEY_ACTIVE_GENERATION, null)
            ?: return preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null)
        if (generation != GENERATION_A && generation != GENERATION_B) return MALFORMED_REGISTRY
        val chunkCount = preferences.getInt(countKey(generation), -1)
        if (chunkCount !in 1..MAX_CHUNKS) return MALFORMED_REGISTRY
        val encoded = buildString {
            repeat(chunkCount) { index ->
                val chunk = preferences.get(chunkKey(generation, index), null)
                    ?: return MALFORMED_REGISTRY
                if (chunk.length > CHUNK_CHARACTER_LIMIT) return MALFORMED_REGISTRY
                append(chunk)
            }
        }
        return encoded.takeIf { value -> value.encodeToByteArray().size <= MAX_ACCOUNT_REGISTRY_BYTES }
            ?: MALFORMED_REGISTRY
    }

    @Synchronized
    fun write(encoded: String?) {
        if (encoded == null) {
            clear()
        } else if (encoded.length <= Preferences.MAX_VALUE_LENGTH) {
            writeSingleValue(encoded)
        } else {
            writeChunked(encoded)
        }
    }

    private fun writeSingleValue(encoded: String) {
        require(encoded.encodeToByteArray().size <= MAX_ACCOUNT_REGISTRY_BYTES)
        val previousGeneration = preferences.get(KEY_ACTIVE_GENERATION, null)
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, encoded)
        flushPreferences()
        if (previousGeneration == null) return
        preferences.remove(KEY_ACTIVE_GENERATION)
        flushPreferences()
        clearGenerationBestEffort(GENERATION_A)
        clearGenerationBestEffort(GENERATION_B)
    }

    private fun writeChunked(encoded: String) {
        require(encoded.encodeToByteArray().size <= MAX_ACCOUNT_REGISTRY_BYTES)
        val previousGeneration = preferences.get(KEY_ACTIVE_GENERATION, null)
        val targetGeneration = if (previousGeneration == GENERATION_A) GENERATION_B else GENERATION_A
        val chunks = encoded.chunked(CHUNK_CHARACTER_LIMIT)
        require(chunks.size in 1..MAX_CHUNKS)

        clearGeneration(targetGeneration)
        chunks.forEachIndexed { index, chunk ->
            preferences.put(chunkKey(targetGeneration, index), chunk)
        }
        preferences.putInt(countKey(targetGeneration), chunks.size)
        flushPreferences()

        preferences.put(KEY_ACTIVE_GENERATION, targetGeneration)
        preferences.remove(DESKTOP_ACCOUNT_REGISTRY_KEY)
        flushPreferences()

        previousGeneration
            ?.takeIf { generation -> generation != targetGeneration }
            ?.let(::clearGenerationBestEffort)
    }

    private fun clear() {
        val hadActiveGeneration = preferences.get(KEY_ACTIVE_GENERATION, null) != null
        val hadSingleValue = preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null) != null
        if (!hadActiveGeneration && !hadSingleValue) return
        preferences.remove(DESKTOP_ACCOUNT_REGISTRY_KEY)
        preferences.remove(KEY_ACTIVE_GENERATION)
        flushPreferences()
        clearGenerationBestEffort(GENERATION_A)
        clearGenerationBestEffort(GENERATION_B)
    }

    private fun clearGenerationBestEffort(generation: String) {
        runCatching {
            clearGeneration(generation)
            flushPreferences()
        }
    }

    private fun clearGeneration(generation: String) {
        preferences.remove(countKey(generation))
        repeat(MAX_CHUNKS) { index -> preferences.remove(chunkKey(generation, index)) }
    }

    private fun countKey(generation: String) = "$KEY_GENERATION_PREFIX.$generation.count"

    private fun chunkKey(generation: String, index: Int) =
        "$KEY_GENERATION_PREFIX.$generation.${index.toString().padStart(2, '0')}"

    private companion object {
        const val KEY_ACTIVE_GENERATION = "account_registry_v2_active"
        const val KEY_GENERATION_PREFIX = "account_registry_v2"
        const val GENERATION_A = "a"
        const val GENERATION_B = "b"
        const val CHUNK_CHARACTER_LIMIT = 8_000
        const val MAX_CHUNKS = (MAX_ACCOUNT_REGISTRY_BYTES / CHUNK_CHARACTER_LIMIT) + 1
        const val MALFORMED_REGISTRY = "{malformed-chunked-account-registry"
    }
}
