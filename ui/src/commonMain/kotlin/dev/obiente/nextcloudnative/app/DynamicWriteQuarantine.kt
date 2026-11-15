package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteRuleState
import kotlinx.serialization.Serializable

/*
 * Device-local write safety state: which routes are in probation under a pattern rule, which
 * were confirmed by a read-back, and which are quarantined because a read-back contradicted the
 * expected postcondition. State is keyed by the local account identity digest, app ID, exact app
 * version, route, rule ID, and rule version. It survives restarts and is cleared only by the
 * person (contract information dialog) or by an app version change. Neither can discard pending
 * verification; only a matching token resolution or account retirement removes it.
 */

/** The rule ID used for writes whose encoding and semantics the contract itself declares. */
const val DYNAMIC_CONTRACT_DECLARED_RULE_ID: String = "contract-declared"
const val DYNAMIC_CONTRACT_DECLARED_RULE_VERSION: Int = 1

@Serializable
data class DynamicWriteRuleKey(
    /** The local account identity digest; never a server URL, user name, or password. */
    val accountDigest: String,
    /** The app ID, or a digest of it when it holds characters outside the key alphabet ([of]). */
    val appId: String,
    /** The exact installed app version the state was observed with, or its digest ([of]). */
    val appVersion: String,
    /** Method and route template, for example `PUT /apps/x/api/items/{id}`, or its digest. Never bound values. */
    val route: String,
    val ruleId: String,
    val ruleVersion: Int,
) {
    init {
        require(accountDigest.length == 64 && accountDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
            "The account digest must be a canonical SHA-256 digest."
        }
        require(appId.isSafeKeyPart(MAX_APP_ID_LENGTH) || appId.isDigestKeyPart()) { "The app ID is not a safe key." }
        require(appVersion.isSafeKeyPart(MAX_VERSION_LENGTH) || appVersion.isDigestKeyPart()) { "The app version is not a safe key." }
        require(ruleId.isSafeKeyPart(MAX_RULE_ID_LENGTH)) { "The rule ID is not a safe key." }
        require(ruleVersion in 1..MAX_RULE_VERSION) { "The rule version is out of range." }
        require(route.isSafeRoute() || route.isDigestKeyPart()) { "The route is not a safe key." }
    }

    companion object {
        /**
         * The key of a write, formed for every app identity and route. A part holding characters
         * outside the key alphabet, or longer than its bound, is keyed by a digest of its exact
         * string, so such a write still has a key and its quarantine is never dropped.
         */
        fun of(
            accountDigest: String,
            appId: String,
            appVersion: String,
            route: String,
            ruleId: String,
            ruleVersion: Int,
        ): DynamicWriteRuleKey = DynamicWriteRuleKey(
            accountDigest = accountDigest,
            appId = dynamicWriteAppKey(appId),
            appVersion = dynamicWriteVersionKey(appVersion),
            route = if (route.isSafeRoute()) route else route.digestKeyPart(),
            ruleId = ruleId,
            ruleVersion = ruleVersion,
        )
    }

    /** The same route under another rule version is another key, so a bump restarts probation. */
    fun sameRouteAndRule(other: DynamicWriteRuleKey): Boolean =
        accountDigest == other.accountDigest && appId == other.appId && route == other.route && ruleId == other.ruleId

    override fun toString(): String = "DynamicWriteRuleKey(<redacted account>, $appId $appVersion, $route, $ruleId v$ruleVersion)"
}

@Serializable
enum class DynamicWriteRuleStatus { probation, confirmed, quarantined }

/** Why a route was quarantined. Typed so diagnostics never carry a payload or a message. */
@Serializable
enum class DynamicWriteQuarantineReason {
    /** The server accepted the write, but the read-back shows it was not applied. */
    notAppliedAfterSuccess,

    /** A field the write did not change differs from the baseline after the write. */
    untouchedFieldDrift,

    /** A written value reads back as something the rule's encoding did not produce. */
    encodingMismatch,

    /** The person stopped checking an unknown result; only explicit Resume may unpause it. */
    abandonedUnknown,
}

@Serializable
data class DynamicPendingRuleOperation(val operationId: String, val createdAtEpochMillis: Long) {
    init {
        require(operationId.length == 36 && operationId.all { it in "0123456789abcdef-" })
        require(createdAtEpochMillis >= 0)
    }
}

enum class DynamicPendingRuleResolution { confirmed, rejected, contradicted, contradictedPending, abandonedUnknown }

