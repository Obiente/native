package dev.obiente.nextcloudnative.nativeui.preview

import dev.obiente.nextcloudnative.app.ExternalFileHandoffSupport
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileListing
import dev.obiente.nextcloudnative.app.NextcloudFileListingSource
import dev.obiente.nextcloudnative.app.NextcloudPlatformServices
import dev.obiente.nextcloudnative.app.marketingHomepageCachedFileListing
import dev.obiente.nextcloudnative.app.marketingHomepageFileListing
import dev.obiente.nextcloudnative.app.marketingHomepageFileOfflineAvailability
import dev.obiente.nextcloudnative.app.marketingHomepageTalkPage
import dev.obiente.nextcloudnative.app.marketingHomepageTalkRoom
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal fun networkInertMarketingServices(
    fallbackPreviewBytes: ByteArray,
    previewBytesByFileId: Map<Long, ByteArray>,
): NextcloudPlatformServices {
    require(fallbackPreviewBytes.isNotEmpty()) { "The marketing preview fixture is empty." }
    require(previewBytesByFileId.values.none(ByteArray::isEmpty)) {
        "A homepage file preview fixture is empty."
    }
    val handler = InvocationHandler { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "NetworkInertMarketingServices"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.singleOrNull()
            "loadPreview" -> {
                val fileId = arguments?.getOrNull(1) as? Long
                previewBytesByFileId[fileId]?.copyOf() ?: fallbackPreviewBytes.copyOf()
            }
            "listFilesCachedWithSource" -> marketingHomepageCachedFileListing
            "listFilesWithSource" -> when (val path = arguments?.getOrNull(2) as? String ?: "") {
                "Photos/Studio", "Photos/Studio/RAW" -> marketingFileSyncListing(path)
                else -> marketingHomepageFileListing
            }
            "loadFileOfflineAvailability" -> marketingHomepageFileOfflineAvailability
            "listTalkRooms" -> listOf(marketingHomepageTalkRoom)
            "listTalkMessagePage" -> marketingHomepageTalkPage
            "getSupportsFileOfflineStorage" -> true
            "getExternalFileHandoffSupport" -> ExternalFileHandoffSupport.Unsupported(
                "Synthetic capture does not launch other applications.",
            )
            else -> error("The network-inert marketing fixture rejected ${method.name}.")
        }
    }
    return Proxy.newProxyInstance(
        NextcloudPlatformServices::class.java.classLoader,
        arrayOf(NextcloudPlatformServices::class.java),
        handler,
    ) as NextcloudPlatformServices
}

private fun marketingFileSyncListing(path: String): NextcloudFileListing {
    val files = when (path) {
        "Photos/Studio" -> listOf(
            marketingFile("Photos/Studio/RAW", directory = true),
            marketingFile("Photos/Studio/Exports", directory = true),
            marketingFile("Photos/Studio/brief.pdf", directory = false),
        )
        "Photos/Studio/RAW" -> listOf(
            marketingFile("Photos/Studio/RAW/Day 1", directory = true),
            marketingFile("Photos/Studio/RAW/Day 2", directory = true),
        )
        else -> emptyList()
    }
    return NextcloudFileListing(files, NextcloudFileListingSource.Network)
}

private fun marketingFile(path: String, directory: Boolean): NextcloudFile = NextcloudFile(
    path = path,
    name = path.substringAfterLast('/'),
    isDirectory = directory,
    mimeType = if (directory) null else "application/pdf",
    size = if (directory) null else 2_400_000L,
    lastModified = "2026-07-28T10:00:00Z",
    fileId = path.hashCode().toLong().let { if (it < 0L) -it else it },
    hasPreview = false,
    etag = "\"fixture-${path.length}\"",
)
