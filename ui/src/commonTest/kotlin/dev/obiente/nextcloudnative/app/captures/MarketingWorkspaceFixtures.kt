package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicForm
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord

internal fun marketingSyncPair(
    id: String,
    name: String,
    remote: String,
    direction: FileSyncDirection,
    pending: Int,
    completed: Int,
    schedule: String,
) = FileSyncPairSummary(
    id = id,
    localDisplayName = name,
    remoteRootPath = remote,
    configuration = FileSyncConfiguration(
        direction = direction,
        deviceLabel = "fixture-mobile",
    ),
    readyCount = pending,
    runningCount = 0,
    conflicts = emptyList(),
    failedCount = 0,
    skippedCount = 0,
    completedCount = completed,
    lastScanEpochMillis = 1_786_640_400_000L,
    scheduleDescription = schedule,
)

internal val marketingAdaptiveSchema = NativeAppSchema(
    schemaVersion = "0.1",
    app = AppIdentity("fixture-inventory", "Community inventory", "fixture"),
    confidence = Confidence.verified,
    resources = listOf(
        ResourceSpec(
            id = "items",
            name = "Inventory items",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("name", "Item", FieldKind.string, required = true, readOnly = true),
                FieldSpec("category", "Category", FieldKind.string, required = false, readOnly = true),
                FieldSpec(
                    "amount",
                    "Value",
                    FieldKind.currency,
                    required = false,
                    readOnly = true,
                    format = "EUR",
                ),
                FieldSpec("status", "Status", FieldKind.enumeration, required = false, readOnly = true),
                FieldSpec("updated", "Updated", FieldKind.date, required = false, readOnly = true),
            ),
        ),
    ),
    views = listOf(
        ViewSpec(
            id = "items.table",
            title = "Inventory",
            resourceId = "items",
            component = NativeComponent.dataTable,
            sourceActionId = "fixture.items.list",
            confidence = Confidence.verified,
        ),
    ),
)

internal val marketingAdaptiveRecords = listOf(
    NativeRecord(
        id = "item-1",
        values = mapOf(
            "name" to "Field recorder",
            "category" to "Audio",
            "amount" to "219.00",
            "status" to "Available",
            "updated" to "2026-07-24",
        ),
    ),
    NativeRecord(
        id = "item-2",
        values = mapOf(
            "name" to "Tripod",
            "category" to "Camera",
            "amount" to "84.50",
            "status" to "On loan",
            "updated" to "2026-07-23",
        ),
    ),
    NativeRecord(
        id = "item-3",
        values = mapOf(
            "name" to "USB-C hub",
            "category" to "Computer",
            "amount" to "49.95",
            "status" to "Available",
            "updated" to "2026-07-22",
        ),
    ),
    NativeRecord(
        id = "item-4",
        values = mapOf(
            "name" to "Lighting kit",
            "category" to "Camera",
            "amount" to "135.00",
            "status" to "Reserved",
            "updated" to "2026-07-21",
        ),
    ),
    NativeRecord(
        id = "item-5",
        values = mapOf(
            "name" to "Studio monitor",
            "category" to "Audio",
            "amount" to "175.00",
            "status" to "Available",
            "updated" to "2026-07-19",
        ),
    ),
)

private val marketingMailComposeAction = DynamicAction(
    id = "route.drafts.create",
    label = "Compose",
    resourceId = "drafts",
    intent = ActionIntent.create,
    risk = ActionRisk.mutating,
    requiresConfirmation = false,
    // Exact Nextcloud Mail v5.10.x route. Capture data remains synthetic, but the visual QA
    // exercises the same contract identities used by signed-package discovery.
    binding = DynamicHttpBinding(method = HttpMethod.POST, path = "/apps/mail/api/drafts"),
    confidence = Confidence.verified,
)

