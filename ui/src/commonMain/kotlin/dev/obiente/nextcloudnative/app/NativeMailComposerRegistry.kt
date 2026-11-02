package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailComposerRetention
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailDraftStore
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailStoredComposer
import dev.obiente.nextcloudnative.nativeui.runtime.decodeNativeMailStoredComposer
import dev.obiente.nextcloudnative.nativeui.runtime.encodeNativeMailStoredComposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-level owner of the semantic mail composers of each signed-in account. A composer's
 * draft and in-flight send outlive every screen and activity; only retiring the account, on
 * sign-out or removal, closes them. Drafts go to the platform's encrypted text draft storage,
 * which binds them to the account and deletes them when the account is removed.
 */
internal object NativeMailComposerRegistry {
    private class Entry(
        var services: TextEditorDraftPlatformServices,
        var session: NextcloudSession,
    ) {
        val retention = NativeMailComposerRetention(CoroutineScope(SupervisorJob() + Dispatchers.Main)) { appId ->
            val path = "$DRAFT_PATH_PREFIX$appId"
            // Without platform draft storage the composer still works, in memory only.
            services.textEditorDraftStore(session, path)?.let {
                TextDraftBackedNativeMailDraftStore { services.textEditorDraftStore(session, path) }
            }
        }
    }

    private val gate = sharedAccountPrivateMemoryGate
    private val entries = mutableMapOf<String, Entry>()

    /** The account's composers, or null while the account is being retired. */
    fun retention(services: TextEditorDraftPlatformServices, session: NextcloudSession): NativeMailComposerRetention? {
        val key = session.accountId.storageKey
        val producer = gate.producer(key) ?: return null
        return gate.read(key, producer, null) {
            val entry = entries.getOrPut(key) { Entry(services, session) }
            // Storage access always uses the latest session of this account identity.
            entry.services = services
            entry.session = session
            entry.retention
        }
    }

    /** Called under the account gate when the account is retired; cancels unfinished sends. */
    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.remove(accountStorageKey)?.retention?.close()
    }
}

/** Stores one composer as one encrypted text draft record. */
internal class TextDraftBackedNativeMailDraftStore(
    private val bind: () -> TextEditorDraftStore?,
) : NativeMailDraftStore {
    override suspend fun load(): NativeMailStoredComposer? {
        val stored = bind()?.load() ?: return null
        return checkNotNull(decodeNativeMailStoredComposer(stored.text)) { "The saved draft is unreadable." }
    }

    override suspend fun save(value: NativeMailStoredComposer) {
        val store = checkNotNull(bind()) { "Draft storage is unavailable." }
        store.save(TextEditorDraft(originalText = "", text = encodeNativeMailStoredComposer(value), etag = null))
    }

    override suspend fun clear() {
        bind()?.remove()
    }
}

private const val DRAFT_PATH_PREFIX = "nextcloud-native:semantic-mail-compose:"
