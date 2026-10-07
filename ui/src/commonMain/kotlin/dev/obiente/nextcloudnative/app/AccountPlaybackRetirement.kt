package dev.obiente.nextcloudnative.app

/** One playback owner registers only its current queue against the account incarnation. */
internal class AccountPlaybackRetirementOwner(
    private val registry: AccountPlaybackRetirementRegistry = sharedAccountPlaybackRetirementRegistry,
) {
    fun bind(producer: AccountPrivateMemoryProducer, onRetired: () -> Unit, publish: () -> Unit): Boolean =
        registry.bind(this, producer, onRetired, publish)

    fun clear() = registry.clear(this)
}

internal class AccountPlaybackRetirementRegistry(
    private val gate: AccountPrivateMemoryGate = sharedAccountPrivateMemoryGate,
) {
    private data class Entry(val producer: AccountPrivateMemoryProducer, val onRetired: () -> Unit)
    private val entries = mutableMapOf<AccountPlaybackRetirementOwner, Entry>()

    fun bind(owner: AccountPlaybackRetirementOwner, producer: AccountPrivateMemoryProducer,
        onRetired: () -> Unit, publish: () -> Unit): Boolean = gate.read(producer, false) {
        entries[owner] = Entry(producer, onRetired)
        publish()
        true
    }

    fun clear(owner: AccountPlaybackRetirementOwner) = gate.withLock { entries.remove(owner); Unit }

    fun purgeRetiredAccount(accountStorageKey: String) = gate.withLock {
        val retired = entries.filterValues { it.producer.accountStorageKey == accountStorageKey }
        retired.keys.forEach(entries::remove)
        // Platform callbacks invalidate only their captured queue and dispatch thread-bound disposal.
        retired.values.forEach { it.onRetired() }
    }
}

internal val sharedAccountPlaybackRetirementRegistry = AccountPlaybackRetirementRegistry()