package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.nextcloudDisclosureChevron
import dev.obiente.nextcloudnative.app.design.nextcloudSharedElement

/**
 * Keys that link the same visible text across screens, so it carries across navigation. Both
 * ends of a link must use the same function. Keys live in memory only and are never saved.
 */
internal object SharedElementKeys {
    fun talkRoomTitle(token: String): String = "talk-room:$token:title"
    fun fileName(path: String): String = "file:$path:name"
    fun noteTitle(id: Long): String = "note:$id:title"
    fun deckCardTitle(id: Long): String = "deck-card:$id:title"
    fun settingsSectionTitle(section: String): String = "settings-section:$section:title"
    fun calendarEventTitle(instanceId: String): String = "calendar-event:$instanceId:title"
    fun newsArticleTitle(id: Long): String = "news-article:$id:title"
}

/** Shorthand for [nextcloudDisclosureChevron] inside the app package. */
@Composable
internal fun Modifier.disclosureChevron(expanded: Boolean): Modifier = nextcloudDisclosureChevron(expanded)

/** Shorthand for [nextcloudSharedElement] inside the app package. */
@Composable
internal fun Modifier.sharedElement(key: String?): Modifier = nextcloudSharedElement(key)

/** Shorthand for [dev.obiente.nextcloudnative.app.design.nextcloudAnimatedColor] inside the app package. */
@Composable
internal fun animatedColor(target: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    dev.obiente.nextcloudnative.app.design.nextcloudAnimatedColor(target)
