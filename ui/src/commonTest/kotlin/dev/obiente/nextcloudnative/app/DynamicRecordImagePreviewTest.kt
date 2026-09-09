package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptorCompiler
import dev.obiente.nextcloudnative.nativeui.model.DynamicDiscoveryInput
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.OpenApiTrust
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicRecordImagePreviewTest {
    @Test
    fun `trusted nested binary preview becomes a typed media capability and request`() {
        val descriptor = compile(PHOTO_CONTRACT, OpenApiTrust.nextcloudSignedAppPackage)
        val resource = descriptor.resources.single { it.id == "photos" }
        val preview = assertNotNull(resource.recordImagePreview)
        val action = descriptor.actions.single { it.id == preview.actionId }
        val nativeSchema = descriptor.toNativeAppSchema()
        val nativeResource = nativeSchema.resources.single { it.id == "photos" }

        assertEquals("photo-preview", preview.actionId)
        assertEquals(listOf("*/*"), preview.declaredContentTypes)
        assertEquals("photos", action.resourceId)
        assertEquals(preview.actionId, assertNotNull(nativeResource.recordImagePreview).actionId)
        assertEquals(NativeComponent.mediaGrid, nativeSchema.views.single().component)

        val request = assertNotNull(
            nativeRecordImageRequest(
                discovery = DynamicDescriptorDiscovery(
                    descriptor = descriptor,
                    sourcePath = "/contract.json",
                    acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
                ),
                resource = nativeResource,
                record = NativeRecord(
                    id = "91",
                    values = mapOf("id" to "91", "houseId" to "7", "caption" to "Summer sky"),
                    bindingContext = mapOf("houseId" to "7"),
                ),
            ),
        )

        assertEquals("/ocs/v2.php/apps/example/api/houses/7/photos/91/preview", request.request.relativePath)
        assertTrue(request.request.ocsApiRequest)
        assertEquals(MAX_DYNAMIC_RECORD_IMAGE_BYTES, request.request.maximumResponseBytes)
        assertEquals("Summer sky", request.contentDescription)
        assertEquals(listOf("*/*"), request.expectedContentTypes)
        assertTrue(request.cacheKey.contains("photo-preview"))
    }

    @Test
    fun `record generation invalidates a stable preview endpoint`() {
        val descriptor = compile(PHOTO_CONTRACT, OpenApiTrust.nextcloudSignedAppPackage)
        val nativeResource = descriptor.toNativeAppSchema().resources.single { it.id == "photos" }
        val discovery = DynamicDescriptorDiscovery(
            descriptor = descriptor,
            sourcePath = "/contract.json",
            acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
        )
        fun request(etag: String) = assertNotNull(
            nativeRecordImageRequest(
                discovery = discovery,
                resource = nativeResource,
                record = NativeRecord(
                    id = "91",
                    values = mapOf("id" to "91", "houseId" to "7", "etag" to etag),
                    bindingContext = mapOf("houseId" to "7"),
                ),
            ),
        )

        val first = request("v1")
        val refreshed = request("v2")

        assertEquals(first.request.relativePath, refreshed.request.relativePath)
        assertFalse(first.cacheKey == refreshed.cacheKey)
    }

    @Test
    fun `untrusted or ambiguous binary reads never enable record image loading`() {
        val untrusted = compile(PHOTO_CONTRACT, OpenApiTrust.sameOriginAdvertisement)
        assertNull(untrusted.resources.single { it.id == "photos" }.recordImagePreview)
        assertEquals(NativeComponent.collectionList, untrusted.toNativeAppSchema().views.single().component)

        val ambiguous = compile(
            PHOTO_CONTRACT.replace(
                """</preview-marker>""",
                """
                ,
                "/ocs/v2.php/apps/example/api/houses/{houseId}/photos/{photoId}/thumbnail": {
                  "get": {
                    "operationId": "photo-thumbnail",
                    "parameters": [
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}},
                      {"name":"photoId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"Image","content":{"image/jpeg":{"schema":{"type":"string","format":"binary"}}}}}
                  }
                }
                """.trimIndent(),
            ),
            OpenApiTrust.nextcloudSignedAppPackage,
        )
        assertNull(ambiguous.resources.single { it.id == "photos" }.recordImagePreview)
    }

    @Test
    fun `resolver fails closed on missing parent identity or acquisition downgrade`() {
        val descriptor = compile(PHOTO_CONTRACT, OpenApiTrust.nextcloudSignedAppPackage)
        val nativeResource = descriptor.toNativeAppSchema().resources.single { it.id == "photos" }
        val record = NativeRecord(
            id = "91",
            values = mapOf("id" to "91", "caption" to "Summer sky"),
        )

        assertNull(
            nativeRecordImageRequest(
                DynamicDescriptorDiscovery(
                    descriptor,
                    null,
                    DynamicDescriptorAcquisition.SignedAppStorePackage,
                ),
                nativeResource,
                record,
            ),
        )
        assertNull(
            nativeRecordImageRequest(
                DynamicDescriptorDiscovery(
                    descriptor,
                    null,
                    DynamicDescriptorAcquisition.MetadataFallback,
                ),
                nativeResource,
                record.copy(bindingContext = mapOf("houseId" to "7")),
            ),
        )
    }

    @Test
    fun `runtime content type gate accepts only bounded images`() {
        assertTrue("image/jpeg; charset=binary".isSupportedDynamicRecordImageContentType())
        assertTrue(" IMAGE/PNG ".isSupportedDynamicRecordImageContentType())
        assertFalse("application/octet-stream".isSupportedDynamicRecordImageContentType())
        assertFalse("application/json".isSupportedDynamicRecordImageContentType())
        assertFalse(null.isSupportedDynamicRecordImageContentType())
        assertEquals(
            listOf<Byte>(1, 2),
            NextcloudApiResponse(200, byteArrayOf(1, 2), "image/jpeg", null)
                .acceptedDynamicRecordImageBytes()
                ?.toList(),
        )
        assertNull(
            NextcloudApiResponse(200, byteArrayOf(1), "application/octet-stream", null)
                .acceptedDynamicRecordImageBytes(),
        )
        assertNull(
            NextcloudApiResponse(404, byteArrayOf(1), "image/jpeg", null)
                .acceptedDynamicRecordImageBytes(),
        )
    }

    private fun compile(contract: String, trust: OpenApiTrust) =
        DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("example", "Example", "1.0.0"),
                endpointPolicy = EndpointPolicy(
                    "https://cloud.example.test",
                    listOf("/ocs/v2.php/apps/example"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    "/ocs/v2.php/apps/example/openapi.json",
                    Json.parseToJsonElement(contract.replace("</preview-marker>", "")),
                    trust,
                ),
            ),
        )

    private companion object {
        val PHOTO_CONTRACT = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Media home","version":"1"},
              "paths":{
                "/ocs/v2.php/apps/example/api/houses/{houseId}/photos":{
                  "get":{
                    "operationId":"photo-index",
                    "tags":["Photos"],
                    "parameters":[
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"OK","content":{"application/json":{"schema":{
                      "type":"array",
                      "items":{"type":"object","properties":{
                        "id":{"type":"integer"},
                        "houseId":{"type":"integer"},
                        "fileId":{"type":"integer"},
                        "caption":{"type":"string"}
                      }}
                    }}}}}
                  }
                },
                "/ocs/v2.php/apps/example/api/houses/{houseId}/photos/{photoId}/preview":{
                  "get":{
                    "operationId":"photo-preview",
                    "tags":["Photos"],
                    "parameters":[
                      {"name":"houseId","in":"path","required":true,"schema":{"type":"integer"}},
                      {"name":"photoId","in":"path","required":true,"schema":{"type":"integer"}}
                    ],
                    "responses":{"200":{"description":"Image","content":{"*/*":{"schema":{"type":"string","format":"binary"}}}}}
                  }
                }
                </preview-marker>
              }
            }
        """.trimIndent()
    }
}
