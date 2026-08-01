package dev.obiente.nextcloudnative.nativeui.preview

import dev.obiente.nextcloudnative.app.ExternalFileHandoffSupport
import dev.obiente.nextcloudnative.app.NextcloudPlatformServices
import dev.obiente.nextcloudnative.app.marketingHomepageCachedFileListing
import dev.obiente.nextcloudnative.app.marketingHomepageFileListing
import dev.obiente.nextcloudnative.app.marketingHomepageFileOfflineAvailability
import dev.obiente.nextcloudnative.app.marketingHomepageTalkPage
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
            "listFilesWithSource" -> marketingHomepageFileListing
            "loadFileOfflineAvailability" -> marketingHomepageFileOfflineAvailability
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
