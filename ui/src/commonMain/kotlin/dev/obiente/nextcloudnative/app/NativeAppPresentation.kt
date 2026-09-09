package dev.obiente.nextcloudnative.app

internal fun nativeSubtitle(appId: String): String = when (appId) {
    "files" -> "Browse your server files"
    "photos", "memories" -> "Photos, videos and RAW previews"
    "spreed", "talk" -> "Continue your conversations"
    "activity" -> "See recent changes across your cloud"
    "notes" -> "Write and organize Markdown notes"
    "dashboard" -> "See your cloud at a glance"
    "user_status" -> "Manage your presence and status message"
    else -> "Open native experience"
}
