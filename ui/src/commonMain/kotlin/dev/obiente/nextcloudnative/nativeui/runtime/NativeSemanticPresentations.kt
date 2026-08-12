package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Instant

internal enum class NativeMailboxItemKind {
    Account,
    Folder,
    Message,
    Unknown,
}

internal data class NativeMailboxPresentation(
    val kind: NativeMailboxItemKind,
    val title: String,
    val sender: String?,
    val preview: String?,
    val timestamp: String?,
    val unread: Boolean,
    val unreadCount: Int?,
    val threadSize: Int?,
    val flagged: Boolean,
    val attachmentCount: Int,
)

internal data class NativeMailMessageDetailPresentation(
    val subject: String,
    val sender: String?,
    val recipients: String?,
    val timestamp: String?,
    val body: String?,
    val htmlBody: Boolean,
    val attachmentCount: Int,
)

internal data class NativeMailMessageRenderTarget(
    val resource: ResourceSpec,
    val record: NativeRecord,
    val presentation: NativeMailMessageDetailPresentation,
)

/**
 * Keeps Mail sender labels focused on the person instead of repeating an address that is already
 * available in message details. Unknown envelope formats remain unchanged rather than guessed.
 */
internal fun nativeMailSenderLabel(sender: String?): String? {
    val trimmed = sender?.trim()?.takeIf(String::isNotBlank) ?: return null
    val displayName = MAIL_SENDER_WITH_ADDRESS.matchEntire(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.trim('"')
        ?.takeIf(String::isNotBlank)
    return displayName ?: trimmed
}

/**
 * Formats the common ISO envelope timestamp compactly for message rows without depending on the
 * device locale. Other server-provided formats are preserved verbatim.
 */
internal fun nativeMailTimestampLabel(timestamp: String?): String? {
    val trimmed = timestamp?.trim()?.takeIf(String::isNotBlank) ?: return null
    val match = MAIL_ISO_TIMESTAMP.matchEntire(trimmed) ?: return trimmed
    val monthIndex = match.groupValues[2].toIntOrNull() ?: return trimmed
    val month = MAIL_MONTH_NAMES.getOrNull(monthIndex - 1) ?: return trimmed
    val day = match.groupValues[3].toIntOrNull() ?: return trimmed
    return "$month $day, ${match.groupValues[4]}:${match.groupValues[5]}"
}

private val MAIL_SENDER_WITH_ADDRESS = Regex("^\\s*(.*?)\\s*<[^<>]+>\\s*$")
private val MAIL_ISO_TIMESTAMP = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2})(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?$",
)
private val MAIL_MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal enum class NativeMediaItemKind {
    Artist,
    Album,
    Track,
    Playlist,
    Genre,
    Radio,
    Podcast,
    Unknown,
}

internal data class NativeMediaPresentation(
    val kind: NativeMediaItemKind,
    val title: String,
    val artist: String?,
    val album: String?,
    val detail: String?,
    val duration: String?,
    val trackNumber: String?,
    val favorite: Boolean,
    val coverUrl: String?,
)

internal enum class NativeGroupwareItemKind {
    Contact,
    Event,
    Task,
}

internal data class NativeGroupwarePresentation(
    val kind: NativeGroupwareItemKind,
    val title: String,
    val subtitle: String?,
    val start: String?,
    val end: String?,
    val due: String?,
    val status: String?,
    val location: String?,
    val recurring: Boolean,
    val completed: Boolean,
    val completionPercent: Int?,
    val priority: Int?,
    val assignee: String?,
    val recurrenceRule: String?,
    val effortPoints: Int?,
    val organization: String?,
    val primaryEmail: String?,
    val primaryPhone: String?,
    val description: String?,
    val address: String?,
    val birthday: String?,
    val organizer: String?,
    val attendeeCount: Int?,
    val allDay: Boolean,
)

