package dev.obiente.nextcloudnative.app

enum class PhotoMediaQueryOwner(
    val scopeSuffix: String,
) {
    Timeline("timeline"),
    FolderInventory("folder-inventory"),
}

internal fun photoMediaQueryOwners(destination: PhotoDestination): Set<PhotoMediaQueryOwner> =
    when (destination) {
        PhotoDestination.Timeline -> setOf(PhotoMediaQueryOwner.Timeline)
        PhotoDestination.Folders -> setOf(PhotoMediaQueryOwner.FolderInventory)
        PhotoDestination.Albums,
        PhotoDestination.People,
        PhotoDestination.Favorites,
        -> emptySet()
    }

fun photoMediaCarryoverScope(
    accountScope: String,
    owner: PhotoMediaQueryOwner,
): String {
    val suffix = "|photos:${owner.scopeSuffix}"
    require(
        accountScope.isNotBlank() &&
            accountScope.none(Char::isISOControl) &&
            accountScope.length + suffix.length <= MAX_PHOTO_MEDIA_CARRYOVER_SCOPE_LENGTH,
    ) {
        "The Photos media query scope is invalid."
    }
    return accountScope + suffix
}

private const val MAX_PHOTO_MEDIA_CARRYOVER_SCOPE_LENGTH = 256
