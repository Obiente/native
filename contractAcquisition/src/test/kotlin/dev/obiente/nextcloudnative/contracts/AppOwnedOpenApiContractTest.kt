package dev.obiente.nextcloudnative.contracts

import org.json.JSONObject
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
    fun `empty servers list receives the proven app base`() {
        val candidate = OpenApiCandidate(
            path = "openapi.json",
            document = """{"openapi":"3.0.3","servers":[],"paths":{"/api/items":{}}}""",
            apiVersion = "1.0.0",
        ).withProvenAppServerBase("inventorycheck")

        assertTrue(
            JSONObject(candidate.document)
                .getJSONArray("servers")
                .getJSONObject(0)
                .getString("url") == "/apps/inventorycheck",
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
    fun `concrete and partially templated foreign servers never prove app ownership`() {
        listOf(
            "https://api.vendor.test/apps/example", "https://localhost/apps/example",
            "https://{tenant}.vendor.test/apps/example", "https://vendor.test:{port}/apps/example",
            "//api.vendor.test/apps/example", "ftp://{host}/apps/example",
        ).forEach { server ->
            listOf(listOf("/api/items"), listOf("/apps/example/api/items"), listOf("/items")).forEach { paths ->
                assertFalse(isAppOwnedOpenApiDocument("example", "openapi.json", document(paths, server)), server)
            }
        }
    }

    @Test
    fun `every server including operation overrides must be portable`() {
        val foreign = JSONObject().put("url", "https://api.vendor.test/apps/example")
        val root = JSONObject(document(listOf("/api/items"), "/apps/example"))
        root.getJSONArray("servers").put(foreign)
        assertFalse(isAppOwnedOpenApiDocument("example", "openapi.json", root.toString()))
        listOf(false, true).forEach { operationLevel ->
            val nested = JSONObject(document(listOf("/api/items"), "/apps/example"))
            val item = nested.getJSONObject("paths").getJSONObject("/api/items")
            val owner = if (operationLevel) JSONObject().also { item.put("post", it) } else item
            owner.put("servers", org.json.JSONArray().put(foreign))
            assertFalse(isAppOwnedOpenApiDocument("example", "openapi.json", nested.toString()))
        }
    }

    @Test
    fun `relative and fully host templated Nextcloud servers remain portable`() {
        listOf("/apps/example", "https://{host}/apps/example", "https://{host}:{port}/apps/example",
            "http://{hostname}:8080/index.php/apps/example").forEach { server ->
            assertTrue(isAppOwnedOpenApiDocument("example", "openapi.json", document(listOf("/items"), server)), server)
        }
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
