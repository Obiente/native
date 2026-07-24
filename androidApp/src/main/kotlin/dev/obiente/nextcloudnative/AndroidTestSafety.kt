package dev.obiente.nextcloudnative

import android.content.Context

private const val PREFERENCES_NAME = "nextcloud_native"
private const val KEY_TEST_READ_ONLY = "emulator_test_read_only"

internal fun Context.isReadOnlyTestMode(): Boolean =
    getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_TEST_READ_ONLY, false)

internal fun Context.cloudMutationGate(): () -> Boolean = {
    !isReadOnlyTestMode()
}