private data class NativeTaskCompletionSemantics(
    val field: FieldSpec,
    val completedWireValue: String,
    val incompleteWireValue: String,
) {
    fun read(record: NativeRecord): Boolean? {
        val rawValue = record.values[field.id]?.trim() ?: return null
        return when (field.kind) {
            FieldKind.boolean -> when (rawValue.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            FieldKind.enumeration -> when (rawValue) {
                completedWireValue -> true
                incompleteWireValue -> false
                else -> null
            }
            else -> null
        }
    }
}

internal enum class NativeHouseholdItemKind {
    Household,
    Member,
    Invitation,
    Completion,
}

internal data class NativeHouseholdPresentation(
    val kind: NativeHouseholdItemKind,
    val title: String,
    val subtitle: String?,
    val owner: String?,
    val member: String?,
    val points: Int?,
    val completedAt: String?,
    val memberCount: Int?,
    val invitationCount: Int?,
)

internal data class NativeFinancePresentation(
    val title: String,
    val amount: Double,
    val currency: String?,
    val date: String?,
    val participant: String?,
    val splitParticipants: List<String>,
    val category: String?,
    val paymentMethod: String?,
    val note: String?,
    val direction: NativeFinanceDirection,
)

internal enum class NativeFinanceDirection { Credit, Debit, Unspecified }

internal enum class NativeFinancialAccountKind { Asset, Liability, Other }

internal data class NativeFinancialAccountPresentation(
    val name: String,
    val balance: Double,
    val currency: String?,
    val type: String?,
    val kind: NativeFinancialAccountKind,
    val institution: String?,
    val accountNumber: String?,
    val lastReconciled: String?,
    val convertedBalance: Double?,
    val baseCurrency: String?,
    val excludedFromReports: Boolean,
)

internal enum class NativeCategoryKind { Expense, Income, Other }

internal data class NativeCategoryPresentation(
    val name: String,
    val kind: NativeCategoryKind,
    val parentId: String?,
    val transactionCount: Int?,
    val shared: Boolean,
    val writable: Boolean,
    val sharedBy: String?,
    val mutedFromReports: Boolean,
)

internal data class NativeBudgetCategoryProgress(
    val id: String?,
    val name: String,
    val budgeted: Double,
    val baseBudget: Double,
    val carried: Double,
    val spent: Double,
    val remaining: Double,
    val percentage: Double,
    val status: String?,
    val color: String?,
)

internal data class NativeBudgetPlanPresentation(
    val startDate: String?,
    val endDate: String?,
    val budgeted: Double,
    val spent: Double,
    val remaining: Double,
    val overallStatus: String?,
    val categories: List<NativeBudgetCategoryProgress>,
) {
    val percentage: Double = if (budgeted > 0.0) spent / budgeted * 100.0 else 0.0
}

internal data class NativeFinanceMemberStatistic(
    val name: String,
    val paid: Double,
    val spent: Double,
    val balance: Double,
)

internal data class NativeFinanceDashboardPresentation(
    val members: List<NativeFinanceMemberStatistic>,
    val monthlySpending: List<NativeChartPoint>,
    val categories: List<NativeChartPoint>,
    val paymentMethods: List<NativeChartPoint>,
) {
    val totalPaid: Double = members.sumOf(NativeFinanceMemberStatistic::paid)
    val totalSpent: Double = members.sumOf(NativeFinanceMemberStatistic::spent)
    val balance: Double = totalPaid - totalSpent
}

internal fun nativeMailboxPresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeMailboxPresentation {
    val values = NativeSemanticValues(record)
    val resourceWords = semanticTokens(resource.id, resource.name)
    val isMessage = resourceWords.any { it in MESSAGE_WORDS } ||
        values.hasAny("subject", "sender", "from", "fromemail", "messageid", "threadid")
    val isFolder = !isMessage && (resourceWords.any { it in FOLDER_WORDS } ||
        values.hasAny("specialuse", "specialrole", "unreadcount", "totalmessages"))
    val isAccount = !isMessage && !isFolder && (resourceWords.any { it in ACCOUNT_WORDS } ||
        values.hasAny("email", "emailaddress", "accountname"))
    val kind = when {
        isMessage -> NativeMailboxItemKind.Message
        isFolder -> NativeMailboxItemKind.Folder
        isAccount -> NativeMailboxItemKind.Account
        else -> NativeMailboxItemKind.Unknown
    }
    val unreadCount = values.int("unreadcount", "unread", "unseen", "unseenmessages")
        ?.takeIf { kind == NativeMailboxItemKind.Folder }
    val seen = values.boolean("seen", "isread", "read")
    val explicitlyUnread = values.boolean("unread", "isunread", "unseen")
    val flags = values.string("flags")?.lowercase().orEmpty()
    val hasFlags = values.hasAny("flags")
    val unread = when {
        kind == NativeMailboxItemKind.Folder -> (unreadCount ?: 0) > 0
        explicitlyUnread != null -> explicitlyUnread
        seen != null -> !seen
        hasFlags -> "seen" !in flags
        else -> false
    }
    val title = when (kind) {
        NativeMailboxItemKind.Message -> values.string("subject", "title") ?: "(No subject)"
        NativeMailboxItemKind.Folder -> values.string("displayname", "name", "label", "path") ?: record.id
        NativeMailboxItemKind.Account -> values.string("name", "accountname", "displayname", "email", "emailaddress")
            ?: record.id
        NativeMailboxItemKind.Unknown -> nativeRecordPresentation(resource, record).title
    }
    val sender = when (kind) {
        NativeMailboxItemKind.Message -> values.person("from", "sender", "author", "fromemail")
        NativeMailboxItemKind.Account -> values.string("email", "emailaddress")?.takeUnless { it == title }
        else -> null
    }
    val preview = values.string("preview", "snippet", "bodypreview", "summary", "text")
        ?.replace('\n', ' ')
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val timestamp = values.formattedTimestamp()
    val flagged = values.boolean("flagged", "starred", "favorite", "favourite") == true || "flagged" in flags
    val threadSize = values.int("messagecount", "messagescount", "threadsize", "threadcount")
        ?: values.arraySize("messages")
    val attachmentCount = values.int("attachmentcount", "attachmentscount")
        ?: values.arraySize("attachments")
        ?: if (values.boolean("hasattachments", "hasattachment") == true) 1 else 0
    return NativeMailboxPresentation(
        kind = kind,
        title = title,
        sender = sender,
        preview = preview,
        timestamp = timestamp,
        unread = unread,
        unreadCount = unreadCount,
        threadSize = threadSize?.takeIf { count -> kind == NativeMailboxItemKind.Message && count > 1 },
        flagged = flagged,
        attachmentCount = attachmentCount,
    )
}

/** Shape-based mail detail semantics, reusable for APIs exposing standard envelope fields. */
internal fun nativeMailMessageDetailPresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeMailMessageDetailPresentation? {
    val values = NativeSemanticValues(record)
    val words = semanticTokens(resource.id, resource.name)
    val messageShape = values.hasAny("subject") &&
        values.hasAny("from", "sender", "author", "fromemail") &&
        values.hasAny("body", "bodyhtml", "bodyplain", "content", "htmlbody", "messagebody", "text")
    if (!messageShape && words.none { it in MESSAGE_WORDS }) return null
    val body = values.string("body", "bodyhtml", "bodyplain", "content", "htmlbody", "messagebody", "text")
    if (body.isNullOrBlank() && !messageShape) return null
    return NativeMailMessageDetailPresentation(
        subject = values.string("subject", "title") ?: "(No subject)",
        sender = values.person("from", "sender", "author", "fromemail"),
        recipients = values.string("to", "recipients", "recipient"),
        timestamp = values.formattedTimestamp(),
        body = body,
        htmlBody = values.boolean("hashtmlbody", "html", "ishtml") == true ||
            values.hasAny("bodyhtml", "htmlbody"),
        attachmentCount = values.arraySize("attachments")
            ?: values.int("attachmentcount", "attachmentscount")
            ?: 0,
    )
}

/** Converts a bounded nested thread response into independently renderable message bodies. */
internal fun nativeMailThreadPresentations(
    resource: ResourceSpec,
    record: NativeRecord,
): List<NativeMailMessageDetailPresentation> {
    val threadItems = record.structuredValues.entries.firstNotNullOfOrNull { (key, value) ->
        if (key.semanticKey() !in setOf("messages", "threadmessages", "conversationmessages")) {
            return@firstNotNullOfOrNull null
        }
        value as? NativeStructuredValue.ListValue
    } ?: return emptyList()
    return threadItems.items.mapIndexedNotNull { index, item ->
        val message = item as? NativeStructuredValue.ObjectValue ?: return@mapIndexedNotNull null
        val values = mutableMapOf<String, String?>()
        val structuredValues = mutableMapOf<String, NativeStructuredValue>()
        message.entries.forEach { entry ->
            when (val value = entry.value) {
                is NativeStructuredValue.Scalar -> values[entry.key] = value.value
                is NativeStructuredValue.ListValue,
                is NativeStructuredValue.ObjectValue,
                -> structuredValues[entry.key] = value
            }
        }
        val id = listOf("id", "messageid", "uid")
            .firstNotNullOfOrNull { alias ->
                values.entries.firstOrNull { (key, _) -> key.semanticKey() == alias }?.value
            }
            ?.takeIf(String::isNotBlank)
            ?: "thread-message-$index"
        nativeMailMessageDetailPresentation(
            resource = resource,
            record = NativeRecord(
                id = id,
                values = values,
                structuredValues = structuredValues,
                actionSafeIdentity = false,
            ),
        )
    }
}

/**
 * Rejoins a message envelope with a separately fetched body/detail facet.
 *
 * APIs commonly return sparse messages in a mailbox collection and expose body plus attachments
 * from `/messages/{id}/body`. The dynamic navigator correctly follows that child, but rendering it
 * as an unrelated record loses the subject, sender and message-scoped actions. This shape-based
 * join retains the authoritative parent identity while letting the richer facet override fields.
 */
internal fun nativeMailMessageRenderTarget(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
    context: NativeDatasetContext,
): NativeMailMessageRenderTarget? {
    val parentRecord = context.parentRecord
    val parentResource = context.parentResourceId?.let(schema::resource)
    if (
        parentRecord != null &&
        parentResource != null &&
        nativeMailboxPresentation(parentResource, parentRecord).kind == NativeMailboxItemKind.Message
    ) {
        val facetWords = semanticTokens(resource.id, resource.name)
        val facetHasMessageContent = facetWords.any { word ->
            word in setOf("body", "content", "detail", "html", "messagebody")
        } || record.hasMailBodyOrAttachmentShape()
        if (facetHasMessageContent) {
            val merged = parentRecord.copy(
                values = parentRecord.values + record.values,
                displayValues = parentRecord.displayValues + record.displayValues,
                ephemeralFields = (parentRecord.ephemeralFields + record.ephemeralFields)
                    .distinctBy { field -> field.id.lowercase() },
                structuredValues = parentRecord.structuredValues + record.structuredValues,
                bindingContext = parentRecord.bindingContext + record.bindingContext,
            )
            nativeMailMessageDetailPresentation(parentResource, merged)?.let { presentation ->
                return NativeMailMessageRenderTarget(parentResource, merged, presentation)
            }
        }
    }
    return nativeMailMessageDetailPresentation(resource, record)?.let { direct ->
        NativeMailMessageRenderTarget(resource, record, direct)
    }
}

private fun NativeRecord.hasMailBodyOrAttachmentShape(): Boolean {
    val keys = buildSet {
        values.keys.forEach { add(it.semanticKey()) }
        displayValues.keys.forEach { add(it.semanticKey()) }
        structuredValues.keys.forEach { add(it.semanticKey()) }
    }
    return keys.any { key ->
        key in setOf(
            "attachments",
            "body",
            "bodyhtml",
            "bodyplain",
            "content",
            "htmlbody",
            "inlineattachments",
            "messagebody",
            "text",
        )
    }
}

internal fun nativeMediaPresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeMediaPresentation {
    val values = NativeSemanticValues(record)
    val words = semanticTokens(resource.id, resource.name)
    val kind = when {
        words.any { it in setOf("track", "tracks", "song", "songs") } ||
            values.hasAny("tracknumber", "ordinal", "discnumber", "bitrate") -> NativeMediaItemKind.Track
        words.any { it in setOf("album", "albums") } -> NativeMediaItemKind.Album
        words.any { it in setOf("artist", "artists", "composer", "composers") } -> NativeMediaItemKind.Artist
        words.any { it in setOf("playlist", "playlists", "queue") } -> NativeMediaItemKind.Playlist
        words.any { it in setOf("genre", "genres") } -> NativeMediaItemKind.Genre
        words.any { it in setOf("radio", "station", "stations") } -> NativeMediaItemKind.Radio
        words.any { it in setOf("podcast", "podcasts", "episode", "episodes") } -> NativeMediaItemKind.Podcast
        else -> NativeMediaItemKind.Unknown
    }
    val title = values.string("title", "name", "track", "song", "displayname")
        ?: nativeRecordPresentation(resource, record).title
    val artist = values.person("artist", "artists", "artistname", "albumartist", "creator", "author")
        ?.takeUnless(String::isStructuralSummary)
    val album = values.string("album", "albumname", "release")
        ?.takeUnless { it == title || it.isStructuralSummary() }
    val durationSeconds = values.number("duration", "length", "durationseconds")
    val count = values.int("trackcount", "songcount", "albumcount", "childcount", "size")
    val year = values.string("year", "released", "releaseyear")
    val genre = values.string("genre", "genres")
    val codec = values.objectKeys("files", "streams", "sources")
        .firstOrNull()
        ?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
        ?.uppercase()
    val bitrate = values.number("bitrate", "bitratebps")
        ?.takeIf { it > 0 }
        ?.div(1_000.0)
        ?.toInt()
        ?.let { "$it kbps" }
    val detail = listOfNotNull(year, genre, count?.let { "$it items" }, codec, bitrate)
        .distinct().joinToString(" · ")
        .takeIf(String::isNotBlank)
    return NativeMediaPresentation(
        kind = kind,
        title = title,
        artist = artist,
        album = album,
        detail = detail,
        duration = durationSeconds?.let(::formatMediaDuration),
        trackNumber = values.string("tracknumber", "trackno", "ordinal", "number", "index")
            ?.takeIf(String::isNotBlank),
        favorite = values.boolean("favorite", "favourite", "starred", "liked") == true,
        coverUrl = values.string("coverurl", "artworkurl", "imageurl", "cover", "artwork", "image")
            ?.let(::safeNativeAssetPath),
    )
}

/**
 * Recognizes transaction-like rows by their declared resource semantics and amount shape.
 * This deliberately stays app-neutral so shared-expense, bookkeeping, invoice, and budget
 * datasets can reuse the same native ledger row without an app-id adapter.
 */
internal fun nativeFinancePresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeFinancePresentation? {
    val words = semanticTokens(resource.id, resource.name)
    val values = NativeSemanticValues(record)
    val hasFinanceResourceSemantics = words.any(FINANCE_WORDS::contains)
    val hasTransactionRecordShape = values.hasAny(
        "what", "merchant", "payerid", "payername", "paidby",
        "paymentmode", "paymentmethod", "categoryid", "owers",
    )
    if (!hasFinanceResourceSemantics && !hasTransactionRecordShape) return null
    val rawAmount = values.number(
        "amount", "value", "total", "cost", "price", "expense", "income", "balance",
    ) ?: return null
    val direction = when (values.string("type", "transactiontype", "direction")?.lowercase()) {
        "credit", "income", "deposit" -> NativeFinanceDirection.Credit
        "debit", "expense", "withdrawal", "payment" -> NativeFinanceDirection.Debit
        else -> NativeFinanceDirection.Unspecified
    }
    val amount = when (direction) {
        NativeFinanceDirection.Credit -> kotlin.math.abs(rawAmount)
        NativeFinanceDirection.Debit -> -kotlin.math.abs(rawAmount)
        NativeFinanceDirection.Unspecified -> rawAmount
    }
    val title = values.string(
        "what", "title", "name", "description", "label", "subject", "merchant",
    ) ?: "Transaction"
    val participants = values.references("owers", "participants", "members", "split")
    val payerId = values.string("payerid", "paidbyid", "participantid", "memberid")
    val payer = values.person(
        "payername", "payer", "paidby", "membername", "member", "username", "user",
    ) ?: participants.firstOrNull { reference -> reference.id == payerId }?.label
    return NativeFinancePresentation(
        title = title,
        amount = amount,
        currency = values.string("currency", "currencycode", "currencyname", "unit")
            ?: resource.fields.firstOrNull { field ->
                field.id.semanticKey() in setOf("amount", "value", "total") &&
                    field.kind == FieldKind.currency
            }?.format,
        date = values.formattedTimestamp()?.compactSemanticDateTime(),
        participant = payer,
        splitParticipants = participants.map(NativeSemanticReference::label).distinct(),
        category = values.string("categoryname", "category", "groupname", "group"),
        paymentMethod = values.string(
            "paymentmodename", "paymentmode", "paymentmethod", "accountname", "account",
        )?.takeIf { it.length > 1 },
        note = values.string("comment", "note", "notes", "memo")
            ?.takeUnless { it.equals(title, ignoreCase = true) },
        direction = direction,
    )
}

/**
 * Recognizes an account balance record without confusing transaction rows that merely reference an
 * account. Account collections need asset/liability semantics, not ledger income/expense filters.
 */
internal fun nativeFinancialAccountPresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeFinancialAccountPresentation? {
    val resourceWords = semanticTokens(resource.id, resource.name)
    if (resourceWords.none { word -> word == "account" || word == "accounts" }) return null
    val values = NativeSemanticValues(record)
    val balance = values.number("balance", "currentbalance") ?: return null
    val type = values.string("type", "accounttype")?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    val kind = when (type) {
        "checking", "savings", "investment", "cash", "cryptocurrency", "money_market" ->
            NativeFinancialAccountKind.Asset
        "credit_card", "loan", "mortgage", "line_of_credit" ->
            NativeFinancialAccountKind.Liability
        else -> NativeFinancialAccountKind.Other
    }
    return NativeFinancialAccountPresentation(
        name = values.string("name", "accountname", "title", "label")
            ?: nativeRecordPresentation(resource, record).title,
        balance = balance,
        currency = values.string("currency", "currencycode", "currencyname", "unit"),
        type = type,
        kind = kind,
        institution = values.string("institution", "bank", "provider"),
        accountNumber = values.string("accountnumber", "number", "ibanmasked", "maskednumber"),
        lastReconciled = values.string("lastreconciled", "lastreconciledat")
            ?.compactSemanticDateTime(),
        convertedBalance = values.number("convertedbalance", "basebalance"),
        baseCurrency = values.string("basecurrency", "reportingcurrency"),
        excludedFromReports = values.boolean("excludedfromreports", "excluded", "isexcluded") == true,
    )
}

internal fun nativeFinancialAccountCollectionPresentations(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): List<Pair<NativeRecord, NativeFinancialAccountPresentation>>? {
    if (records.isEmpty()) return null
    val rows = records.mapNotNull { record ->
        nativeFinancialAccountPresentation(resource, record)?.let { presentation -> record to presentation }
    }
    return rows.takeIf { it.size == records.size }
}

internal fun nativeCategoryPresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeCategoryPresentation? {
    val resourceWords = semanticTokens(resource.id, resource.name)
    if (resourceWords.none { word -> word == "category" || word == "categories" }) return null
    val values = NativeSemanticValues(record)
    val kind = when (values.string("type", "categorytype")?.lowercase()) {
        "expense", "expenses" -> NativeCategoryKind.Expense
        "income", "incomes" -> NativeCategoryKind.Income
        else -> NativeCategoryKind.Other
    }
    val shared = values.boolean("shared", "isshared") == true ||
        values.hasAny("sharedby", "sharedbyname", "sharedowner")
    return NativeCategoryPresentation(
        name = values.string("name", "categoryname", "title", "label")
            ?: nativeRecordPresentation(resource, record).title,
        kind = kind,
        parentId = values.string("parentid", "parent", "parentcategoryid")
            ?.takeIf { it.isNotBlank() && it != "0" && !it.equals("null", ignoreCase = true) },
        transactionCount = values.int("transactioncount", "transactions", "count")
            ?.takeIf { it >= 0 },
        shared = shared,
        writable = !shared || values.boolean("canwrite", "writable", "sharedwrite") == true,
        sharedBy = values.string("sharedbyname", "sharedby", "sharedowner"),
        mutedFromReports = values.boolean(
            "muted",
            "reportmuted",
            "hiddenfromreports",
            "excludedfromreports",
        ) == true,
    )
}

internal fun nativeCategoryCollectionPresentations(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): List<Pair<NativeRecord, NativeCategoryPresentation>>? {
    if (records.isEmpty()) return null
    val rows = records.mapNotNull { record ->
        nativeCategoryPresentation(resource, record)?.let { presentation -> record to presentation }
    }
    return rows.takeIf { it.size == records.size }
}

internal fun nativeFinanceCollectionPresentations(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): List<Pair<NativeRecord, NativeFinancePresentation?>>? {
    if (records.isEmpty()) return null
    val presentations = records.map { record -> record to nativeFinancePresentation(resource, record) }
    return presentations.takeIf { rows -> rows.any { (_, presentation) -> presentation != null } }
}

internal fun formatNativeFinanceAmount(amount: Double, currency: String?): String {
    val rounded = kotlin.math.round(amount * 100.0) / 100.0
    val stableAmount = if (kotlin.math.abs(amount) < 0.0051) 0.0 else rounded
    val normalized = if (!currency.isNullOrBlank()) {
        val absoluteCents = kotlin.math.round(kotlin.math.abs(stableAmount) * 100.0).toLong()
        val sign = if (stableAmount < 0.0) "-" else ""
        "$sign${absoluteCents / 100}.${(absoluteCents % 100).toString().padStart(2, '0')}"
    } else if (stableAmount == stableAmount.toLong().toDouble()) {
        stableAmount.toLong().toString()
    } else {
        stableAmount.toString().trimEnd('0').trimEnd('.')
    }
    return listOfNotNull(currency?.trim()?.takeIf(String::isNotBlank), normalized).joinToString(" ")
}

/**
 * Promotes nested statistical responses into a reusable finance dashboard. The recognizer is
 * based on balance/paid/spent and aggregate-series shapes, not an app or endpoint name.
 */
internal fun nativeFinanceDashboardPresentation(
    record: NativeRecord,
): NativeFinanceDashboardPresentation? {
    val structured = record.structuredValues.entries.associateBy { (key, _) -> key.semanticKey() }
    val stats = structured["stats"]?.value as? NativeStructuredValue.ListValue ?: return null
    val members = stats.items.mapNotNull { item ->
        val row = item.semanticObjectEntries() ?: return@mapNotNull null
        val member = row["member"]?.semanticObjectEntries()
        val name = member?.semanticText("name", "displayname", "label") ?: return@mapNotNull null
        val paid = row.semanticNumber("paid") ?: return@mapNotNull null
        val spent = row.semanticNumber("spent") ?: return@mapNotNull null
        NativeFinanceMemberStatistic(
            name = name,
            paid = paid,
            spent = spent,
            balance = row.semanticNumber("balance", "filteredbalance") ?: paid - spent,
        )
    }
    if (members.isEmpty()) return null
    return NativeFinanceDashboardPresentation(
        members = members,
        monthlySpending = structured["membermonthlyspentstats"]
            ?.value
            .semanticTotalSeries()
            .filter { point -> point.label.matches(Regex("\\d{4}-\\d{2}")) },
        categories = structured["categorystats"]?.value.semanticNamedSeries("Category"),
        paymentMethods = structured["paymentmodestats"]?.value.semanticNamedSeries("Payment method"),
    )
}

/** Recognizes category-by-category budget reports by their nested totals and progress shape. */
internal fun nativeBudgetPlanPresentation(record: NativeRecord): NativeBudgetPlanPresentation? {
    val structured = record.structuredValues.entries.associateBy { (key, _) -> key.semanticKey() }
    val totals = structured["totals"]?.value?.semanticObjectEntries() ?: return null
    val categoryItems = (structured["categories"]?.value as? NativeStructuredValue.ListValue)?.items
        ?: return null
    val categories = categoryItems.mapNotNull { item ->
        val row = item.semanticObjectEntries() ?: return@mapNotNull null
        val name = row.semanticText("categoryname", "name", "label") ?: return@mapNotNull null
        val budgeted = row.semanticNumber("budgeted", "budget", "limit") ?: return@mapNotNull null
        val spent = row.semanticNumber("spent") ?: return@mapNotNull null
        NativeBudgetCategoryProgress(
            id = row.semanticText("categoryid", "id"),
            name = name,
            budgeted = budgeted,
            baseBudget = row.semanticNumber("basebudget") ?: budgeted,
            carried = row.semanticNumber("carried", "carryover") ?: 0.0,
            spent = spent,
            remaining = row.semanticNumber("remaining") ?: budgeted - spent,
            percentage = row.semanticNumber("percentage", "percent")
                ?: if (budgeted > 0.0) spent / budgeted * 100.0 else 0.0,
            status = row.semanticText("status"),
            color = row.semanticText("color"),
        )
    }
    if (categories.isEmpty()) return null
    val period = structured["period"]?.value?.semanticObjectEntries()
    val budgeted = totals.semanticNumber("budgeted") ?: return null
    val spent = totals.semanticNumber("spent") ?: return null
    return NativeBudgetPlanPresentation(
        startDate = period?.semanticText("startdate", "start"),
        endDate = period?.semanticText("enddate", "end"),
        budgeted = budgeted,
        spent = spent,
        remaining = totals.semanticNumber("remaining") ?: budgeted - spent,
        overallStatus = structured["overallstatus"]?.value?.semanticString(),
        categories = categories.sortedByDescending(NativeBudgetCategoryProgress::percentage),
    )
}

private fun NativeStructuredValue?.semanticNamedSeries(
    labelPrefix: String,
): List<NativeChartPoint> {
    val entries = (this as? NativeStructuredValue.ObjectValue)?.entries.orEmpty()
    return entries.mapNotNull { entry ->
        entry.value.semanticNumber()?.let { amount ->
            NativeChartPoint(
                label = entry.label.takeUnless { label -> label.isBlank() || label.toDoubleOrNull() != null }
                    ?: "$labelPrefix ${entry.key}",
                value = amount,
            )
        }
    }.sortedByDescending { point -> kotlin.math.abs(point.value) }
}

private fun NativeStructuredValue?.semanticTotalSeries(): List<NativeChartPoint> {
    val entries = (this as? NativeStructuredValue.ObjectValue)?.entries.orEmpty()
    return entries.mapNotNull { entry ->
        val totals = entry.value.semanticObjectEntries() ?: return@mapNotNull null
        val total = totals.semanticNumber("total", "0")
            ?: totals.values.mapNotNull(NativeStructuredValue::semanticNumber).sum().takeIf { it != 0.0 }
            ?: return@mapNotNull null
        NativeChartPoint(entry.label.ifBlank { entry.key }, total)
    }
}

private fun NativeStructuredValue.semanticObjectEntries(): Map<String, NativeStructuredValue>? =
    (this as? NativeStructuredValue.ObjectValue)
        ?.entries
        ?.associate { entry -> entry.key.semanticKey() to entry.value }

private fun Map<String, NativeStructuredValue>.semanticText(vararg aliases: String): String? =
    aliases.firstNotNullOfOrNull { alias ->
        get(alias.semanticKey())?.semanticString()?.takeIf(String::isNotBlank)
    }

private fun Map<String, NativeStructuredValue>.semanticNumber(vararg aliases: String): Double? =
    aliases.firstNotNullOfOrNull { alias -> get(alias.semanticKey())?.semanticNumber() }

private fun NativeStructuredValue.semanticNumber(): Double? =
    (this as? NativeStructuredValue.Scalar)?.value?.toDoubleOrNull()

/**
 * Recognizes common CardDAV and CalDAV property names without requiring an app-specific schema.
 * Unknown records return null so the generic field-priority renderer remains the fallback.
 */
internal fun nativeGroupwarePresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeGroupwarePresentation? {
    val values = NativeSemanticValues(record)
    val words = semanticTokens(resource.id, resource.name)
    val typedCompletion = resource.uniqueNativeTaskCompletionSemantics()
        ?.takeIf { semantics ->
            semantics.read(record) != null &&
                values.hasAny("summary", "title", "name", "displayname", "label")
        }
    val taskShape = values.hasAny(
        "assignee", "assignedto", "due", "duedate", "percentcomplete", "priority",
        "relatedto",
    ) || typedCompletion != null
    val completionShape = values.hasAny("worktime", "completedat", "donetimestamp") &&
        values.hasAny("member", "assignee", "user")
    val eventShape = values.hasAny("dtstart", "start", "startdate") &&
        values.hasAny("summary", "title", "dtend", "end", "enddate", "location")
    val contactShape = values.hasAny("fn", "formattedname", "displayname") &&
        values.hasAny("email", "emails", "tel", "phone", "telephone", "org", "organization")
    val contactEvidence = values.hasAny(
        "fn", "formattedname", "email", "emails", "tel", "phone", "telephone", "org", "organization",
    )
    val kind = when {
        completionShape -> return null
        words.any { it in TASK_WORDS } || taskShape -> NativeGroupwareItemKind.Task
        eventShape -> NativeGroupwareItemKind.Event
        words.any { it in CONTACT_WORDS } && contactEvidence || contactShape -> NativeGroupwareItemKind.Contact
        else -> return null
    }

    val start = values.string("dtstart", "start", "startdate")
    val end = values.string("dtend", "end", "enddate")
    val due = values.string("due", "duedate")
    val status = values.string("status", "state")
        ?: typedCompletion
            ?.takeIf { semantics -> semantics.field.kind == FieldKind.enumeration }
            ?.let { semantics -> record.values[semantics.field.id] }
    val recurrence = values.string("rrule", "recurrencerule", "recurrenceid")
        ?: values.string("repeat")
    val recurring = recurrence?.isRecurringTaskRule() == true
    val completionPercent = values.int("percentcomplete", "completionpercent", "progress")
        ?.coerceIn(0, 100)
    val completedValue = values.string("completedat")
    val completed = typedCompletion?.read(record) ?: (
        values.boolean("completed", "done") == true ||
        status.equals("completed", ignoreCase = true) ||
        completionPercent == 100 ||
        !completedValue.isNullOrBlank()
        )
    val organization = values.string("org", "organization", "company")
    val email = values.person("email", "emails", "mail")
    val phone = values.string("tel", "phone", "telephone", "phones")
    val location = values.string("location", "place")
    val description = values.string("description", "notes", "note", "comment")
    val address = values.string("address", "adr", "streetaddress")
    val birthday = values.string("birthday", "bday", "birthdate")
    val organizer = values.person("organizer", "owner", "createdby")
    val attendeeCount = values.arraySize("attendees", "participants")
    val allDay = values.boolean("allday", "isallday") == true ||
        (start?.length == 8 && start.all(Char::isDigit))
    val assignee = values.person("assignee", "assigneeuid", "assignedto")
    val effortPoints = values.int("points", "effortpoints", "effort", "weight")
    val title = when (kind) {
        NativeGroupwareItemKind.Contact ->
            values.string("fn", "formattedname", "displayname", "name") ?: email ?: phone ?: record.id
        NativeGroupwareItemKind.Event, NativeGroupwareItemKind.Task ->
            values.string("summary", "title", "name", "displayname", "label") ?: record.id
    }
    val subtitle = when (kind) {
        NativeGroupwareItemKind.Contact -> listOfNotNull(
            organization,
            values.string("title", "role"),
            email?.takeUnless { it.equals(title, ignoreCase = true) },
            phone?.takeUnless { it.equals(title, ignoreCase = true) },
        )
        NativeGroupwareItemKind.Event -> listOfNotNull(
            listOfNotNull(start, end).joinToString(" - ").takeIf(String::isNotBlank),
            location,
            status,
            "Recurring".takeIf { recurring },
        )
        NativeGroupwareItemKind.Task -> listOfNotNull(
            due?.let { "Due ${it.compactSemanticDateTime()}" } ?: start?.compactSemanticDateTime(),
            assignee?.let { "Assigned to $it" },
            status,
            completionPercent?.let { "$it%" },
            recurrence?.taskRecurrenceLabel(),
            effortPoints?.let { "$it ${if (it == 1) "point" else "points"}" },
        )
    }.distinct().joinToString(" · ").takeIf(String::isNotBlank)

    return NativeGroupwarePresentation(
        kind = kind,
        title = title,
        subtitle = subtitle,
        start = start,
        end = end,
        due = due,
        status = status,
        location = location,
        recurring = recurring,
        completed = completed,
        completionPercent = completionPercent,
        priority = values.int("priority"),
        assignee = assignee,
        recurrenceRule = recurrence,
        effortPoints = effortPoints,
        organization = organization,
        primaryEmail = email,
        primaryPhone = phone,
        description = description,
        address = address,
        birthday = birthday,
        organizer = organizer,
        attendeeCount = attendeeCount,
        allDay = allDay,
    )
}

/**
 * Promotes a whole collection only when every returned item has task semantics. This prevents a
 * mixed or weakly inferred payload from hiding records behind a task-specific surface.
 */
internal fun nativeTaskCollectionPresentations(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): List<Pair<NativeRecord, NativeGroupwarePresentation>>? {
    if (records.isEmpty()) return null
    val typedCompletion = resource.uniqueNativeTaskCompletionSemantics()
    return records.map { record ->
        val values = NativeSemanticValues(record)
        if (!values.hasAny(
                "assignee", "assignedto", "completed", "completedat", "done", "donetimestamp",
                "due", "duedate", "effort", "percentcomplete", "points", "priority", "repeat",
                "rrule", "status", "worktime",
            ) && (
                typedCompletion?.read(record) == null ||
                    !values.hasAny("summary", "title", "name", "displayname", "label")
                )
        ) return null
        val presentation = nativeGroupwarePresentation(resource, record)
            ?.takeIf { it.kind == NativeGroupwareItemKind.Task }
            ?: return null
        record to presentation
    }
}

/**
 * Uses the declared field type and a standard completion concept to recognize reversible item
 * state without relying on an app or endpoint identifier. Enumeration values must prove both a
 * completed and an incomplete state; an arbitrary boolean such as `favorite` is not enough.
 */
private fun ResourceSpec.uniqueNativeTaskCompletionSemantics(): NativeTaskCompletionSemantics? {
    val candidates = fields.mapNotNull { field ->
        if (field.taskCompletionFieldScore() == 0) return@mapNotNull null
        when (field.kind) {
            FieldKind.boolean -> if (
                !field.requiresIndependentNativeTaskEvidence() ||
                hasIndependentNativeTaskEvidence(field)
            ) {
                NativeTaskCompletionSemantics(
                    field = field,
                    completedWireValue = "true",
                    incompleteWireValue = "false",
                )
            } else {
                null
            }
            FieldKind.enumeration -> {
                val values = field.enumValues.orEmpty()
                val completed = values.singleOrNull { value ->
                    value.semanticKey() in TASK_COMPLETED_VALUES
                }
                val incomplete = values.singleOrNull { value ->
                    value.semanticKey() in TASK_INCOMPLETE_VALUES
                }
                if (completed == null || incomplete == null) null else {
                    NativeTaskCompletionSemantics(
                        field = field,
                        completedWireValue = completed,
                        incompleteWireValue = incomplete,
                    )
                }
            }
            else -> null
        }?.let { semantics -> semantics to field.taskCompletionFieldScore() }
    }.sortedByDescending { (_, score) -> score }
    val best = candidates.firstOrNull() ?: return null
    return best.first.takeIf { candidates.drop(1).none { (_, score) -> score == best.second } }
}

private fun FieldSpec.taskCompletionFieldScore(): Int {
    if (kind !in setOf(FieldKind.boolean, FieldKind.enumeration)) return 0
    val id = id.semanticKey()
    val label = label.semanticKey()
    return when {
        id in TASK_COMPLETION_FIELD_NAMES -> 2
        label in TASK_COMPLETION_FIELD_NAMES -> 1
        else -> 0
    }
}

/**
 * Promotes homogeneous contact or event collections into dedicated native surfaces.
 *
 * Mixed payloads stay generic so one record containing an email or date cannot misclassify an
 * arbitrary dataset.
 */
internal fun nativeGroupwareCollectionPresentations(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): List<Pair<NativeRecord, NativeGroupwarePresentation>>? {
    if (records.isEmpty()) return null
    val rows = records.map { record ->
        val presentation = nativeGroupwarePresentation(resource, record) ?: return null
        if (presentation.kind == NativeGroupwareItemKind.Task) return null
        record to presentation
    }
    val kind = rows.first().second.kind
    return rows.takeIf { candidates -> candidates.all { (_, presentation) -> presentation.kind == kind } }
}

internal fun nativeContactEmailUri(email: String?): String? {
    val candidate = email?.trim()?.takeIf { it.length in 3..320 } ?: return null
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) return null
    if ('?' in candidate || '#' in candidate) return null
    if (candidate.count { it == '@' } != 1 || candidate.startsWith('@') || candidate.endsWith('@')) return null
    if (candidate.any { it !in CONTACT_EMAIL_CHARACTERS }) return null
    return "mailto:$candidate"
}

