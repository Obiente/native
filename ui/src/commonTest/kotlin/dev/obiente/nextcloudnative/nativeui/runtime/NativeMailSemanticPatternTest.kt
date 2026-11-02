package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptorCompiler
import dev.obiente.nextcloudnative.nativeui.model.DynamicDiscoveryInput
import dev.obiente.nextcloudnative.nativeui.model.DynamicOperationRiskClass
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.OpenApiTrust
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mailbox surface and its writes come from the contract's shape. Registering the same contract
 * under another app identifier or version must not change what the user gets.
 */
class NativeMailSemanticPatternTest {
    @Test
    fun `a synchronous send is reserved for the exactly-once composer under every app id and version`() {
        val identities = listOf("mail" to "5.12.6", "acme_mail" to "99.1.0", "postbox" to "0.0.1")
        val outcomes = identities.map { (appId, version) ->
            val descriptor = compileDescriptor(appId, version, mailOpenApi(appId, SendShape.SynchronousPost), signed = true)
            val schema = descriptor.toNativeAppSchema()
            assertTrue(schema.hasNativeMailWorkspaceSemantics(), "$appId is not a mailbox workspace")
            assertTrue(schema.views.any { it.resourceId == "messages" && it.component == NativeComponent.mailbox }, appId)
            // An external effect is never a generic form or record action.
            assertTrue(schema.actions.none { "send" in it.id } && schema.views.none { "send" in it.sourceActionId }, appId)
            val capability = assertNotNull(nativeMailSendCapability(schema, accountContext(schema)), appId)
            assertEquals(DynamicOperationRiskClass.externalEffect, capability.action.riskClass)
            assertNull(capability.action.resultRecoveryActionId, "A POST send has no recovery read; Unknown stays may-have-been-sent.")
            capability.action.binding.path.substringAfter("/apps/$appId")
        }
        assertEquals(1, outcomes.distinct().size, "$outcomes")
    }

    @Test
    fun `the same recoverable send gets the same composer request under any app id and version`() {
        val requests = listOf("acme_mail" to "99.1.0", "postbox" to "0.0.1", "mail" to "5.12.6").map { (appId, version) ->
            val schema = compile(appId, version, mailOpenApi(appId, SendShape.RecoverablePut))
            assertTrue(schema.hasNativeMailWorkspaceSemantics(), "$appId is not a mailbox workspace")
            val capability = assertNotNull(
                nativeMailSendCapability(schema, accountContext(schema)),
                "$appId has no send: ${schema.describeSendActions()}",
            )
            assertEquals(listOf("ada@example.test"), capability.senders.map(NativeMailSender::email))
            assertTrue(capability.supportsCc && capability.supportsBcc)
            val request = capability.request(
                NativeMailComposeDraft(
                    mode = NativeMailComposeMode.New,
                    senderId = capability.senders.single().key,
                    to = "Grace <grace@example.test>, lin@example.test",
                    cc = "kai@example.test",
                    subject = "Status",
                    body = "Hello",
                ),
            )
            assertTrue(request.action.binding.path.endsWith("/apps/$appId/accounts/{accountId}/send"), request.action.binding.path)
            request.values
        }
        assertEquals(1, requests.distinct().size, "requests differ: $requests")
        val values = requests.first()
        assertEquals("7", values["accountId"])
        assertEquals("ada@example.test", values["fromEmail"])
        assertEquals("false", values["isHtml"])
        assertEquals("Status", values["subject"])
        assertEquals("Hello", values["body"])
        val to = Json.parseToJsonElement(assertNotNull(values["to"])) as JsonArray
        assertEquals(
            listOf("grace@example.test" to "Grace", "lin@example.test" to null),
            to.map { item ->
                val entry = item as JsonObject
                entry.getValue("email").jsonPrimitive.content to (entry["label"] as? JsonPrimitive)?.content
            },
        )
        assertFalse("bcc" in values)
        assertFalse("references" in values)
    }

    @Test
    fun `a contract without a send operation offers no composer`() {
        val schema = compile("acme_mail", "99.1.0", mailOpenApi("acme_mail", SendShape.None))
        assertTrue(schema.hasNativeMailWorkspaceSemantics())
        assertNull(nativeMailSendCapability(schema, accountContext(schema)))
    }

    @Test
    fun `a send operation with an unknown required input is not offered`() {
        val schema = compile(
            "acme_mail",
            "1.0.0",
            mailOpenApi("acme_mail", SendShape.RecoverablePut, extraRequiredSendField = "signingKeyId"),
        )
        assertTrue(schema.dedicatedActions.any { "send" in it.id && it.binding.method.name == "PUT" }, schema.describeSendActions())
        assertNull(nativeMailSendCapability(schema, accountContext(schema)))
    }

