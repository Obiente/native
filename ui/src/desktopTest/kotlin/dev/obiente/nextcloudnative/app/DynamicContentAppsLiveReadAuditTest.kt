package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.planDynamicNavigation
import dev.obiente.nextcloudnative.nativeui.model.preferredSemanticContextualChild
import dev.obiente.nextcloudnative.nativeui.model.resolveDynamicRecordReadParameters
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailboxItemKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMediaItemKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.nativeMailMessageActionPlan
import dev.obiente.nextcloudnative.nativeui.runtime.nativeMailboxPresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeMediaPresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.nativeStructuredDetail
import dev.obiente.nextcloudnative.nativeui.runtime.withEphemeralDisplayFields
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in, content-redacted integration audit for the three dynamic content families that depend
 * most heavily on a parent/detail relationship. Every observed transport request is asserted to
 * be GET-only before it reaches the saved authenticated desktop session.
 */
class DynamicContentAppsLiveReadAuditTest {
    @Test
    fun `live Mail actions are planned from signed contracts with GET only data`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_CONTENT_AUDIT") != "1") return@runBlocking
        val delegate = DesktopNextcloudServices()
        val session = assertNotNull(delegate.loadSession())
        val observed = mutableListOf<NextcloudApiRequest>()
        val services = GetOnlyAuditServices(delegate, observed)
        val server = services.loadServerInfo(session)

