package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.math.abs

internal fun nativeBudgetRemainingLabel(remaining: Double, currency: String?): String =
    if (remaining < 0.0) "${formatNativeFinanceAmount(abs(remaining), currency)} over budget"
    else "${formatNativeFinanceAmount(remaining, currency)} left"