    @Test
    fun `send is not offered without a loaded sending account`() {
        val schema = compile("acme_mail", "1.0.0", mailOpenApi("acme_mail", SendShape.RecoverablePut))
        assertNull(nativeMailSendCapability(schema, NativeDatasetContext()))
    }

    @Test
    fun `a contract that only mentions mail in a field does not become a mailbox`() {
        val schema = compile("crm", "3.0.0", CRM_OPEN_API, signed = false)
        assertTrue(schema.views.isNotEmpty())
        assertTrue(schema.views.none { it.component == NativeComponent.mailbox }, "${schema.views.map { it.component }}")
        assertFalse(schema.hasNativeMailWorkspaceSemantics())
        assertNull(nativeMailSendCapability(schema, NativeDatasetContext()))
    }

    @Test
    fun `a reply threads through the original message id when the contract declares it`() {
        val schema = compile("acme_mail", "2.0.0", mailOpenApi("acme_mail", SendShape.RecoverablePut))
        val capability = assertNotNull(nativeMailSendCapability(schema, accountContext(schema)), schema.describeSendActions())
        val messageResource = assertNotNull(schema.resources.firstOrNull { it.id == "messages" })
        val original = NativeRecord(
            id = "41",
            values = mapOf(
                "subject" to "Plans",
                "from" to """[{"label":"Grace","email":"grace@example.test"}]""",
                "to" to """[{"label":"Ada","email":"ada@example.test"},{"email":"lin@example.test"}]""",
                "messageId" to "<plans-41@example.test>",
                "body" to "See you there.",
            ),
        )
        val draft = assertNotNull(nativeMailOriginal(messageResource, original)).let { parsed ->
            nativeMailResponseDraft(NativeMailComposeMode.ReplyAll, parsed, capability.senders.single().key, setOf("ada@example.test"))
        }
        assertEquals("Grace <grace@example.test>, lin@example.test", draft.to)
        assertEquals("Re: Plans", draft.subject)
        val values = capability.request(draft).values
        assertEquals("<plans-41@example.test>", values["references"])
        assertTrue(assertNotNull(values["body"]).contains("> See you there."))
    }

    @Test
    fun `a contract that needs both an account id and a sender identity id offers no composer`() {
        val schema = compile("acme_mail", "1.0.0", mailOpenApi("acme_mail", SendShape.RecoverablePut, extraRequiredSendField = "identityId"))
        assertNull(nativeMailSendCapability(schema, accountContext(schema)), schema.describeSendActions())
    }

    @Test
    fun `an optional sender identity id is left for the server instead of receiving the account id`() {
        val schema = compile("acme_mail", "1.0.0", mailOpenApi("acme_mail", SendShape.RecoverablePut, optionalSendField = "aliasId"))
        val capability = assertNotNull(nativeMailSendCapability(schema, accountContext(schema)), schema.describeSendActions())
        assertEquals(listOf("accountId"), capability.senderIdentityFields)
        val values = capability.request(
            NativeMailComposeDraft(NativeMailComposeMode.New, capability.senders.single().key, to = "grace@example.test", body = "Hi"),
        ).values
        assertEquals("7", values["accountId"])
        assertFalse("aliasId" in values)
    }

    @Test
    fun `contacts with addresses never become sending accounts`() {
        val schema = compile("acme_mail", "1.0.0", mailOpenApi("acme_mail", SendShape.RecoverablePut, includeContacts = true))
        val contacts = assertNotNull(schema.resources.firstOrNull { it.id == "suggestions" }, "${schema.resources.map { it.id }}")
        val suggestion = NativeRecord("9", mapOf("id" to "9", "email" to "lin@example.test", "name" to "Lin"))
        assertNull(nativeMailSendCapability(schema, NativeDatasetContext(relatedRecords = mapOf(contacts.id to listOf(suggestion)))))
        val withAccount = accountContext(schema).let { it.copy(relatedRecords = it.relatedRecords + (contacts.id to listOf(suggestion))) }
        val capability = assertNotNull(nativeMailSendCapability(schema, withAccount))
        assertEquals(listOf("ada@example.test"), capability.senders.map(NativeMailSender::email))
    }

