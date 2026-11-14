package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.Serializable

/**
 * How strongly one operation is evidenced. Tiers are assigned per action from its provenance,
 * never per app, and never from an app ID or version.
 *
 * - E1 [standardProtocol]: a standard protocol (WebDAV, CalDAV, CardDAV, OCS core) with RFC
 *   semantics. It proves method semantics, preconditions, and idempotency.
 * - E2 [advertisedOpenApi]: the installed app's own OpenAPI document. It proves the route, method,
 *   parameters, body schema, and declared responses, but not idempotency or merge semantics.
 * - E3 [signedPackage]: static evidence from the verified signed package (or, at lower trust, the
 *   exact App Store-linked source tag). Same as E2, plus scalar parameter types and defaults.
 * - E4 [patternRule]: a versioned, reviewed rule matched against an E2 or E3 shape. It proposes a
 *   semantic reading and needs E5 confirmation on first use.
 * - E5 [observedRoundTrip]: a device-local read/re-encode/compare or write/read-back/diff for one
 *   account, app version, and route.
 * - E6 [observedRead]: a successful approved read. It proves identity values and field shapes, and
 *   never any operation, encoding, or write semantics.
 */
@Serializable
enum class DynamicEvidenceTier(val code: String) {
    standardProtocol("E1"),
    advertisedOpenApi("E2"),
    signedPackage("E3"),
    patternRule("E4"),
    observedRoundTrip("E5"),
    observedRead("E6"),
    ;

    /** True when this tier itself declares an operation (route, method, and inputs). */
    val declaresOperation: Boolean
        get() = this == standardProtocol || this == advertisedOpenApi || this == signedPackage

    /** Lower is stronger when one action carries several kinds of evidence. */
    internal val strength: Int
        get() = when (this) {
            standardProtocol -> 0
            signedPackage -> 1
            advertisedOpenApi -> 2
            patternRule -> 3
            observedRoundTrip -> 4
            observedRead -> 5
        }
}

/**
 * The tier one provenance record establishes for an operation, or null when it says nothing about
 * operations (app metadata, capabilities, and local inference describe the app, not a route).
 *
 * A reviewed adapter contract is held at E3 until it is converted into a pattern rule: it was
 * reviewed against the same static evidence the signed package provides.
 */
fun ProvenanceKind.evidenceTier(): DynamicEvidenceTier? = when (this) {
    ProvenanceKind.advertisedOpenApi -> DynamicEvidenceTier.advertisedOpenApi
    ProvenanceKind.verifiedAppPackage,
    ProvenanceKind.appStoreLinkedSourceTag,
    ProvenanceKind.verifiedAdapter,
    -> DynamicEvidenceTier.signedPackage
    ProvenanceKind.successfulReadObservation -> DynamicEvidenceTier.observedRead
    ProvenanceKind.appMetadata,
    ProvenanceKind.capability,
    ProvenanceKind.deterministicInference,
    -> null
}

/** The schema-side mirror of [ProvenanceKind.evidenceTier]. */
fun EvidenceSource.evidenceTier(): DynamicEvidenceTier? = when (this) {
    EvidenceSource.openApi -> DynamicEvidenceTier.advertisedOpenApi
    EvidenceSource.verifiedAppPackage,
    EvidenceSource.appStoreLinkedSourceTag,
    EvidenceSource.verifiedAdapter,
    -> DynamicEvidenceTier.signedPackage
    EvidenceSource.networkObservation -> DynamicEvidenceTier.observedRead
    EvidenceSource.appMetadata,
    EvidenceSource.capability,
    EvidenceSource.accessibility,
    EvidenceSource.localInference,
    -> null
}

/** The strongest operation tier among [tiers], or null when none applies. */
fun strongestEvidenceTier(tiers: Iterable<DynamicEvidenceTier?>): DynamicEvidenceTier? =
    tiers.filterNotNull().minByOrNull(DynamicEvidenceTier::strength)

/** The strongest tier this action's own provenance establishes. */
val DynamicAction.evidenceTier: DynamicEvidenceTier?
    get() = strongestEvidenceTier(provenance.map { it.kind.evidenceTier() })

/** The strongest tier this schema action's own evidence establishes. */
val ActionSpec.evidenceTier: DynamicEvidenceTier?
    get() = strongestEvidenceTier(evidence.map { it.source.evidenceTier() })
