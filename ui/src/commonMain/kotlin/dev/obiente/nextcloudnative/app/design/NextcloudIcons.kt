package dev.obiente.nextcloudnative.app.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.ui.graphics.vector.ImageVector

/** Stable destinations used by the shared bottom navigation. */
enum class NextcloudDestination {
    Home,
    Apps,
    Activity,
    Settings,
}

object NextcloudIcons {
    val Home: ImageVector = Icons.Outlined.Home
    val Apps: ImageVector = Icons.Outlined.GridView
    val Search: ImageVector = Icons.Outlined.Search
    val Profile: ImageVector = Icons.Outlined.Person
    val Activity: ImageVector = Icons.Outlined.NotificationsNone
    val Settings: ImageVector = Icons.Outlined.Settings
    val Back: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack
    val More: ImageVector = Icons.Outlined.MoreHoriz
    val Drag: ImageVector = Icons.Outlined.DragIndicator
    val Add: ImageVector = Icons.Outlined.Add
    val ChevronRight: ImageVector = Icons.Outlined.ChevronRight
    val Refresh: ImageVector = Icons.Outlined.Refresh
    val Error: ImageVector = Icons.Outlined.ErrorOutline
    val Cloud: ImageVector = Icons.Outlined.Cloud
    val LightMode: ImageVector = Icons.Outlined.LightMode
    val DarkMode: ImageVector = Icons.Outlined.DarkMode
    val SystemMode: ImageVector = Icons.Outlined.Apps
    val Logout: ImageVector = Icons.AutoMirrored.Outlined.Logout
    val Send: ImageVector = Icons.AutoMirrored.Outlined.Send
    val Folder: ImageVector = Icons.Outlined.Folder
    val FolderOpen: ImageVector = Icons.Outlined.FolderOpen
    val File: ImageVector = Icons.AutoMirrored.Outlined.InsertDriveFile
    val Image: ImageVector = Icons.Outlined.Image
    val Video: ImageVector = Icons.Outlined.VideoLibrary
    val ListView: ImageVector = Icons.AutoMirrored.Outlined.ViewList
    val Edit: ImageVector = Icons.Outlined.Edit
    val Save: ImageVector = Icons.Outlined.Save
    val Info: ImageVector = Icons.Outlined.Info
    val CheckCircle: ImageVector = Icons.Outlined.CheckCircle
    val People: ImageVector = Icons.Outlined.PeopleOutline
    val Schedule: ImageVector = Icons.Outlined.CalendarMonth
    val Calendar: ImageVector = Icons.Outlined.CalendarMonth
    val Photo: ImageVector = Icons.Outlined.PhotoLibrary
    val Favorite: ImageVector = Icons.Outlined.Star
    val FavoriteBorder: ImageVector = Icons.Outlined.StarOutline
    val FormatBold: ImageVector = Icons.Outlined.FormatBold
    val FormatItalic: ImageVector = Icons.Outlined.FormatItalic
    val FormatHeading: ImageVector = Icons.Outlined.Title
    val FormatChecklist: ImageVector = Icons.Outlined.Checklist
    val FormatLink: ImageVector = Icons.Outlined.Link
    val FormatQuote: ImageVector = Icons.Outlined.FormatQuote
    val FormatCode: ImageVector = Icons.Outlined.Code
    val Tag: ImageVector = Icons.AutoMirrored.Outlined.Label
    val Play: ImageVector = Icons.Outlined.PlayArrow
    val Pause: ImageVector = Icons.Outlined.Pause
    val SkipNext: ImageVector = Icons.Outlined.SkipNext
    val SkipPrevious: ImageVector = Icons.Outlined.SkipPrevious

    fun destination(destination: NextcloudDestination): ImageVector = when (destination) {
        NextcloudDestination.Home -> Home
        NextcloudDestination.Apps -> Apps
        NextcloudDestination.Activity -> Activity
        NextcloudDestination.Settings -> Settings
    }

    /**
     * Maps known Nextcloud app IDs to real Material icons. Unknown apps deliberately receive
     * the same neutral app icon instead of a generated letter, emoji, or invented mark.
     */
    fun app(appId: String): ImageVector = when (appId.lowercase()) {
        "dashboard" -> Icons.Outlined.Dashboard
        "user_status" -> Icons.Outlined.Person
        "spreed", "talk" -> Icons.AutoMirrored.Outlined.Chat
        "files" -> Icons.Outlined.Folder
        "photos", "memories" -> Icons.Outlined.PhotoLibrary
        "activity" -> Icons.Outlined.Bolt
        "mail" -> Icons.Outlined.Email
        "contacts" -> Icons.Outlined.Contacts
        "calendar" -> Icons.Outlined.CalendarMonth
        "cospend" -> Icons.Outlined.AccountBalanceWallet
        "github" -> Icons.Outlined.Code
        "notes" -> Icons.Outlined.NoteAlt
        "music", "audioplayer" -> Icons.Outlined.MusicNote
        "deck" -> Icons.Outlined.ViewKanban
        "budget" -> Icons.Outlined.Savings
        "tasks" -> Icons.Outlined.TaskAlt
        "tables" -> Icons.Outlined.TableChart
        "cookbook" -> Icons.Outlined.RestaurantMenu
        "office", "richdocuments" -> Icons.Outlined.Description
        "collectives" -> Icons.AutoMirrored.Outlined.Article
        "chores" -> Icons.Outlined.CleaningServices
        "money" -> Icons.Outlined.AttachMoney
        "forms" -> Icons.Outlined.Checklist
        else -> Icons.Outlined.Apps
    }
}