internal fun nativeContactPhoneUri(phone: String?): String? {
    val candidate = phone?.trim()?.takeIf { it.length in 3..64 } ?: return null
    if (candidate.any(Char::isISOControl)) return null
    if (candidate.any { !it.isDigit() && it !in CONTACT_PHONE_FORMATTING }) return null
    val normalized = buildString(candidate.length) {
        candidate.forEachIndexed { index, character ->
            when {
                character.isDigit() -> append(character)
                character == '+' && index == 0 -> append(character)
            }
        }
    }
    if (normalized.count(Char::isDigit) < 3) return null
    return "tel:$normalized"
}

/** Shape-based household semantics shared by chore, rota and family-task APIs. */
internal fun nativeHouseholdPresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeHouseholdPresentation? {
    val values = NativeSemanticValues(record)
    val words = semanticTokens(resource.id, resource.name)
    val kind = when {
        values.hasAny("owner") && values.hasAny("members", "invites") ->
            NativeHouseholdItemKind.Household
        values.hasAny("worktime", "completedat", "donetimestamp") &&
            values.hasAny("member", "assignee", "user") ->
            NativeHouseholdItemKind.Completion
        values.hasAny("inviteid") && values.hasAny("teamid", "householdid", "groupid") ->
            NativeHouseholdItemKind.Invitation
        words.any { it in setOf("member", "members", "participant", "participants") } &&
            values.hasAny("member", "userid", "displayname") ->
            NativeHouseholdItemKind.Member
        else -> return null
    }
    val owner = values.person("owner", "owneruserid")
    val member = values.person("member", "memberuserid", "assignee", "user", "userid")
    val points = values.int("points", "score")
    val completedAt = values.string("worktime", "completedat", "donetimestamp")
    val memberCount = values.arraySize("members", "participants")
    val invitationCount = values.arraySize("invites", "invitations")
    val title = when (kind) {
        NativeHouseholdItemKind.Household ->
            values.string("name", "teamname", "householdname", "groupname") ?: record.id
        NativeHouseholdItemKind.Member ->
            values.string("displayname", "name") ?: member ?: record.id
        NativeHouseholdItemKind.Invitation ->
            values.string("teamname", "householdname", "groupname") ?: member ?: "Invitation"
        NativeHouseholdItemKind.Completion ->
            values.string("name", "title", "summary") ?: "Completed task"
    }
    val subtitle = when (kind) {
        NativeHouseholdItemKind.Household -> listOfNotNull(
            owner?.let { "Owned by $it" },
            memberCount?.let { "$it ${if (it == 1) "member" else "members"}" },
            invitationCount?.takeIf { it > 0 }?.let { "$it pending" },
        ).joinToString(" · ").takeIf(String::isNotBlank)
        NativeHouseholdItemKind.Member -> points?.let { "$it ${if (it == 1) "point" else "points"}" }
        NativeHouseholdItemKind.Invitation -> member?.let { "Invited user $it" }
        NativeHouseholdItemKind.Completion -> listOfNotNull(
            member?.let { "Completed by $it" },
            completedAt,
            points?.let { "$it ${if (it == 1) "point" else "points"}" },
        ).joinToString(" · ").takeIf(String::isNotBlank)
    }
    return NativeHouseholdPresentation(
        kind = kind,
        title = title,
        subtitle = subtitle,
        owner = owner,
        member = member,
        points = points,
        completedAt = completedAt,
        memberCount = memberCount,
        invitationCount = invitationCount,
    )
}

