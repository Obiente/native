package dev.obiente.nextcloudnative.app

import android.content.Context
import okhttp3.OkHttpClient

/** Android password-confirmed transport with the same certificate trust as account requests. */
fun androidAccountPasswordConfirmedTransport(context: Context): AccountPasswordConfirmedTransport =
    JvmAccountPasswordConfirmedTransport(
        client = OkHttpClient.Builder().useAndroidNextcloudCertificateTrust(context.applicationContext).build(),
        userAgent = "Nextcloud-Native/0.1.0 (Android)",
    )
