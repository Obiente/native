package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicLiveContractCompatibilityTest {
    @Test
    fun `signed Music contract keeps library collections and singleton settings as roots`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val contract = assertNotNull(
            SignedAppStoreContractAcquirer(
                catalogCache = FileAppStoreCatalogCache(
                    File(System.getProperty("user.home"), ".cache/nextcloud-native/contracts/catalogs"),
                ),
            ).acquire(
                ContractAcquisitionRequest("music", "34.0.1", "3.1.1"),
            ),
        )
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("music", "Music", "3.1.1"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/music"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/music#${contract.specFile}",
                    document = Json.parseToJsonElement(contract.document),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )
        val rootPaths = descriptor.planDynamicNavigation().rootDestinations.map { destination ->
            descriptor.actions.single { it.id == destination.actionId }.binding.path
        }
        assertEquals(
            setOf("artist", "album", "page_size", "page"),
            descriptor.actions.first { action -> action.binding.path == "/apps/music/api/tracks" }
                .binding.queryParameters.mapTo(linkedSetOf(), HttpParameter::name),
        )
        val albumTracks = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "albums",
                recordId = "784",
                actionSafeIdentity = false,
            ),
        ).contextualChildDestinations.single { destination -> destination.resourceId == "tracks" }
        assertEquals(mapOf("album" to "784"), albumTracks.pathParameterValues)
        val artistAlbums = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "artists",
                recordId = "659",
                actionSafeIdentity = false,
            ),
        ).contextualChildDestinations.single { destination -> destination.resourceId == "albums" }
        assertEquals(mapOf("artist" to "659"), artistAlbums.pathParameterValues)

        listOf("albums", "artists", "tracks", "settings").forEach { resource ->
            assertTrue(
                rootPaths.any { path -> path == "/apps/music/api/$resource" },
                "missing=$resource roots=$rootPaths layouts=${descriptor.layouts.map { it.id to it.sourceActionId }}",
            )
        }
        val settingForms = descriptor.forms.filter { it.resourceId == "settings" }
        assertEquals(
            setOf("excludedPaths", "ignoredArticles", "path", "scanMetadata"),
            settingForms.flatMap { form -> form.fields.map { it.fieldId } }.toSet(),
        )
        assertTrue(settingForms.all { form ->
            descriptor.actions.single { it.id == form.actionId }.binding.path.startsWith(
                "/apps/music/api/settings/user/",
            )
        })
        assertTrue(descriptor.actions.any { action ->
            action.binding.method == HttpMethod.GET &&
                action.binding.path == "/apps/music/api/settings/user/keys"
        })
        assertTrue(descriptor.actions.none { action ->
            action.binding.method != HttpMethod.GET &&
                (action.binding.path == "/apps/music/api/settings/user/keys" ||
                    action.binding.path == "/apps/music/api/settings/userkey/generate")
        })
        assertTrue(descriptor.validationErrors().isEmpty())
    }
}
