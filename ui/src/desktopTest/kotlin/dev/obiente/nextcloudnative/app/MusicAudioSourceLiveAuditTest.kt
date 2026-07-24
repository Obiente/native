package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in authenticated probe for the inferred binary source.
 *
 * The request is bounded with Range, rejects every redirect, and keeps the app password solely in
 * the Authorization header. Content and credentials are never printed.
 */
class MusicAudioSourceLiveAuditTest {
    @Test
    fun `live track file source supports authenticated bounded reads`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_CONTENT_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        val app = assertNotNull(server.apps.firstOrNull { it.id == "music" })
        val discovery = discoverDynamicAppDescriptor(services, session, app, server.version)
        assertTrue(discovery.acquisition != DynamicDescriptorAcquisition.MetadataFallback)
        val action = assertNotNull(discovery.descriptor.actions.firstOrNull { action ->
            action.binding.method == HttpMethod.GET &&
                action.intent == ActionIntent.list &&
                action.binding.path.trimEnd('/').endsWith("/tracks") &&
                action.binding.pathParameters.isEmpty()
        })
        val records = loadDynamicRecords(services, session, discovery.descriptor, action.id)
        val schema = discovery.descriptor.toNativeAppSchema()
        val resource = assertNotNull(schema.resources.firstOrNull { it.id == action.resourceId })
        val track = assertNotNull(
            records.firstNotNullOfOrNull { nativeAudioTrack(resource, it) },
            "trackKeys=${records.firstOrNull()?.values?.keys?.sorted()} " +
                "structuredKeys=${records.firstOrNull()?.structuredValues?.keys?.sorted()} " +
                "fileShape=${records.firstOrNull()?.structuredValues?.get("files").redactedFileShape()}",
        )
        val capability = assertNotNull(nativeAudioSourceCapability(discovery, action))
        val source = assertNotNull(capability.source(track))
        val request = Request.Builder()
            .url(nativeAudioPlaybackUrl(session, source))
            .header("Authorization", Credentials.basic(session.loginName, session.appPassword))
            .header("Range", "bytes=0-4095")
            .header("Accept", source.mimeType)
            .get()
            .build()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.code in setOf(200, 206), "source status=${response.code}")
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            assertTrue(
                contentType.startsWith("audio/") || contentType == "application/octet-stream",
                "source must return audio content",
            )
            val probe = response.body.byteStream().use { stream ->
                val buffer = ByteArray(4_096)
                stream.read(buffer)
            }
            assertTrue(probe > 0, "source returned no bytes")
        }
    }
}

private fun NativeStructuredValue?.redactedFileShape(): String = when (this) {
    is NativeStructuredValue.ObjectValue -> entries.joinToString(prefix = "object[", postfix = "]") { entry ->
        val scalar = entry.value as? NativeStructuredValue.Scalar
        val category = scalar?.value?.let { value ->
            when {
                value.toLongOrNull() != null -> "number"
                value.startsWith("/") -> "relative"
                value.startsWith("http://") || value.startsWith("https://") -> "absolute"
                else -> "other"
            }
        } ?: entry.value::class.simpleName.orEmpty()
        "${entry.key}:$category"
    }
    null -> "missing"
    else -> this::class.simpleName.orEmpty()
}