    @Test
    fun `the recoverable send keeps its declared status read for unknown outcomes`() {
        val schema = compile("acme_mail", "1.0.0", mailOpenApi("acme_mail", SendShape.RecoverablePut))
        val capability = assertNotNull(nativeMailSendCapability(schema, accountContext(schema)))
        val recovery = assertNotNull(schema.action(assertNotNull(capability.action.resultRecoveryActionId)))
        assertEquals(capability.action.binding.path, recovery.binding.path)
        assertEquals("GET", recovery.binding.method.name)
    }

    @Test
    fun `a reply sends the original references chain`() {
        val schema = compile("acme_mail", "2.0.0", mailOpenApi("acme_mail", SendShape.RecoverablePut))
        val capability = assertNotNull(nativeMailSendCapability(schema, accountContext(schema)))
        val messageResource = assertNotNull(schema.resources.firstOrNull { it.id == "messages" })
        val original = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Plans",
                "from" to "grace@example.test",
                "messageId" to "<plans-42@example.test>",
                "references" to "<plans-1@example.test> <plans-41@example.test>",
                "body" to "Later.",
            ),
        )
        val draft = nativeMailResponseDraft(
            NativeMailComposeMode.Reply,
            assertNotNull(nativeMailOriginal(messageResource, original)),
            capability.senders.single().key,
            setOf("ada@example.test"),
        )
        assertEquals(
            "<plans-1@example.test> <plans-41@example.test> <plans-42@example.test>",
            capability.request(draft).values["references"],
        )
    }

    private fun NativeAppSchema.describeSendActions(): String = actions.filter { "send" in it.id.lowercase() }.joinToString { action ->
        listOf(
            action.id, action.binding.method, action.intent, action.risk, action.confidence, action.evidence.map { it.source },
            action.resultRecoveryActionId, action.binding.bodyContentType, action.binding.requiredBodyFieldNames,
            action.binding.requiredPathParameterNames, action.binding.requiredQueryParameterNames,
        ).toString()
    }.ifEmpty { "no send action among ${actions.map { it.id }}" }

    private fun accountContext(schema: NativeAppSchema): NativeDatasetContext {
        val account = assertNotNull(schema.resources.firstOrNull { it.id == "account" }, "${schema.resources.map { it.id }}")
        return NativeDatasetContext(
            relatedRecords = mapOf(
                account.id to listOf(
                    NativeRecord("7", mapOf("id" to "7", "email" to "ada@example.test", "name" to "Ada")),
                ),
            ),
        )
    }

    private fun compile(appId: String, version: String, document: String, signed: Boolean = true): NativeAppSchema =
        compileDescriptor(appId, version, document, signed).toNativeAppSchema()

    private fun compileDescriptor(appId: String, version: String, document: String, signed: Boolean): DynamicAppDescriptor =
        DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity(appId, appId.replaceFirstChar(Char::uppercase), version),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/ocs/v2.php/apps/$appId", "/apps/$appId"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = if (signed) "https://apps.nextcloud.com/packages/$appId#openapi.json" else "/apps/$appId/openapi.json",
                    document = Json.parseToJsonElement(document),
                    trust = if (signed) OpenApiTrust.nextcloudSignedAppPackage else OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

    private enum class SendShape { None, SynchronousPost, RecoverablePut }

    private companion object {
        fun mailOpenApi(
            appId: String,
            sendShape: SendShape,
            extraRequiredSendField: String? = null,
            optionalSendField: String? = null,
            includeContacts: Boolean = false,
        ): String {
            val extraProperty = listOfNotNull(extraRequiredSendField, optionalSendField)
                .joinToString("") { """, "$it": { "type": "string" }""" }
            val extraRequired = extraRequiredSendField?.let { """, "$it"""" }.orEmpty()
            val synchronous = sendShape == SendShape.SynchronousPost
            val identityRequired = if (synchronous) """"accountId", """ else ""
            val identityProperty = if (synchronous) """"accountId": { "type": "integer" },""" else ""
            val sendBody = """
                "requestBody": { "required": true, "content": { "application/json": { "schema": {
                  "type": "object",
                  "required": [$identityRequired"fromEmail", "subject", "body", "isHtml", "to"$extraRequired],
                  "properties": {
                    $identityProperty
                    "fromEmail": { "type": "string" },
                    "subject": { "type": "string" },
                    "body": { "type": "string" },
                    "isHtml": { "type": "boolean" },
                    "to": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Recipient" } },
                    "cc": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Recipient" } },
                    "bcc": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Recipient" } },
                    "references": { "type": "string", "nullable": true }$extraProperty
                  }
                } } } },
                "responses": { "200": { "description": "Sent", "content": { "application/json": { "schema": {
                  "type": "object", "properties": { "ocs": { "type": "object", "properties": { "data": { "type": "string" } } } }
                } } } } }
            """.trimIndent()
            val send = when (sendShape) {
                SendShape.None -> ""
                SendShape.SynchronousPost -> """
                    ,
                    "/ocs/v2.php/apps/$appId/message/send": {
                      "post": { "operationId": "message_api-send", "tags": ["message_api"], $sendBody }
                    }
                """.trimIndent()
                SendShape.RecoverablePut -> """
                    ,
                    "/ocs/v2.php/apps/$appId/accounts/{accountId}/send": {
                      "parameters": [ { "name": "accountId", "in": "path", "required": true, "schema": { "type": "integer" } } ],
                      "get": {
                        "operationId": "send_api-status", "tags": ["send_api"],
                        "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                          "type": "object", "properties": { "ocs": { "type": "object", "properties": {
                            "data": { "type": "object", "properties": { "status": { "type": "string" } } }
                          } } }
                        } } } } }
                      },
                      "put": { "operationId": "send_api-send", "tags": ["send_api"], $sendBody }
                    }
                """.trimIndent()
            }
            return """
            {
              "openapi": "3.0.3",
              "components": { "schemas": {
                "Account": { "type": "object", "required": ["id"], "properties": {
                  "id": { "type": "integer" }, "email": { "type": "string" }, "name": { "type": "string" }
                } },
                "Mailbox": { "type": "object", "required": ["databaseId"], "properties": {
                  "databaseId": { "type": "integer" }, "name": { "type": "string" }, "unread": { "type": "integer" }
                } },
                "Message": { "type": "object", "required": ["databaseId"], "properties": {
                  "databaseId": { "type": "integer" }, "subject": { "type": "string" },
                  "from": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Recipient" } },
                  "to": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Recipient" } },
                  "dateInt": { "type": "integer" }
                } },
                "Recipient": { "type": "object", "required": ["email"], "properties": {
                  "label": { "type": "string" }, "email": { "type": "string" }
                } }
              } },
              "paths": {
                "/ocs/v2.php/apps/$appId/account/list": {
                  "get": {
                    "operationId": "account_api-list", "tags": ["account_api"],
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "object", "properties": { "ocs": { "type": "object", "properties": {
                        "data": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Account" } }
                      } } }
                    } } } } }
                  }
                },
                "/ocs/v2.php/apps/$appId/ocs/mailboxes": {
                  "get": {
                    "operationId": "mailboxes_api-list", "tags": ["mailboxes_api"],
                    "parameters": [ { "name": "accountId", "in": "query", "required": true, "schema": { "type": "integer" } } ],
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "object", "properties": { "ocs": { "type": "object", "properties": {
                        "data": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Mailbox" } }
                      } } }
                    } } } } }
                  }
                },
                "/ocs/v2.php/apps/$appId/ocs/mailboxes/{mailboxId}/messages": {
                  "get": {
                    "operationId": "mailboxes_api-list-messages", "tags": ["mailboxes_api"],
                    "parameters": [ { "name": "mailboxId", "in": "path", "required": true, "schema": { "type": "integer" } } ],
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "object", "properties": { "ocs": { "type": "object", "properties": {
                        "data": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Message" } }
                      } } }
                    } } } } }
                  }
                }$send${if (includeContacts) CONTACTS_PATH.replace("APP", appId) else ""}
              }
            }
            """.trimIndent()
        }

        val CONTACTS_PATH = """
            ,
            "/ocs/v2.php/apps/APP/contacts/suggestions": {
              "get": {
                "operationId": "contacts_api-suggestions", "tags": ["contacts_api"],
                "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                  "type": "object", "properties": { "ocs": { "type": "object", "properties": {
                    "data": { "type": "array", "items": { "type": "object", "required": ["id"], "properties": {
                      "id": { "type": "integer" }, "email": { "type": "string" }, "name": { "type": "string" }
                    } } }
                  } } }
                } } } } }
              }
            }
        """.trimIndent()

        val CRM_OPEN_API = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/apps/crm/api/mail-templates": {
                  "get": {
                    "operationId": "templates-list",
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "array", "items": { "type": "object", "required": ["id"], "properties": {
                        "id": { "type": "integer" }, "name": { "type": "string" },
                        "content": { "type": "string" }, "mail": { "type": "string" }
                      } }
                    } } } } }
                  }
                },
                "/apps/crm/api/contacts": {
                  "get": {
                    "operationId": "contacts-list",
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "array", "items": { "type": "object", "required": ["id"], "properties": {
                        "id": { "type": "integer" }, "name": { "type": "string" }, "mail": { "type": "string" }
                      } }
                    } } } } }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
