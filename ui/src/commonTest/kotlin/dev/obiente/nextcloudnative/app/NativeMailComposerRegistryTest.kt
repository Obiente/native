package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailComposeDraft
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailComposeMode
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailComposerState
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailRecipientEncoding
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailRecipientField
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailSendCapability
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailSendPhase
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailSender
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailStoredComposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Composer drafts persist through the platform's account-scoped, encrypted text draft storage. */
class NativeMailComposerRegistryTest {
    @Test
    fun `a draft is saved restored and cleared through the text draft store`() = runBlocking<Unit> {
        val text = MemoryTextDraftStore()
        val store = TextDraftBackedNativeMailDraftStore { text }
        val stored = NativeMailStoredComposer(draft(), unknownAttempt = draft())
        store.save(stored)
        assertEquals("", text.value?.originalText)
        assertNull(text.value?.etag)
        assertEquals(stored, store.load())
        store.clear()
        assertNull(text.value)
        assertNull(store.load())
    }

    @Test
    fun `an unreadable saved record is reported instead of restored`() = runBlocking<Unit> {
        val text = MemoryTextDraftStore(TextEditorDraft(originalText = "", text = "not a draft", etag = null))
        assertFailsWith<IllegalStateException> { TextDraftBackedNativeMailDraftStore { text }.load() }
        val state = NativeMailComposerState(null, TextDraftBackedNativeMailDraftStore { text })
        state.restore()
        assertNull(state.draft)
        assertTrue(state.storageProblem.orEmpty().startsWith("A draft saved on this device could not be read."))
    }

    @Test
    fun `a draft survives a new process and is removed after a confirmed send`() = runBlocking<Unit> {
        val text = MemoryTextDraftStore()
        val owner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        NativeMailComposerState(owner, TextDraftBackedNativeMailDraftStore { text }, saveDelayMillis = 0).open(draft())
        owner.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertNotNull(text.value)

        val restarted = NativeMailComposerState(owner, TextDraftBackedNativeMailDraftStore { text }, saveDelayMillis = 0)
        restarted.restore()
        assertEquals(draft(), restarted.draft)
        assertEquals(NativeMailSendPhase.Editing, restarted.phase)
        restarted.send(capability(), NativeActionExecutor { NativeActionExecutionResult.Success() })
        assertNull(restarted.draft)
        assertNull(text.value, "a confirmed send leaves no draft on the device")
        owner.cancel()
    }

    @Test
    fun `each account keeps one composer owner until it is retired`() {
        val session = NextcloudSession("https://cloud.example.test", "composer-registry-user", "synthetic-app-password")
        val services = object : TextEditorDraftPlatformServices {}
        val first = assertNotNull(NativeMailComposerRegistry.retention(services, session))
        assertSame(first, NativeMailComposerRegistry.retention(services, session.copy(appPassword = "rotated-synthetic")))
        val key = session.accountId.storageKey
        AccountPrivateMemoryLifecycle.retireAccount(key)
        try {
            assertNull(NativeMailComposerRegistry.retention(services, session), "a retiring account gets no composer")
        } finally {
            AccountPrivateMemoryLifecycle.activateAccount(key)
        }
        assertNotSame(first, NativeMailComposerRegistry.retention(services, session))
        NativeMailComposerRegistry.purgeRetiredAccount(key)
    }

    private class MemoryTextDraftStore(var value: TextEditorDraft? = null) : TextEditorDraftStore {
        override suspend fun load(): TextEditorDraft? = value

        override suspend fun save(draft: TextEditorDraft) {
            value = draft
        }

        override suspend fun remove() {
            value = null
        }
    }

    private fun draft() = NativeMailComposeDraft(
        mode = NativeMailComposeMode.New,
        senderId = "7",
        to = "grace@example.test",
        subject = "Hello",
        body = "Text",
    )

    private fun capability() = NativeMailSendCapability(
        action = ActionSpec(
            id = "send",
            label = "Send",
            resourceId = "message",
            binding = ApiBinding(method = HttpMethod.POST, path = "/apps/synthetic/send", operationId = "send"),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        ),
        senders = listOf(NativeMailSender(id = "7", email = "ada@example.test", label = "Ada")),
        to = NativeMailRecipientField("to", NativeMailRecipientEncoding.StringList),
        cc = null,
        bcc = null,
        subjectField = "subject",
        bodyField = "body",
        htmlFlagField = null,
        senderIdentityFields = listOf("accountId"),
        senderAddressField = null,
    )
}