@Serializable
data class DynamicWriteRuleRecord(
    val key: DynamicWriteRuleKey,
    val status: DynamicWriteRuleStatus,
    val reason: DynamicWriteQuarantineReason? = null,
    val updatedAtEpochMillis: Long,
    /** Only an opaque token. Recovery values belong in encrypted draft storage. */
    val pendingVerification: DynamicPendingRuleOperation? = null,
) {
    val ruleState: DynamicWriteRuleState
        get() = if (pendingVerification != null) DynamicWriteRuleState.pendingVerification else when (status) {
            DynamicWriteRuleStatus.probation -> DynamicWriteRuleState.probation
            DynamicWriteRuleStatus.confirmed -> DynamicWriteRuleState.confirmed
            DynamicWriteRuleStatus.quarantined -> DynamicWriteRuleState.quarantined
        }
}

/**
 * Persisted probation and quarantine state. Implementations bound the number of records and keep
 * every account's records separate. Writes are durable before the call returns.
 */
interface DynamicWriteQuarantineStore {
    /** Incarnation captured before saving recovery; retirement invalidates outstanding producers. */
    suspend fun generation(accountDigest: String): Long

    /** Atomic across store instances. Pending routes are protected across app and rule versions. */
    suspend fun reserve(key: DynamicWriteRuleKey, pending: DynamicPendingRuleOperation, generation: Long): Boolean

    /** Only the owner of the matching token can resolve an operation. */
    suspend fun resolve(key: DynamicWriteRuleKey, operationId: String, resolution: DynamicPendingRuleResolution, nowEpochMillis: Long): Boolean

    /** The record for exactly [key], or null when the route has no recorded state. */
    suspend fun record(key: DynamicWriteRuleKey): DynamicWriteRuleRecord?

    /** Every record of one app for one account, for the contract information dialog. */
    suspend fun records(accountDigest: String, appId: String): List<DynamicWriteRuleRecord>

    /** Protects recovery evidence for every app and version during account-local maintenance. */
    suspend fun pendingOperationIds(accountDigest: String): Set<String>

    /**
     * Starts probation for [key] unless it already has a state. Records of the same route and rule
     * under another rule version are replaced unless pending, so a rule change restarts probation
     * without discarding an unresolved operation.
     */
    suspend fun beginProbation(key: DynamicWriteRuleKey, nowEpochMillis: Long, generation: Long? = null): DynamicWriteRuleRecord

    /** Marks [key] confirmed by a read-back. A quarantined key stays quarantined. */
    suspend fun confirm(key: DynamicWriteRuleKey, nowEpochMillis: Long): DynamicWriteRuleRecord

    /** Quarantines [key] until the person clears it or the app version changes. */
    suspend fun quarantine(key: DynamicWriteRuleKey, reason: DynamicWriteQuarantineReason, nowEpochMillis: Long): DynamicWriteRuleRecord

    /** The person's clear action for one route. */
    suspend fun clear(key: DynamicWriteRuleKey)

    /** The person's clear action for every route of one app. */
    suspend fun clearApp(accountDigest: String, appId: String)

    /** Drops resolved records of [appId] observed with another version; pending tokens survive. */
    suspend fun observeAppVersion(accountDigest: String, appId: String, appVersion: String)

    /** Removes every record of a removed account. */
    suspend fun removeAccount(accountDigest: String)
}

/** The rule state [admitDynamicWrite][dev.obiente.nextcloudnative.nativeui.model.admitDynamicWrite] consults. */
suspend fun DynamicWriteQuarantineStore.ruleState(key: DynamicWriteRuleKey, ruleApplies: Boolean): DynamicWriteRuleState {
    val record = record(key)
    return when {
        record?.pendingVerification != null -> DynamicWriteRuleState.pendingVerification
        record?.status == DynamicWriteRuleStatus.quarantined -> DynamicWriteRuleState.quarantined
        !ruleApplies -> DynamicWriteRuleState.notRequired
        record == null -> DynamicWriteRuleState.probation
        else -> record.ruleState
    }
}

/**
 * The shared record set behind every store: bounded, account-separated, and pure, so the memory
 * store and the file store apply exactly the same transitions.
 */
internal class DynamicWriteRuleRecords(initial: List<DynamicWriteRuleRecord> = emptyList()) {
    private val records = initial.associateBy(DynamicWriteRuleRecord::key).toMutableMap()

    init { require(initial.size <= MAX_RECORDS_PER_ACCOUNT && records.size == initial.size) }

    val all: List<DynamicWriteRuleRecord> get() = records.values.sortedWith(compareBy({ it.key.appId }, { it.key.route }, { it.key.ruleId }))

    fun record(key: DynamicWriteRuleKey): DynamicWriteRuleRecord? = records[key]

