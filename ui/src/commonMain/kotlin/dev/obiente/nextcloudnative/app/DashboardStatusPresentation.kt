package dev.obiente.nextcloudnative.app

internal fun availableUserPresences(
    capabilities: NativeUserStatusCapabilities,
): List<NativeUserPresence> {
    if (!capabilities.enabled) return emptyList()
    return NativeUserPresence.entries.filter { presence ->
        presence != NativeUserPresence.Busy || capabilities.supportsBusy
    }
}

internal fun NativeUserPresence.displayLabel(): String = when (this) {
    NativeUserPresence.Online -> "Online"
    NativeUserPresence.Away -> "Away"
    NativeUserPresence.DoNotDisturb -> "Do not disturb"
    NativeUserPresence.Invisible -> "Invisible"
    NativeUserPresence.Offline -> "Offline"
    NativeUserPresence.Busy -> "Busy"
}

internal fun NativeUserStatusEdit.confirmationLabel(): String = when (this) {
    is NativeUserStatusEdit.Presence -> "Set presence to ${presence.displayLabel().lowercase()}"
    is NativeUserStatusEdit.CustomMessage -> "Set a custom status message"
    is NativeUserStatusEdit.PredefinedMessage -> "Use the selected status message"
    NativeUserStatusEdit.ClearMessage -> "Clear the current status message"
    is NativeUserStatusEdit.Restore -> "Restore the previous status message"
}