internal val marketingMailSchema = NativeAppSchema(
    schemaVersion = "0.1",
    app = AppIdentity("fixture-mail", "Mail", "5.10.12"),
    confidence = Confidence.verified,
    resources = listOf(
        ResourceSpec(
            id = "accounts",
            name = "Accounts",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("accountName", "Account", FieldKind.string, required = true, readOnly = true),
                FieldSpec("emailAddress", "Email", FieldKind.string, required = true, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "mailboxes",
            name = "Mailboxes",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("name", "Mailbox", FieldKind.string, required = true, readOnly = true),
                FieldSpec("specialUse", "Role", FieldKind.string, required = false, readOnly = true),
                FieldSpec("unreadCount", "Unread", FieldKind.integer, required = false, readOnly = true),
                FieldSpec("path", "Path", FieldKind.string, required = false, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "messages",
            name = "Messages",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("subject", "Subject", FieldKind.string, required = false, readOnly = true),
                FieldSpec("from", "From", FieldKind.string, required = false, readOnly = true),
                FieldSpec("preview", "Preview", FieldKind.string, required = false, readOnly = true),
                FieldSpec("date", "Date", FieldKind.dateTime, required = false, readOnly = true),
                FieldSpec("seen", "Seen", FieldKind.boolean, required = false, readOnly = true),
                FieldSpec("flagged", "Flagged", FieldKind.boolean, required = false, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "mailboxStats",
            name = "Mailbox stats",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("total", "Messages", FieldKind.integer, required = true, readOnly = true),
                FieldSpec("unread", "Unread", FieldKind.integer, required = true, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "messageBody",
            name = "Message body",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("body", "Body", FieldKind.longText, required = true, readOnly = true),
                FieldSpec("hasHtmlBody", "HTML", FieldKind.boolean, required = false, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "drafts",
            name = "Drafts",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("accountId", "From", FieldKind.integer, required = true, readOnly = false),
                FieldSpec("subject", "Subject", FieldKind.string, required = true, readOnly = false),
                FieldSpec("bodyPlain", "Message", FieldKind.longText, required = false, readOnly = false),
                FieldSpec("editorBody", "Message", FieldKind.longText, required = false, readOnly = false),
                FieldSpec("isHtml", "Rich text", FieldKind.boolean, required = true, readOnly = false),
            ),
        ),
    ),
    views = listOf(
        ViewSpec(
            id = "messages.mailbox",
            title = "Inbox",
            resourceId = "messages",
            component = NativeComponent.mailbox,
            sourceActionId = "route.messages.index",
            confidence = Confidence.verified,
        ),
        ViewSpec(
            id = "message.body",
            title = "Message",
            resourceId = "messageBody",
            component = NativeComponent.detail,
            sourceActionId = "route.messages.getbody",
            confidence = Confidence.verified,
        ),
    ),
    actions = listOf(
        ActionSpec(
            id = "route.messages.index",
            label = "Messages",
            resourceId = "messages",
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = "/apps/mail/api/messages",
                operationId = "route.messages.index",
                queryParameterNames = listOf("mailboxId", "cursor", "filter", "limit", "view", "v"),
                requiredQueryParameterNames = listOf("mailboxId"),
            ),
            intent = ActionIntent.list,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        ),
        ActionSpec(
            id = "route.messages.getbody",
            label = "Message body",
            resourceId = "messageBody",
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = "/apps/mail/api/messages/{id}/body",
                operationId = "route.messages.getbody",
                pathParameterNames = listOf("id"),
                requiredPathParameterNames = listOf("id"),
            ),
            intent = ActionIntent.read,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        ),
        ActionSpec(
            id = "route.mailboxes.stats",
            label = "Mailbox stats",
            resourceId = "mailboxStats",
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = "/apps/mail/api/mailboxes/{id}/stats",
                operationId = "route.mailboxes.stats",
                pathParameterNames = listOf("id"),
                requiredPathParameterNames = listOf("id"),
            ),
            intent = ActionIntent.read,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        ),
        ActionSpec(
            id = marketingMailComposeAction.id,
            label = marketingMailComposeAction.label,
            resourceId = marketingMailComposeAction.resourceId,
            binding = ApiBinding(
                method = marketingMailComposeAction.binding.method,
                path = marketingMailComposeAction.binding.path,
                operationId = marketingMailComposeAction.id,
            ),
            intent = marketingMailComposeAction.intent,
            risk = marketingMailComposeAction.risk,
            requiresConfirmation = marketingMailComposeAction.requiresConfirmation,
            confidence = marketingMailComposeAction.confidence,
        ),
    ),
)

internal val marketingMailDescriptor = DynamicAppDescriptor(
    descriptorVersion = "0.1",
    app = AppIdentity("fixture-mail", "Mail", "5.10.12"),
    endpointPolicy = EndpointPolicy(serverOrigin = "https://fixture.invalid"),
    resources = emptyList(),
    actions = listOf(marketingMailComposeAction),
    forms = listOf(
        DynamicForm(
            id = "compose-message-form",
            title = "Compose",
            resourceId = "messages",
            actionId = marketingMailComposeAction.id,
            confidence = Confidence.verified,
        ),
    ),
)

internal val marketingMailMessageView = requireNotNull(
    marketingMailSchema.views.firstOrNull { view -> view.id == "messages.mailbox" },
)
internal val marketingMailBodyView = requireNotNull(
    marketingMailSchema.views.firstOrNull { view -> view.id == "message.body" },
)
internal val marketingMailAccount = NativeRecord(
    id = "personal",
    values = mapOf(
        "accountName" to "Obiente",
        "emailAddress" to "obiente@example.test",
    ),
)
internal val marketingMailInbox = NativeRecord(
    id = "inbox",
    values = mapOf(
        "name" to "Inbox",
        "specialUse" to "inbox",
        "unreadCount" to "2",
        "path" to "Personal/Inbox",
        "accountId" to marketingMailAccount.id,
    ),
)
internal val marketingMailInboxStats = NativeRecord(
    id = "inbox-stats",
    values = mapOf("total" to "84", "unread" to "2"),
)
internal val marketingMailboxes = listOf(
    marketingMailInbox,
    NativeRecord(
        id = "drafts",
        values = mapOf(
            "name" to "Drafts", "specialUse" to "drafts", "path" to "Personal/Drafts",
            "accountId" to marketingMailAccount.id,
        ),
    ),
    NativeRecord(
        id = "sent",
        values = mapOf(
            "name" to "Sent", "specialUse" to "sent", "path" to "Personal/Sent",
            "accountId" to marketingMailAccount.id,
        ),
    ),
    NativeRecord(
        id = "archive",
        values = mapOf(
            "name" to "Archive", "specialUse" to "archive", "path" to "Personal/Archive",
            "accountId" to marketingMailAccount.id,
        ),
    ),
)
internal val marketingMailMessages = listOf(
    NativeRecord(
        id = "mail-1",
        values = mapOf(
            "subject" to "Release candidate is ready",
            "from" to "Ada <ada@example.test>",
            "preview" to "The Android and desktop artifacts passed the final checks.",
            "date" to "2026-07-29T08:42:00Z",
            "seen" to "false",
            "flagged" to "true",
            "accountId" to marketingMailAccount.id,
            "mailboxId" to marketingMailInbox.id,
        ),
    ),
    NativeRecord(
        id = "mail-2",
        values = mapOf(
            "subject" to "Design review notes",
            "from" to "Mira <mira@example.test>",
            "preview" to "I added the adaptive navigation feedback to the shared notes.",
            "date" to "2026-07-28T17:30:00Z",
            "seen" to "false",
            "accountId" to marketingMailAccount.id,
            "mailboxId" to marketingMailInbox.id,
        ),
    ),
    NativeRecord(
        id = "mail-3",
        values = mapOf(
            "subject" to "Community call",
            "from" to "Nextcloud community <community@example.test>",
            "preview" to "Here is the agenda for Thursday's community call.",
            "date" to "2026-07-27T11:05:00Z",
            "seen" to "true",
            "accountId" to marketingMailAccount.id,
            "mailboxId" to marketingMailInbox.id,
        ),
    ),
)
internal val marketingMailSelectedMessage = marketingMailMessages.first()
internal val marketingMailBodyRecord = NativeRecord(
    id = marketingMailSelectedMessage.id,
    values = mapOf(
        "body" to """
            <p>Hello Obiente,</p>
            <p>The <strong>release candidate</strong> is ready for review.</p>
            <p>Android and desktop artifacts passed the final checks. The visual audit is attached to the build.</p>
            <p>Thanks,<br>Ada</p>
        """.trimIndent(),
        "hasHtmlBody" to "true",
    ),
)

internal val marketingDashboardSnapshot = NativeDashboardSnapshot(
    widgets = listOf(
        marketingDashboardWidget("activity", "Recent activity", 10),
        marketingDashboardWidget("calendar", "Upcoming events", 20),
        marketingDashboardWidget("recommendations", "Recent files", 30),
        marketingDashboardWidget("photos", "Photo backup", 40),
        marketingDashboardWidget("favorites", "Favorite files", 50),
        marketingDashboardWidget("storage", "Storage", 60),
        marketingDashboardWidget("talk", "Unread conversations", 70),
        marketingDashboardWidget("mail", "Important mail", 80),
    ),
    itemsByWidget = mapOf(
        "activity" to listOf(
            marketingDashboardItem(
                widgetId = "activity",
                title = "Project brief was updated",
                subtitle = "A few minutes ago",
                sinceId = "activity-2",
            ),
            marketingDashboardItem(
                widgetId = "activity",
                title = "A design file was shared",
                subtitle = "Today",
                sinceId = "activity-1",
            ),
            marketingDashboardItem("activity", "Kai commented on Q3 roadmap.xlsx", "18 minutes ago", "activity-0b"),
            marketingDashboardItem("activity", "Camera backup uploaded 27 new photos", "42 minutes ago", "activity-0a"),
        ),
        "calendar" to listOf(
            marketingDashboardItem(
                widgetId = "calendar",
                title = "Product planning",
                subtitle = "Today at 14:00",
                sinceId = "calendar-2",
            ),
            marketingDashboardItem(
                widgetId = "calendar",
                title = "Community call",
                subtitle = "Tomorrow at 10:30",
                sinceId = "calendar-1",
            ),
            marketingDashboardItem("calendar", "Design review", "Tomorrow at 14:30 · Product room", "calendar-0b"),
            marketingDashboardItem("calendar", "Release retrospective", "Friday at 09:30", "calendar-0a"),
        ),
        "recommendations" to listOf(
            marketingDashboardItem(
                widgetId = "recommendations",
                title = "Product brief.pdf",
                subtitle = "Projects",
                sinceId = "files-2",
            ),
            marketingDashboardItem(
                widgetId = "recommendations",
                title = "Release notes.md",
                subtitle = "Notes",
                sinceId = "files-1",
            ),
            marketingDashboardItem("recommendations", "Q3 roadmap.xlsx", "Projects · edited 18 min ago", "files-0b"),
            marketingDashboardItem("recommendations", "Brand presentation.pptx", "Design system", "files-0a"),
        ),
        "photos" to listOf(
            marketingDashboardItem(
                widgetId = "photos",
                title = "Camera backup is up to date",
                subtitle = "128 photos and 14 videos",
                sinceId = "photos-1",
            ),
            marketingDashboardItem("photos", "Weekend in Texel", "38 new photos · Yesterday", "photos-0b"),
            marketingDashboardItem("photos", "7 people recognized", "Review suggested matches", "photos-0a"),
        ),
        "favorites" to listOf(
            marketingDashboardItem("favorites", "Q3 roadmap.xlsx", "Projects/Planning", "favorites-3"),
            marketingDashboardItem("favorites", "Product direction.md", "Projects/Native", "favorites-2"),
            marketingDashboardItem("favorites", "Brand presentation.pptx", "Design system", "favorites-1"),
        ),
        "storage" to listOf(
            marketingDashboardItem("storage", "34.2 GB of 100 GB used", "65.8 GB available", "storage-2"),
            marketingDashboardItem("storage", "8.6 GB available offline", "4 folder sync pairs", "storage-1"),
        ),
        "talk" to listOf(
            marketingDashboardItem("talk", "nati.ve", "Mara: The updated brief is ready · 3 unread", "talk-4"),
            marketingDashboardItem("talk", "Design system", "Kai: I reviewed the new tokens · 1 unread", "talk-3"),
            marketingDashboardItem("talk", "Community", "Elena: See you at the call · 1 unread", "talk-2"),
            marketingDashboardItem("talk", "Release crew", "You: Desktop artifacts are ready", "talk-1"),
        ),
        "mail" to listOf(
            marketingDashboardItem("mail", "Release candidate is ready", "Ada Lovelace · 8 minutes ago", "mail-4"),
            marketingDashboardItem("mail", "Design review notes", "Kai Lind · 31 minutes ago", "mail-3"),
            marketingDashboardItem("mail", "Your weekly cloud summary", "Nextcloud · Today", "mail-2"),
            marketingDashboardItem("mail", "Community call agenda", "Elena Schneider · Yesterday", "mail-1"),
        ),
    ),
)

internal val marketingUserStatus = NativeUserStatus(
    userId = "fixture-user",
    presence = NativeUserPresence.Online,
    message = "Building the next native experience",
    icon = null,
    messageId = null,
    clearAtEpochSeconds = null,
    messageIsPredefined = false,
    statusIsUserDefined = true,
)

internal val marketingHomepageUserStatus = marketingUserStatus.copy(
    message = "Your cloud is ready",
)

private fun marketingDashboardWidget(
    id: String,
    title: String,
    order: Int,
) = NativeDashboardWidget(
    id = id,
    title = title,
    order = order,
    iconUrl = null,
    iconClass = null,
    widgetUrl = null,
    itemApiVersions = setOf(2),
    itemIconsRound = false,
    reloadIntervalSeconds = null,
    actions = emptyList(),
)

private fun marketingDashboardItem(
    widgetId: String,
    title: String,
    subtitle: String,
    sinceId: String,
) = NativeDashboardItem(
    widgetId = widgetId,
    title = title,
    subtitle = subtitle,
    link = null,
    iconUrl = null,
    overlayIconUrl = null,
    sinceId = sinceId,
)

internal val marketingDeckBoard = DeckBoard(
    id = 1,
    title = "Home renovation",
    color = "8b5cf6",
    archived = false,
    owner = DeckUser("fixture-owner", "Demo owner"),
    labels = emptyList(),
    permissions = DeckPermissions(
        canRead = true,
        canEdit = true,
        canManage = true,
        canShare = false,
    ),
    shared = false,
    lastModified = null,
    etag = null,
)

internal val marketingDeckStacks = listOf(
    marketingDeckStack(
        id = 10,
        title = "Planned",
        cards = listOf(
            marketingDeckCard(100, 10, "Measure kitchen cabinets", 100),
            marketingDeckCard(101, 10, "Compare paint samples", 200),
            marketingDeckCard(102, 10, "Request tile samples", 300),
        ),
    ),
    marketingDeckStack(
        id = 20,
        title = "In progress",
        cards = listOf(
            marketingDeckCard(200, 20, "Book the electrician", 100),
            marketingDeckCard(201, 20, "Choose hallway lighting", 200),
            marketingDeckCard(202, 20, "Patch the living room wall", 300),
        ),
    ),
    marketingDeckStack(
        id = 30,
        title = "Done",
        cards = listOf(
            marketingDeckCard(300, 30, "Order shelf brackets", 100),
            marketingDeckCard(301, 30, "Set the renovation budget", 200),
            marketingDeckCard(302, 30, "Photograph existing wiring", 300),
        ),
    ),
)

private fun marketingDeckStack(
    id: Long,
    title: String,
    cards: List<DeckCard>,
) = DeckStack(
    id = id,
    boardId = marketingDeckBoard.id,
    title = title,
    order = id,
    doneColumn = title == "Done",
    cards = cards,
    lastModified = null,
    etag = null,
)

private fun marketingDeckCard(
    id: Long,
    stackId: Long,
    title: String,
    order: Long,
) = DeckCard(
    id = id,
    boardId = marketingDeckBoard.id,
    stackId = stackId,
    title = title,
    descriptionMarkdown = null,
    ownerId = "fixture-owner",
    color = null,
    order = order,
    dueAt = null,
    startAt = null,
    completedAt = null,
    archived = false,
    overdue = false,
    labels = emptyList(),
    assignees = emptyList(),
    attachmentCount = 0,
    unreadCommentCount = 0,
    etag = null,
)
