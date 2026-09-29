package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun MarketingOfflineFileTransferScenario() {
    val snapshot = FileOfflineCenterSnapshot(
        support = FileOfflineCenterSupport.Available,
        items = listOf(
            FileOfflineCenterItem(
                key = FileOfflineKey("00000000000000000000000000000000", "Travel/Boarding-pass.pdf"),
                displayName = "Boarding-pass.pdf",
                sizeBytes = 1_842_176,
                availability = FileOfflineAvailability.Available,
                detail = "Complete file verified for offline use.",
                canRetry = false,
                canRemove = true,
            ),
            FileOfflineCenterItem(
                key = FileOfflineKey("00000000000000000000000000000000", "Travel/Route-map.gpx"),
                displayName = "Route-map.gpx",
                sizeBytes = 284_672,
                availability = FileOfflineAvailability.WaitingForNetwork,
                detail = "Waiting for a permitted network.",
                canRetry = true,
                canRemove = true,
            ),
            FileOfflineCenterItem(
                key = FileOfflineKey("00000000000000000000000000000000", "Travel/Hotel-confirmation.pdf"),
                displayName = "Hotel-confirmation.pdf",
                sizeBytes = 943_104,
                availability = FileOfflineAvailability.Failed,
                detail = "The file changed in Nextcloud during download. Download the updated version.",
                canRetry = true,
                canRemove = true,
            ),
        ),
        storageUsage = FileOfflineStorageUsage(
            usedBytes = 2_785_280,
            capacityBytes = 64L * 1024L * 1024L * 1024L,
            estimated = false,
        ),
        limitations = emptyList(),
        folderAvailability = FileOfflineFolderAvailability.RecursiveDownloadOnly,
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        ScreenHeader(title = "Offline files", subtitle = "Downloads and local copies", onBack = {})
        OfflineCenterSummaryCard(snapshot, loading = false)
        snapshot.items.forEach { item ->
            OfflineCenterItemCard(item = item, busy = false, onRetry = {}, onRemove = {})
        }
    }
}
