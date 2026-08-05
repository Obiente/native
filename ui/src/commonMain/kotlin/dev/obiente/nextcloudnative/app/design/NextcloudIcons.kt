package dev.obiente.nextcloudnative.app.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.BakeryDining
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.DinnerDining
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FaceRetouchingNatural
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Icecream
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KebabDining
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalPizza
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LunchDining
import androidx.compose.material.icons.outlined.Liquor
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.OutdoorGrill
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.PedalBike
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SetMeal
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tapas
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Toys
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material.icons.outlined.Warehouse
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.ui.graphics.vector.ImageVector

/** Stable destinations used by the shared bottom navigation. */
enum class NextcloudDestination {
    Home,
    FolderSync,
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
    val Menu: ImageVector = Icons.Outlined.Menu
    val Drag: ImageVector = Icons.Outlined.DragIndicator
    val Add: ImageVector = Icons.Outlined.Add
    val ChevronRight: ImageVector = Icons.Outlined.ChevronRight
    val ExpandMore: ImageVector = Icons.Outlined.ExpandMore
    val Filter: ImageVector = Icons.Outlined.FilterList
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
    val Board: ImageVector = Icons.Outlined.ViewKanban
    val Mail: ImageVector = Icons.Outlined.Email
    val Task: ImageVector = Icons.Outlined.TaskAlt
    val Table: ImageVector = Icons.Outlined.TableChart
    val Recipe: ImageVector = Icons.Outlined.RestaurantMenu
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
        NextcloudDestination.FolderSync -> Folder
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

    /**
     * Resolves bounded record-provided icon keys to bundled native vectors. Unknown values return
     * null; they are never interpreted as URLs, file paths, CSS classes, or remote assets.
     */
    fun semantic(iconKey: String): ImageVector? = when (iconKey.normalizedSemanticIconKey()) {
        "clipboard-check", "format-list-checks", "checklist", "task", "todo" -> FormatChecklist
        "clipboard-list", "list" -> ListView
        "cart", "shopping" -> Icons.Outlined.ShoppingCart
        "supermarket", "market" -> Icons.Outlined.Storefront
        "basket" -> Icons.Outlined.ShoppingBasket
        "produce" -> Icons.Outlined.Agriculture
        "food" -> Icons.Outlined.Fastfood
        "bakery" -> Icons.Outlined.BakeryDining
        "dairy" -> Icons.Outlined.Icecream
        "meat" -> Icons.Outlined.OutdoorGrill
        "fish" -> Icons.Outlined.SetMeal
        "snacks" -> Icons.Outlined.Tapas
        "cookie" -> Icons.Outlined.Cookie
        "drinks" -> Icons.Outlined.LocalDrink
        "frozen" -> Icons.Outlined.AcUnit
        "silverware" -> Icons.Outlined.Restaurant
        "deli" -> Icons.Outlined.LunchDining
        "butcher" -> Icons.Outlined.KebabDining
        "seafood" -> Icons.Outlined.DinnerDining
        "coffee" -> Icons.Outlined.LocalCafe
        "pizza" -> Icons.Outlined.LocalPizza
        "home", "household" -> Home
        "homegoods" -> Icons.Outlined.Inventory2
        "furniture" -> Icons.Outlined.Chair
        "pets", "paw" -> Icons.Outlined.Pets
        "baby" -> Icons.Outlined.ChildCare
        "leaf", "fruit" -> Icons.Outlined.Eco
        "vegetable" -> Icons.Outlined.Agriculture
        "flower", "florist" -> Icons.Outlined.LocalFlorist
        "tree", "garden" -> Icons.Outlined.Park
        "star" -> Favorite
        "heart" -> Icons.Outlined.FavoriteBorder
        "calendar" -> Calendar
        "bell" -> Activity
        "flag" -> Icons.Outlined.Flag
        "bookmark" -> Icons.Outlined.BookmarkBorder
        "pin", "map-marker", "location" -> Icons.Outlined.LocationOn
        "briefcase", "office" -> Icons.Outlined.BusinessCenter
        "wrench", "tools", "hardware" -> Icons.Outlined.Build
        "gift" -> Icons.Outlined.CardGiftcard
        "book", "books" -> Icons.AutoMirrored.Outlined.MenuBook
        "school" -> Icons.Outlined.School
        "palette" -> Icons.Outlined.Palette
        "camera" -> Icons.Outlined.PhotoCamera
        "music", "audio" -> Icons.Outlined.MusicNote
        "gamepad" -> Icons.Outlined.SportsEsports
        "online" -> Icons.Outlined.Web
        "electronics" -> Icons.Outlined.Laptop
        "phone" -> Icons.Outlined.Smartphone
        "toys" -> Icons.Outlined.Toys
        "run" -> Icons.AutoMirrored.Outlined.DirectionsRun
        "dumbbell" -> Icons.Outlined.FitnessCenter
        "sports" -> Icons.Outlined.SportsSoccer
        "pill" -> Icons.Outlined.Medication
        "pharmacy" -> Icons.Outlined.LocalPharmacy
        "health" -> Icons.Outlined.HealthAndSafety
        "broom" -> Icons.Outlined.CleaningServices
        "lightbulb" -> Icons.Outlined.Lightbulb
        "package" -> Icons.Outlined.Inventory2
        "car", "gas" -> Icons.Outlined.DirectionsCar
        "bike" -> Icons.Outlined.PedalBike
        "beach" -> Icons.Outlined.BeachAccess
        "store", "storefront", "convenience" -> Icons.Outlined.Storefront
        "warehouse" -> Icons.Outlined.Warehouse
        "clothing", "shoes" -> Icons.Outlined.Checkroom
        "jewelry" -> Icons.Outlined.Diamond
        "liquor" -> Icons.Outlined.Liquor
        "beauty" -> Icons.Outlined.FaceRetouchingNatural
        "tag", "category", "label" -> Tag
        "note" -> Icons.Outlined.NoteAlt
        else -> null
    }

    /**
     * Resolves a contract icon token without dropping the option. Unknown tokens share one neutral
     * bundled glyph so arbitrary strings never become remote assets or invented app-specific art.
     */
    fun semanticOrFallback(iconKey: String): ImageVector = semantic(iconKey) ?: Apps

    private fun String.normalizedSemanticIconKey(): String =
        trim()
            .lowercase()
            .map { character ->
                if (character.isLetterOrDigit()) character else '-'
            }
            .joinToString("")
            .split('-')
            .filter(String::isNotBlank)
            .joinToString("-")
}
