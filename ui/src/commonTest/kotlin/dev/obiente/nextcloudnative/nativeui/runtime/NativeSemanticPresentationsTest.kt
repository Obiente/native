package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSemanticPresentationsTest {
    @Test
    fun `liability credits offset debt before the owed total is derived`() {
        assertEquals(80.0, nativeFinanceLiabilityTotal(-100.0 + 20.0))
        assertEquals(0.0, nativeFinanceLiabilityTotal(20.0))
    }

    @Test
    fun `mail message fields become a native mailbox row`() {
        val resource = resource(
            "messages",
            "Messages",
            "subject", "from", "preview", "date", "flags", "attachments",
        )
        val record = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Release checklist",
                "from" to "[{\"name\":\"Ada\",\"email\":\"ada@example.test\"}]",
                "preview" to "The build is ready for review.",
                "date" to "2026-07-23T10:15:00Z",
                "flags" to "[]",
                "attachments" to "[{\"name\":\"report.pdf\"}]",
            ),
        )

        val presentation = nativeMailboxPresentation(resource, record)

        assertEquals(NativeMailboxItemKind.Message, presentation.kind)
        assertEquals("Release checklist", presentation.title)
        assertEquals("Ada <ada@example.test>", presentation.sender)
        assertEquals(1, presentation.attachmentCount)
        assertTrue(presentation.unread)
    }

    @Test
    fun `mail row labels prefer a sender name and compact ISO timestamp`() {
        assertEquals("Ada", nativeMailSenderLabel("Ada <ada@example.test>"))
        assertEquals("plain@example.test", nativeMailSenderLabel("plain@example.test"))
        assertEquals("Jul 29, 08:42", nativeMailTimestampLabel("2026-07-29T08:42:00Z"))
        assertEquals("Server time unknown", nativeMailTimestampLabel("Server time unknown"))
    }

    @Test
    fun `mailbox counts become folder badges without treating zero as unread`() {
        val resource = resource("mailboxes", "Mailboxes", "name", "unread")
        val unread = nativeMailboxPresentation(
            resource,
            NativeRecord("inbox", mapOf("name" to "Inbox", "unread" to "7")),
        )
        val empty = nativeMailboxPresentation(
            resource,
            NativeRecord("archive", mapOf("name" to "Archive", "unread" to "0")),
        )

        assertEquals(NativeMailboxItemKind.Folder, unread.kind)
        assertEquals(7, unread.unreadCount)
        assertTrue(unread.unread)
        assertFalse(empty.unread)
    }

    @Test
    fun `mail thread rows retain their message count`() {
        val presentation = nativeMailboxPresentation(
            resource("threads", "Threads", "subject", "from", "messageCount"),
            NativeRecord(
                id = "thread-42",
                values = mapOf(
                    "subject" to "Release checklist",
                    "from" to "Ada <ada@example.test>",
                    "messageCount" to "4",
                ),
            ),
        )

        assertEquals(NativeMailboxItemKind.Message, presentation.kind)
        assertEquals(4, presentation.threadSize)
    }

    @Test
    fun `standard envelope and body fields become a native message detail`() {
        val presentation = nativeMailMessageDetailPresentation(
            resource("message", "Message", "subject", "from", "to", "dateInt", "body", "hasHtmlBody"),
            NativeRecord(
                id = "42",
                values = mapOf(
                    "subject" to "Release checklist",
                    "from" to "[{\"label\":\"Ada\",\"email\":\"ada@example.test\"}]",
                    "to" to "[{\"label\":\"Team\",\"email\":\"team@example.test\"}]",
                    "dateInt" to "1781532587",
                    "body" to "<p>The build is ready.</p>",
                    "hasHtmlBody" to "true",
                ),
                displayValues = mapOf("body" to "Long value"),
            ),
        )

        assertEquals("Release checklist", presentation?.subject)
        assertEquals("Ada <ada@example.test>", presentation?.sender)
        assertEquals("Team <team@example.test>", presentation?.recipients)
        assertEquals("2026-06-15 14:09", presentation?.timestamp)
        assertEquals("<p>The build is ready.</p>", presentation?.body)
        assertTrue(presentation?.htmlBody == true)
    }

    @Test
    fun `bounded nested thread messages become individual native details`() {
        fun scalar(value: String) = NativeStructuredValue.Scalar(
            value = value,
            kind = NativeStructuredScalarKind.string,
        )
        fun message(id: String, sender: String, body: String) = NativeStructuredValue.ObjectValue(
            entries = listOf(
                NativeStructuredEntry("id", "Id", scalar(id)),
                NativeStructuredEntry("subject", "Subject", scalar("Release checklist")),
                NativeStructuredEntry("from", "From", scalar(sender)),
                NativeStructuredEntry("body", "Body", scalar(body)),
                NativeStructuredEntry("hasHtmlBody", "HTML", scalar("true")),
            ),
        )
        val presentations = nativeMailThreadPresentations(
            resource("threads", "Threads", "subject", "from", "messages"),
            NativeRecord(
                id = "thread-42",
                values = mapOf("subject" to "Release checklist"),
                structuredValues = mapOf(
                    "messages" to NativeStructuredValue.ListValue(
                        items = listOf(
                            message("41", "Ada <ada@example.test>", "<p>Ready for review.</p>"),
                            message("42", "Mira <mira@example.test>", "<p>I will review it.</p>"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(2, presentations.size)
        assertEquals("Ada <ada@example.test>", presentations.first().sender)
        assertEquals("<p>I will review it.</p>", presentations.last().body)
    }

    @Test
    fun `track semantics retain artist album duration favorite and ordering`() {
        val resource = resource(
            "tracks",
            "Tracks",
            "title", "artist", "album", "duration", "trackNumber", "favorite",
        )
        val presentation = nativeMediaPresentation(
            resource,
            NativeRecord(
                "9",
                mapOf(
                    "title" to "A Native Song",
                    "artist" to "The Components",
                    "album" to "Semantic Shapes",
                    "duration" to "245",
                    "trackNumber" to "3",
                    "favorite" to "true",
                ),
            ),
        )

        assertEquals(NativeMediaItemKind.Track, presentation.kind)
        assertEquals("The Components", presentation.artist)
        assertEquals("Semantic Shapes", presentation.album)
        assertEquals("4:05", presentation.duration)
        assertEquals("3", presentation.trackNumber)
        assertTrue(presentation.favorite)
    }

    @Test
    fun `track semantics read names from observed nested artist and album objects`() {
        fun namedObject(name: String) = NativeStructuredValue.ObjectValue(
            entries = listOf(
                NativeStructuredEntry(
                    key = "name",
                    label = "Name",
                    value = NativeStructuredValue.Scalar(name, NativeStructuredScalarKind.string),
                ),
            ),
        )
        val presentation = nativeMediaPresentation(
            resource("tracks", "Tracks", "title", "artist", "album", "length"),
            NativeRecord(
                id = "9",
                values = mapOf("title" to "A Native Song", "length" to "245"),
                displayValues = mapOf("artist" to "2 fields", "album" to "3 fields"),
                structuredValues = mapOf(
                    "artist" to namedObject("The Components"),
                    "album" to namedObject("Semantic Shapes"),
                ),
            ),
        )

        assertEquals("The Components", presentation.artist)
        assertEquals("Semantic Shapes", presentation.album)
    }

    @Test
    fun `media semantics accept ordinal ordering and relative image artwork`() {
        val track = nativeMediaPresentation(
            resource("tracks", "Tracks", "title", "ordinal", "length"),
            NativeRecord(
                id = "9",
                values = mapOf(
                    "title" to "A Native Song",
                    "ordinal" to "7",
                    "length" to "185",
                ),
            ),
        )
        val artist = nativeMediaPresentation(
            resource("artists", "Artists", "name", "image"),
            NativeRecord(
                id = "2",
                values = mapOf(
                    "name" to "The Components",
                    "image" to "/index.php/apps/example/api/artists/2/cover",
                ),
            ),
        )
        val unsafeArtist = nativeMediaPresentation(
            resource("artists", "Artists", "name", "image"),
            NativeRecord(
                id = "3",
                values = mapOf(
                    "name" to "External Artist",
                    "image" to "https://media.example.test/artist.jpg",
                ),
            ),
        )

        assertEquals("7", track.trackNumber)
        assertEquals("3:05", track.duration)
        assertEquals("/index.php/apps/example/api/artists/2/cover", artist.coverUrl)
        assertEquals(null, unsafeArtist.coverUrl)
    }

    @Test
    fun `nameless media references do not leak structural summaries`() {
        fun reference(id: String) = NativeStructuredValue.ObjectValue(
            entries = listOf(
                NativeStructuredEntry(
                    key = "id",
                    label = "Id",
                    value = NativeStructuredValue.Scalar(id, NativeStructuredScalarKind.number),
                ),
            ),
        )
        val presentation = nativeMediaPresentation(
            resource("tracks", "Tracks", "title", "artist", "album", "length", "bitrate", "files"),
            NativeRecord(
                id = "9",
                values = mapOf("title" to "A Native Song", "length" to "245", "bitrate" to "817973"),
                displayValues = mapOf("artist" to "2 fields", "album" to "1 item"),
                structuredValues = mapOf(
                    "artist" to reference("659"),
                    "album" to reference("903"),
                    "files" to NativeStructuredValue.ObjectValue(
                        entries = listOf(
                            NativeStructuredEntry(
                                key = "audio/flac",
                                label = "Audio/flac",
                                value = NativeStructuredValue.Scalar(
                                    "/apps/music/api/files/1/download",
                                    NativeStructuredScalarKind.string,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(null, presentation.artist)
        assertEquals(null, presentation.album)
        assertEquals("FLAC · 817 kbps", presentation.detail)
    }

    @Test
    fun `shared expense rows become native ledger presentations`() {
        val resource = resource(
            "bills",
            "Bills",
            "what", "amount", "currency", "payer_name", "category_name", "payment_mode_name", "date", "comment",
        )
        val presentation = requireNotNull(
            nativeFinancePresentation(
                resource,
                NativeRecord(
                    id = "bill-1",
                    values = mapOf(
                        "what" to "Groceries",
                        "amount" to "42.50",
                        "currency" to "EUR",
                        "payer_name" to "Ada",
                        "category_name" to "Food",
                        "payment_mode_name" to "Card",
                        "date" to "2026-07-23T18:30:00Z",
                        "comment" to "Dinner ingredients",
                    ),
                ),
            ),
        )

        assertEquals("Groceries", presentation.title)
        assertEquals(42.5, presentation.amount)
        assertEquals("EUR", presentation.currency)
        assertEquals("Ada", presentation.participant)
        assertEquals("Food", presentation.category)
        assertEquals("Card", presentation.paymentMethod)
        assertEquals("2026-07-23 18:30", presentation.date)
        assertEquals("Dinner ingredients", presentation.note)
        assertEquals("EUR 42.50", formatNativeFinanceAmount(presentation.amount, presentation.currency))
        assertEquals("EUR -3.10", formatNativeFinanceAmount(-3.1, "EUR"))
    }

    @Test
    fun `transaction direction supplies the sign when the server amount is absolute`() {
        val transactions = resource("transactions", "Transactions", "description", "amount", "type")
        val debit = requireNotNull(
            nativeFinancePresentation(
                transactions,
                NativeRecord("1", mapOf("description" to "Groceries", "amount" to "38.40", "type" to "debit")),
            ),
        )
        val credit = requireNotNull(
            nativeFinancePresentation(
                transactions,
                NativeRecord("2", mapOf("description" to "Refund", "amount" to "12", "type" to "credit")),
            ),
        )

        assertEquals(-38.4, debit.amount)
        assertEquals(NativeFinanceDirection.Debit, debit.direction)
        assertEquals(12.0, credit.amount)
        assertEquals(NativeFinanceDirection.Credit, credit.direction)
    }

    @Test
    fun `account balance records retain asset liability and reporting semantics`() {
        val accounts = resource(
            "accounts",
            "Accounts",
            "name", "balance", "currency", "type", "institution", "convertedBalance",
            "baseCurrency", "excludedFromReports",
        )
        val checking = requireNotNull(
            nativeFinancialAccountPresentation(
                accounts,
                NativeRecord(
                    "1",
                    mapOf(
                        "name" to "Daily banking",
                        "balance" to "1250.45",
                        "currency" to "EUR",
                        "type" to "checking",
                        "institution" to "Example Bank",
                        "accountNumber" to "NL91ABNA0417164300",
                        "ibanMasked" to "NL91 **** 4300",
                        "excludedFromReports" to "false",
                    ),
                ),
            ),
        )
        val card = requireNotNull(
            nativeFinancialAccountPresentation(
                accounts,
                NativeRecord(
                    "2",
                    mapOf(
                        "name" to "Credit card",
                        "balance" to "-320.10",
                        "currency" to "USD",
                        "type" to "credit_card",
                        "convertedBalance" to "-295.80",
                        "baseCurrency" to "EUR",
                        "excludedFromReports" to "true",
                    ),
                ),
            ),
        )

        assertEquals(NativeFinancialAccountKind.Asset, checking.kind)
        assertEquals("Example Bank", checking.institution)
        assertEquals("NL91 **** 4300", checking.accountNumber)
        assertEquals(NativeFinancialAccountKind.Liability, card.kind)
        assertEquals(-295.8, card.convertedBalance)
        assertEquals("EUR", card.baseCurrency)
        assertTrue(card.excludedFromReports)
    }

    @Test
    fun `transaction rows referencing an account are not account balance cards`() {
        assertEquals(
            null,
            nativeFinancialAccountPresentation(
                resource("transactions", "Transactions", "description", "amount", "accountName"),
                NativeRecord(
                    "1",
                    mapOf(
                        "description" to "Groceries",
                        "amount" to "42.50",
                        "accountName" to "Daily banking",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `finance presentation requires both finance semantics and a numeric amount`() {
        val generic = resource("items", "Items", "title", "amount")
        val bills = resource("bills", "Bills", "what", "amount")

        assertEquals(
            null,
            nativeFinancePresentation(generic, NativeRecord("1", mapOf("title" to "Item", "amount" to "12"))),
        )
        assertEquals(
            null,
            nativeFinanceCollectionPresentations(
                bills,
                listOf(NativeRecord("1", mapOf("what" to "Broken", "amount" to "not-a-number"))),
            ),
        )
    }

    @Test
    fun `one irregular finance row does not downgrade valid transaction cards`() {
        val rows = requireNotNull(
            nativeFinanceCollectionPresentations(
                resource("bills", "Bills", "what", "amount"),
                listOf(
                    NativeRecord("1", mapOf("what" to "Groceries", "amount" to "12.50")),
                    NativeRecord("2", mapOf("what" to "Pending import", "amount" to null)),
                ),
            ),
        )

        assertEquals("Groceries", requireNotNull(rows[0].second).title)
        assertEquals(null, rows[1].second)
    }

    @Test
    fun `transaction shaped records use finance cards without app specific resource names`() {
        val presentation = requireNotNull(
            nativeFinancePresentation(
                resource("records", "Records", "what", "amount", "payer_id", "comment"),
                NativeRecord(
                    id = "11",
                    values = mapOf(
                        "what" to "Groceries",
                        "amount" to "42.50",
                        "payer_id" to "7",
                        "comment" to "Private receipt bookkeeping belongs in details",
                    ),
                ),
            ),
        )

        assertEquals("Groceries", presentation.title)
        assertEquals(42.5, presentation.amount)
        assertEquals("Private receipt bookkeeping belongs in details", presentation.note)
    }

    @Test
    fun `finance presentation resolves payer and split from structured participants`() {
        fun participant(id: String, name: String) = NativeStructuredValue.ObjectValue(
            entries = listOf(
                NativeStructuredEntry(
                    key = "id",
                    label = "Id",
                    value = NativeStructuredValue.Scalar(id, NativeStructuredScalarKind.number),
                ),
                NativeStructuredEntry(
                    key = "name",
                    label = "Name",
                    value = NativeStructuredValue.Scalar(name, NativeStructuredScalarKind.string),
                ),
            ),
        )
        val presentation = requireNotNull(
            nativeFinancePresentation(
                resource("entries", "Entries", "what", "amount", "payer_id", "owers"),
                NativeRecord(
                    id = "entry-11",
                    values = mapOf(
                        "what" to "Shared groceries",
                        "amount" to "42.50",
                        "payer_id" to "7",
                    ),
                    structuredValues = mapOf(
                        "owers" to NativeStructuredValue.ListValue(
                            listOf(
                                participant("7", "Ada"),
                                participant("9", "Sam"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Ada", presentation.participant)
        assertEquals(listOf("Ada", "Sam"), presentation.splitParticipants)
    }

    @Test
    fun `nested finance statistics become a rounded dashboard model`() {
        fun scalar(value: String) =
            NativeStructuredValue.Scalar(value, NativeStructuredScalarKind.number)
        fun member(name: String, paid: String, spent: String) = NativeStructuredValue.ObjectValue(
            entries = listOf(
                NativeStructuredEntry("paid", "Paid", scalar(paid)),
                NativeStructuredEntry("spent", "Spent", scalar(spent)),
                NativeStructuredEntry("balance", "Balance", scalar((paid.toDouble() - spent.toDouble()).toString())),
                NativeStructuredEntry(
                    "member",
                    "Member",
                    NativeStructuredValue.ObjectValue(
                        listOf(
                            NativeStructuredEntry(
                                "name",
                                "Name",
                                NativeStructuredValue.Scalar(name, NativeStructuredScalarKind.string),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val dashboard = requireNotNull(
            nativeFinanceDashboardPresentation(
                NativeRecord(
                    id = "statistics",
                    values = emptyMap(),
                    structuredValues = mapOf(
                        "stats" to NativeStructuredValue.ListValue(
                            listOf(member("Ada", "60.10", "40.10"), member("Sam", "39.90", "59.90")),
                        ),
                        "memberMonthlySpentStats" to NativeStructuredValue.ObjectValue(
                            listOf(
                                NativeStructuredEntry(
                                    "2026-07",
                                    "2026-07",
                                    NativeStructuredValue.ObjectValue(
                                        listOf(NativeStructuredEntry("total", "Total", scalar("100"))),
                                    ),
                                ),
                            ),
                        ),
                        "categoryStats" to NativeStructuredValue.ObjectValue(
                            listOf(NativeStructuredEntry("food", "Food", scalar("75"))),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(100.0, dashboard.totalPaid)
        assertEquals(100.0, dashboard.totalSpent)
        assertEquals(0.0, dashboard.balance)
        assertEquals(listOf(NativeChartPoint("2026-07", 100.0)), dashboard.monthlySpending)
        assertEquals(listOf(NativeChartPoint("Food", 75.0)), dashboard.categories)
        assertEquals("0", formatNativeFinanceAmount(-0.005000000000009663, null))
    }

    @Test
    fun `recurring calendar events retain time location and status semantics`() {
        val resource = resource(
            "events",
            "Events",
            "SUMMARY", "DTSTART", "DTEND", "LOCATION", "RRULE", "STATUS",
        )
        val record = NativeRecord(
            id = "opaque-event",
            values = mapOf(
                "SUMMARY" to "Planning session",
                "DTSTART" to "20260723T090000Z",
                "DTEND" to "20260723T100000Z",
                "LOCATION" to "Studio",
                "RRULE" to "FREQ=WEEKLY",
                "STATUS" to "CONFIRMED",
            ),
        )

        val presentation = requireNotNull(nativeGroupwarePresentation(resource, record))

        assertEquals(NativeGroupwareItemKind.Event, presentation.kind)
        assertEquals("Planning session", presentation.title)
        assertEquals("20260723T090000Z", presentation.start)
        assertEquals("20260723T100000Z", presentation.end)
        assertEquals("Studio", presentation.location)
        assertEquals("CONFIRMED", presentation.status)
        assertTrue(presentation.recurring)
        assertTrue(nativeRecordPresentation(resource, record).subtitle?.contains("Recurring") == true)
    }

    @Test
    fun `caldav tasks retain due completion priority and recurrence semantics`() {
        val presentation = requireNotNull(
            nativeGroupwarePresentation(
                resource(
                    "todos",
                    "Tasks",
                    "SUMMARY", "DUE", "STATUS", "PERCENT-COMPLETE", "PRIORITY", "RRULE",
                ),
                NativeRecord(
                    id = "opaque-task",
                    values = mapOf(
                        "SUMMARY" to "Publish release",
                        "DUE" to "20260724T150000Z",
                        "STATUS" to "COMPLETED",
                        "PERCENT-COMPLETE" to "100",
                        "PRIORITY" to "3",
                        "RRULE" to "FREQ=MONTHLY",
                    ),
                ),
            ),
        )

        assertEquals(NativeGroupwareItemKind.Task, presentation.kind)
        assertEquals("Publish release", presentation.title)
        assertEquals("20260724T150000Z", presentation.due)
        assertEquals(100, presentation.completionPercent)
        assertEquals(3, presentation.priority)
        assertTrue(presentation.completed)
        assertTrue(presentation.recurring)
        assertTrue(presentation.subtitle?.contains("Due 2026-07-24 15:00") == true)
    }

    @Test
    fun `carddav contacts retain formatted name organization email and telephone`() {
        val resource = resource("contacts", "Contacts", "FN", "ORG", "TITLE", "EMAIL", "TEL")
        val record = NativeRecord(
            id = "opaque-contact",
            values = mapOf(
                "FN" to "Ada Example",
                "ORG" to "Example Cooperative",
                "TITLE" to "Designer",
                "EMAIL" to "ada@example.test",
                "TEL" to "+31 20 000 0000",
            ),
        )

        val presentation = requireNotNull(nativeGroupwarePresentation(resource, record))

        assertEquals(NativeGroupwareItemKind.Contact, presentation.kind)
        assertEquals("Ada Example", presentation.title)
        assertEquals("Example Cooperative", presentation.organization)
        assertEquals("ada@example.test", presentation.primaryEmail)
        assertEquals("+31 20 000 0000", presentation.primaryPhone)
        assertTrue(presentation.subtitle?.contains("Designer") == true)
        assertEquals("Ada Example", nativeRecordPresentation(resource, record).title)
    }

    @Test
    fun `homogeneous contacts and events become native collections while mixed data does not`() {
        val contacts = resource("contacts", "Contacts", "FN", "EMAIL", "TEL")
        val contactRows = listOf(
            NativeRecord("a", mapOf("FN" to "Ada Example", "EMAIL" to "ada@example.test")),
            NativeRecord("b", mapOf("FN" to "Lin Example", "TEL" to "+31 20 000 0000")),
        )
        val events = resource("events", "Events", "SUMMARY", "DTSTART", "LOCATION")
        val eventRows = listOf(
            NativeRecord(
                "e",
                mapOf("SUMMARY" to "Planning", "DTSTART" to "20260723T090000Z", "LOCATION" to "Studio"),
            ),
        )

        assertEquals(
            listOf(NativeGroupwareItemKind.Contact, NativeGroupwareItemKind.Contact),
            nativeGroupwareCollectionPresentations(contacts, contactRows)?.map { it.second.kind },
        )
        assertEquals(
            listOf(NativeGroupwareItemKind.Event),
            nativeGroupwareCollectionPresentations(events, eventRows)?.map { it.second.kind },
        )
        assertEquals(
            null,
            nativeGroupwareCollectionPresentations(
                contacts,
                contactRows + NativeRecord("unknown", mapOf("title" to "Unclassified")),
            ),
        )
    }

    @Test
    fun `contact action uris reject ambiguous values and normalize telephone formatting`() {
        assertEquals("mailto:ada@example.test", nativeContactEmailUri("ada@example.test"))
        assertEquals(null, nativeContactEmailUri("Ada Example <ada@example.test>"))
        assertEquals(null, nativeContactEmailUri("ada@example.test?subject=Injected"))
        assertEquals("tel:+31200000000", nativeContactPhoneUri("+31 (20) 000-0000"))
        assertEquals(null, nativeContactPhoneUri("123;phone-context=example.test"))
    }

    @Test
    fun `household chores retain assignee effort and compact schedule semantics`() {
        val resource = resource("chores", "Chores", "name", "assignee", "points", "due", "repeat")
        val repeating = requireNotNull(
            nativeGroupwarePresentation(
                resource,
                NativeRecord(
                    id = "opaque-chore",
                    values = mapOf(
                        "name" to "Shared task",
                        "assignee" to "member-a",
                        "points" to "3",
                        "due" to "2026-07-25T12:00:00+02:00",
                        "repeat" to "d:2:-",
                    ),
                ),
            ),
        )
        val single = requireNotNull(
            nativeGroupwarePresentation(
                resource,
                NativeRecord(
                    id = "single-chore",
                    values = mapOf(
                        "name" to "One-off task",
                        "due" to "2026-07-26T12:00:00+02:00",
                        "repeat" to "s:1:-",
                    ),
                ),
            ),
        )

        assertEquals(NativeGroupwareItemKind.Task, repeating.kind)
        assertEquals("member-a", repeating.assignee)
        assertEquals(3, repeating.effortPoints)
        assertEquals("d:2:-", repeating.recurrenceRule)
        assertTrue(repeating.recurring)
        val repeatingSubtitle = requireNotNull(repeating.subtitle)
        assertTrue(repeatingSubtitle.contains("Every 2 days"))
        assertTrue(repeatingSubtitle.contains("Assigned to member-a"))
        assertFalse(single.recurring)
        assertFalse(single.subtitle?.contains("Recurring") == true)
    }

    @Test
    fun `signed Chores shaped records become a conservative native task collection`() {
        val resource = resource("chores", "Chores", "id", "name", "assignee", "points", "due", "repeat")
        val record = NativeRecord(
            id = "observed-chore",
            values = emptyMap(),
            displayValues = mapOf(
                "id" to "observed-chore",
                "name" to "Shared task",
                "assignee" to "member-a",
                "points" to "3",
                "repeat" to "d:2:-",
            ),
            actionSafeIdentity = false,
        )

        val collection = requireNotNull(nativeTaskCollectionPresentations(resource, listOf(record)))
        assertEquals(1, collection.size)
        assertEquals(NativeGroupwareItemKind.Task, collection.single().second.kind)
        assertEquals("Shared task", collection.single().second.title)
        assertEquals("member-a", collection.single().second.assignee)
        assertEquals(3, collection.single().second.effortPoints)
        assertFalse(collection.single().first.actionSafeIdentity)
    }

    @Test
    fun `household and completion records get reusable native summaries`() {
        val household = requireNotNull(
            nativeHouseholdPresentation(
                resource("team", "Household", "id", "name", "owner", "members", "invites"),
                NativeRecord(
                    id = "household",
                    values = mapOf(
                        "name" to "Shared home",
                        "owner" to "owner-a",
                        "members" to "[]",
                        "invites" to "[]",
                    ),
                ),
            ),
        )
        val completion = requireNotNull(
            nativeHouseholdPresentation(
                resource("work", "Completed chores", "name", "member", "work_time", "points"),
                NativeRecord(
                    id = "completion",
                    values = mapOf(
                        "name" to "Completed task",
                        "member" to "member-a",
                        "work_time" to "2026-07-23T11:00:00Z",
                        "points" to "2",
                    ),
                ),
            ),
        )

        assertEquals(NativeHouseholdItemKind.Household, household.kind)
        assertEquals("owner-a", household.owner)
        assertEquals(0, household.memberCount)
        assertEquals(0, household.invitationCount)
        assertTrue(household.subtitle?.contains("0 members") == true)
        assertEquals(NativeHouseholdItemKind.Completion, completion.kind)
        assertEquals("member-a", completion.member)
        assertEquals(2, completion.points)
        assertTrue(nativeRecordPresentation(
            resource("work", "Completed chores", "name", "member", "work_time", "points"),
            NativeRecord(
                "completion",
                mapOf(
                    "name" to "Completed task",
                    "member" to "member-a",
                    "work_time" to "2026-07-23T11:00:00Z",
                    "points" to "2",
                ),
            ),
        ).subtitle?.contains("Completed by member-a") == true)
    }

    @Test
    fun `household recognition retains empty structured member collections`() {
        val household = nativeHouseholdPresentation(
            resource("team", "Household", "name", "owner", "members", "invites"),
            NativeRecord(
                id = "team-1",
                values = mapOf("name" to "Shared home", "owner" to "owner-a"),
                structuredValues = mapOf(
                    "members" to NativeStructuredValue.ListValue(emptyList()),
                    "invites" to NativeStructuredValue.ListValue(emptyList()),
                ),
                actionSafeIdentity = false,
            ),
        )

        assertEquals(NativeHouseholdItemKind.Household, household?.kind)
        assertEquals(0, household?.memberCount)
        assertEquals(0, household?.invitationCount)
    }

    @Test
    fun `task dates and recurrence are compact native summaries`() {
        val resource = resource("rota", "Rota", "title", "due", "repeat")
        val record = NativeRecord(
            id = "task-1",
            values = mapOf(
                "title" to "Water plants",
                "due" to "2026-07-25T12:34:56Z",
                "repeat" to "w:2:-",
            ),
        )
        val presentation = requireNotNull(
            nativeGroupwarePresentation(
                resource,
                record,
            ),
        )

        val subtitle = requireNotNull(presentation.subtitle)
        assertTrue(subtitle.contains("Due 2026-07-25 12:34"))
        assertTrue(subtitle.contains("Every 2 weeks"))
        assertEquals("2026-07-25 12:34", requireNotNull(presentation.due).compactSemanticDateTime())
        assertEquals(1, nativeTaskCollectionPresentations(resource, listOf(record))?.size)
        assertEquals(
            null,
            nativeTaskCollectionPresentations(
                resource,
                listOf(record, NativeRecord("other", mapOf("title" to "Unclassified"))),
            ),
        )
    }

    @Test
    fun `typed completion fields promote generic item collections without app identifiers`() {
        val booleanResource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.high,
            fields = listOf(
                FieldSpec("label", "Label", FieldKind.string, required = true, readOnly = false),
                FieldSpec("completed", "Completed", FieldKind.boolean, required = true, readOnly = false),
            ),
        )
        val booleanRows = requireNotNull(
            nativeTaskCollectionPresentations(
                booleanResource,
                listOf(
                    NativeRecord("entry-a", mapOf("label" to "First entry", "completed" to "false")),
                    NativeRecord("entry-b", mapOf("label" to "Second entry", "completed" to "true")),
                ),
            ),
        )

        assertEquals(listOf("First entry", "Second entry"), booleanRows.map { (_, item) -> item.title })
        assertEquals(listOf(false, true), booleanRows.map { (_, item) -> item.completed })

        val enumeratedResource = ResourceSpec(
            id = "records",
            name = "Records",
            confidence = Confidence.high,
            fields = listOf(
                FieldSpec("label", "Label", FieldKind.string, required = true, readOnly = false),
                FieldSpec(
                    "state",
                    "State",
                    FieldKind.enumeration,
                    required = true,
                    readOnly = false,
                    enumValues = listOf("active", "finished"),
                ),
            ),
        )
        val enumeratedRows = requireNotNull(
            nativeTaskCollectionPresentations(
                enumeratedResource,
                listOf(
                    NativeRecord("record-a", mapOf("label" to "Open record", "state" to "active")),
                    NativeRecord("record-b", mapOf("label" to "Closed record", "state" to "finished")),
                ),
            ),
        )

        assertEquals(listOf("Open record", "Closed record"), enumeratedRows.map { (_, item) -> item.title })
        assertEquals(listOf(false, true), enumeratedRows.map { (_, item) -> item.completed })
    }

    @Test
    fun `non-task boolean state aliases do not render as task completion`() {
        listOf(
            "enabled" to "Status",
            "published" to "State",
            "available" to "Status",
        ).forEach { (stateFieldId, stateFieldLabel) ->
            val resource = ResourceSpec(
                id = "entries",
                name = "Entries",
                confidence = Confidence.high,
                fields = listOf(
                    FieldSpec("label", "Label", FieldKind.string, required = true, readOnly = false),
                    FieldSpec(
                        stateFieldId,
                        stateFieldLabel,
                        FieldKind.boolean,
                        required = true,
                        readOnly = false,
                    ),
                ),
            )
            val record = NativeRecord(
                "entry-a",
                mapOf("label" to "Ordinary record", stateFieldId to "true"),
            )

            assertEquals(null, nativeGroupwarePresentation(resource, record))
            assertEquals(null, nativeTaskCollectionPresentations(resource, listOf(record)))
        }
    }

    @Test
    fun `unrelated boolean fields do not promote ordinary records to tasks`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.high,
            fields = listOf(
                FieldSpec("label", "Label", FieldKind.string, required = true, readOnly = false),
                FieldSpec("favorite", "Favorite", FieldKind.boolean, required = false, readOnly = false),
            ),
        )

        assertEquals(
            null,
            nativeTaskCollectionPresentations(
                resource,
                listOf(NativeRecord("entry-a", mapOf("label" to "Ordinary record", "favorite" to "true"))),
            ),
        )
    }

    @Test
    fun `ordinary cards do not become contacts from their resource name alone`() {
        val presentation = nativeGroupwarePresentation(
            resource("cards", "Cards", "title", "stackId"),
            NativeRecord("card-1", mapOf("title" to "Review", "stackId" to "doing")),
        )

        assertEquals(null, presentation)
    }

    @Test
    fun `category fields preserve hierarchy counts and shared report state`() {
        val categories = resource(
            "categories",
            "Categories",
            "name", "type", "parentId", "transactionCount", "_shared", "_canWrite",
            "_sharedByName", "excludedFromReports",
        )
        val presentation = requireNotNull(
            nativeCategoryPresentation(
                categories,
                NativeRecord(
                    "child-1",
                    mapOf(
                        "name" to "Shared groceries",
                        "type" to "expense",
                        "parentId" to "food",
                        "transactionCount" to "14",
                        "_shared" to "true",
                        "_canWrite" to "false",
                        "_sharedByName" to "Morgan",
                        "excludedFromReports" to "true",
                    ),
                ),
            ),
        )

        assertEquals("Shared groceries", presentation.name)
        assertEquals(NativeCategoryKind.Expense, presentation.kind)
        assertEquals("food", presentation.parentId)
        assertEquals(14, presentation.transactionCount)
        assertTrue(presentation.shared)
        assertFalse(presentation.writable)
        assertEquals("Morgan", presentation.sharedBy)
        assertTrue(presentation.mutedFromReports)
    }

    @Test
    fun `category renderer does not claim transaction resources with category labels`() {
        val transactions = resource(
            "transactions",
            "Transactions",
            "description", "categoryName", "type",
        )

        assertEquals(
            null,
            nativeCategoryPresentation(
                transactions,
                NativeRecord(
                    "transaction-1",
                    mapOf(
                        "description" to "Weekly groceries",
                        "categoryName" to "Groceries",
                        "type" to "debit",
                    ),
                ),
            ),
        )
    }

    private fun resource(id: String, name: String, vararg fields: String): ResourceSpec = ResourceSpec(
        id = id,
        name = name,
        confidence = Confidence.high,
        fields = fields.map { field ->
            FieldSpec(
                id = field,
                label = field,
                kind = FieldKind.string,
                required = false,
                readOnly = true,
            )
        },
    )
}