private fun String.isRecurringTaskRule(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.isBlank() || normalized == "false" || normalized == "0" || normalized == "none") return false
    return !normalized.startsWith("s:")
}

internal fun String.taskRecurrenceLabel(): String? {
    if (!isRecurringTaskRule()) return null
    val parts = split(':')
    val interval = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    return when (parts.firstOrNull()?.lowercase()) {
        "o" -> "On demand"
        "d" -> interval?.let { if (it == 1) "Daily" else "Every $it days" }
        "w" -> interval?.let { if (it == 1) "Weekly" else "Every $it weeks" }
        "m" -> interval?.let { if (it == 1) "Monthly" else "Every $it months" }
        else -> "Recurring"
    }
}

internal fun String.compactSemanticDateTime(): String {
    val candidate = trim()
    if (candidate.length <= 10) return candidate
    if (
        candidate.length >= 13 &&
        candidate[8] == 'T' &&
        candidate.take(8).all(Char::isDigit) &&
        candidate.substring(9, 13).all(Char::isDigit)
    ) {
        return "${candidate.take(4)}-${candidate.substring(4, 6)}-${candidate.substring(6, 8)} " +
            "${candidate.substring(9, 11)}:${candidate.substring(11, 13)}"
    }
    return runCatching {
        Instant.parse(candidate).toString().replace('T', ' ').take(16)
    }.getOrElse {
        candidate.replace('T', ' ').removeSuffix("Z").take(16)
    }
}

