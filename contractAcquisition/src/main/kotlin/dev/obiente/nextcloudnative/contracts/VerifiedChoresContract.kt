package dev.obiente.nextcloudnative.contracts

// The 0.1.0 and 0.2.0 packages have byte-identical API controllers and route/response contracts.
// The 0.2.0 server mapper only changes database-specific due-date serialization.
internal fun isVerifiedChoresControllerDigest(appVersion: String, controllerSha256: String): Boolean =
    appVersion in setOf("0.1.0", "0.2.0") && controllerSha256 == CHORES_API_CONTROLLER_SHA256

private const val CHORES_API_CONTROLLER_SHA256 =
    "146286dcb68bddd025e0a47e7edc134fbc94f0e9f594e9030663bb0f217f3cc6"