    fun beginProbation(key: DynamicWriteRuleKey, now: Long): DynamicWriteRuleRecord {
        records[key]?.let { return it }
        removeObsolete(key)
        return put(DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.probation, null, now))
    }

    fun confirm(key: DynamicWriteRuleKey, now: Long): DynamicWriteRuleRecord {
        val current = records[key]
        if (current?.status == DynamicWriteRuleStatus.quarantined || current?.pendingVerification != null) return current
        removeObsolete(key)
        return put(DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.confirmed, null, now))
    }

    fun quarantine(key: DynamicWriteRuleKey, reason: DynamicWriteQuarantineReason, now: Long): DynamicWriteRuleRecord =
        put(DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.quarantined, reason, now, records[key]?.pendingVerification))

    fun clear(key: DynamicWriteRuleKey): Boolean = if (records[key]?.pendingVerification != null) false else records.remove(key) != null

    fun forApp(appId: String): List<DynamicWriteRuleRecord> = dynamicWriteAppKey(appId).let { app -> all.filter { it.key.appId == app } }

    fun clearApp(appId: String): Boolean = dynamicWriteAppKey(appId).let { app ->
        records.keys.removeAll { it.appId == app && records[it]?.pendingVerification == null }
    }

    fun observeAppVersion(appId: String, appVersion: String): Boolean {
        val app = dynamicWriteAppKey(appId)
        val version = dynamicWriteVersionKey(appVersion)
        return records.keys.removeAll { it.appId == app && it.appVersion != version && records[it]?.pendingVerification == null &&
            records[it]?.reason != DynamicWriteQuarantineReason.abandonedUnknown }
    }

    fun reserve(key: DynamicWriteRuleKey, pending: DynamicPendingRuleOperation): Boolean {
        if (records.values.any { it.key.sameCanonicalRoute(key) && (it.pendingVerification != null || it.reason == DynamicWriteQuarantineReason.abandonedUnknown) }) return false
        if (records[key]?.status == DynamicWriteRuleStatus.quarantined) return false
        if (key !in records && records.size >= MAX_RECORDS_PER_ACCOUNT && records.values.none { !it.protected }) return false
        val current = records[key] ?: DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.probation, updatedAtEpochMillis = pending.createdAtEpochMillis)
        put(current.copy(pendingVerification = pending, updatedAtEpochMillis = pending.createdAtEpochMillis))
        return true
    }

    fun resolve(key: DynamicWriteRuleKey, token: String, resolution: DynamicPendingRuleResolution, now: Long): Boolean {
        val current = records[key]?.takeIf { it.pendingVerification?.operationId == token } ?: return false
        val status = when (resolution) {
            DynamicPendingRuleResolution.confirmed -> if (current.status == DynamicWriteRuleStatus.quarantined) current.status else DynamicWriteRuleStatus.confirmed
            DynamicPendingRuleResolution.rejected -> current.status
            DynamicPendingRuleResolution.contradicted, DynamicPendingRuleResolution.contradictedPending,
            DynamicPendingRuleResolution.abandonedUnknown -> DynamicWriteRuleStatus.quarantined
        }
        put(current.copy(status = status,
            pendingVerification = current.pendingVerification.takeIf { resolution == DynamicPendingRuleResolution.contradictedPending }, updatedAtEpochMillis = now,
            reason = when (resolution) {
                DynamicPendingRuleResolution.abandonedUnknown -> DynamicWriteQuarantineReason.abandonedUnknown
                DynamicPendingRuleResolution.contradicted, DynamicPendingRuleResolution.contradictedPending -> DynamicWriteQuarantineReason.encodingMismatch
                else -> current.reason
            }))
        return true
    }

    private fun removeObsolete(key: DynamicWriteRuleKey) {
        records.keys.removeAll { it != key && it.sameRouteAndRule(key) && records[it]?.pendingVerification == null &&
            records[it]?.reason != DynamicWriteQuarantineReason.abandonedUnknown }
    }

    private fun put(record: DynamicWriteRuleRecord): DynamicWriteRuleRecord {
        if (record.key !in records && records.size >= MAX_RECORDS_PER_ACCOUNT) {
            val evicted = records.values.filter { !it.protected }.minByOrNull { it.updatedAtEpochMillis }
            check(evicted != null) { "Write safety storage has no unprotected capacity." }
            records.remove(evicted.key)
        }
        records.remove(record.key)
        records[record.key] = record
        return record
    }
}

/** Platform access to the durable store. Without one, writes that need probation are not offered. */
interface DynamicWriteSafetyPlatformServices {
    fun dynamicWriteQuarantineStore(): DynamicWriteQuarantineStore? = null
}

/** A process-memory store for tests and for platforms without durable storage. */
class MemoryDynamicWriteQuarantineStore : DynamicWriteQuarantineStore {
    private val lock = DynamicNativeMemoryCacheLock()
    private val accounts = mutableMapOf<String, DynamicWriteRuleRecords>()
    private val generations = mutableMapOf<String, Long>()

