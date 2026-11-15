package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.serialization.json.*

internal class MembershipExecutionFixture(app: String = "planning-app", nested: Boolean = false) : AutoCloseable {
    val transport = DynamicRuleExecutionFixture(app = app)
    var descriptor: DynamicAppDescriptor
        get() = transport.descriptor
        set(value) { transport.descriptor = value }
    init {
        descriptor = membershipContract(app, nested, renameParents = nested)
        transport.readBody = body()
        transport.afterWrite = body("""[{"id":3,"title":"Kept"},{"id":5,"title":"New"}]""")
    }
    val read get() = descriptor.actions.single { it.binding.method == HttpMethod.GET && it.binding.path.endsWith("/{id}") }
    val bindings get() = read.binding.pathParameters.associate { it.name to when (it.name) { "id" -> "41"; "workspaceId", "boardId" -> "7"; else -> "10" } }
    var authority = freshAuthority()
    fun freshAuthority(body: String = transport.readBody) = NativeItemReadAuthority(
        NativeScreenState.Ready.authoritative(parseDynamicRecords(read, DynamicRuleExecutionFixture.response(200, body), read.responseFieldIds.toSet())),
        read.id, bindings, canEdit = true)
    fun executor() = DynamicNextcloudActionExecutor(transport.services, transport.session, descriptor, membershipAuthority = { authority })
    fun card(body: String = body()): DeckCard = parseDeckCard(7, 10, DynamicRuleExecutionFixture.response(200, body)).copy(
        readSource = DeckCardReadSource(read.id, read.binding.path,
            buildDynamicApiRequest(descriptor, read, bindings, emptyMap()).relativePath),
    )
    fun request(assign: Boolean = true, member: JsonPrimitive = JsonPrimitive(5)): NativeActionRequest.Submit {
        val schema = descriptor.toNativeAppSchema()
        val action = (schema.actions + schema.dedicatedActions).single { it.binding.path.endsWith(if (assign) "/assignLabel" else "/removeLabel") }
        val plan = checkNotNull(planNativeMembershipRule(schema, action.id, "41", member, authority)) { "No membership plan: $action" }
        return NativeActionRequest.Submit(action, plan.writeBindings, true, membershipPlan = plan)
    }
    fun restart() {
        transport.safety = FileDynamicWriteQuarantineStore(transport.safetyDirectory)
        authority = freshAuthority()
    }
    override fun close() = transport.close()
    companion object {
        fun body(labels: String = """[{"id":3,"title":"Kept"}]""", extra: String = "") =
            """{"id":41,"title":"Synthetic card","labels":$labels$extra}"""
    }
}
