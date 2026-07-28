package dev.obiente.nextcloudnative.nativeui.preview

import dev.obiente.nextcloudnative.app.NextcloudPlatformServices
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal fun networkInertMarketingServices(
    previewBytes: ByteArray,
): NextcloudPlatformServices {
    require(previewBytes.isNotEmpty()) { "The marketing preview fixture is empty." }
    val handler = InvocationHandler { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "NetworkInertMarketingServices"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.singleOrNull()
            "loadPreview" -> previewBytes.copyOf()
            else -> error("The network-inert marketing fixture rejected ${method.name}.")
        }
    }
    return Proxy.newProxyInstance(
        NextcloudPlatformServices::class.java.classLoader,
        arrayOf(NextcloudPlatformServices::class.java),
        handler,
    ) as NextcloudPlatformServices
}
