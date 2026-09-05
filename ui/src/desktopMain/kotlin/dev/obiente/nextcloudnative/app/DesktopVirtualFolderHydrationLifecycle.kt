package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.Job

internal fun Job?.occupiesVirtualFolderHydrationSlot(): Boolean = this != null && !isCompleted

internal fun hasLiveVirtualFolderHydrationJobs(jobs: Iterable<Job>): Boolean =
    jobs.any { job -> job.occupiesVirtualFolderHydrationSlot() }
