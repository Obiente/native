package dev.obiente.nextcloudnative.app

import okhttp3.OkHttpClient

/** Desktop password-confirmed transport with the same certificate trust as account requests. */
fun desktopAccountPasswordConfirmedTransport(): AccountPasswordConfirmedTransport =
    JvmAccountPasswordConfirmedTransport(
        client = OkHttpClient.Builder().useDesktopSystemTrust().build(),
        userAgent = "Nextcloud-Native/0.1.0 (Desktop)",
    )
