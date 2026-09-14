package dev.obiente.nextcloudnative

/** Activity results have no request ID, so cancellation cannot free an outstanding chooser slot. */
internal class AndroidFileSyncPickerRequestSlot<Request : Any> {
    private var pending: Request? = null

    @Synchronized fun begin(request: Request) {
        check(pending == null) { "A folder chooser is already open." }
        pending = request
    }

    @Synchronized fun complete(): Request? = pending.also { pending = null }

    @Synchronized fun launchFailed(request: Request) {
        if (pending === request) pending = null
    }
}
