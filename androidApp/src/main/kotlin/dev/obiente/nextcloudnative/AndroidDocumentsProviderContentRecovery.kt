package dev.obiente.nextcloudnative

/** Recovery authentication may read cached bytes only after matching an authoritative generation. */
internal inline fun <Content> readAndroidUnversionedProviderContent(
    recoveryAuthorized: Boolean,
    readCached: () -> Content?,
): Content? = if (recoveryAuthorized) null else readCached()
