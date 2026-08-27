package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppOwnedOpenApiContractTest {
    @Test
    fun `root contract may declare app relative api paths`() {
        assertTrue(
            isAppOwnedOpenApiDocument(
                appId = "inventorycheck",
                specPath = "openapi.json",
                document = document(paths = listOf("/api/balances", "/api/config")),
            ),
        )
    }

    @Test
    fun `nested contract requires an app owned server or path`() {
        assertTrue(
            isAppOwnedOpenApiDocument(
                appId = "quicknotes",
                specPath = "doc/openapi.yml",
                document = document(
                    server = "https://{host}/index.php/apps/quicknotes/api/v1",
                    paths = listOf("/notes"),
                ),
            ),
        )
        assertFalse(
            isAppOwnedOpenApiDocument(
                appId = "workflow_ocr",
                specPath = "lib/OcrProcessors/Remote/Client/openapi-spec.json",
                document = document(paths = listOf("/enabled", "/heartbeat")),
            ),
        )
    }

    @Test
    fun `vendor and cross app contracts are rejected`() {
        assertFalse(
            isAppOwnedOpenApiDocument(
                appId = "aiquila",
                specPath = "vendor/nextcloud/openapi-extractor/tests/openapi-administration.json",
                document = document(paths = listOf("/ocs/v2.php/apps/notifications/api/v2/204")),
            ),
        )
        assertFalse(
            isAppOwnedOpenApiDocument(
                appId = "moodle_collectives_sync",
                specPath = "openapi-collectives.json",
                document = document(paths = listOf("/ocs/v2.php/apps/collectives/api/v1/collectives")),
            ),
        )
    }

    private fun document(
        paths: List<String>,
        server: String? = null,
    ): String = buildString {
        append("{\"openapi\":\"3.0.3\",")
        server?.let { append("\"servers\":[{\"url\":\"").append(it).append("\"}],") }
        append("\"paths\":{")
        paths.forEachIndexed { index, path ->
            if (index > 0) append(',')
            append('"').append(path).append("\":{}")
        }
        append("}}")
    }
}
