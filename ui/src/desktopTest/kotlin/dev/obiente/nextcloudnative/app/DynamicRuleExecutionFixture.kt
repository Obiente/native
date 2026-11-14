package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

/** Synthetic contracts and real planner/executor/persistence adapters. No external traffic. */
internal class DynamicRuleExecutionFixture(
    app: String = "news",
    version: String = "37.0.0",
    val parent: Boolean = false,
    val itemRead: Boolean = false,
    val booleanUpdate: Boolean = false,
    val routePadding: Int = 0,
) : AutoCloseable {
    val directory = Files.createTempDirectory("native-phase3-state-rule-test").toFile()
    val session = NextcloudSession("https://cloud.example.test", "synthetic-${java.util.UUID.randomUUID()}", "synthetic-password")
    val safetyDirectory = directory.resolve("safety")
    val draftDirectory = directory.resolve("drafts")
    val storage = JvmTextEditorDraftStorage(draftDirectory, keyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
    var safety: DynamicWriteQuarantineStore = FileDynamicWriteQuarantineStore(safetyDirectory)
    val requests = mutableListOf<NextcloudApiRequest>()
    val events = mutableListOf<SupportDiagnosticEventDraft>()
    var writeStatus = 200
    var readStatus = 200
    var afterWrite: String? = records(unread = false)
    var readBody = records()
    var onSend: suspend () -> Unit = {}
    var onRead: suspend () -> Unit = {}
    var beforeSave: suspend () -> Unit = {}
    var afterSave: suspend () -> Unit = {}
    var removeFails = false
    val base = "/apps/$app/api/" + (if (routePadding > 0) "x".repeat(routePadding) + "/" else "") + if (parent) "folders/{folderId}/items" else "items"
    var descriptor = compile(app, version)
    val navigation get() = if (parent) mapOf("folderId" to "3") else emptyMap()
    var authority = freshAuthority()
    val services: NextcloudPlatformServices

    init {
        AccountPrivateMemoryLifecycle.activateAccount(session.accountId.storageKey)
        val unavailable = Proxy.newProxyInstance(NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as NextcloudPlatformServices
        services = object : NextcloudPlatformServices by unavailable {
            override fun dynamicWriteQuarantineStore() = safety
            override fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) { events += event }
            override fun textEditorDraftStore(session: NextcloudSession, path: String): TextEditorDraftStore {
                val bound = storage.bind(session.accountId.storageKey, path)
                return object : TextEditorDraftStore by bound {
                    override suspend fun save(draft: TextEditorDraft) { beforeSave(); bound.save(draft); afterSave() }
                    override suspend fun remove() { check(!removeFails); bound.remove() }
                }
            }
            override suspend fun executeNextcloudApi(session: NextcloudSession, request: NextcloudApiRequest): NextcloudApiResponse {
                requests += request
                if (request.method != NextcloudApiMethod.GET) {
                    onSend()
                    afterWrite?.let { readBody = it }
                    return response(writeStatus, "{}")
                }
                onRead()
                val body = if (itemRead && request.relativePath.endsWith("/41")) {
                    (Json.parseToJsonElement(readBody) as JsonArray).firstOrNull()?.toString() ?: "[]"
                } else readBody
                return response(readStatus, body)
            }
        }
    }

    fun executor() = DynamicNextcloudActionExecutor(services, session, descriptor, navigation, clock = { 1_000L }, ruleAuthority = { authority })
    fun source() = descriptor.actions.single { it.binding.method == HttpMethod.GET && it.binding.path == base }
    fun freshAuthority(body: String = readBody): NativeRuleAuthority = NativeRuleAuthority(
        NativeScreenState.Ready.authoritative(parseDynamicRecords(source(), response(200, body), source().responseFieldIds.toSet())), source().id, navigation)

    fun request(command: String = "read", id: String = "41"): NativeActionRequest.Submit {
        val schema = descriptor.toNativeAppSchema()
        val action = (schema.actions + schema.dedicatedActions).single { it.binding.method != HttpMethod.GET &&
            if (booleanUpdate) it.binding.path.endsWith("/{itemId}") else it.binding.path.endsWith("/$command") }
        val values = if (booleanUpdate) mapOf("unread" to (command == "unread").toString()) else emptyMap()
        val plan = checkNotNull(planNativePatternRule(schema, action.id, id, authority, values)) { "No plan for $command" }
        return NativeActionRequest.Submit(action, plan.writeBindings, true, rulePlan = plan)
    }

    fun feedRequest(star: Boolean = false): NativeActionRequest.Submit {
        val schema = descriptor.toNativeAppSchema()
        val article = checkNotNull(nativeFeedArticle(checkNotNull(schema.resource(source().resourceId)), authority.state.records.first()))
        val actions = nativeFeedItemActions(schema, article, authority)
        return checkNotNull(if (star) actions.starState else actions.readState).request()
    }

    suspend fun entries() = safety.records(session.accountId.storageKey, descriptor.app.id)
    val writes get() = requests.count { it.method != NextcloudApiMethod.GET }
    fun restart() { safety = FileDynamicWriteQuarantineStore(safetyDirectory); authority = freshAuthority() }
    override fun close() { directory.deleteRecursively() }

    private fun compile(app: String, version: String): DynamicAppDescriptor {
        fun parameter(name: String) = """{"name":"$name","in":"path","required":true,"schema":{"type":"integer"}}"""
        val parents = if (parent) listOf(parameter("folderId")) else emptyList()
        val itemParameters = (parents + parameter("itemId")).joinToString(",")
        val response = """{"200":{"description":"OK","content":{"application/json":{"schema":{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"},"title":{"type":"string"},"body":{"type":"string"},"unread":{"type":"boolean"},"starred":{"type":"boolean"}}}}}}}}"""
        val paths = mutableListOf(""""$base":{"get":{"operationId":"list-items","parameters":[${parents.joinToString(",")}],"responses":$response}}""")
        if (itemRead) paths += """"$base/{id}":{"get":{"operationId":"get-item","parameters":[${(parents + parameter("id")).joinToString(",")}],"responses":$response}}"""
        if (booleanUpdate) paths += """"$base/{itemId}":{"put":{"operationId":"set-unread","parameters":[$itemParameters],"requestBody":{"required":true,"content":{"application/json":{"schema":{"type":"object","required":["unread"],"properties":{"unread":{"type":"boolean"}}}}}},"responses":{"200":{"description":"OK"}}}}"""
        else for (command in listOf("read", "unread", "star", "unstar")) paths += """"$base/{itemId}/$command":{"post":{"operationId":"item-$command","parameters":[$itemParameters],"responses":{"200":{"description":"OK"}}}}"""
        val document = """{"openapi":"3.0.3","paths":{${paths.joinToString(",")}}}"""
        return DynamicAppDescriptorCompiler().compile(DynamicDiscoveryInput(AppIdentity(app, app, version),
            EndpointPolicy("https://cloud.example.test", listOf("/apps/$app")),
            advertisedOpenApi = AdvertisedOpenApi("/apps/$app/openapi.json", Json.parseToJsonElement(document), OpenApiTrust.sameOriginAdvertisement)))
    }

    companion object {
        fun records(unread: Boolean = true, id: JsonPrimitive = JsonPrimitive(41), extra: String = "", starred: Boolean = false): String =
            """[{"id":$id,"title":"Synthetic article","body":"Synthetic content","unread":$unread,"starred":$starred$extra}]"""
        fun response(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
    }
}
