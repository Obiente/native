package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val videoRangeOpenDispatcher: CoroutineDispatcher = Dispatchers.IO
