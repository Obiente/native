package dev.obiente.nextcloudnative.contracts

import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.infoXml
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.tarGz
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Package-level evidence for the new static extraction: the exact signed version, provenance,
 * malformed input, downgrade attempts, signature failures, and the supplementary file budget.
 * Archives are built in memory from synthetic sources; nothing is fetched.
 */
class PackageContractExtractionTest {
    @Test
    fun `an attribute only package yields a contract stamped with its exact version and source`() {
        val contract = extractOpenApiContract(archive("5.4.1"), release("5.4.1"))

        assertEquals(VerifiedContractKind.VerifiedReadRoutes, contract.contractKind)
        assertEquals("surveys_x", contract.appId)
        assertEquals("5.4.1", contract.appVersion)
        assertEquals("lib/Controller", contract.specFile)
        val document = JSONObject(contract.document)
        assertEquals("5.4.1", document.getJSONObject("info").getString("version"))
        assertEquals(STATIC_EVIDENCE_FORMAT, document.getInt(STATIC_EVIDENCE_FORMAT_EXTENSION))
        assertTrue(document.getJSONObject("paths").has("/ocs/v2.php/apps/surveys_x/api/v3/forms"))
    }

    @Test
    fun `a package whose metadata names another version is rejected, including a downgrade`() {
        // A catalog entry for 5.4.1 must not deliver the 5.4.0 package, nor a newer one.
        assertFailsWith<IllegalStateException> { extractOpenApiContract(archive("5.4.0"), release("5.4.1")) }
        assertFailsWith<IllegalStateException> { extractOpenApiContract(archive("5.5.0"), release("5.4.1")) }
        assertFailsWith<IllegalStateException> {
            extractOpenApiContract(archive("5.4.1", appId = "other_x"), release("5.4.1"))
        }
    }

    @Test
    fun `a package without readable routes or controllers has no static contract`() {
        val archive = tarGz(
            "surveys_x",
            "appinfo/info.xml" to infoXml("surveys_x", "5.4.1"),
            "lib/Controller/ApiController.php" to "<?php class ApiController extends OCSController { #[ApiRoute(verb: 'GET', url: ",
        )
        assertFailsWith<OpenApiContractMissingException> { extractOpenApiContract(archive, release("5.4.1")) }
    }

    @Test
    fun `a bad signature over an attribute only package never reaches extraction`() {
        val roots = loadBundledTrustResource("nextcloud-codesigning/root.crt").decodeToString()
        val intermediate = roots.substring(0, roots.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length) + "\n"
        val signed = release("5.4.1").copy(
            appId = "Nextcloud Code Signing Intermediate Authority",
            signature = "c2lnbmF0dXJl",
            certificatePem = intermediate,
        )
        val failure = assertFailsWith<Exception> { NextcloudAppPackageTrustVerifier().verifyAndExtract(signed, archive("5.4.1")) }
        assertFalse(failure is OpenApiContractMissingException)
        assertEquals(
            ContractAcquisitionFailureKind.VerificationFailed,
            classifyContractAcquisitionFailure(failure.asSourceVerificationFailure("surveys_x")),
        )
    }

    @Test
    fun `supplementary evidence beyond its budget is skipped and flagged, not fatal`() {
        val entities = (1..200).map { index ->
            "lib/Db/Entity$index.php" to "<?php namespace OCA\\SurveysX\\Db; class Entity$index extends \\OCP\\AppFramework\\Db\\Entity {}"
        }
        val archive = tarGz("surveys_x", *(baseFiles("5.4.1") + entities).toTypedArray())
        val selection = readSelectedTarFileSet(archive)
        assertTrue(selection.supplementaryTruncated)
        assertEquals(192, selection.files.keys.count { "/lib/Db/" in it })

        val document = JSONObject(extractOpenApiContract(archive, release("5.4.1")).document)
        assertTrue(document.getBoolean(SUPPLEMENTARY_TRUNCATED_EXTENSION))
        assertTrue(document.getJSONObject("paths").has("/ocs/v2.php/apps/surveys_x/api/v3/forms"))
    }

    @Test
    fun `files outside the evidence locations are never selected`() {
        val archive = tarGz(
            "surveys_x",
            *(
                baseFiles("5.4.1") + listOf(
                    "lib/Service/FormService.php" to "<?php class FormService {}",
                    "lib/Db/Nested/Deep.php" to "<?php class Deep {}",
                    "vendor/lib/Db/Vendor.php" to "<?php class Vendor {}",
                    "lib/ResponseDefinitions.php" to "<?php class ResponseDefinitions {}",
                )
                ).toTypedArray(),
        )
        assertEquals(
            setOf(
                "surveys_x/appinfo/info.xml",
                "surveys_x/lib/Controller/ApiController.php",
                "surveys_x/lib/ResponseDefinitions.php",
            ),
            readSelectedTarFileSet(archive).files.keys,
        )
    }

    @Test
    fun `a shipped OpenAPI document gains package policy facts without losing its declared shape`() {
        // Shaped like Polls v9.3.0, which ships openapi.json and declares #[ApiRoute] attributes.
        val openApi = """
            {"openapi": "3.0.3", "info": {"title": "surveys_x", "version": "0.0.1"},
             "paths": {"/ocs/v2.php/apps/surveys_x/api/v3/forms": {"get": {
                "operationId": "api-get-forms",
                "responses": {"200": {"description": "ok", "content": {"application/json": {"schema": {"type": "array", "items": {"type": "object"}}}}}}
             }}}}
        """
        val archive = tarGz("surveys_x", *(baseFiles("5.4.1") + ("openapi.json" to openApi)).toTypedArray())
        val contract = extractOpenApiContract(archive, release("5.4.1"))
        val document = JSONObject(contract.document)
        val operation = document.getJSONObject("paths").getJSONObject("/ocs/v2.php/apps/surveys_x/api/v3/forms").getJSONObject("get")

        assertEquals("api-get-forms", operation.getString("operationId"))
        assertEquals("user", operation.getJSONObject(POLICY_EXTENSION).getString("access"))
        assertEquals("attribute", operation.getString(ROUTE_SOURCE_EXTENSION))
        assertTrue(document.has(STATIC_ROUTES_EXTENSION))
    }

    private fun archive(version: String, appId: String = "surveys_x"): ByteArray =
        tarGz("surveys_x", *baseFiles(version, appId).toTypedArray())

    private fun baseFiles(version: String, appId: String = "surveys_x"): List<Pair<String, String>> = listOf(
        "appinfo/info.xml" to infoXml(appId, version),
        "lib/Controller/ApiController.php" to """
            <?php
            namespace OCA\SurveysX\Controller;
            use OCP\AppFramework\OCSController;
            use OCP\AppFramework\Http\Attribute\ApiRoute;
            use OCP\AppFramework\Http\Attribute\NoAdminRequired;
            use OCP\AppFramework\Http\DataResponse;
            class ApiController extends OCSController {
                #[NoAdminRequired]
                #[ApiRoute(verb: 'GET', url: '/api/v3/forms')]
                public function getForms(): DataResponse { return new DataResponse([]); }
            }
        """,
    )

    private fun release(version: String) = AppStoreRelease(
        appId = "surveys_x",
        version = version,
        downloadUrl = "https://fixture.invalid/surveys_x.tar.gz",
        signature = "unused",
        signatureDigest = "sha512",
        certificatePem = "unused",
        catalogUrl = "https://fixture.invalid/apps.json",
    )
}