/** Accepts only bounded same-origin paths. The host still constrains the path to the active app. */
internal fun safeNativeAssetPath(value: String): String? {
    val candidate = value.trim()
    if (candidate.length !in 2..2_048 || !candidate.startsWith('/')) return null
    if (candidate.startsWith("//") || '\\' in candidate || '#' in candidate) return null
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) return null
    val path = candidate.substringBefore('?').substringBefore('#')
    if (path.split('/').any { it == "." || it == ".." }) return null
    return candidate
}

private class NativeSemanticValues(record: NativeRecord) {
    private val structuredValues = record.structuredValues.mapKeys { (key, _) -> key.semanticKey() }
    private val values = buildMap {
        record.values.forEach { (key, value) -> value?.let { put(key.semanticKey(), it) } }
        record.structuredValues.forEach { (key, value) ->
            value.semanticString()?.let { put(key.semanticKey(), it) }
        }
        record.displayValues.forEach { (key, value) -> putIfAbsent(key.semanticKey(), value) }
    }

    fun hasAny(vararg aliases: String): Boolean = aliases.any { alias ->
        alias.semanticKey() in values || alias.semanticKey() in structuredValues
    }

    fun string(vararg aliases: String): String? = aliases.firstNotNullOfOrNull { alias ->
        values[alias.semanticKey()]?.semanticString()?.takeIf(String::isNotBlank)
    }

