package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteRuleState
import kotlinx.serialization.Serializable

/*
 * Device-local write safety state: which routes are in probation under a pattern rule, which
 * were confirmed by a read-back, and which are quarantined because a read-back contradicted the
 * expected postcondition. State is keyed by the local account identity digest, app ID, exact app
 * version, route, rule ID, and rule version. It survives restarts and is cleared only by the
 * person (contract information dialog) or by an app version change.
 */

/** The rule ID used for writes whose encoding and semantics the contract itself declares. */
const val DYNAMIC_CONTRACT_DECLARED_RULE_ID: String = "contract-declared"
const val DYNAMIC_CONTRACT_DECLARED_RULE_VERSION: Int = 1

@Serializable
data class DynamicWriteRuleKey(
    /** The local account identity digest; never a server URL, user name, or password. */
    val accountDigest: String,
    val appId: String,
    /** The exact installed app version the state was observed with. */
    val appVersion: String,
    /** Method and route template, for example `PUT /apps/x/api/items/{id}`. Never bound values. */
    val route: String,
    val ruleId: String,
    val ruleVersion: Int,
) {
    init {
        require(accountDigest.length == 64 && accountDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
            "The account digest must be a canonical SHA-256 digest."
        }
        require(appId.isSafeKeyPart(MAX_APP_ID_LENGTH)) { "The app ID is not a safe key." }
        require(appVersion.isSafeKeyPart(MAX_VERSION_LENGTH)) { "The app version is not a safe key." }
        require(ruleId.isSafeKeyPart(MAX_RULE_ID_LENGTH)) { "The rule ID is not a safe key." }
        require(ruleVersion in 1..MAX_RULE_VERSION) { "The rule version is out of range." }
        require(route.length in 1..MAX_ROUTE_LENGTH && route.none(Char::isISOControl)) { "The route is not a safe key." }
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
}

@Serializable
data class DynamicWriteRuleRecord(
    val key: DynamicWriteRuleKey,
    val status: DynamicWriteRuleStatus,
    val reason: DynamicWriteQuarantineReason? = null,
    val updatedAtEpochMillis: Long,
) {
    val ruleState: DynamicWriteRuleState
        get() = when (status) {
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
    /** The record for exactly [key], or null when the route has no recorded state. */
    suspend fun record(key: DynamicWriteRuleKey): DynamicWriteRuleRecord?

    /** Every record of one app for one account, for the contract information dialog. */
    suspend fun records(accountDigest: String, appId: String): List<DynamicWriteRuleRecord>

    /**
     * Starts probation for [key] unless it already has a state. Records of the same route and rule
     * under another rule version are replaced, so a rule change restarts probation.
     */
    suspend fun beginProbation(key: DynamicWriteRuleKey, nowEpochMillis: Long): DynamicWriteRuleRecord

    /** Marks [key] confirmed by a read-back. A quarantined key stays quarantined. */
    suspend fun confirm(key: DynamicWriteRuleKey, nowEpochMillis: Long): DynamicWriteRuleRecord

    /** Quarantines [key] until the person clears it or the app version changes. */
    suspend fun quarantine(key: DynamicWriteRuleKey, reason: DynamicWriteQuarantineReason, nowEpochMillis: Long): DynamicWriteRuleRecord

    /** The person's clear action for one route. */
    suspend fun clear(key: DynamicWriteRuleKey)

    /** The person's clear action for every route of one app. */
    suspend fun clearApp(accountDigest: String, appId: String)

    /** Drops every record of [appId] observed with a version other than [appVersion]. */
    suspend fun observeAppVersion(accountDigest: String, appId: String, appVersion: String)

    /** Removes every record of a removed account. */
    suspend fun removeAccount(accountDigest: String)
}

/** The rule state [admitDynamicWrite][dev.obiente.nextcloudnative.nativeui.model.admitDynamicWrite] consults. */
suspend fun DynamicWriteQuarantineStore.ruleState(key: DynamicWriteRuleKey, ruleApplies: Boolean): DynamicWriteRuleState {
    val record = record(key)
    return when {
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
    private val records = initial.takeLast(MAX_RECORDS_PER_ACCOUNT).associateBy(DynamicWriteRuleRecord::key).toMutableMap()

    val all: List<DynamicWriteRuleRecord> get() = records.values.sortedWith(compareBy({ it.key.appId }, { it.key.route }, { it.key.ruleId }))

    fun record(key: DynamicWriteRuleKey): DynamicWriteRuleRecord? = records[key]

    fun beginProbation(key: DynamicWriteRuleKey, now: Long): DynamicWriteRuleRecord {
        records[key]?.let { return it }
        records.keys.removeAll { other -> other != key && other.sameRouteAndRule(key) }
        return put(DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.probation, null, now))
    }

    fun confirm(key: DynamicWriteRuleKey, now: Long): DynamicWriteRuleRecord {
        val current = records[key]
        if (current?.status == DynamicWriteRuleStatus.quarantined) return current
        records.keys.removeAll { other -> other != key && other.sameRouteAndRule(key) }
        return put(DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.confirmed, null, now))
    }

    fun quarantine(key: DynamicWriteRuleKey, reason: DynamicWriteQuarantineReason, now: Long): DynamicWriteRuleRecord =
        put(DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.quarantined, reason, now))

    fun clear(key: DynamicWriteRuleKey): Boolean = records.remove(key) != null

    fun clearApp(appId: String): Boolean = records.keys.removeAll { it.appId == appId }

    fun observeAppVersion(appId: String, appVersion: String): Boolean =
        records.keys.removeAll { it.appId == appId && it.appVersion != appVersion }

    private fun put(record: DynamicWriteRuleRecord): DynamicWriteRuleRecord {
        records.remove(record.key)
        records[record.key] = record
        // Keep the bound by evicting the oldest non-quarantined state first; a quarantine is the
        // last thing to forget because forgetting it would silently re-enable a contradicted route.
        while (records.size > MAX_RECORDS_PER_ACCOUNT) {
            val evicted = records.values.filter { it.status != DynamicWriteRuleStatus.quarantined }.minByOrNull { it.updatedAtEpochMillis }
                ?: records.values.minBy { it.updatedAtEpochMillis }
            records.remove(evicted.key)
        }
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

    private fun <T> account(digest: String, block: DynamicWriteRuleRecords.() -> T): T =
        lock.withLock { accounts.getOrPut(digest) { DynamicWriteRuleRecords() }.block() }

    override suspend fun record(key: DynamicWriteRuleKey) = account(key.accountDigest) { record(key) }
    override suspend fun records(accountDigest: String, appId: String) = account(accountDigest) { all.filter { it.key.appId == appId } }
    override suspend fun beginProbation(key: DynamicWriteRuleKey, nowEpochMillis: Long) = account(key.accountDigest) { beginProbation(key, nowEpochMillis) }
    override suspend fun confirm(key: DynamicWriteRuleKey, nowEpochMillis: Long) = account(key.accountDigest) { confirm(key, nowEpochMillis) }
    override suspend fun quarantine(key: DynamicWriteRuleKey, reason: DynamicWriteQuarantineReason, nowEpochMillis: Long) =
        account(key.accountDigest) { quarantine(key, reason, nowEpochMillis) }
    override suspend fun clear(key: DynamicWriteRuleKey) { account(key.accountDigest) { clear(key) } }
    override suspend fun clearApp(accountDigest: String, appId: String) { account(accountDigest) { clearApp(appId) } }
    override suspend fun observeAppVersion(accountDigest: String, appId: String, appVersion: String) {
        account(accountDigest) { observeAppVersion(appId, appVersion) }
    }
    override suspend fun removeAccount(accountDigest: String) { lock.withLock { accounts.remove(accountDigest) } }
}

internal const val MAX_RECORDS_PER_ACCOUNT = 512
private const val MAX_APP_ID_LENGTH = 64
private const val MAX_VERSION_LENGTH = 48
private const val MAX_RULE_ID_LENGTH = 64
private const val MAX_RULE_VERSION = 10_000
private const val MAX_ROUTE_LENGTH = 512

private fun String.isSafeKeyPart(maximum: Int): Boolean =
    length in 1..maximum && all { it.isLetterOrDigit() || it in ".-_+" }
