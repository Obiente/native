package dev.obiente.nextcloudnative.app

internal const val SUPPORT_RUNTIME_DIAGNOSTICS_DISCLOSURE =
    "Reports include bounded event-history metadata and a one-time runtime snapshot: heap and non-heap memory, " +
        "direct and mapped buffers, process uptime, thread counts, and garbage collection. Unavailable " +
        "measurements are identified as unavailable."

internal const val SUPPORT_SEND_DIAGNOSTICS_DISCLOSURE =
    "Obiente Support receives the text you reviewed, sanitized event history, a bounded runtime snapshot, and " +
        "release details. Runtime measurements cover memory, buffers, uptime, threads, and garbage collection " +
        "when available. A stable pseudonymous account scope links reports from the same account on this " +
        "installation. Authorized maintainers can read it. Retention is 30 days unless you delete it first."
