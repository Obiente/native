package dev.obiente.nextcloudnative.nativeui.runtime

internal fun nativeDedicatedCollectionState(
    state: NativeScreenState,
    presentedRecords: List<NativeRecord>,
    visiblePresentedRecords: List<NativeRecord>,
    searchableCollection: Boolean,
): NativeScreenState = when (state) {
    // copy keeps the load generation, so presented records still mark authoritative refreshes.
    is NativeScreenState.Ready -> state.copy(
        records = if (searchableCollection) visiblePresentedRecords else presentedRecords,
    )
    else -> state
}

internal fun genericCollectionSearchAvailable(
    state: NativeScreenState,
    recordCount: Int,
    surface: GenericNativeSurface,
    nativeMailWorkspaceEligible: Boolean,
): Boolean = state is NativeScreenState.Ready &&
    recordCount > 0 &&
    !nativeMailWorkspaceEligible &&
    surface in setOf(
        GenericNativeSurface.List,
        GenericNativeSurface.Grid,
        GenericNativeSurface.Table,
        GenericNativeSurface.Mailbox,
    )