    override suspend fun generation(accountDigest: String): Long = lock.withLock { generations[accountDigest] ?: 0L }
    override suspend fun reserve(key: DynamicWriteRuleKey, pending: DynamicPendingRuleOperation, generation: Long): Boolean = lock.withLock {
        if ((generations[key.accountDigest] ?: 0L) != generation) false else account(key.accountDigest) { reserve(key, pending) }
    }
    override suspend fun resolve(key: DynamicWriteRuleKey, operationId: String, resolution: DynamicPendingRuleResolution, nowEpochMillis: Long) =
        account(key.accountDigest) { resolve(key, operationId, resolution, nowEpochMillis) }

    private fun <T> account(digest: String, block: DynamicWriteRuleRecords.() -> T): T =
        lock.withLock { accounts.getOrPut(digest) { DynamicWriteRuleRecords() }.block() }

    override suspend fun record(key: DynamicWriteRuleKey) = account(key.accountDigest) { record(key) }
    override suspend fun records(accountDigest: String, appId: String) = account(accountDigest) { forApp(appId) }
    override suspend fun pendingOperationIds(accountDigest: String) = account(accountDigest) {
        all.mapNotNullTo(mutableSetOf()) { it.pendingVerification?.operationId }
    }
    override suspend fun beginProbation(key: DynamicWriteRuleKey, nowEpochMillis: Long, generation: Long?) = lock.withLock {
        check(generation == null || (generations[key.accountDigest] ?: 0L) == generation) { "The account changed before probation could begin." }
        account(key.accountDigest) { beginProbation(key, nowEpochMillis) }
    }
    override suspend fun confirm(key: DynamicWriteRuleKey, nowEpochMillis: Long) = account(key.accountDigest) { confirm(key, nowEpochMillis) }
    override suspend fun quarantine(key: DynamicWriteRuleKey, reason: DynamicWriteQuarantineReason, nowEpochMillis: Long) =
        account(key.accountDigest) { quarantine(key, reason, nowEpochMillis) }
    override suspend fun clear(key: DynamicWriteRuleKey) { account(key.accountDigest) { clear(key) } }
    override suspend fun clearApp(accountDigest: String, appId: String) { account(accountDigest) { clearApp(appId) } }
    override suspend fun observeAppVersion(accountDigest: String, appId: String, appVersion: String) {
        account(accountDigest) { observeAppVersion(appId, appVersion) }
    }
    override suspend fun removeAccount(accountDigest: String) { lock.withLock {
        generations[accountDigest] = (generations[accountDigest] ?: 0L) + 1L
        accounts.remove(accountDigest)
    } }
}

internal fun DynamicWriteRuleKey.sameCanonicalRoute(other: DynamicWriteRuleKey): Boolean =
    accountDigest == other.accountDigest && appId == other.appId && route == other.route

private val DynamicWriteRuleRecord.protected: Boolean
    get() = pendingVerification != null || status == DynamicWriteRuleStatus.quarantined

/** The stored form of an app ID: itself when it fits the key alphabet, otherwise its digest. */
fun dynamicWriteAppKey(appId: String): String = appId.stableKeyPart(MAX_APP_ID_LENGTH)

/** The stored form of an exact app version: itself when it fits the key alphabet, otherwise its digest. */
fun dynamicWriteVersionKey(appVersion: String): String = appVersion.stableKeyPart(MAX_VERSION_LENGTH)

internal const val MAX_RECORDS_PER_ACCOUNT = 512
private const val MAX_APP_ID_LENGTH = 64
private const val MAX_VERSION_LENGTH = 48
private const val MAX_RULE_ID_LENGTH = 64
private const val MAX_RULE_VERSION = 10_000
private const val MAX_ROUTE_LENGTH = 512

private fun String.isSafeKeyPart(maximum: Int): Boolean =
    length in 1..maximum && all { it.isLetterOrDigit() || it in ".-_+" }

private fun String.isSafeRoute(): Boolean = length in 1..MAX_ROUTE_LENGTH && none(Char::isISOControl)

/** Already a stored part when it is safe or a digest, so normalizing twice never digests a digest. */
private fun String.stableKeyPart(maximum: Int): String = if (isSafeKeyPart(maximum) || isDigestKeyPart()) this else digestKeyPart()

/** `#` is outside the key alphabet, so a digest part can never equal a plain part. */
private fun String.digestKeyPart(): String = DIGEST_KEY_PREFIX + publicContentSha256(encodeToByteArray())

private fun String.isDigestKeyPart(): Boolean =
    length == DIGEST_KEY_PREFIX.length + 64 && startsWith(DIGEST_KEY_PREFIX) &&
        drop(DIGEST_KEY_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }

private const val DIGEST_KEY_PREFIX = "#"
