package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.Serializable

/**
 * What can go wrong when one operation runs, and therefore which evidence and safeguards it needs
 * before it is offered (see [admitDynamicWrite]).
 *
 * The class is derived from the HTTP method, the declared effect, the operation's own semantic
 * words, and declared permission fields. It is never derived from an app ID or a version.
 * [ActionRisk] stays the schema wire field for confirmation and styling; this class is an added
 * descriptor field.
 */
@Serializable
enum class DynamicOperationRiskClass(val code: String) {
    /** R0: a read. */
    read("R0"),

    /** R1: an idempotent state set, such as marking read, starring, or completing a task. */
    idempotentStateSet("R1"),

    /** R2: a reversible scalar edit whose merge semantics are known, such as a reorder or move. */
    reversibleScalarEdit("R2"),

    /** R3: a declared partial update (PATCH, or a declared merge). */
    partialUpdate("R3"),

    /** R4: a whole replacement (PUT). */
    replacement("R4"),

    /** R5: a create that a later read can verify. */
    create("R5"),

    /** R6: an effect outside the app: sending mail, inviting, notifying, merging. */
    externalEffect("R6"),

    /** R7: a destructive change: delete, empty, purge, leave. */
    destructive("R7"),

    /** R8: sharing and permissions. */
    sharing("R8"),

    /** R9: administration and app lifecycle. Never offered dynamically. */
    administration("R9"),

    /** R10: credentials. Never offered dynamically. */
    credential("R10"),
    ;

    /**
     * True when no generic form, record action, or menu may offer this operation. Such an
     * operation is offered only by a surface that applies its class's safeguards (for example the
     * exactly-once composer for a send), or not at all.
     */
    val requiresDedicatedSurface: Boolean
        get() = this == externalEffect || this == administration || this == credential
}

/**
 * Classifies one operation. Semantic words are read from the operation ID, the summary, and the
 * literal route segments; path placeholders are ignored, so `{token}` in a conversation route is
 * not mistaken for a credential.
 */
fun dynamicOperationRiskClass(
    method: HttpMethod,
    effect: ActionEffect,
    risk: ActionRisk,
    path: String,
    operationId: String,
    label: String,
    hasBody: Boolean,
): DynamicOperationRiskClass {
    if (method == HttpMethod.GET) return DynamicOperationRiskClass.read
    val words = actionSemanticWords(path.withoutRoutePlaceholders(), operationId, label)
    return when {
        words.isCredentialOperation() -> DynamicOperationRiskClass.credential
        words.any(ADMINISTRATION_WORDS::contains) -> DynamicOperationRiskClass.administration
        words.any(SHARING_WORDS::contains) -> DynamicOperationRiskClass.sharing
        words.any(EXTERNAL_EFFECT_WORDS::contains) -> DynamicOperationRiskClass.externalEffect
        risk == ActionRisk.destructive || effect in DESTRUCTIVE_EFFECTS -> DynamicOperationRiskClass.destructive
        effect in STATE_SET_EFFECTS -> DynamicOperationRiskClass.idempotentStateSet
        effect == ActionEffect.reorder || effect == ActionEffect.move -> DynamicOperationRiskClass.reversibleScalarEdit
        method == HttpMethod.PATCH -> DynamicOperationRiskClass.partialUpdate
        // A body-free PUT on a command route sets one state; it carries no representation.
        method == HttpMethod.PUT && !hasBody -> DynamicOperationRiskClass.idempotentStateSet
        method == HttpMethod.PUT -> DynamicOperationRiskClass.replacement
        method == HttpMethod.DELETE -> DynamicOperationRiskClass.destructive
        // Any other POST is treated as non-idempotent: single attempt, no automatic retry.
        else -> DynamicOperationRiskClass.create
    }
}

/**
 * The class the compiler recorded, or the same classification for a hand-built descriptor. A
 * package-stated access rule that limits the method to administrators always makes a write R9.
 */
val DynamicAction.operationRiskClass: DynamicOperationRiskClass
    get() = contractFacts.withAccessRule(
        riskClass ?: dynamicOperationRiskClass(
            method = binding.method,
            effect = effect,
            risk = risk,
            path = binding.path,
            operationId = id,
            label = label,
            hasBody = binding.body != null,
        ),
    )

/**
 * Applies the verified package's access rule to [classified]. A write the server limits to
 * administrators (`admin`, inferred from a missing NoAdminRequired) is administration (R9), and so
 * are the other privileged rules acquisition already refuses. Missing facts, such as for a
 * same-origin advertisement, are unknown, never administration. Reads keep their class.
 */
fun DynamicContractFacts?.withAccessRule(classified: DynamicOperationRiskClass): DynamicOperationRiskClass =
    if (classified != DynamicOperationRiskClass.read && this?.access in PRIVILEGED_ACCESS) {
        DynamicOperationRiskClass.administration
    } else {
        classified
    }

private val PRIVILEGED_ACCESS = setOf(
    DynamicAccessLevel.admin,
    DynamicAccessLevel.subAdmin,
    DynamicAccessLevel.delegatedAdmin,
    DynamicAccessLevel.externalApp,
)

/** The class the schema carries, or the same classification for a hand-built schema. */
val ActionSpec.operationRiskClass: DynamicOperationRiskClass
    get() = riskClass ?: dynamicOperationRiskClass(
        method = binding.method,
        effect = effect,
        risk = risk,
        path = binding.path,
        operationId = binding.operationId,
        label = label,
        hasBody = binding.bodyContentType != null,
    )

private fun String.withoutRoutePlaceholders(): String =
    split('/').filterNot { segment -> segment.startsWith('{') && segment.endsWith('}') }.joinToString("/")

private fun Set<String>.isCredentialOperation(): Boolean =
    any(CREDENTIAL_WORDS::contains) ||
        ("api" in this && ("key" in this || "keys" in this)) ||
        ("app" in this && "password" in this)

private val CREDENTIAL_WORDS = setOf(
    "password", "passwords", "passphrase", "credential", "credentials", "secret", "secrets", "apikey", "apikeys",
)

private val ADMINISTRATION_WORDS = setOf("admin", "administration", "administrator", "provisioning", "install", "uninstall")

/** Exact words: `shared`, `sharee`, and `shareable` describe data, not the act of sharing. */
private val SHARING_WORDS = setOf("share", "shares", "sharing", "reshare", "unshare")

/** Exact words: `sender`, `sending`, and `mergeable` describe data, not an external effect. */
private val EXTERNAL_EFFECT_WORDS = setOf("send", "merge", "notify", "invite", "publish")

private val DESTRUCTIVE_EFFECTS = setOf(
    ActionEffect.delete,
    ActionEffect.permanentDelete,
    ActionEffect.empty,
    ActionEffect.leave,
    ActionEffect.clear,
)

private val STATE_SET_EFFECTS = setOf(
    ActionEffect.toggle,
    ActionEffect.archive,
    ActionEffect.unarchive,
    ActionEffect.assign,
    ActionEffect.restore,
)
