package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicForm
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicResource
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMailWorkspaceTest {
    @Test
    fun `mail paging starts within the shared prefetch window`() {
        assertFalse(nativeMailShouldLoadMore(lastVisibleIndex = -1, totalItems = 0))
        assertFalse(nativeMailShouldLoadMore(lastVisibleIndex = 6, totalItems = 10))
        assertTrue(nativeMailShouldLoadMore(lastVisibleIndex = 7, totalItems = 10))
        assertTrue(nativeMailShouldLoadMore(lastVisibleIndex = 9, totalItems = 10))
        assertTrue(nativeMailShouldLoadMore(lastVisibleIndex = 0, totalItems = 1))
    }

    @Test
    fun `mail search filters message metadata without changing the source list`() {
        val messages = listOf(
            mailItem(id = "release", sender = "Ada", subject = "Release candidate is ready"),
            mailItem(id = "review", sender = "Mira", subject = "Design review notes"),
        )

        assertEquals(
            listOf("release"),
            nativeMailVisibleMessages(messages, "Ada release").map { it.record.id },
        )
        assertEquals(messages, nativeMailVisibleMessages(messages, ""))
        assertTrue(nativeMailVisibleMessages(messages, "budget").isEmpty())
        assertTrue(nativeMailSearchAllowsAutoPaging(""))
        assertFalse(nativeMailSearchAllowsAutoPaging("Ada"))
    }

    @Test
    fun `compact mail owns search only for message collections`() {
        val messages = listOf(
            mailItem(id = "release", sender = "Ada", subject = "Release candidate is ready"),
            mailItem(id = "review", sender = "Mira", subject = "Design review notes"),
        )
        val folderResource = resource("mailboxes", "Mailboxes")
        val folderRecord = NativeRecord("inbox", values = mapOf("name" to "Inbox"))
        val folder = NativeMailWorkspaceItem(
            resource = folderResource,
            record = folderRecord,
            presentation = nativeMailboxPresentation(folderResource, folderRecord),
        )

        assertTrue(nativeMailCompactSearchAvailable(messages, searchHandlerAvailable = true))
        assertFalse(nativeMailCompactSearchAvailable(messages, searchHandlerAvailable = false))
        assertFalse(nativeMailCompactSearchAvailable(listOf(folder), searchHandlerAvailable = true))
        assertFalse(nativeMailCompactSearchAvailable(emptyList(), searchHandlerAvailable = true))
    }

    @Test
    fun `mailbox stats enrich the selected mailbox instead of becoming mail content`() {
        val mailboxes = ResourceSpec(
            id = "mailboxes",
            name = "Mailboxes",
            confidence = Confidence.verified,
        )
        val stats = ResourceSpec(
            id = "mailboxStats",
            name = "Mailbox stats",
            confidence = Confidence.verified,
        )
        val messages = ResourceSpec(
            id = "messages",
            name = "Messages",
            confidence = Confidence.verified,
        )
        val inbox = NativeRecord(
            id = "9",
            values = mapOf("name" to "Inbox", "specialUse" to "inbox"),
        )
        val context = NativeDatasetContext(
            parentResourceId = "mailboxes",
            parentRecord = inbox,
            relatedRecords = mapOf(
                "mailboxes" to listOf(inbox),
                "mailboxStats" to listOf(
                    NativeRecord("stats-9", values = mapOf("total" to "84", "unread" to "7")),
                ),
            ),
        )
        val schema = schema(mailboxes, stats, messages)

        assertEquals(NativeMailCollectionSummary(total = 84, unread = 7), context.nativeMailCollectionSummary(schema))
        val plan = nativeMailWorkspacePlan(schema, messages, emptyList(), context, null)
        assertEquals(84, plan.selectedContainer?.presentation?.totalCount)
        assertEquals(7, plan.selectedContainer?.presentation?.unreadCount)
        assertEquals(listOf("9"), plan.folders.map { item -> item.record.id })
        assertTrue(plan.messages.isEmpty())
    }

    @Test
    fun `complementary mailbox summary resources merge without accepting conflicts`() {
        val totalStats = ResourceSpec("mailboxStats", "Mailbox stats", confidence = Confidence.verified)
        val unreadStatus = ResourceSpec("mailboxStatus", "Mailbox status", confidence = Confidence.verified)
        val schema = schema(totalStats, unreadStatus)

        val complementary = NativeDatasetContext(
            relatedRecords = mapOf(
                totalStats.id to listOf(NativeRecord("total", values = mapOf("total" to "84"))),
                unreadStatus.id to listOf(NativeRecord("unread", values = mapOf("unread" to "7"))),
            ),
        )
        assertEquals(
            NativeMailCollectionSummary(total = 84, unread = 7),
            complementary.nativeMailCollectionSummary(schema),
        )

        val conflictingTotal = complementary.copy(
            relatedRecords = complementary.relatedRecords + (
                "mailboxStatistics" to listOf(NativeRecord("other-total", values = mapOf("total" to "85")))
                ),
        )
        val schemaWithConflict = schema(
            totalStats,
            unreadStatus,
            ResourceSpec("mailboxStatistics", "Mailbox statistics", confidence = Confidence.verified),
        )
        assertEquals(
            NativeMailCollectionSummary(total = null, unread = 7),
            conflictingTotal.nativeMailCollectionSummary(schemaWithConflict),
        )
    }

    @Test
    fun `message body keeps its proven mailbox selected and enriched`() {
        val mailboxes = ResourceSpec(
            id = "mailboxes",
            name = "Mailboxes",
            confidence = Confidence.verified,
        )
        val stats = ResourceSpec(
            id = "mailboxStats",
            name = "Mailbox stats",
            confidence = Confidence.verified,
        )
        val messages = ResourceSpec(
            id = "messages",
            name = "Messages",
            confidence = Confidence.verified,
        )
        val body = ResourceSpec(
            id = "messageBody",
            name = "Message body",
            confidence = Confidence.verified,
        )
        val inbox = NativeRecord(
            id = "9",
            values = mapOf(
                "name" to "Inbox",
                "specialUse" to "inbox",
                "accountId" to "personal",
            ),
        )
        val message = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Release candidate is ready",
                "from" to "Ada <ada@example.test>",
                "accountId" to "personal",
                "mailboxId" to "9",
            ),
        )
        val schema = schema(mailboxes, stats, messages, body)

        val plan = nativeMailWorkspacePlan(
            schema = schema,
            currentResource = body,
            currentRecords = listOf(NativeRecord("42-body", values = mapOf("body" to "Hello"))),
            context = NativeDatasetContext(
                parentResourceId = "messages",
                parentRecord = message,
                relatedRecords = mapOf(
                    "mailboxes" to listOf(inbox),
                    "messages" to listOf(message),
                    "mailboxStats" to listOf(
                        NativeRecord("stats-9", values = mapOf("total" to "84", "unread" to "2")),
                    ),
                ),
            ),
            selectedRecordId = message.id,
            selectedRecordResourceId = messages.id,
        )

        assertEquals("42", plan.selectedMessage?.record?.id)
        assertEquals("9", plan.selectedContainer?.record?.id)
        assertEquals(84, plan.selectedContainer?.presentation?.totalCount)
        assertEquals(2, plan.selectedContainer?.presentation?.unreadCount)
        assertEquals(listOf("9"), plan.folders.map { item -> item.record.id })
    }

    @Test
    fun `semantic mail datasets become account mailbox and message panes`() {
        val account = resource("accounts", "Accounts")
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("messages", "Messages")
        val schema = schema(account, mailboxes, messages)
        val accountRecord = NativeRecord(
            id = "personal",
            values = mapOf("accountName" to "Personal", "emailAddress" to "me@example.test"),
        )
        val inboxRecord = NativeRecord(
            id = "inbox",
            values = mapOf(
                "name" to "Inbox",
                "specialUse" to "inbox",
                "unreadCount" to "3",
                "path" to "Personal/Inbox",
            ),
        )
        val messageRecord = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Release checklist",
                "from" to "Ada <ada@example.test>",
                "preview" to "The build is ready.",
                "seen" to "false",
            ),
        )

        val plan = nativeMailWorkspacePlan(
            schema = schema,
            currentResource = messages,
            currentRecords = listOf(messageRecord),
            context = NativeDatasetContext(
                parentResourceId = "mailboxes",
                parentRecord = inboxRecord,
                relatedRecords = mapOf(
                    "accounts" to listOf(accountRecord),
                    "mailboxes" to listOf(inboxRecord),
                ),
            ),
            selectedRecordId = "42",
            // Dynamic navigation records the source resource with the selected record. Keeping
            // that scope in this fixture avoids treating an ID-only restored selection as safe.
            selectedRecordResourceId = messages.id,
        )

        assertTrue(plan.hasMailData)
        assertEquals(listOf("Personal"), plan.accounts.map { it.presentation.title })
        assertEquals("Inbox", plan.preferredInbox?.presentation?.title)
        assertEquals(0, plan.folders.single().hierarchyDepth)
        assertEquals("Release checklist", plan.messages.single().presentation.title)
        assertEquals("42", plan.selectedMessage?.record?.id)
        assertEquals("inbox", plan.selectedContainer?.record?.id)
        assertEquals(listOf("42"), plan.visibleMessages.map { item -> item.record.id })
        assertEquals("accounts", plan.accounts.single().record.effectiveNativeResourceId("messages"))
        assertEquals("mailboxes", plan.folders.single().record.effectiveNativeResourceId("messages"))
        assertEquals("messages", plan.messages.single().record.effectiveNativeResourceId("messages"))
    }

    @Test
    fun `multi account landing does not expose cached messages before an account is selected`() {
        val accounts = resource("accounts", "Accounts")
        val messages = resource("messages", "Messages")
        val plan = nativeMailWorkspacePlan(
            schema = schema(accounts, messages),
            currentResource = accounts,
            currentRecords = listOf(
                NativeRecord("personal", mapOf("accountName" to "Personal")),
                NativeRecord("work", mapOf("accountName" to "Work")),
            ),
            context = NativeDatasetContext(
                relatedRecords = mapOf(
                    "messages" to listOf(
                        NativeRecord(
                            id = "42",
                            values = mapOf(
                                "subject" to "Cached subject",
                                "from" to "Ada <ada@example.test>",
                            ),
                        ),
                    ),
                ),
            ),
            selectedRecordId = null,
        )

        assertEquals(2, plan.accounts.size)
        assertEquals(1, plan.messages.size)
        assertTrue(plan.visibleMessages.isEmpty())
    }

    @Test
    fun `selected mailbox filters cached rows by mailbox and account when mailbox ids overlap`() {
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("messages", "Messages")
        val personalInbox = NativeRecord(
            id = "inbox",
            values = mapOf("name" to "Inbox", "accountId" to "personal"),
        )
        val workInbox = personalInbox.copy(values = personalInbox.values + ("accountId" to "work"))
        val plan = nativeMailWorkspacePlan(
            schema = schema(mailboxes, messages),
            currentResource = mailboxes,
            currentRecords = listOf(personalInbox, workInbox),
            context = NativeDatasetContext(
                parentResourceId = "mailboxes",
                parentRecord = personalInbox,
                relatedRecords = mapOf(
                    "messages" to listOf(
                        message(id = "same-id", accountId = "personal", mailboxId = "inbox", subject = "Personal"),
                        message(id = "same-id", accountId = "work", mailboxId = "inbox", subject = "Work"),
                        message(id = "archive", accountId = "personal", mailboxId = "archive", subject = "Archive"),
                    ),
                ),
            ),
            selectedRecordId = personalInbox.id,
            selectedRecordResourceId = mailboxes.id,
        )

        assertEquals("personal", plan.selectedContainer?.record?.values?.get("accountId"))
        assertEquals(listOf("Personal"), plan.visibleMessages.map { item -> item.presentation.title })
    }

    @Test
    fun `overlapping mailbox ids have distinct rail identities and a single selected item`() {
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("messages", "Messages")
        val personalInbox = NativeRecord(
            id = "inbox",
            values = mapOf("name" to "Inbox", "accountId" to "personal"),
        )
        val workInbox = personalInbox.copy(values = personalInbox.values + ("accountId" to "work"))
        val plan = nativeMailWorkspacePlan(
            schema = schema(mailboxes, messages),
            currentResource = mailboxes,
            currentRecords = listOf(personalInbox, workInbox),
            context = NativeDatasetContext(
                parentResourceId = mailboxes.id,
                parentRecord = personalInbox,
            ),
            selectedRecordId = personalInbox.id,
            selectedRecordResourceId = mailboxes.id,
        )

        val selectedKey = plan.selectedContainer?.nativeMailWorkspaceRecordKey()
        val railKeys = plan.folders.map { item -> item.nativeMailWorkspaceRecordKey() }

        assertEquals(2, railKeys.toSet().size)
        assertEquals(1, railKeys.count { key -> key == selectedKey })
        assertNotEquals(railKeys[0], railKeys[1])
    }

    @Test
    fun `sparse mail selections fail closed until an account scope is present`() {
        val accounts = resource("accounts", "Accounts")
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("messages", "Messages")
        val schema = schema(accounts, mailboxes, messages)

        assertFalse(
            nativeMailScreenCacheScopeIsSafe(
                schema,
                mailboxes.id,
                NativeRecord("inbox", mapOf("name" to "Inbox")),
            ),
        )
        assertTrue(
            nativeMailScreenCacheScopeIsSafe(
                schema,
                mailboxes.id,
                NativeRecord("inbox", mapOf("name" to "Inbox", "accountId" to "personal")),
            ),
        )
        assertTrue(
            nativeMailScreenCacheScopeIsSafe(
                schema,
                accounts.id,
                NativeRecord("personal", mapOf("accountName" to "Personal")),
            ),
        )
        assertFalse(
            nativeMailScreenCacheScopeIsSafe(
                schema,
                messages.id,
                NativeRecord("42", mapOf("subject" to "Missing relations", "from" to "Ada")),
            ),
        )
    }

    @Test
    fun `inbox and archive cached messages remain isolated`() {
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("messages", "Messages")
        val inbox = NativeRecord(
            id = "inbox",
            values = mapOf("name" to "Inbox", "accountId" to "personal"),
        )
        val archive = NativeRecord(
            id = "archive",
            values = mapOf("name" to "Archive", "accountId" to "personal"),
        )
        val plan = nativeMailWorkspacePlan(
            schema = schema(mailboxes, messages),
            currentResource = mailboxes,
            currentRecords = listOf(inbox, archive),
            context = NativeDatasetContext(
                parentResourceId = "mailboxes",
                parentRecord = archive,
                relatedRecords = mapOf(
                    "messages" to listOf(
                        message(id = "inbox-message", accountId = "personal", mailboxId = "inbox", subject = "Inbox only"),
                        message(id = "archive-message", accountId = "personal", mailboxId = "archive", subject = "Archive only"),
                    ),
                ),
            ),
            selectedRecordId = archive.id,
            selectedRecordResourceId = mailboxes.id,
        )

        assertEquals(listOf("Archive only"), plan.visibleMessages.map { item -> item.presentation.title })
    }

    @Test
    fun `orphaned and account-ambiguous cached rows stay hidden from a selected mailbox`() {
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("messages", "Messages")
        val inbox = NativeRecord(
            id = "inbox",
            values = mapOf("name" to "Inbox", "accountId" to "personal"),
        )
        val plan = nativeMailWorkspacePlan(
            schema = schema(mailboxes, messages),
            currentResource = mailboxes,
            currentRecords = listOf(inbox),
            context = NativeDatasetContext(
                parentResourceId = "mailboxes",
                parentRecord = inbox,
                relatedRecords = mapOf(
                    "messages" to listOf(
                        NativeRecord(
                            id = "orphan",
                            values = mapOf("subject" to "No mailbox", "from" to "Ada <ada@example.test>"),
                        ),
                        message(id = "no-account", accountId = null, mailboxId = "inbox", subject = "Unknown account"),
                        message(id = "wrong-account", accountId = "work", mailboxId = "inbox", subject = "Wrong account"),
                    ),
                ),
            ),
            selectedRecordId = inbox.id,
            selectedRecordResourceId = mailboxes.id,
        )

        assertTrue(plan.visibleMessages.isEmpty())
    }

    @Test
    fun `message selection uses resource and account instead of record id alone`() {
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("messages", "Messages")
        val threads = resource("threads", "Threads")
        val personalMessage = message(
            id = "42",
            accountId = "personal",
            mailboxId = "inbox",
            subject = "Personal resource message",
        )
        val workMessage = message(
            id = "42",
            accountId = "work",
            mailboxId = "inbox",
            subject = "Work resource message",
        )
        val threadWithSameId = message(
            id = "42",
            accountId = "personal",
            mailboxId = "inbox",
            subject = "Thread with same id",
        )
        val plan = nativeMailWorkspacePlan(
            schema = schema(mailboxes, messages, threads),
            currentResource = mailboxes,
            currentRecords = emptyList(),
            context = NativeDatasetContext(
                parentResourceId = "messages",
                parentRecord = workMessage,
                relatedRecords = mapOf(
                    "messages" to listOf(personalMessage, workMessage),
                    "threads" to listOf(threadWithSameId),
                ),
            ),
            selectedRecordId = workMessage.id,
            selectedRecordResourceId = "messages",
        )

        assertEquals("Work resource message", plan.selectedMessage?.presentation?.title)
        assertEquals(listOf("Work resource message"), plan.visibleMessages.map { item -> item.presentation.title })
    }

    @Test
    fun `record id only selection stays empty when cached mail identities are ambiguous`() {
        val messages = resource("messages", "Messages")
        val plan = nativeMailWorkspacePlan(
            schema = schema(messages),
            currentResource = messages,
            currentRecords = emptyList(),
            context = NativeDatasetContext(
                relatedRecords = mapOf(
                    "messages" to listOf(
                        message(id = "42", accountId = "personal", mailboxId = "inbox", subject = "Personal"),
                        message(id = "42", accountId = "work", mailboxId = "inbox", subject = "Work"),
                    ),
                ),
            ),
            selectedRecordId = "42",
        )

        assertNull(plan.selectedMessage)
        assertTrue(plan.visibleMessages.isEmpty())
    }

    @Test
    fun `message body facet keeps the parent message selected`() {
        val messages = resource("messages", "Messages")
        val body = resource("messageBody", "Message body")
        val schema = schema(messages, body)
        val parentMessage = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Release checklist",
                "from" to "Ada <ada@example.test>",
            ),
        )

        val bodyRecord = NativeRecord(
            id = "body-42",
            values = mapOf("body" to "<p>The build is ready.</p>", "hasHtmlBody" to "true"),
        )
        val context = NativeDatasetContext(
            parentResourceId = "messages",
            parentRecord = parentMessage,
            relatedRecords = emptyMap(),
        )
        val plan = nativeMailWorkspacePlan(
            schema = schema,
            currentResource = body,
            currentRecords = listOf(bodyRecord),
            context = context,
            selectedRecordId = "body-42",
        )

        assertEquals("42", plan.selectedMessage?.record?.id)
        assertEquals("Release checklist", plan.selectedMessage?.presentation?.title)
        assertTrue(plan.currentItems.isEmpty())
        assertEquals(listOf("42"), plan.messages.map { item -> item.record.id })
        assertEquals(listOf("42"), plan.visibleMessages.map { item -> item.record.id })
        assertTrue(plan.hasMailData)
        val detail = nativeMailWorkspaceDetailTarget(
            schema = schema,
            currentResource = body,
            currentRecords = listOf(bodyRecord),
            context = context,
            selectedMessage = plan.selectedMessage,
        )
        assertEquals("42", detail?.record?.id)
        assertEquals("<p>The build is ready.</p>", detail?.presentation?.body)
    }

    @Test
    fun `message detail does not require its envelope to remain in a related list cache`() {
        val messages = resource("messages", "Messages")
        val body = resource("body", "Message body")
        val schema = schema(messages, body)
        val parentMessage = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Release checklist",
                "from" to "Ada <ada@example.test>",
                "mailboxId" to "9",
            ),
        )
        val bodyRecord = NativeRecord(
            id = "body-42",
            values = mapOf("body" to "The build is ready."),
        )
        val context = NativeDatasetContext(
            parentResourceId = messages.id,
            parentRecord = parentMessage,
            relatedRecords = emptyMap(),
        )

        val plan = nativeMailWorkspacePlan(
            schema = schema,
            currentResource = body,
            currentRecords = listOf(bodyRecord),
            context = context,
            selectedRecordId = bodyRecord.id,
            selectedRecordResourceId = body.id,
        )
        val detail = nativeMailWorkspaceDetailTarget(
            schema = schema,
            currentResource = body,
            currentRecords = listOf(bodyRecord),
            context = context,
            selectedMessage = plan.selectedMessage,
        )

        assertTrue(plan.hasMailData)
        assertEquals(listOf("42"), plan.messages.map { item -> item.record.id })
        assertEquals("42", plan.selectedMessage?.record?.id)
        assertEquals("The build is ready.", detail?.presentation?.body)
    }

    @Test
    fun `restored envelope snippet is not rendered as a loaded message body`() {
        val messages = resource("messages", "Messages")
        val body = resource("messageBody", "Message body")
        val schema = schema(messages, body)
        val envelope = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Release checklist",
                "from" to "Ada <ada@example.test>",
                "text" to "Only the mailbox preview, not the loaded body.",
            ),
        )
        val context = NativeDatasetContext(
            parentResourceId = messages.id,
            parentRecord = envelope,
            relatedRecords = emptyMap(),
        )
        val plan = nativeMailWorkspacePlan(
            schema = schema,
            currentResource = body,
            currentRecords = emptyList(),
            context = context,
            selectedRecordId = envelope.id,
            selectedRecordResourceId = messages.id,
        )

        assertEquals("42", plan.selectedMessage?.record?.id)
        assertNull(
            nativeMailWorkspaceDetailTarget(
                schema = schema,
                currentResource = body,
                currentRecords = emptyList(),
                context = context,
                selectedMessage = plan.selectedMessage,
            ),
        )
    }

    @Test
    fun `message detail never borrows body content from another envelope row`() {
        val messages = resource("messages", "Messages")
        val schema = schema(messages)
        val first = message("mail-1", "personal", "inbox", "First private message").copy(
            values = message("mail-1", "personal", "inbox", "First private message").values +
                ("body" to "Private body from the first message"),
        )
        val selected = message("mail-2", "personal", "inbox", "Selected message").copy(
            values = message("mail-2", "personal", "inbox", "Selected message").values +
                ("body" to "Selected body"),
        )
        val selectedItem = NativeMailWorkspaceItem(
            resource = messages,
            record = selected,
            presentation = nativeMailboxPresentation(messages, selected),
        )

        val detail = nativeMailWorkspaceDetailTarget(
            schema = schema,
            currentResource = messages,
            currentRecords = listOf(first, selected),
            context = NativeDatasetContext(),
            selectedMessage = selectedItem,
        )

        assertEquals("mail-2", detail?.record?.id)
        assertEquals("Selected body", detail?.presentation?.body)
    }

    @Test
    fun `message body facet keeps verified siblings from only the selected mailbox`() {
        val messages = resource("messages", "Messages")
        val body = resource("messageBody", "Message body")
        val schema = schema(messages, body)
        val selected = message("mail-1", "personal", "inbox", "Selected message")
        val sameMailbox = message("mail-2", "personal", "inbox", "Inbox sibling")
        val otherMailbox = message("mail-3", "personal", "archive", "Archive message")
        val otherAccount = message("mail-4", "work", "inbox", "Work inbox message")
        val context = NativeDatasetContext(
            parentResourceId = messages.id,
            parentRecord = selected,
            relatedRecords = mapOf(
                messages.id to listOf(selected, sameMailbox, otherMailbox, otherAccount),
            ),
        )

        val plan = nativeMailWorkspacePlan(
            schema = schema,
            currentResource = body,
            currentRecords = listOf(
                NativeRecord("body-1", mapOf("body" to "Selected body", "hasHtmlBody" to "false")),
            ),
            context = context,
            selectedRecordId = selected.id,
            selectedRecordResourceId = messages.id,
        )

        assertEquals(
            setOf(selected.id, sameMailbox.id),
            plan.visibleMessages.map { item -> item.record.id }.toSet(),
        )
        assertFalse(plan.visibleMessages.any { item -> item.record.id in setOf(otherMailbox.id, otherAccount.id) })
    }

    @Test
    fun `current mailbox response replaces stale related rows for the same resource`() {
        val messages = resource("messages", "Messages")
        val current = NativeRecord(
            id = "42",
            values = mapOf("subject" to "Current subject", "from" to "Ada <ada@example.test>"),
        )
        val stale = current.copy(values = current.values + ("subject" to "Stale subject"))

        val plan = nativeMailWorkspacePlan(
            schema = schema(messages),
            currentResource = messages,
            currentRecords = listOf(current),
            context = NativeDatasetContext(relatedRecords = mapOf("messages" to listOf(stale))),
            selectedRecordId = null,
        )

        assertEquals(listOf("Current subject"), plan.messages.map { item -> item.presentation.title })
    }

    @Test
    fun `sole account and unique inbox provide a useful automatic landing`() {
        val accounts = resource("accounts", "Accounts")
        val mailboxes = resource("mailboxes", "Mailboxes")
        val account = NativeRecord(
            id = "personal",
            values = mapOf("accountName" to "Personal", "emailAddress" to "me@example.test"),
        )
        val inbox = NativeRecord(
            id = "inbox",
            values = mapOf("name" to "Inbox", "specialUse" to "inbox"),
        )
        val archive = NativeRecord(
            id = "archive",
            values = mapOf("name" to "Archive", "specialUse" to "archive"),
        )

        assertEquals(account, nativeMailSoleAccountLandingRecord(accounts, listOf(account)))
        assertEquals(inbox, nativeMailInboxLandingRecord(mailboxes, listOf(archive, inbox)))
        assertNull(nativeMailSoleAccountLandingRecord(accounts, listOf(account, account.copy(id = "work"))))
        assertNull(
            nativeMailInboxLandingRecord(
                mailboxes,
                listOf(inbox, inbox.copy(id = "second-inbox")),
            ),
        )
        val schema = schema(accounts, mailboxes)
        assertTrue(isNativeMailContainerRecord(schema, "accounts", account))
        assertTrue(isNativeMailContainerRecord(schema, "mailboxes", inbox))
    }

    @Test
    fun `mailbox hierarchy removes the account prefix and keeps children with their parent`() {
        val mailboxes = resource("mailboxes", "Mailboxes")
        val records = listOf(
            NativeRecord("archive", mapOf("name" to "Archive", "path" to "Personal/Archive")),
            NativeRecord("project-child", mapOf("name" to "Native", "path" to "Personal/Projects/Native")),
            NativeRecord("projects", mapOf("name" to "Projects", "path" to "Personal/Projects")),
        )

        val plan = nativeMailWorkspacePlan(
            schema = schema(mailboxes),
            currentResource = mailboxes,
            currentRecords = records,
            context = NativeDatasetContext(),
            selectedRecordId = null,
        )

        assertEquals(listOf("Archive", "Projects", "Native"), plan.folders.map { it.presentation.title })
        assertEquals(listOf(0, 0, 1), plan.folders.map { it.hierarchyDepth })
    }

    @Test
    fun `ambiguous non mail datasets do not opt into mail workspace`() {
        val inventory = resource("inventory", "Inventory")
        val schema = schema(inventory)
        val record = NativeRecord(
            id = "tripod",
            values = mapOf("name" to "Tripod", "category" to "Camera"),
        )

        val plan = nativeMailWorkspacePlan(
            schema = schema,
            currentResource = inventory,
            currentRecords = listOf(record),
            context = NativeDatasetContext(),
            selectedRecordId = null,
        )

        assertFalse(plan.hasMailData)
        assertTrue(plan.currentItems.isEmpty())
        assertNull(plan.preferredInbox)
    }

    @Test
    fun `parameter free semantic compose form becomes the workspace compose entry`() {
        val messages = resource("messages", "Messages")
        val action = nativeAction("send-message", "Send message", "messages")
        val descriptor = descriptor(
            actions = listOf(action),
            forms = listOf(
                DynamicForm(
                    id = "compose-message",
                    title = "Compose",
                    resourceId = "messages",
                    actionId = action.id,
                    confidence = Confidence.verified,
                ),
            ),
        )
        val schema = schemaWithActions(
            resources = listOf(messages),
            actions = listOf(schemaAction(action)),
        )

        val compose = descriptor.preferredNativeMailComposeAction(schema)

        assertEquals("compose-message", compose?.formId)
        assertEquals("send-message", compose?.actionId)
    }

    @Test
    fun `automatic landing requires the complete semantic mail hierarchy`() {
        val mailDescriptor = descriptor(
            resources = listOf(
                dynamicResource("accounts", "Accounts"),
                dynamicResource("mailboxes", "Mailboxes"),
                dynamicResource("messages", "Messages"),
            ),
            actions = emptyList(),
            forms = emptyList(),
        )
        val financeDescriptor = descriptor(
            resources = listOf(dynamicResource("accounts", "Expense accounts")),
            actions = emptyList(),
            forms = emptyList(),
        )

        assertTrue(mailDescriptor.hasNativeMailWorkspaceSemantics())
        assertTrue(
            schema(
                resource("accounts", "Accounts"),
                resource("mailboxes", "Mailboxes"),
                resource("messages", "Messages"),
            ).hasNativeMailWorkspaceSemantics(),
        )
        assertFalse(financeDescriptor.hasNativeMailWorkspaceSemantics())
        assertFalse(schema(resource("accounts", "Expense accounts")).hasNativeMailWorkspaceSemantics())
    }

    @Test
    fun `workspace state follows account mailbox message and body context`() {
        val accounts = resource("accounts", "Accounts")
        val mailboxes = resource("mailboxes", "Mailboxes")
        val messages = resource("threads", "Threads")
        val body = resource("content", "Message body")
        val schema = schema(accounts, mailboxes, messages, body)
        val parentMessage = NativeRecord(
            id = "thread-42",
            values = mapOf("subject" to "Release checklist", "from" to "Ada <ada@example.test>"),
        )

        assertEquals(
            NativeMailWorkspaceSection.Accounts,
            nativeMailWorkspaceSection(schema, accounts, NativeDatasetContext()),
        )
        assertEquals(
            NativeMailWorkspaceSection.Mailboxes,
            nativeMailWorkspaceSection(schema, mailboxes, NativeDatasetContext()),
        )
        assertEquals(
            NativeMailWorkspaceSection.Messages,
            nativeMailWorkspaceSection(schema, messages, NativeDatasetContext()),
        )
        assertEquals(
            NativeMailWorkspaceSection.MessageDetail,
            nativeMailWorkspaceSection(
                schema,
                body,
                NativeDatasetContext(parentResourceId = "threads", parentRecord = parentMessage),
            ),
        )
    }

    @Test
    fun `ambiguous compose routes are not guessed`() {
        val messages = resource("messages", "Messages")
        val actions = listOf(
            nativeAction("compose-message", "Compose message", "messages"),
            nativeAction("compose-email", "Compose email", "messages"),
        )
        val descriptor = descriptor(
            actions = actions,
            forms = actions.map { action ->
                DynamicForm(
                    id = "form-${action.id}",
                    title = action.label,
                    resourceId = action.resourceId,
                    actionId = action.id,
                    confidence = Confidence.verified,
                )
            },
        )
        val schema = schemaWithActions(
            resources = listOf(messages),
            actions = actions.map(::schemaAction),
        )

        assertNull(descriptor.preferredNativeMailComposeAction(schema))
    }

    @Test
    fun `compose entry does not guess a missing route parameter`() {
        val messages = resource("messages", "Messages")
        val action = nativeAction("compose-message", "Compose", "messages").copy(
            binding = DynamicHttpBinding(
                method = HttpMethod.POST,
                path = "/semantic/accounts/{accountId}/messages",
                pathParameters = listOf(
                    HttpParameter(
                        name = "accountId",
                        required = true,
                        schema = JsonPrimitive("string"),
                        source = ParameterSource.runtimeContext,
                    ),
                ),
            ),
        )
        val descriptor = descriptor(
            actions = listOf(action),
            forms = listOf(
                DynamicForm(
                    id = "compose-message",
                    title = "Compose",
                    resourceId = "messages",
                    actionId = action.id,
                    confidence = Confidence.verified,
                ),
            ),
        )

        assertNull(
            descriptor.preferredNativeMailComposeAction(
                schemaWithActions(listOf(messages), listOf(schemaAction(action))),
            ),
        )
    }

    private fun message(
        id: String,
        accountId: String?,
        mailboxId: String,
        subject: String,
    ): NativeRecord = NativeRecord(
        id = id,
        values = buildMap {
            put("subject", subject)
            put("from", "Ada <ada@example.test>")
            put("mailboxId", mailboxId)
            accountId?.let { value -> put("accountId", value) }
        },
    )

    private fun mailItem(
        id: String,
        sender: String,
        subject: String,
    ): NativeMailWorkspaceItem {
        val resource = ResourceSpec(
            id = "messages",
            name = "Messages",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("from", "From", FieldKind.string, required = false, readOnly = true),
                FieldSpec("subject", "Subject", FieldKind.string, required = false, readOnly = true),
            ),
        )
        val record = NativeRecord(
            id = id,
            values = mapOf(
                "from" to sender,
                "subject" to subject,
            ),
        )
        return NativeMailWorkspaceItem(
            resource = resource,
            record = record,
            presentation = nativeMailboxPresentation(resource, record),
        )
    }

    private fun resource(id: String, name: String): ResourceSpec = ResourceSpec(
        id = id,
        name = name,
        confidence = Confidence.verified,
    )

    private fun schema(vararg resources: ResourceSpec): NativeAppSchema = NativeAppSchema(
        schemaVersion = "1",
        app = AppIdentity(id = "semantic-mail-fixture", name = "Mail fixture", version = "1"),
        confidence = Confidence.verified,
        resources = resources.toList(),
    )

    private fun schemaWithActions(
        resources: List<ResourceSpec>,
        actions: List<ActionSpec>,
    ): NativeAppSchema = NativeAppSchema(
        schemaVersion = "1",
        app = AppIdentity(id = "semantic-mail-fixture", name = "Mail fixture", version = "1"),
        confidence = Confidence.verified,
        resources = resources,
        actions = actions,
    )

    private fun descriptor(
        resources: List<DynamicResource> = emptyList(),
        actions: List<DynamicAction>,
        forms: List<DynamicForm>,
    ): DynamicAppDescriptor = DynamicAppDescriptor(
        descriptorVersion = "1",
        app = AppIdentity(id = "semantic-mail-fixture", name = "Mail fixture", version = "1"),
        endpointPolicy = EndpointPolicy(serverOrigin = "https://cloud.example.test"),
        resources = resources,
        actions = actions,
        forms = forms,
    )

    private fun dynamicResource(id: String, label: String): DynamicResource = DynamicResource(
        id = id,
        label = label,
        collection = true,
        confidence = Confidence.verified,
    )

    private fun nativeAction(
        id: String,
        label: String,
        resourceId: String,
    ): DynamicAction = DynamicAction(
        id = id,
        label = label,
        resourceId = resourceId,
        intent = ActionIntent.create,
        risk = ActionRisk.mutating,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.POST,
            path = "/semantic/messages",
        ),
        confidence = Confidence.verified,
    )

    private fun schemaAction(action: DynamicAction): ActionSpec = ActionSpec(
        id = action.id,
        label = action.label,
        resourceId = action.resourceId,
        binding = ApiBinding(
            method = action.binding.method,
            path = action.binding.path,
            operationId = action.id,
        ),
        intent = action.intent,
        risk = action.risk,
        requiresConfirmation = action.requiresConfirmation,
        confidence = action.confidence,
    )
}