        auditMail(services, session, server)

        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { request ->
            request.method == NextcloudApiMethod.GET && request.body == null
        })
    }

    @Test
    fun `live Mail Music and Cookbook shapes remain native and GET only`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_CONTENT_AUDIT") != "1") return@runBlocking
        val delegate = DesktopNextcloudServices()
        val session = assertNotNull(delegate.loadSession())
        val observed = mutableListOf<NextcloudApiRequest>()
        val services = GetOnlyAuditServices(delegate, observed)
        val server = services.loadServerInfo(session)

        auditMail(services, session, server)
        auditMusic(services, session, server)
        auditCookbook(services, session, server)

        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { request ->
            request.method == NextcloudApiMethod.GET && request.body == null
        })
        println(
            "content-app-audit outcome=success methods=get-only " +
                "mail=account-mailbox-message music=library-settings cookbook=list-detail content=redacted",
        )
    }

    private suspend fun auditMail(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
    ) {
        val descriptor = discover(services, session, server, "mail")
        val schema = descriptor.toNativeAppSchema()
        val messageMove = assertNotNull(
            descriptor.actions.firstOrNull { action ->
                action.binding.method == HttpMethod.POST &&
                    action.binding.path.endsWith("/messages/{id}/move") &&
                    ((action.binding.body?.schema as? JsonObject)?.get("properties") as? JsonObject)
                        ?.containsKey("destFolderId") == true
            },
            "The signed Mail package must expose its exact verified message move contract.",
        )
        val accountDestination = assertNotNull(
            descriptor.planDynamicNavigation().rootDestinations.firstOrNull { destination ->
                destination.resourceId.semanticWords().any { it in setOf("account", "accounts") }
            },
        )
        val accounts = loadDynamicRecords(services, session, descriptor, accountDestination.actionId)
        val account = assertNotNull(accounts.firstOrNull())
        val accountContext = account.context(accountDestination.resourceId)
        val mailboxDestination = assertNotNull(
            descriptor.planDynamicNavigation(accountContext).contextualChildDestinations.firstOrNull { destination ->
                destination.resourceId.semanticWords().any { it in setOf("mailbox", "mailboxes", "folder", "folders") }
            },
        )
        assertEquals(
            mailboxDestination.actionId,
            descriptor.preferredSemanticContextualChild(accountContext)?.actionId,
            "account=${accountDestination.resourceId} mailbox=$mailboxDestination " +
                "children=${descriptor.planDynamicNavigation(accountContext).contextualChildDestinations}",
        )
        val accountResource = assertNotNull(schema.resources.firstOrNull { it.id == accountDestination.resourceId })
            .withEphemeralDisplayFields(accounts)
        val accountOverviewFields = nativeStructuredDetail(accountResource, account).fields.map { it.fieldId }
        assertTrue(accountOverviewFields.none(::isMailAccountInternalField))
        val mailboxes = loadDynamicRecords(
            services,
            session,
            descriptor,
            mailboxDestination.actionId,
            mailboxDestination.pathParameterValues,
            mailboxDestination.pathParameterValues,
        )
        assertTrue(mailboxes.isNotEmpty())
        val mailboxResource = assertNotNull(schema.resources.firstOrNull { it.id == mailboxDestination.resourceId })
        assertTrue(mailboxes.all { record ->
            nativeMailboxPresentation(mailboxResource, record).kind == NativeMailboxItemKind.Folder
        })

        var messageRecords: Pair<
            dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination,
            List<NativeRecord>,
        >? = null
        for (mailbox in mailboxes.take(MAX_MAILBOX_PROBES)) {
            val context = mailbox.context(
                mailboxDestination.resourceId,
                mailboxDestination.pathParameterValues,
            )
            val destination = descriptor.planDynamicNavigation(context).contextualChildDestinations
                .firstOrNull { child ->
                    child.resourceId.semanticWords().any { it in setOf("message", "messages", "email", "emails") }
                } ?: continue
            assertEquals(
                destination.actionId,
                descriptor.preferredSemanticContextualChild(context)?.actionId,
            )
            val loaded = runCatching {
                loadDynamicRecords(
                    services,
                    session,
                    descriptor,
                    destination.actionId,
                    destination.pathParameterValues,
                    destination.pathParameterValues,
                )
            }.getOrNull().orEmpty()
            if (loaded.isNotEmpty()) {
                messageRecords = destination to loaded
                break
            }
        }
        val (messageDestination, messages) = assertNotNull(messageRecords)
        val messageResource = assertNotNull(schema.resources.firstOrNull { it.id == messageDestination.resourceId })
        assertTrue(messages.all { record ->
            nativeMailboxPresentation(messageResource, record).kind == NativeMailboxItemKind.Message
        })
        val message = messages.first()
        val messageContext = message.context(
            messageDestination.resourceId,
            messageDestination.pathParameterValues,
        )
        val bodyDestination = assertNotNull(
            descriptor.planDynamicNavigation(messageContext).contextualChildDestinations.firstOrNull { destination ->
                destination.resourceId.semanticWords().any { it in setOf("body", "content", "messagebody", "htmlbody") }
            },
        )
        val body = assertNotNull(
            loadDynamicRecords(
                services,
                session,
                descriptor,
                bodyDestination.actionId,
                bodyDestination.pathParameterValues,
                bodyDestination.pathParameterValues,
            ).firstOrNull(),
        )
        val bodyResource = assertNotNull(schema.resources.firstOrNull { it.id == bodyDestination.resourceId })
            .withEphemeralDisplayFields(listOf(body))
        assertNotNull(
            dev.obiente.nextcloudnative.nativeui.runtime.nativeMailMessageDetailPresentation(bodyResource, body),
            "bodyKeys=${body.displayValues.keys.sorted()}",
        )
        val plannedKinds = nativeMailMessageActionPlan(
            schema = schema,
            displayedResource = messageResource,
            displayedRecord = message,
            context = NativeDatasetContext(
                relatedRecords = mapOf(
                    accountDestination.resourceId to accounts,
                    mailboxDestination.resourceId to mailboxes,
                ),
            ),
        ).all.map { plan -> plan.kind.name }
        assertTrue(
            "Archive" in plannedKinds,
            "The signed move route and unique archive mailbox must produce a native Archive action. " +
                "messageSafe=${message.actionSafeIdentity} messageKeys=${message.redactedKeyNames()} " +
                "mailboxKeys=${mailboxes.firstOrNull()?.redactedKeyNames().orEmpty()}",
        )
        println(
            "mail-action-audit exposed=${messageMove.binding.method}:${messageMove.binding.path} " +
                "planned=${plannedKinds.sorted()} " +
                "message-safe-id=${message.actionSafeIdentity} " +
                "message-resource=${messageResource.id} action-resource=${messageMove.resourceId} " +
                "archive-folder-count=${mailboxes.count { it.hasRedactedArchiveMarker() }} " +
                "message-keys=${message.redactedKeyNames()} " +
                "account-keys=${accounts.firstOrNull()?.redactedKeyNames().orEmpty()} " +
                "mailbox-keys=${mailboxes.firstOrNull()?.redactedKeyNames().orEmpty()} " +
                "content=redacted execution=disabled",
        )
    }

    private suspend fun auditMusic(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
    ) {
        val app = assertNotNull(server.apps.firstOrNull { app -> app.id == "music" })
        val discovery = discoverDynamicAppDescriptor(services, session, app, server.version)
        assertTrue(discovery.acquisition != DynamicDescriptorAcquisition.MetadataFallback)
        val descriptor = discovery.descriptor
        val schema = descriptor.toNativeAppSchema()
        val trackAction = assertNotNull(descriptor.actions.firstOrNull { action ->
            action.binding.method == HttpMethod.GET &&
                action.intent == ActionIntent.list &&
                action.binding.path.trimEnd('/').endsWith("/tracks") &&
                action.binding.pathParameters.isEmpty()
        })
        val tracks = loadDynamicRecords(services, session, descriptor, trackAction.id)
        assertTrue(tracks.isNotEmpty())
        val trackResource = assertNotNull(schema.resources.firstOrNull { it.id == trackAction.resourceId })
        assertTrue(tracks.all { record ->
            nativeMediaPresentation(trackResource, record).kind == NativeMediaItemKind.Track
        })
        val playableTracks = tracks.mapNotNull { record -> nativeAudioTrack(trackResource, record) }
        assertTrue(playableTracks.isNotEmpty(), "tracks must expose an audio MIME-to-file-id map")
        val sourceCapability = assertNotNull(nativeAudioSourceCapability(discovery, trackAction))
        val source = assertNotNull(sourceCapability.source(playableTracks.first()))
        assertTrue(source.relativePath.contains("/api/files/") && source.relativePath.endsWith("/download"))

        val settingsRead = assertNotNull(descriptor.actions.firstOrNull { action ->
            action.binding.method == HttpMethod.GET &&
                action.binding.path.trimEnd('/').endsWith("/settings") &&
                action.binding.pathParameters.isEmpty()
        })
        val settings = loadDynamicRecords(services, session, descriptor, settingsRead.id)
        assertTrue(settings.isNotEmpty())
        assertTrue(descriptor.forms.any { form -> form.resourceId == settingsRead.resourceId })
    }

    private suspend fun auditCookbook(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
    ) {
        val descriptor = discover(services, session, server, "cookbook")
        val schema = descriptor.toNativeAppSchema()
        val listAction = assertNotNull(descriptor.actions.firstOrNull { action ->
            action.binding.method == HttpMethod.GET &&
                action.intent == ActionIntent.list &&
                action.binding.path.trimEnd('/').endsWith("/recipes") &&
                action.binding.pathParameters.isEmpty()
        })
        val recipes = loadDynamicRecords(services, session, descriptor, listAction.id)
        val recipe = assertNotNull(recipes.firstOrNull())
        val detailAction = assertNotNull(descriptor.actions.firstOrNull { action ->
            action.binding.method == HttpMethod.GET &&
                action.intent == ActionIntent.read &&
                action.resourceId == listAction.resourceId &&
                action.binding.pathParameters.isNotEmpty()
        }, descriptor.actions.filter { action ->
            action.binding.method == HttpMethod.GET && action.binding.path.contains("/recipes")
        }.joinToString { action ->
            "${action.id}:${action.resourceId}:${action.intent}:${action.binding.path}:" +
                action.binding.pathParameters.joinToString { parameter -> parameter.name }
        })
        val context = recipe.context(listAction.resourceId)
        val parameters = assertNotNull(
            descriptor.resolveDynamicRecordReadParameters(detailAction.id, context),
        )
        val details = loadDynamicRecords(
            services,
            session,
            descriptor,
            detailAction.id,
            parameters,
            parameters,
        )
        val detail = assertNotNull(details.firstOrNull())
        val resource = assertNotNull(schema.resources.firstOrNull { it.id == detailAction.resourceId })
            .withEphemeralDisplayFields(listOf(detail))
        val structured = nativeStructuredDetail(resource, detail)
        assertTrue(structured.sections.any { section ->
            section.fieldId.normalizedSemanticKey() in setOf("recipeingredient", "ingredients")
        })
        assertTrue(structured.sections.any { section ->
            section.fieldId.normalizedSemanticKey() in setOf("recipeinstructions", "instructions")
        })
        assertTrue(detail.actionSafeIdentity, "typed detail id must be safe for contextual actions")
        val contextualActions = descriptor.actions.filter { action ->
            action.resourceId == detailAction.resourceId &&
                action.binding.path == detailAction.binding.path &&
                action.intent in setOf(ActionIntent.update, ActionIntent.delete)
        }
        assertTrue(contextualActions.any { action -> action.intent == ActionIntent.update })
        assertTrue(contextualActions.any { action -> action.intent == ActionIntent.delete })
        val contextualForms = descriptor.planDynamicNavigation(
            detail.context(detailAction.resourceId),
        ).contextualFormActions
        assertTrue(contextualActions.all { action ->
            contextualForms.any { form -> form.actionId == action.id }
        })
    }

    private suspend fun discover(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
        appId: String,
    ): DynamicAppDescriptor {
        val app = assertNotNull(server.apps.firstOrNull { app -> app.id == appId })
        val discovery = discoverDynamicAppDescriptor(services, session, app, server.version)
        assertTrue(discovery.acquisition != DynamicDescriptorAcquisition.MetadataFallback)
        return discovery.descriptor
    }

    private fun NativeRecord.context(
        resourceId: String,
        parameters: Map<String, String> = emptyMap(),
    ): DynamicResourceRecordContext = DynamicResourceRecordContext(
        resourceId = resourceId,
        recordId = id,
        fieldValues = values,
        parameterValues = parameters,
        actionSafeIdentity = actionSafeIdentity,
    )

    private fun String.semanticWords(): Set<String> = lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .toSet()

    private fun NativeRecord.redactedKeyNames(): List<String> =
        (values.keys + displayValues.keys).distinct().sorted()

    private fun NativeRecord.hasRedactedArchiveMarker(): Boolean =
        (values + displayValues).entries
            .filter { (key, _) ->
                key.lowercase().filter(Char::isLetterOrDigit) in
                    setOf("specialuse", "specialrole", "name", "displayname")
            }
            .any { (_, value) -> value.orEmpty().contains("archive", ignoreCase = true) }

    private fun String.normalizedSemanticKey(): String = lowercase().filter(Char::isLetterOrDigit)

    private fun isMailAccountInternalField(fieldId: String): Boolean {
        val key = fieldId.normalizedSemanticKey()
        return key.startsWith("imap") ||
            key.startsWith("smtp") ||
            key.startsWith("inbound") ||
            key.startsWith("outbound") ||
            listOf(
                "password",
                "passphrase",
                "secret",
                "credential",
                "privatekey",
                "apikey",
                "accesstoken",
                "refreshtoken",
                "server",
                "host",
                "hostname",
                "port",
                "sslmode",
                "tlsmode",
            ).any(key::contains)
    }

    private class GetOnlyAuditServices(
        private val delegate: NextcloudPlatformServices,
        private val observed: MutableList<NextcloudApiRequest>,
    ) : NextcloudPlatformServices by delegate {
        override suspend fun executeNextcloudApi(
            session: NextcloudSession,
            request: NextcloudApiRequest,
        ): NextcloudApiResponse {
            assertTrue(request.method == NextcloudApiMethod.GET)
            assertTrue(request.body == null)
            observed += request
            return delegate.executeNextcloudApi(session, request)
        }
    }

    private companion object {
        const val MAX_MAILBOX_PROBES = 8
    }
}
