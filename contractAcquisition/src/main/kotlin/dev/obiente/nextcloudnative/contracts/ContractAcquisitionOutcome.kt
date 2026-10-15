package dev.obiente.nextcloudnative.contracts

/** Why an acquisition completed normally without a verified contract. */
enum class ContractUnavailableReason {
    /** The App Store lists no release of this app for the installed or a patch-compatible version. */
    NoMatchingRelease,

    /**
     * The App Store-linked source host refused or rate limited the last fallback request (HTTP 403
     * or 429). A contract may exist, so the result is not evidence that the release has none.
     */
    SourceTemporarilyUnavailable,

    /** Matching releases were inspected and none provides a usable contract or safe read routes. */
    NoUsableContract,
}

/** The typed result of [SignedAppStoreContractAcquirer.acquireOutcome]. Failures are thrown instead. */
sealed interface ContractAcquisitionOutcome {
    data class Acquired(val contract: VerifiedOpenApiContract) : ContractAcquisitionOutcome

    data class Unavailable(val reason: ContractUnavailableReason) : ContractAcquisitionOutcome
}

/** Evidence gathered by one uncoalesced acquisition that decides its no-contract reason. */
internal class ContractAcquisitionAttempt {
    var sourceTemporarilyUnavailable: Boolean = false
        private set

    fun markSourceTemporarilyUnavailable() {
        sourceTemporarilyUnavailable = true
    }

    fun unavailable(): ContractAcquisitionOutcome.Unavailable = ContractAcquisitionOutcome.Unavailable(
        if (sourceTemporarilyUnavailable) {
            ContractUnavailableReason.SourceTemporarilyUnavailable
        } else {
            ContractUnavailableReason.NoUsableContract
        },
    )
}