    fun person(vararg aliases: String): String? = aliases.firstNotNullOfOrNull { alias ->
        values[alias.semanticKey()]?.semanticPerson()?.takeIf(String::isNotBlank)
    }

    fun boolean(vararg aliases: String): Boolean? = aliases.firstNotNullOfOrNull { alias ->
        val raw = values[alias.semanticKey()]?.trim()?.lowercase() ?: return@firstNotNullOfOrNull null
        when (raw) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> runCatching { Json.parseToJsonElement(raw) }.getOrNull().let { element ->
                (element as? JsonPrimitive)?.booleanOrNull
            }
        }
    }

    fun int(vararg aliases: String): Int? = number(*aliases)?.toInt()

    fun number(vararg aliases: String): Double? = aliases.firstNotNullOfOrNull { alias ->
        val raw = values[alias.semanticKey()]?.trim() ?: return@firstNotNullOfOrNull null
        raw.toDoubleOrNull() ?: (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonPrimitive)?.doubleOrNull
    }

    fun arraySize(vararg aliases: String): Int? = aliases.firstNotNullOfOrNull { alias ->
        (structuredValues[alias.semanticKey()] as? NativeStructuredValue.ListValue)?.let { value ->
            return@firstNotNullOfOrNull value.items.size + value.omittedItems
        }
        val raw = values[alias.semanticKey()] ?: return@firstNotNullOfOrNull null
        (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray)?.size
    }

    fun objectKeys(vararg aliases: String): List<String> = aliases.firstNotNullOfOrNull { alias ->
        (structuredValues[alias.semanticKey()] as? NativeStructuredValue.ObjectValue)
            ?.entries
            ?.map(NativeStructuredEntry::key)
            ?.takeIf(List<String>::isNotEmpty)
    }.orEmpty()

    fun references(vararg aliases: String): List<NativeSemanticReference> =
        aliases.firstNotNullOfOrNull { alias ->
            val list = structuredValues[alias.semanticKey()] as? NativeStructuredValue.ListValue
                ?: return@firstNotNullOfOrNull null
            list.items.mapNotNull(NativeStructuredValue::semanticReference)
                .takeIf(List<NativeSemanticReference>::isNotEmpty)
        }.orEmpty()
}

