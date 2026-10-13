package dev.obiente.nextcloudnative.app

/** Test-only fence for scenes that do not exercise deletes; production callers always pass a real fence. */
internal val NoFileWriteBlocksForTests = FileWriteFence { null }