private data class NativeSemanticReference(
    val id: String?,
    val label: String,
)

private fun NativeStructuredValue.semanticReference(): NativeSemanticReference? {
    val objectValue = this as? NativeStructuredValue.ObjectValue ?: return null
    val entries = objectValue.entries.associateBy { entry -> entry.key.semanticKey() }
    fun value(vararg aliases: String): String? = aliases.firstNotNullOfOrNull { alias ->
        entries[alias.semanticKey()]?.value?.semanticString()?.takeIf(String::isNotBlank)
    }
    val label = value("name", "displayname", "label", "username", "user", "email") ?: return null
    return NativeSemanticReference(
        id = value("id", "userid", "uid", "memberid", "participantid"),
        label = label,
    )
}

private fun String.isStructuralSummary(): Boolean {
    if (equals("Structured data", ignoreCase = true)) return true
    val words = trim().lowercase().split(' ').filter(String::isNotBlank)
    return words.size == 2 && words.first().toIntOrNull() != null &&
        words.last() in setOf("field", "fields", "item", "items")
}

private fun NativeSemanticValues.formattedTimestamp(): String? =
    number("dateint", "epoch", "timestamp")?.toLong()?.takeIf { it > 0 }?.let { seconds ->
        runCatching { Instant.fromEpochSeconds(seconds).toString().replace('T', ' ').take(16) }.getOrNull()
    } ?: string("receivedat", "sentat", "date", "datetime", "timestamp", "createdat")

private fun NativeStructuredValue.semanticString(): String? = when (this) {
    is NativeStructuredValue.Scalar -> value
    is NativeStructuredValue.ListValue -> items.mapNotNull(NativeStructuredValue::semanticString)
        .joinToString(", ")
        .takeIf(String::isNotBlank)
    is NativeStructuredValue.ObjectValue -> {
        val normalized = entries.associateBy { entry -> entry.key.semanticKey() }
        listOf("name", "title", "displayname", "label", "email", "address")
            .firstNotNullOfOrNull { alias -> normalized[alias]?.value?.semanticString() }
    }
}

private fun String.semanticString(): String? {
    val trimmed = trim()
    val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
    return element.semanticText()
}

private fun String.semanticPerson(): String? {
    val trimmed = trim()
    val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
    return when (element) {
        is JsonArray -> element.firstNotNullOfOrNull(JsonElement::semanticPersonText)
        else -> element.semanticPersonText()
    }
}

private fun JsonElement.semanticPersonText(): String? = when (this) {
    is JsonObject -> {
        val name = semanticObjectValue("name", "displayName", "label")
        val email = semanticObjectValue("email", "address", "mail")
        when {
            !name.isNullOrBlank() && !email.isNullOrBlank() -> "$name <$email>"
            !name.isNullOrBlank() -> name
            else -> email
        }
    }
    else -> semanticText()
}

private fun JsonElement.semanticText(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull(JsonElement::semanticPersonText).joinToString(", ").takeIf(String::isNotBlank)
    is JsonObject -> semanticObjectValue("name", "title", "displayName", "label", "email", "address")
}

private fun JsonObject.semanticObjectValue(vararg aliases: String): String? {
    val normalized = entries.associate { (key, value) -> key.semanticKey() to value }
    return aliases.firstNotNullOfOrNull { alias ->
        normalized[alias.semanticKey()]?.let { value -> (value as? JsonPrimitive)?.contentOrNull }
    }
}

private fun formatMediaDuration(seconds: Double): String {
    val wholeSeconds = seconds.toLong().coerceAtLeast(0)
    val hours = wholeSeconds / 3_600
    val minutes = wholeSeconds % 3_600 / 60
    val remainingSeconds = wholeSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
    }
}

private fun semanticTokens(vararg values: String): Set<String> = values
    .flatMap { value ->
        value.lowercase().map { character -> if (character.isLetterOrDigit()) character else ' ' }
            .joinToString("")
            .split(' ')
    }
    .filter(String::isNotBlank)
    .toSet()

private fun String.semanticKey(): String = lowercase().filter(Char::isLetterOrDigit)

private val MESSAGE_WORDS = setOf("message", "messages", "thread", "threads", "email", "emails")
private val FOLDER_WORDS = setOf("mailbox", "mailboxes", "folder", "folders", "inbox", "outbox")
private val ACCOUNT_WORDS = setOf("account", "accounts", "identity", "identities")
private val FINANCE_WORDS = setOf(
    "account", "accounts", "bill", "bills", "budget", "budgets", "expense", "expenses",
    "finance", "financial", "income", "ledger", "ledgers", "payment", "payments",
    "spending", "transaction", "transactions",
)
private val CONTACT_WORDS = setOf("contact", "contacts", "addressbook", "addressbooks", "card", "cards")
private val CONTACT_EMAIL_CHARACTERS =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.!#$%&'*+/=?^_`{|}~-@".toSet()
private val CONTACT_PHONE_FORMATTING = setOf('+', ' ', '-', '(', ')', '.')
private val TASK_WORDS = setOf(
    "assignment", "assignments", "chore", "chores", "duty", "duties",
    "rota", "rotas", "task", "tasks", "todo", "todos", "vtodo",
)
private val TASK_COMPLETION_FIELD_NAMES = setOf(
    "complete",
    "completed",
    "done",
    "finished",
    "iscomplete",
    "iscompleted",
    "isdone",
    "status",
    "state",
)
private val TASK_COMPLETED_VALUES = setOf(
    "closed",
    "complete",
    "completed",
    "done",
    "finished",
)
private val TASK_INCOMPLETE_VALUES = setOf(
    "active",
    "incomplete",
    "new",
    "open",
    "pending",
    "todo",
)
