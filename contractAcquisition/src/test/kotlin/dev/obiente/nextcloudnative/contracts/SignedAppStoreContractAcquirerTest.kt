package dev.obiente.nextcloudnative.contracts

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue

class SignedAppStoreContractAcquirerTest {
    @Test
    fun `live official package verifies against bundled Nextcloud trust roots`() {
        assumeTrue(System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") == "1")

        val contract = SignedAppStoreContractAcquirer().acquire(
            ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2"),
        )

        assertEquals("cospend", contract?.appId)
        assertEquals("4.0.2", contract?.appVersion)
        assertEquals("openapi.json", contract?.specFile)
        assertEquals(OpenApiContractSourceKind.SignedAppPackage, contract?.sourceKind)
    }

    @Test
    fun `live Cookbook release enriches signed routes with its exact linked OpenAPI contract`() {
        assumeTrue(System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") == "1")

        val contract = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("cookbook", "34.0.1", "0.11.9"),
            ),
        )

        assertEquals("cookbook", contract.appId)
        assertEquals("0.11.9", contract.contractVersion)
        assertEquals("docs/dev/api/0.1.3/openapi-cookbook.yaml", contract.specFile)
        assertEquals(OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag, contract.sourceKind)
        assertEquals(VerifiedContractKind.OpenApi, contract.contractKind)
        val recipeItem = JSONObject(contract.document).getJSONObject("paths")
            .getJSONObject("/apps/cookbook/api/v1/recipes/{id}")
        assertTrue(recipeItem.has("put"))
        assertTrue(recipeItem.has("delete"))
    }

    @Test
    fun `live Cookbook YAML parses with the bounded safe loader`() {
        assumeTrue(System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") == "1")
        val response = OkHttpClient().newCall(
            Request.Builder().url(
                "https://raw.githubusercontent.com/nextcloud/cookbook/v0.11.9/docs/dev/api/0.1.3/openapi-cookbook.yaml",
            ).build(),
        ).execute()
        val source = response.use { assertNotNull(it.body).string() }

        val parsed = assertNotNull(parseYamlObject(source))

        assertEquals("3.0.1", parsed.getString("openapi"))
        assertTrue(parsed.getJSONObject("paths").has("/apps/cookbook/api/v1/recipes"))
    }

    @Test
    fun `live Deck package yields verified reads plus exact scalar card actions without an app adapter`() {
        assumeTrue(System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") == "1")

        val contract = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("deck", "34.0.1", "1.18.2"),
            ),
        )
        val document = JSONObject(contract.document)
        val paths = document.getJSONObject("paths")

        assertEquals("appinfo/routes.php", contract.specFile)
        assertEquals("verified-read-only-routes", document.getString("x-nextcloud-native-contract-kind"))
        assertTrue(paths.has("/apps/deck/boards"))
        assertTrue(paths.has("/apps/deck/boards/{boardId}"))
        assertTrue(paths.has("/apps/deck/stacks/{boardId}"))
        assertTrue(
            paths.keys().asSequence().any { path -> path.startsWith("/ocs/v2.php/apps/deck/api/") },
        )
        assertTrue(paths.length() > 10)
        val rename = paths.getJSONObject("/apps/deck/cards/{cardId}/rename").getJSONObject("put")
        val reorder = paths.getJSONObject("/apps/deck/cards/{cardId}/reorder").getJSONObject("put")
        assertEquals("cards", rename.getString("x-nextcloud-native-resource-id"))
        assertEquals("cards", reorder.getString("x-nextcloud-native-resource-id"))
        assertEquals(
            setOf("title"),
            rename.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getJSONObject("properties").keySet(),
        )
        assertEquals(
            setOf("stackId", "order"),
            reorder.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getJSONObject("properties").keySet(),
        )
        val create = paths.getJSONObject("/ocs/v2.php/apps/deck/api/v1.1/cards").getJSONObject("post")
        assertEquals(
            setOf("title", "stackId", "boardId", "type", "owner", "order", "description", "labels", "users", "color"),
            create.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getJSONObject("properties").keySet(),
        )
        assertEquals(
            setOf("title", "stackId"),
            create.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getJSONArray("required").toList().toSet(),
        )
        assertTrue(
            paths.getJSONObject(
                "/apps/deck/api/v1.1/boards/{boardId}/stacks/{stackId}/cards/{cardId}/archive",
            ).has("put"),
        )
    }

    @Test
    fun `live Mail package merges its typed contract with verified legacy account reads`() {
        assumeTrue(System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") == "1")

        val contract = assertNotNull(
            SignedAppStoreContractAcquirer(
                catalogCache = FileAppStoreCatalogCache(
                    java.io.File(System.getProperty("user.home"), ".cache/nextcloud-native/contracts/catalogs"),
                ),
            ).acquire(
                ContractAcquisitionRequest("mail", "34.0.1", "5.10.9"),
            ),
        )
        val document = JSONObject(contract.document)
        val paths = document.getJSONObject("paths")
        val preferred = paths.getJSONObject("/ocs/v2.php/apps/mail/account/list").getJSONObject("get")
        val fallback = paths.getJSONObject("/apps/mail/api/accounts").getJSONObject("get")

        assertEquals(VerifiedContractKind.OpenApiWithVerifiedReadRoutes, contract.contractKind)
        assertEquals("openapi-with-verified-read-routes", document.getString("x-nextcloud-native-contract-kind"))
        assertEquals("route.accounts.index", preferred.getJSONArray(READ_FALLBACKS_EXTENSION).getString(0))
        assertEquals("account_api-list", fallback.getString(FALLBACK_FOR_OPERATION_EXTENSION))
        assertTrue(fallback.getBoolean(VERIFIED_READ_ROUTE_EXTENSION))
    }

    @Test
    fun `static route fallback accepts only GET methods backed by API controllers`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "2.4.1",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php
                        return [
                          'routes' => [
                            ['name' => 'item_api#index', 'url' => '/items', 'verb' => 'GET'],
                            ['name' => 'item_api#read', 'url' => '/items/{itemId}', 'verb' => 'GET'],
                            ['name' => 'item_api#create', 'url' => '/items', 'verb' => 'POST'],
                            ['name' => 'item_api#missing', 'url' => '/missing', 'verb' => 'GET'],
                            ['name' => 'page#index', 'url' => '/', 'verb' => 'GET'],
                          ],
                          'ocs' => [
                            [
                              'name' => 'item_api#index',
                              'url' => '/api/v{apiVersion}/items',
                              'verb' => 'GET',
                              'requirements' => ['apiVersion' => '2.0'],
                            ],
                          ],
                        ];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/ItemApiController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class ItemApiController extends ApiController {
                          public function index(): array { return []; }
                          public function read(string ${'$'}itemId): array { return []; }
                          public function create(): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/PageController.php" to """
                        <?php
                        class PageController extends Controller {
                          public function index() {}
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")

        assertEquals(
            setOf(
                "/apps/example/items",
                "/apps/example/items/{itemId}",
                "/ocs/v2.php/apps/example/api/v2.0/items",
            ),
            paths.keySet(),
        )
        assertEquals(
            "itemId",
            paths.getJSONObject("/apps/example/items/{itemId}")
                .getJSONObject("get")
                .getJSONArray("parameters")
                .getJSONObject(0)
                .getString("name"),
        )
        assertEquals(
            "array",
            paths.getJSONObject("/apps/example/items")
                .getJSONObject("get")
                .getJSONObject("responses")
                .getJSONObject("200")
                .getJSONObject("content")
                .getJSONObject("application/json")
                .getJSONObject("schema")
                .getString("type"),
        )
    }

    @Test
    fun `static routes synthesize only fully proven conventional CRUD forms`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "2.4.1",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['routes' => [
                          ['name' => 'item#index', 'url' => '/items', 'verb' => 'GET'],
                          ['name' => 'item#create', 'url' => '/items', 'verb' => 'POST'],
                          ['name' => 'item#update', 'url' => '/items/{id}', 'verb' => 'PUT'],
                          ['name' => 'item#patch', 'url' => '/items/{id}', 'verb' => 'PATCH'],
                          ['name' => 'item#delete', 'url' => '/items/{id}', 'verb' => 'DELETE'],
                          ['name' => 'item#sync', 'url' => '/rejected/method', 'verb' => 'POST'],
                          ['name' => 'item#createUnknown', 'url' => '/rejected/unknown', 'verb' => 'POST'],
                          ['name' => 'item#createSensitive', 'url' => '/rejected/sensitive', 'verb' => 'POST'],
                          ['name' => 'item#createWithOptionalObject', 'url' => '/optional-object', 'verb' => 'POST'],
                          ['name' => 'item#deleteWithBody', 'url' => '/rejected/body/{id}', 'verb' => 'DELETE'],
                          ['name' => 'item#updateMissingPath', 'url' => '/rejected/path/{id}', 'verb' => 'PUT'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/ItemController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class ItemController extends ApiController {
                          public function index(): array { return []; }
                          public function create(string ${'$'}title, array ${'$'}labels = []): array { return []; }
                          public function update(int ${'$'}id, string ${'$'}title, bool ${'$'}enabled): array { return []; }
                          public function patch(int ${'$'}id, string ${'$'}title = ''): array { return []; }
                          public function delete(int ${'$'}id): array { return []; }
                          public function sync(string ${'$'}value): array { return []; }
                          public function createUnknown(string ${'$'}title, Request ${'$'}request): array { return []; }
                          public function createSensitive(string ${'$'}password): array { return []; }
                          public function createWithOptionalObject(string ${'$'}title, Request ${'$'}request = null): array {
                            return [];
                          }
                          public function deleteWithBody(int ${'$'}id, string ${'$'}reason): array { return []; }
                          public function updateMissingPath(string ${'$'}title): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")
        val collection = paths.getJSONObject("/apps/example/items")
        val item = paths.getJSONObject("/apps/example/items/{id}")

        assertEquals(setOf("get", "post"), collection.keySet())
        assertEquals(
            setOf("post"),
            paths.getJSONObject("/apps/example/optional-object").keySet(),
        )
        assertEquals(
            setOf("title"),
            paths.getJSONObject("/apps/example/optional-object").getJSONObject("post")
                .getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getJSONObject("properties").keySet(),
        )
        assertEquals(setOf("put", "patch", "delete"), item.keySet())
        assertTrue(collection.getJSONObject("post").getBoolean(VERIFIED_CRUD_EXTENSION))
        assertEquals(
            setOf("title", "labels"),
            collection.getJSONObject("post")
                .getJSONObject("requestBody")
                .getJSONObject("content")
                .getJSONObject("application/json")
                .getJSONObject("schema")
                .getJSONObject("properties")
                .keySet(),
        )
        assertEquals(
            setOf("title", "enabled"),
            item.getJSONObject("put")
                .getJSONObject("requestBody")
                .getJSONObject("content")
                .getJSONObject("application/json")
                .getJSONObject("schema")
                .getJSONObject("properties")
                .keySet(),
        )
        assertEquals(
            setOf("title"),
            item.getJSONObject("patch")
                .getJSONObject("requestBody")
                .getJSONObject("content")
                .getJSONObject("application/json")
                .getJSONObject("schema")
                .getJSONObject("properties")
                .keySet(),
        )
        assertFalse(item.getJSONObject("delete").has("requestBody"))
        assertTrue(
            item.getJSONObject("delete")
                .getJSONArray("parameters")
                .getJSONObject(0)
                .let { parameter ->
                    parameter.getString("name") == "id" &&
                        parameter.getJSONObject("schema").getString("type") == "integer"
                },
        )
        assertTrue(paths.keys().asSequence().none { path -> "/rejected/" in path })
    }

    @Test
    fun `body free item workflow routes remain exact signed mutations`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "workflow",
                appVersion = "1.0.0",
                files = mapOf(
                    "workflow/appinfo/routes.php" to """
                        <?php return ['routes' => [
                          ['name' => 'card#archive', 'url' => '/boards/{boardId}/stacks/{stackId}/cards/{cardId}/archive', 'verb' => 'PUT'],
                          ['name' => 'card#unarchive', 'url' => '/cards/{cardId}/unarchive', 'verb' => 'PUT'],
                          ['name' => 'card#done', 'url' => '/cards/{cardId}/done', 'verb' => 'PUT'],
                          ['name' => 'card#undone', 'url' => '/cards/{cardId}/undone', 'verb' => 'PUT'],
                          ['name' => 'card#publish', 'url' => '/cards/{cardId}/publish', 'verb' => 'PUT'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "workflow/lib/Controller/CardController.php" to """
                        <?php
                        use OCP\AppFramework\Controller;
                        class CardController extends Controller {
                          public function archive(int ${'$'}cardId): array { return []; }
                          public function unarchive(int ${'$'}cardId): array { return []; }
                          public function done(int ${'$'}cardId): array { return []; }
                          public function undone(int ${'$'}cardId): array { return []; }
                          public function publish(int ${'$'}cardId): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")

        listOf("archive", "unarchive", "done", "undone").forEach { workflow ->
            val path = if (workflow == "archive") {
                "/apps/workflow/boards/{boardId}/stacks/{stackId}/cards/{cardId}/archive"
            } else {
                "/apps/workflow/cards/{cardId}/$workflow"
            }
            val operation = paths.getJSONObject(path)
                .getJSONObject("put")
            assertTrue(operation.getBoolean(VERIFIED_CRUD_EXTENSION))
            assertEquals("cards", operation.getString("x-nextcloud-native-resource-id"))
            assertFalse(operation.has("requestBody"))
        }
        assertFalse(paths.has("/apps/workflow/cards/{cardId}/publish"))
    }

    @Test
    fun `static scalar workflow routes retain exact editable and lane mutation contracts`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "2.4.1",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['routes' => [
                          ['name' => 'card#read', 'url' => '/cards/{cardId}', 'verb' => 'GET'],
                          ['name' => 'card#rename', 'url' => '/cards/{cardId}/rename', 'verb' => 'PUT'],
                          ['name' => 'card#reorder', 'url' => '/cards/{cardId}/reorder', 'verb' => 'PUT'],
                          ['name' => 'card#moveUnknown', 'url' => '/cards/{cardId}/move', 'verb' => 'PUT'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/CardController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class CardController extends ApiController {
                          public function read(int ${'$'}cardId): array { return []; }
                          public function rename(int ${'$'}cardId, string ${'$'}title): array { return []; }
                          public function reorder(int ${'$'}cardId, int ${'$'}stackId, int ${'$'}order): array { return []; }
                          public function moveUnknown(int ${'$'}cardId, Request ${'$'}request): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )

        val paths = JSONObject(contract.document).getJSONObject("paths")
        val rename = paths.getJSONObject("/apps/example/cards/{cardId}/rename").getJSONObject("put")
        val reorder = paths.getJSONObject("/apps/example/cards/{cardId}/reorder").getJSONObject("put")

        assertEquals("cards", rename.getString("x-nextcloud-native-resource-id"))
        assertEquals("cards", reorder.getString("x-nextcloud-native-resource-id"))
        assertEquals(
            setOf("title"),
            rename.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getJSONObject("properties").keySet(),
        )
        assertEquals(
            setOf("stackId", "order"),
            reorder.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getJSONObject("properties").keySet(),
        )
        assertTrue(rename.getBoolean(VERIFIED_CRUD_EXTENSION))
        assertTrue(reorder.getBoolean(VERIFIED_CRUD_EXTENSION))
        assertFalse(paths.has("/apps/example/cards/{cardId}/move"))
    }

    @Test
    fun `legacy DataResponse controller yields Chores reads without hidden request writes`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "chores",
                appVersion = "0.1.0",
                files = mapOf(
                    "chores/appinfo/routes.php" to """
                        <?php return ['routes' => [
                          ['name' => 'API#getTeam', 'url' => '/api/v1.0/team', 'verb' => 'GET'],
                          ['name' => 'API#getChores', 'url' => '/api/v1.0/team/{teamId}/chores', 'verb' => 'GET'],
                          ['name' => 'API#postChores', 'url' => '/api/v1.0/team/{teamId}/chores', 'verb' => 'POST'],
                          ['name' => 'API#patchChore', 'url' => '/api/v1.0/team/{teamId}/chores/{choreId}', 'verb' => 'PATCH'],
                          ['name' => 'API#deleteChore', 'url' => '/api/v1.0/team/{teamId}/chores/{choreId}', 'verb' => 'DELETE'],
                          ['name' => 'API#getWorklog', 'url' => '/api/v1.0/team/{teamId}/work', 'verb' => 'GET'],
                          ['name' => 'Page#index', 'url' => '/', 'verb' => 'GET'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "chores/lib/Controller/APIController.php" to """
                        <?php
                        use OCP\AppFramework\Controller;
                        use OCP\AppFramework\Http\DataResponse;
                        class APIController extends Controller {
                          public function getTeam() {
                            return new DataResponse([]);
                          }
                          public function getChores(int ${'$'}teamId) {
                            return new DataResponse([]);
                          }
                          public function postChores(int ${'$'}teamId) {
                            ${'$'}chores = ${'$'}this->request->getParam("chores");
                            return new DataResponse(["result" => []]);
                          }
                          public function patchChore(int ${'$'}teamId, int ${'$'}choreId) {
                            ${'$'}updated = (array) ${'$'}this->request->patch;
                            return new DataResponse(${'$'}updated);
                          }
                          public function deleteChore(int ${'$'}teamId, int ${'$'}choreId) {
                            return new DataResponse([]);
                          }
                          public function getWorklog(string ${'$'}teamId) {
                            return new DataResponse(["worklog" => []]);
                          }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "chores/lib/Controller/PageController.php" to """
                        <?php
                        class PageController extends Controller {
                          public function index() {
                            return new TemplateResponse("chores", "main");
                          }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")
        val collection = paths.getJSONObject("/apps/chores/api/v1.0/team/{teamId}/chores")
        val item = paths.getJSONObject("/apps/chores/api/v1.0/team/{teamId}/chores/{choreId}")

        assertEquals(
            setOf(
                "/apps/chores/api/v1.0/team",
                "/apps/chores/api/v1.0/team/{teamId}/chores",
                "/apps/chores/api/v1.0/team/{teamId}/chores/{choreId}",
                "/apps/chores/api/v1.0/team/{teamId}/work",
            ),
            paths.keySet(),
        )
        assertEquals(setOf("get"), collection.keySet())
        assertEquals(setOf("delete"), item.keySet())
        val unverifiedTeamSchema = paths.getJSONObject("/apps/chores/api/v1.0/team")
            .getJSONObject("get")
            .getJSONObject("responses")
            .getJSONObject("200")
            .getJSONObject("content")
            .getJSONObject("application/json")
            .getJSONObject("schema")
        assertTrue(unverifiedTeamSchema.getBoolean("additionalProperties"))
        assertFalse(unverifiedTeamSchema.has("properties"))
        assertTrue(item.getJSONObject("delete").getBoolean(VERIFIED_CRUD_EXTENSION))
        assertFalse(item.getJSONObject("delete").has("requestBody"))
        assertEquals(
            setOf("teamId", "choreId", "OCS-APIRequest"),
            item.getJSONObject("delete").getJSONArray("parameters")
                .let { parameters ->
                    (0 until parameters.length())
                        .map { index -> parameters.getJSONObject(index).getString("name") }
                        .toSet()
                },
        )
    }

    @Test
    fun `opaque route helpers are skipped while package local API inheritance is resolved`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "1.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php
                        function wrap(${'$'}route, ${'$'}parameter) { return ${'$'}route; }
                        return ['routes' => [
                          wrap(['name' => 'page#show', 'url' => '/page/{path}', 'verb' => 'GET'], 'path'),
                          ['name' => 'entry#index', 'url' => '/api/entries', 'verb' => 'GET'],
                          ['name' => 'entry#create', 'url' => '/api/entries', 'verb' => 'POST'],
                          ['name' => 'untrusted#index', 'url' => '/api/untrusted', 'verb' => 'GET'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/GenericApiController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        abstract class GenericApiController extends ApiController {}
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/EntryController.php" to """
                        <?php
                        final class EntryController extends GenericApiController {
                          public function index(): array { return []; }
                          public function create(string ${'$'}title): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/PageController.php" to """
                        <?php
                        class PageController extends Controller {
                          public function show(string ${'$'}path): TemplateResponse { return new TemplateResponse(); }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/UntrustedController.php" to """
                        <?php
                        class UntrustedController extends MissingPackageBase {
                          public function index(): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")

        assertEquals(setOf("/apps/example/api/entries"), paths.keySet())
        assertEquals(setOf("get", "post"), paths.getJSONObject("/apps/example/api/entries").keySet())
        assertTrue(
            paths.getJSONObject("/apps/example/api/entries")
                .getJSONObject("post")
                .getBoolean(VERIFIED_CRUD_EXTENSION),
        )
    }

    @Test
    fun `route regexes remain parameters while bounded API versions become callable literals`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "2.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php
                        return ['routes' => [
                          [
                            'name' => 'notes_api#index',
                            'url' => '/api/{apiVersion}/notes',
                            'verb' => 'GET',
                            'requirements' => ['apiVersion' => '(v0.2|v1)'],
                          ],
                          [
                            'name' => 'notes_api#get',
                            'url' => '/api/{apiVersion}/notes/{id}',
                            'verb' => 'GET',
                            'requirements' => ['apiVersion' => '(v0.2|v1)', 'id' => '\d+'],
                          ],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/NotesApiController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class NotesApiController extends ApiController {
                          public function index(): array { return []; }
                          public function get(int ${'$'}id): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")

        assertTrue(paths.has("/apps/example/api/v1/notes"))
        assertTrue(paths.has("/apps/example/api/v1/notes/{id}"))
        assertTrue(paths.keys().asSequence().none { path -> "/d+" in path })
    }

    @Test
    fun `router only API versions use the highest version evidenced by the same manifest`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "2.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php
                        return ['routes' => [
                          ['name' => 'board_api#index', 'url' => '/api/v{apiVersion}/boards', 'verb' => 'GET'],
                          [
                            'name' => 'attachment_api#index',
                            'url' => '/api/v{apiVersion}/attachments',
                            'verb' => 'GET',
                            'requirements' => ['apiVersion' => '1.0'],
                          ],
                          [
                            'name' => 'attachment_v11_api#index',
                            'url' => '/api/v{apiVersion}/attachments',
                            'verb' => 'GET',
                            'requirements' => ['apiVersion' => '1.1'],
                          ],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/BoardApiController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class BoardApiController extends ApiController {
                          public function index(): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/AttachmentApiController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class AttachmentApiController extends ApiController {
                          public function index(): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/AttachmentV11ApiController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class AttachmentV11ApiController extends ApiController {
                          public function index(): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")

        assertTrue(paths.has("/apps/example/api/v1.1/boards"))
        assertTrue(paths.keys().asSequence().none { path -> "{apiVersion}" in path })
    }

    @Test
    fun `duplicate route bindings preserve the first registered controller`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "2.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['routes' => [
                          ['name' => 'stable#index', 'url' => '/items', 'verb' => 'GET'],
                          ['name' => 'duplicate#index', 'url' => '/items', 'verb' => 'GET'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/StableController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class StableController extends ApiController {
                          public function index(): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/DuplicateController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class DuplicateController extends ApiController {
                          public function index(): array { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val operation = JSONObject(contract.document)
            .getJSONObject("paths")
            .getJSONObject("/apps/example/items")
            .getJSONObject("get")

        assertEquals("route.stable.index", operation.getString("operationId"))
    }

    @Test
    fun `resource declarations derive only verified index and show reads`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "1.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['resources' => [
                          'widgets' => ['url' => '/api/widgets'],
                          'unsafe' => ['url' => '/api/unsafe'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/WidgetsController.php" to """
                        <?php
                        class WidgetsController extends Controller {
                          public function index(): JSONResponse { return new JSONResponse([]); }
                          public function show(int ${'$'}id): JSONResponse { return new JSONResponse([]); }
                          public function create(): JSONResponse { return new JSONResponse([]); }
                          public function destroy(int ${'$'}id): JSONResponse { return new JSONResponse([]); }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/UnsafeController.php" to """
                        <?php
                        class UnsafeController extends Controller {
                          public function index() { return []; }
                          public function show(int ${'$'}id) { return []; }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")

        assertEquals(
            setOf("/apps/example/api/widgets", "/apps/example/api/widgets/{id}"),
            paths.keySet(),
        )
        assertEquals(VerifiedContractKind.VerifiedReadRoutes, contract.contractKind)
        paths.keys().forEach { path -> assertEquals(setOf("get"), paths.getJSONObject(path).keySet()) }
    }

    @Test
    fun `read routes accept serialized domain entities but reject transport responses`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "1.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['routes' => [
                          ['name' => 'card#read', 'url' => '/cards/{cardId}', 'verb' => 'GET'],
                          ['name' => 'card#write', 'url' => '/cards/{cardId}', 'verb' => 'POST'],
                          ['name' => 'page#index', 'url' => '/', 'verb' => 'GET'],
                          ['name' => 'download#show', 'url' => '/download/{fileId}', 'verb' => 'GET'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/CardController.php" to """
                        <?php
                        class CardController extends Controller {
                          public function read(int ${'$'}cardId): Card { return new Card(); }
                          public function write(int ${'$'}cardId): Card { return new Card(); }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/PageController.php" to """
                        <?php
                        class PageController extends Controller {
                          public function index(): TemplateResponse { return new TemplateResponse(); }
                        }
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/DownloadController.php" to """
                        <?php
                        class DownloadController extends Controller {
                          public function show(int ${'$'}fileId): DataDownloadResponse { return new DataDownloadResponse(); }
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
        val paths = JSONObject(contract.document).getJSONObject("paths")

        assertEquals(setOf("/apps/example/cards/{cardId}"), paths.keySet())
        assertEquals(setOf("get"), paths.getJSONObject("/apps/example/cards/{cardId}").keySet())
    }

    @Test
    fun `typed OpenAPI and verified routes merge with conservative collection fallback links`() {
        val typed = VerifiedPackageContract(
            appId = "example",
            appVersion = "1.0.0",
            specFile = "openapi.json",
            document = """
                {
                  "openapi":"3.0.3",
                  "paths":{
                    "/ocs/v2.php/apps/example/widget/list":{"get":{
                      "operationId":"widget-api-list",
                      "parameters":[],
                      "responses":{"200":{"content":{"application/json":{"schema":{"type":"array","items":{"type":"object"}}}}}}
                    }},
                    "/ocs/v2.php/apps/example/gadgets":{"get":{
                      "operationId":"gadget-api-list",
                      "parameters":[{"name":"ownerId","in":"query","required":true,"schema":{"type":"integer"}}],
                      "responses":{"200":{"content":{"application/json":{"schema":{"type":"array","items":{"type":"object"}}}}}}
                    }},
                    "/apps/example/status":{"get":{"operationId":"status","responses":{"200":{"description":"ok"}}}},
                    "/apps/example/widgets":{"post":{"operationId":"create-widget","responses":{"200":{"description":"ok"}}}}
                  }
                }
            """.trimIndent(),
        )
        val routes = VerifiedPackageContract(
            appId = "example",
            appVersion = "1.0.0",
            specFile = "appinfo/routes.php",
            contractKind = VerifiedContractKind.VerifiedReadRoutes,
            document = """
                {
                  "openapi":"3.0.3",
                  "paths":{
                    "/apps/example/widgets":{"get":{
                      "operationId":"route.widgets.index",
                      "parameters":[],
                      "responses":{"200":{"content":{"application/json":{"schema":{"type":"array","items":{"type":"object"}}}}}}
                    }},
                    "/apps/example/gadgets":{"get":{
                      "operationId":"route.gadgets.index",
                      "parameters":[],
                      "responses":{"200":{"content":{"application/json":{"schema":{"type":"array","items":{"type":"object"}}}}}}
                    }},
                    "/apps/example/status":{"get":{"operationId":"route.status.index","responses":{"200":{"description":"ok"}}}}
                  }
                }
            """.trimIndent(),
        )

        val merged = mergeOpenApiWithVerifiedReadRoutes(typed, routes)
        val document = JSONObject(merged.document)
        val paths = document.getJSONObject("paths")
        val preferred = paths.getJSONObject("/ocs/v2.php/apps/example/widget/list").getJSONObject("get")
        val widgetFallback = paths.getJSONObject("/apps/example/widgets").getJSONObject("get")
        val gadgetFallback = paths.getJSONObject("/apps/example/gadgets").getJSONObject("get")

        assertEquals(VerifiedContractKind.OpenApiWithVerifiedReadRoutes, merged.contractKind)
        assertTrue(paths.getJSONObject("/apps/example/widgets").has("post"))
        assertEquals("route.widgets.index", preferred.getJSONArray(READ_FALLBACKS_EXTENSION).getString(0))
        assertEquals("widget-api-list", widgetFallback.getString(FALLBACK_FOR_OPERATION_EXTENSION))
        assertTrue(widgetFallback.getBoolean(VERIFIED_READ_ROUTE_EXTENSION))
        assertFalse(gadgetFallback.has(FALLBACK_FOR_OPERATION_EXTENSION))
        assertEquals("status", paths.getJSONObject("/apps/example/status").getJSONObject("get").getString("operationId"))
        assertEquals(2, document.getInt("x-nextcloud-native-verified-read-route-count"))
        assertEquals(1, document.getInt("x-nextcloud-native-linked-read-fallback-count"))
    }

    @Test
    fun `Mail 5_10_9 excerpts link only the parameter-equivalent account collection`() {
        fun fixture(name: String): String = assertNotNull(javaClass.classLoader.getResource(name)).readText()
        val routes = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "mail",
                appVersion = "5.10.9",
                files = mapOf(
                    "mail/appinfo/routes.php" to fixture("fixtures/mail-5.10.9-routes-excerpt.php.txt").encodeToByteArray(),
                    "mail/lib/Controller/AccountsController.php" to fixture("fixtures/mail-5.10.9-accounts-controller-excerpt.php.txt").encodeToByteArray(),
                    "mail/lib/Controller/MailboxesController.php" to fixture("fixtures/mail-5.10.9-mailboxes-controller-excerpt.php.txt").encodeToByteArray(),
                    "mail/lib/Controller/MessagesController.php" to fixture("fixtures/mail-5.10.9-messages-controller-excerpt.php.txt").encodeToByteArray(),
                ),
            ),
        )
        val typed = VerifiedPackageContract(
            appId = "mail",
            appVersion = "5.10.9",
            specFile = "openapi.json",
            document = fixture("fixtures/mail-5.10.9-openapi-read-excerpt.json"),
        )

        val document = JSONObject(mergeOpenApiWithVerifiedReadRoutes(typed, routes).document)
        val paths = document.getJSONObject("paths")
        val account = paths.getJSONObject("/ocs/v2.php/apps/mail/account/list").getJSONObject("get")
        val mailboxes = paths.getJSONObject("/ocs/v2.php/apps/mail/ocs/mailboxes").getJSONObject("get")
        val messages = paths.getJSONObject("/ocs/v2.php/apps/mail/ocs/mailboxes/{id}/messages").getJSONObject("get")

        assertEquals(listOf("route.accounts.index"), account.getJSONArray(READ_FALLBACKS_EXTENSION).toList())
        assertFalse(mailboxes.has(READ_FALLBACKS_EXTENSION))
        assertTrue(
            messages.has(READ_FALLBACKS_EXTENSION),
            "message=${messages} fallback=${paths.optJSONObject("/apps/mail/api/messages")}",
        )
        assertEquals(listOf("route.messages.index"), messages.getJSONArray(READ_FALLBACKS_EXTENSION).toList())
        assertTrue(paths.getJSONObject("/apps/mail/api/accounts").getJSONObject("get")
            .getBoolean(VERIFIED_READ_ROUTE_EXTENSION))
        assertTrue(paths.getJSONObject("/apps/mail/api/accounts/{id}").getJSONObject("get")
            .getBoolean(VERIFIED_READ_ROUTE_EXTENSION))
        val messageFallback = paths.getJSONObject("/apps/mail/api/messages").getJSONObject("get")
        assertTrue(messageFallback.getBoolean(VERIFIED_READ_ROUTE_EXTENSION))
        val messageParameters = messageFallback.getJSONArray("parameters")
        assertEquals(
            listOf("mailboxId", "cursor", "filter", "limit", "view", "v", "OCS-APIRequest"),
            buildList {
                repeat(messageParameters.length()) { index ->
                    add(messageParameters.getJSONObject(index).getString("name"))
                }
            },
        )
        assertTrue(messageParameters.getJSONObject(0).getBoolean("required"))
        assertEquals("header", messageParameters.getJSONObject(messageParameters.length() - 1).getString("in"))
        val sync = paths.getJSONObject("/apps/mail/api/mailboxes/{id}/sync").getJSONObject("post")
        assertEquals("refresh", sync.getString(OPERATIONAL_ACTION_EXTENSION))
        assertTrue(sync.getBoolean(VERIFIED_READ_ROUTE_EXTENSION))
        assertEquals("integer", sync.getJSONArray("parameters").getJSONObject(0)
            .getJSONObject("schema").getString("type"))
        val move = paths.getJSONObject("/apps/mail/api/messages/{id}/move").getJSONObject("post")
        assertTrue(move.getBoolean(VERIFIED_STATIC_WRITE_EXTENSION))
        assertEquals("messages", move.getString("x-nextcloud-native-resource-id"))
        assertEquals(
            setOf("destFolderId"),
            move.getJSONObject("requestBody")
                .getJSONObject("content")
                .getJSONObject("application/json")
                .getJSONObject("schema")
                .getJSONObject("properties")
                .keySet(),
        )
        val flags = paths.getJSONObject("/apps/mail/api/messages/{id}/flags").getJSONObject("put")
        val flagsSchema = flags.getJSONObject("requestBody")
            .getJSONObject("content")
            .getJSONObject("application/json")
            .getJSONObject("schema")
            .getJSONObject("properties")
            .getJSONObject("flags")
        assertEquals("object", flagsSchema.getString("type"))
        assertEquals("boolean", flagsSchema.getJSONObject("additionalProperties").getString("type"))
        assertTrue(flagsSchema.getBoolean("x-nextcloud-native-boolean-map"))
        assertTrue(
            paths.getJSONObject("/apps/mail/api/messages/{id}").getJSONObject("delete")
                .getBoolean(VERIFIED_STATIC_WRITE_EXTENSION),
        )
    }

    @Test
    fun `verified Music routes expose collections and typed descendant settings setters`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "music",
                appVersion = "3.1.1",
                files = mapOf(
                    "music/appinfo/routes.php" to """
                        <?php return ['routes' => [
                            ['name' => 'shiva_api#artists', 'url' => '/api/artists', 'verb' => 'GET'],
                            ['name' => 'shiva_api#albums', 'url' => '/api/albums', 'verb' => 'GET'],
                            ['name' => 'shiva_api#tracks', 'url' => '/api/tracks', 'verb' => 'GET'],
                            ['name' => 'playlist_api#get_all', 'url' => '/api/playlists', 'verb' => 'GET'],
                            ['name' => 'setting#get_all', 'url' => '/api/settings', 'verb' => 'GET'],
                            ['name' => 'setting#user_path', 'url' => '/api/settings/user/path', 'verb' => 'POST'],
                            ['name' => 'setting#enable_scan_metadata', 'url' => '/api/settings/user/enable_scan_metadata', 'verb' => 'POST'],
                            ['name' => 'setting#set_credentials', 'url' => '/api/settings/user/credentials', 'verb' => 'POST'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "music/lib/Controller/ShivaApiController.php" to """
                        <?php class ShivaApiController extends ApiController {
                            public function artists() {}
                            public function albums(?int ${'$'}artist=null, string|int|bool|null ${'$'}fulltree=null, ?int ${'$'}page_size=null, ?int ${'$'}page=null) {}
                            public function tracks(?int ${'$'}artist=null, ?int ${'$'}album=null, string|int|bool|null ${'$'}fulltree=null, ?int ${'$'}page_size=null, ?int ${'$'}page=null) {}
                        }
                    """.trimIndent().encodeToByteArray(),
                    "music/lib/Controller/PlaylistApiController.php" to """
                        <?php class PlaylistApiController extends ApiController { public function getAll() {} }
                    """.trimIndent().encodeToByteArray(),
                    "music/lib/Controller/SettingController.php" to """
                        <?php class SettingController extends ApiController {
                            public function getAll() {}
                            public function userPath(string ${'$'}value) {}
                            public function enableScanMetadata(bool ${'$'}value) {}
                            public function setCredentials(string ${'$'}value) {}
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )

        val paths = JSONObject(contract.document).getJSONObject("paths")
        assertTrue(paths.has("/apps/music/api/artists"))
        assertTrue(paths.has("/apps/music/api/albums"))
        assertTrue(paths.has("/apps/music/api/tracks"))
        assertTrue(paths.has("/apps/music/api/playlists"))
        assertTrue(paths.has("/apps/music/api/settings"))
        val pathSetter = paths.getJSONObject("/apps/music/api/settings/user/path").getJSONObject("post")
        val pathSchema = pathSetter.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals("settings", pathSetter.getString("x-nextcloud-native-resource-id"))
        assertEquals("value", pathSchema.getJSONObject("properties").getJSONObject("path")
            .getString("x-nextcloud-native-wire-name"))
        assertTrue(pathSchema.getJSONArray("required").toList().contains("path"))
        val scanSchema = paths.getJSONObject("/apps/music/api/settings/user/enable_scan_metadata")
            .getJSONObject("post").getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals("boolean", scanSchema.getJSONObject("properties").getJSONObject("scanMetadata")
            .getString("type"))
        assertFalse(paths.has("/apps/music/api/settings/user/credentials"))
        assertEquals(
            "array",
            paths.getJSONObject("/apps/music/api/artists").getJSONObject("get")
                .getJSONObject("responses").getJSONObject("200").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema").getString("type"),
        )
        val trackParameters = paths.getJSONObject("/apps/music/api/tracks").getJSONObject("get")
            .getJSONArray("parameters")
        assertEquals(
            listOf("artist", "album", "page_size", "page", "OCS-APIRequest"),
            buildList {
                repeat(trackParameters.length()) { index -> add(trackParameters.getJSONObject(index).getString("name")) }
            },
        )
        assertEquals(
            "object",
            paths.getJSONObject("/apps/music/api/settings").getJSONObject("get")
                .getJSONObject("responses").getJSONObject("200").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema").getString("type"),
        )
    }

    @Test
    fun `verified Cookbook config exposes only its paired observed settings write`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "cookbook",
                appVersion = "0.11.9",
                files = mapOf(
                    "cookbook/appinfo/routes.php" to """
                        <?php return ['routes' => [
                            ['name' => 'recipe_api#list', 'url' => '/api/v1/recipes', 'verb' => 'GET'],
                            ['name' => 'config_api#list', 'url' => '/api/v1/config', 'verb' => 'GET'],
                            ['name' => 'config_api#config', 'url' => '/api/v1/config', 'verb' => 'POST'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "cookbook/lib/Controller/RecipeApiController.php" to """
                        <?php class RecipeApiController extends ApiController { public function list() {} }
                    """.trimIndent().encodeToByteArray(),
                    "cookbook/lib/Controller/ConfigApiController.php" to """
                        <?php class ConfigApiController extends ApiController { public function list() {} public function config() {} }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )

        val paths = JSONObject(contract.document).getJSONObject("paths")
        assertTrue(paths.has("/apps/cookbook/api/v1/recipes"))
        assertTrue(paths.has("/apps/cookbook/api/v1/config"))
        assertTrue(paths.getJSONObject("/apps/cookbook/api/v1/config").has("get"))
        val configWrite = paths.getJSONObject("/apps/cookbook/api/v1/config").getJSONObject("post")
        assertTrue(
            configWrite.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema")
                .getBoolean("x-nextcloud-native-observed-settings-body"),
        )
    }

    @Test
    fun `live dynamic app contract audit emits sanitized ladder outcomes`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_AUDIT") != "1") return
        val targets = listOf(
            "mail" to "5.10.9",
            "cospend" to "4.0.2",
            "deck" to "1.18.2",
            "tables" to "2.2.0",
            "budget" to "2.39.1",
            "chores" to "0.1.0",
            "music" to "3.1.1",
            "contacts" to "8.7.4",
            "calendar" to "6.5.1",
            "richdocuments" to "11.0.1",
            "tasks" to "0.18.1",
        )
        val acquirer = SignedAppStoreContractAcquirer()
        targets.forEach { (appId, installedVersion) ->
            val result = runCatching {
                acquirer.acquire(ContractAcquisitionRequest(appId, "34.0.1", installedVersion))
            }
            val contract = result.getOrNull()
            val outcome = when {
                result.isFailure -> "error:${result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}"
                contract == null -> "metadata-only:no-compatible-contract"
                else -> "success:${contract.sourceKind.name}:${contract.contractVersion}:${contract.specFile.substringAfterLast('/')}"
            }
            println("contract-audit app=$appId installed=$installedVersion outcome=$outcome")
        }
    }

    @Test
    fun `release selection uses semantic order rather than catalog order`() {
        val selected = selectRelease(
            catalogJson = catalog(
                release("3.0.4"),
                release("3.0.11"),
                release("4.0.0-beta.1"),
                release("3.2.0"),
            ),
            request = ContractAcquisitionRequest("cospend", "34.0.1"),
            catalogUrl = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json",
        )

        assertEquals("3.2.0", selected?.version)
    }

    @Test
    fun `exact installed version wins and nightlies are excluded`() {
        val selected = selectRelease(
            catalogJson = catalog(
                release("4.0.2", nightly = true),
                release("4.0.2"),
                release("4.0.0"),
            ),
            request = ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2"),
            catalogUrl = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json",
        )

        assertEquals("4.0.2", selected?.version)
        assertEquals("sha512", selected?.signatureDigest)
    }

    @Test
    fun `exact installed prerelease can be selected without admitting nightlies`() {
        val selected = selectRelease(
            catalogJson = catalog(
                release("4.1.0-beta.1"),
                release("4.1.0-beta.1", nightly = true),
                release("4.0.2"),
            ),
            request = ContractAcquisitionRequest("cospend", "34.0.1", "4.1.0-beta.1"),
            catalogUrl = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json",
        )

        assertEquals("4.1.0-beta.1", selected?.version)
    }

    @Test
    fun `missing app returns no release`() {
        val selected = selectRelease(
            catalogJson = catalog(release("4.0.2")),
            request = ContractAcquisitionRequest("deck", "34.0.1"),
            catalogUrl = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json",
        )

        assertNull(selected)
    }

    @Test
    fun `acquirer fetches compatible catalog and passes package through verifier`() {
        MockWebServer().use { server ->
            server.start()
            val archive = "signed-package".encodeToByteArray()
            server.enqueue(MockResponse(body = catalog(release("4.0.2", server.url("package.tar.gz").toString()))))
            server.enqueue(MockResponse(body = archive.decodeToString()))
            val verifier = RecordingVerifier()
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = verifier,
            )

            val contract = acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2"))

            assertEquals("cospend", contract?.appId)
            assertEquals("4.0.2", contract?.appVersion)
            assertEquals("openapi.json", contract?.specFile)
            assertEquals(OpenApiContractSourceKind.SignedAppPackage, contract?.sourceKind)
            assertEquals(archive.toList(), verifier.archive?.toList())
            assertEquals("/api/v1/platform/34.0.1/apps.json", server.takeRequest().url.encodedPath)
            assertEquals("/package.tar.gz", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `server build suffix uses normalized three component platform catalog`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(release("4.0.2", server.url("package.tar.gz").toString()))))
            server.enqueue(MockResponse(body = "signed-package"))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = RecordingVerifier(),
            )

            val contract = acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1.2+vendor", "4.0.2"))

            assertEquals("4.0.2", contract?.contractVersion)
            assertEquals("/api/v1/platform/34.0.1/apps.json", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `vendor suffixed installed version uses signed same patch contract as compatible`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(release("4.0.2", server.url("package.tar.gz").toString()))))
            server.enqueue(MockResponse(body = catalog(release("4.0.2", server.url("package.tar.gz").toString()))))
            server.enqueue(MockResponse(body = "signed-package"))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = RecordingVerifier(),
            )

            val contract = acquirer.acquire(
                ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2+vendor.1"),
            )

            assertEquals("4.0.2+vendor.1", contract?.appVersion)
            assertEquals("4.0.2", contract?.contractVersion)
            assertEquals(OpenApiContractSourceKind.SignedCompatibleAppPackage, contract?.sourceKind)
        }
    }

    @Test
    fun `patch drift prefers nearest older signed compatible release`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(
                release("4.0.5", server.url("newer.tar.gz").toString()),
                release("4.0.3", server.url("older.tar.gz").toString()),
                release("4.1.0", server.url("other-line.tar.gz").toString()),
            )))
            server.enqueue(MockResponse(body = catalog(release("4.0.5"), release("4.0.3"))))
            server.enqueue(MockResponse(body = "signed-compatible-package"))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = RecordingVerifier(),
            )

            val contract = acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "4.0.4"))

            assertEquals("4.0.4", contract?.appVersion)
            assertEquals("4.0.3", contract?.contractVersion)
            assertEquals(OpenApiContractSourceKind.SignedCompatibleAppPackage, contract?.sourceKind)
            val paths = List(server.requestCount) { server.takeRequest().url.encodedPath }
            assertEquals("/older.tar.gz", paths[2])
        }
    }

    @Test
    fun `no matching app version line returns metadata eligible null without downloading a package`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(release("4.0.3"), release("5.1.2"))))
            server.enqueue(MockResponse(body = catalog(release("4.0.3"), release("5.1.2"))))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = RecordingVerifier(),
            )

            assertNull(acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "5.2.0")))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `compatible signed package precedes exact unsigned tag fallback`() {
        MockWebServer().use { server ->
            server.start()
            val exactDownload = server.url(
                "owner/repo/releases/download/v4.0.4/cospend-4.0.4.tar.gz",
            ).toString()
            server.enqueue(MockResponse(body = catalogWithWebsite(
                website = "https://github.com/owner/repo",
                releases = arrayOf(
                    release("4.0.4", exactDownload),
                    release("4.0.3", server.url("compatible.tar.gz").toString()),
                ),
            )))
            server.enqueue(MockResponse(body = "exact-without-openapi"))
            server.enqueue(MockResponse(body = "compatible-with-openapi"))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = ExactMissingVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            val contract = acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "4.0.4"))

            assertEquals(OpenApiContractSourceKind.SignedCompatibleAppPackage, contract?.sourceKind)
            assertEquals("4.0.3", contract?.contractVersion)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `missing package spec falls back to exact App Store linked GitHub tag`() {
        MockWebServer().use { server ->
            server.start()
            val download = server.url(
                "source-owner/source-repo/releases/download/v4.0.2/cospend-4.0.2.tar.gz",
            ).toString()
            server.enqueue(MockResponse(body = catalogWithWebsite(
                website = "https://github.com/source-owner/source-repo",
                release = release("4.0.2", download),
            )))
            server.enqueue(MockResponse(body = "signed-package-without-openapi"))
            server.enqueue(MockResponse(body = appInfo("cospend", "4.0.2")))
            server.enqueue(MockResponse(body = githubTree("openapi-client.json")))
            server.enqueue(MockResponse(body = """{"openapi":"3.1.0","paths":{}}"""))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = MissingContractVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            val contract = acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2"))

            assertEquals(OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag, contract?.sourceKind)
            assertEquals("openapi-client.json", contract?.specFile)
            assertEquals("4.0.2", contract?.appVersion)
            assertEquals(
                server.url("raw/source-owner/source-repo/v4.0.2/openapi-client.json").toString(),
                contract?.sourceUrl,
            )
            assertEquals(
                listOf(
                    "/api/v1/platform/34.0.1/apps.json",
                    "/source-owner/source-repo/releases/download/v4.0.2/cospend-4.0.2.tar.gz",
                    "/raw/source-owner/source-repo/v4.0.2/appinfo/info.xml",
                    "/github-api/repos/source-owner/source-repo/git/trees/v4.0.2",
                    "/raw/source-owner/source-repo/v4.0.2/openapi-client.json",
                ),
                List(server.requestCount) { server.takeRequest().url.encodedPath },
            )
        }
    }

    @Test
    fun `verified package routes are enriched by exact linked OpenAPI instead of ending discovery`() {
        MockWebServer().use { server ->
            server.start()
            val download = server.url(
                "source-owner/source-repo/releases/download/v0.11.9/app-0.11.9.tar.gz",
            ).toString()
            server.enqueue(MockResponse(body = catalogWithWebsite(
                website = "https://github.com/source-owner/source-repo",
                release = release("0.11.9", download),
            )))
            server.enqueue(MockResponse(body = "signed-package-with-static-routes"))
            server.enqueue(MockResponse(body = appInfo("cospend", "0.11.9")))
            server.enqueue(MockResponse(body = githubTree("docs/api/openapi-client.json")))
            server.enqueue(MockResponse(body = """
                {
                  "openapi":"3.0.3",
                  "info":{"title":"Recipes","version":"0.1.3"},
                  "paths":{
                    "/apps/cospend/api/v1/recipes":{
                      "get":{"operationId":"listRecipes","responses":{"200":{"description":"ok"}}},
                      "post":{
                        "operationId":"newRecipe",
                        "requestBody":{"content":{"application/json":{"schema":{"type":"object"}}}},
                        "responses":{"200":{"description":"ok"}}
                      }
                    },
                    "/apps/cospend/api/v1/recipes/{id}":{
                      "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                      "get":{"operationId":"recipeDetails","responses":{"200":{"description":"ok"}}},
                      "put":{
                        "operationId":"updateRecipe",
                        "requestBody":{"content":{"application/json":{"schema":{"type":"object"}}}},
                        "responses":{"200":{"description":"ok"}}
                      },
                      "delete":{"operationId":"deleteRecipe","responses":{"200":{"description":"ok"}}}
                    }
                  }
                }
            """.trimIndent()))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = VerifiedRouteOnlyContractVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            val contract = assertNotNull(
                acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "0.11.9")),
            )
            val recipeItem = JSONObject(contract.document).getJSONObject("paths")
                .getJSONObject("/apps/cospend/api/v1/recipes/{id}")

            assertEquals(OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag, contract.sourceKind)
            assertTrue(recipeItem.has("put"))
            assertTrue(recipeItem.has("delete"))
            assertEquals(
                listOf(
                    "/api/v1/platform/34.0.1/apps.json",
                    "/source-owner/source-repo/releases/download/v0.11.9/app-0.11.9.tar.gz",
                    "/raw/source-owner/source-repo/v0.11.9/appinfo/info.xml",
                    "/github-api/repos/source-owner/source-repo/git/trees/v0.11.9",
                    "/raw/source-owner/source-repo/v0.11.9/docs/api/openapi-client.json",
                ),
                List(server.requestCount) { server.takeRequest().url.encodedPath },
            )
        }
    }

    @Test
    fun `nested OpenAPI YAML is normalized from an exact linked source tag`() {
        MockWebServer().use { server ->
            server.start()
            val download = server.url(
                "source-owner/source-repo/releases/download/v0.11.9/app-0.11.9.tar.gz",
            ).toString()
            server.enqueue(MockResponse(body = catalogWithWebsite(
                website = "https://github.com/source-owner/source-repo",
                release = release("0.11.9", download),
            )))
            server.enqueue(MockResponse(body = "signed-package-without-openapi"))
            server.enqueue(MockResponse(body = appInfo("cospend", "0.11.9")))
            server.enqueue(MockResponse(body = githubTree(
                "docs/dev/api/0.1.2/openapi-client.yaml",
                "docs/dev/api/0.1.3/openapi-client.yaml",
            )))
            server.enqueue(MockResponse(body = openApiYaml("0.1.2", "/old")))
            server.enqueue(MockResponse(body = openApiYaml("0.1.3", "/new")))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = MissingContractVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            val contract = acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "0.11.9"))

            assertEquals("docs/dev/api/0.1.3/openapi-client.yaml", contract?.specFile)
            val parsed = JSONObject(contract?.document.orEmpty())
            assertTrue(parsed.getJSONObject("paths").has("/new"))
        }
    }

    @Test
    fun `same tag sibling YAML schemas are bundled without a repository adapter`() {
        MockWebServer().use { server ->
            server.start()
            val download = server.url(
                "source-owner/source-repo/releases/download/v0.11.9/app-0.11.9.tar.gz",
            ).toString()
            server.enqueue(MockResponse(body = catalogWithWebsite(
                website = "https://github.com/source-owner/source-repo#readme",
                release = release("0.11.9", download),
            )))
            server.enqueue(MockResponse(body = "signed-package-without-openapi"))
            server.enqueue(MockResponse(body = appInfo("cospend", "0.11.9")))
            server.enqueue(MockResponse(body = githubTree("docs/api/openapi-client.yaml")))
            server.enqueue(MockResponse(body = """
                openapi: 3.0.1
                info: { title: Example, version: 1.0.0 }
                components:
                  schemas:
                    Recipe:
                      ${'$'}ref: objects.yaml#/Recipe
                paths:
                  /recipes/{id}:
                    get:
                      operationId: getRecipe
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                ${'$'}ref: '#/components/schemas/Recipe'
            """.trimIndent()))
            server.enqueue(MockResponse(body = """
                Recipe:
                  type: object
                  required: [id, name]
                  properties:
                    id: { type: integer }
                    name: { type: string }
                    ingredients: { type: array, items: { type: string } }
            """.trimIndent()))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = MissingContractVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            val contract = assertNotNull(
                acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "0.11.9")),
            )
            val recipe = JSONObject(contract.document)
                .getJSONObject("components")
                .getJSONObject("schemas")
                .getJSONObject("Recipe")

            assertTrue(recipe.getJSONObject("properties").has("ingredients"))
            val paths = List(server.requestCount) { server.takeRequest().url.encodedPath }
            assertEquals(
                "/raw/source-owner/source-repo/v0.11.9/docs/api/objects.yaml",
                paths[5],
            )
        }
    }

    @Test
    fun `compatible linked tag is used only after signed compatible package has no contract`() {
        MockWebServer().use { server ->
            server.start()
            val download = server.url(
                "source-owner/source-repo/releases/download/v4.0.3/app-4.0.3.tar.gz",
            ).toString()
            val catalog = catalogWithWebsite(
                website = "https://github.com/source-owner/source-repo",
                release = release("4.0.3", download),
            )
            server.enqueue(MockResponse(body = catalog))
            server.enqueue(MockResponse(body = catalog))
            server.enqueue(MockResponse(body = "signed-package-without-openapi"))
            server.enqueue(MockResponse(body = appInfo("cospend", "4.0.3")))
            server.enqueue(MockResponse(body = githubTree("openapi.json")))
            server.enqueue(MockResponse(body = """{"openapi":"3.1.0","paths":{}}"""))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = MissingContractVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            val contract = acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "4.0.4"))

            assertEquals(OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag, contract?.sourceKind)
            assertEquals("4.0.4", contract?.appVersion)
            assertEquals("4.0.3", contract?.contractVersion)
        }
    }

    @Test
    fun `fallback rejects a tag whose app metadata does not match the selected release`() {
        MockWebServer().use { server ->
            server.start()
            val download = server.url(
                "source-owner/source-repo/releases/download/v4.0.2/cospend-4.0.2.tar.gz",
            ).toString()
            server.enqueue(MockResponse(body = catalogWithWebsite(
                website = "https://github.com/source-owner/source-repo",
                release = release("4.0.2", download),
            )))
            server.enqueue(MockResponse(body = "signed-package-without-openapi"))
            server.enqueue(MockResponse(body = appInfo("different_app", "4.0.2")))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = MissingContractVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            assertNull(acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2")))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `signature verification failures never downgrade to GitHub fallback`() {
        MockWebServer().use { server ->
            server.start()
            val download = server.url(
                "source-owner/source-repo/releases/download/v4.0.2/cospend-4.0.2.tar.gz",
            ).toString()
            server.enqueue(MockResponse(body = catalogWithWebsite(
                website = "https://github.com/source-owner/source-repo",
                release = release("4.0.2", download),
            )))
            server.enqueue(MockResponse(body = "tampered-package"))
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = RejectingVerifier(),
                rawGitHubBaseUrl = server.url("raw").toString(),
                githubApiBaseUrl = server.url("github-api").toString(),
            )

            assertFailsWith<SecurityException> {
                acquirer.acquire(ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `repository candidates are derived only from exact GitHub metadata and release URLs`() {
        val selected = selectRelease(
            catalogJson = catalogWithWebsite(
                website = "https://gitlab.example/owner/repository",
                release = release("4.0.2", "https://downloads.example/cospend-4.0.2.tar.gz"),
            ),
            request = ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2"),
            catalogUrl = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json",
        )

        assertTrue(selected?.githubSources.orEmpty().isEmpty())
    }

    @Test
    fun `app metadata parser rejects doctypes and duplicate identities`() {
        assertFailsWith<Exception> {
            parseAppInfoIdentity(
                """<!DOCTYPE info [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><info><id>&xxe;</id><version>1.0.0</version></info>""",
            )
        }
        assertFailsWith<IllegalStateException> {
            parseAppInfoIdentity("<info><id>one</id><id>two</id><version>1.0.0</version></info>")
        }
    }

    @Test
    fun `app metadata identity ignores nested navigation ids`() {
        assertEquals(
            "cospend" to "4.0.2",
            parseAppInfoIdentity(
                """
                    <info>
                        <id>cospend</id>
                        <version>4.0.2</version>
                        <navigations><navigation><id>cospend</id></navigation></navigations>
                    </info>
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `verified contract is reused during the application session`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(release("4.0.2", server.url("package.tar.gz").toString()))))
            server.enqueue(MockResponse(body = "signed-package"))
            val verifier = RecordingVerifier()
            val acquirer = SignedAppStoreContractAcquirer(
                httpClient = OkHttpClient(),
                appStoreBaseUrl = server.url("api/v1").toString(),
                requireHttps = false,
                trustVerifier = verifier,
            )
            val request = ContractAcquisitionRequest("cospend", "34.0.1", "4.0.2")

            val first = acquirer.acquire(request)
            val second = acquirer.acquire(request)

            assertSame(first, second)
            assertEquals(2, server.requestCount)
        }
    }

    private class RecordingVerifier : AppPackageTrustVerifier {
        var archive: ByteArray? = null

        override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
            this.archive = archive
            return VerifiedPackageContract(
                appId = release.appId,
                appVersion = release.version,
                specFile = "openapi.json",
                document = """{"openapi":"3.0.3","paths":{}}""",
            )
        }
    }

    private class MissingContractVerifier : AppPackageTrustVerifier {
        override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
            throw OpenApiContractMissingException("signed package has no OpenAPI")
        }
    }

    private class VerifiedRouteOnlyContractVerifier : AppPackageTrustVerifier {
        override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract =
            VerifiedPackageContract(
                appId = release.appId,
                appVersion = release.version,
                specFile = "appinfo/routes.php",
                document = """
                    {
                      "openapi":"3.0.3",
                      "info":{"title":"verified routes","version":"${release.version}"},
                      "paths":{
                        "/apps/${release.appId}/api/v1/config":{
                          "get":{
                            "operationId":"route.config.list",
                            "responses":{"200":{"description":"ok"}}
                          }
                        }
                      }
                    }
                """.trimIndent(),
                contractKind = VerifiedContractKind.VerifiedReadRoutes,
            )
    }

    private class RejectingVerifier : AppPackageTrustVerifier {
        override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
            throw SecurityException("signature rejected")
        }
    }

    private class ExactMissingVerifier : AppPackageTrustVerifier {
        override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
            if (release.version == "4.0.4") {
                throw OpenApiContractMissingException("exact package has no OpenAPI")
            }
            return VerifiedPackageContract(
                appId = release.appId,
                appVersion = release.version,
                specFile = "openapi.json",
                document = """{"openapi":"3.0.3","paths":{}}""",
            )
        }
    }

    private fun catalog(vararg releases: String): String =
        """[{"id":"cospend","certificate":"certificate","releases":[${releases.joinToString()}]}]"""

    private fun catalogWithWebsite(website: String, release: String): String =
        """[{"id":"cospend","website":"$website","certificate":"certificate","releases":[$release]}]"""

    private fun catalogWithWebsite(website: String, releases: Array<String>): String =
        """[{"id":"cospend","website":"$website","certificate":"certificate","releases":[${releases.joinToString()}]}]"""

    private fun appInfo(appId: String, version: String): String = """
        <?xml version="1.0"?>
        <info>
          <id>$appId</id>
          <name>Example</name>
          <version>$version</version>
        </info>
    """.trimIndent()

    private fun githubTree(vararg paths: String): String = JSONObject()
        .put("truncated", false)
        .put(
            "tree",
            paths.map { path -> JSONObject().put("path", path).put("type", "blob") },
        )
        .toString()

    private fun openApiYaml(version: String, path: String): String = """
        openapi: 3.0.1
        info:
          title: Example
          version: $version
        paths:
          $path:
            get:
              operationId: listExample
              responses:
                '200':
                  description: ok
    """.trimIndent()

    private fun release(
        version: String,
        download: String = "https://downloads.example/cospend-$version.tar.gz",
        nightly: Boolean = false,
    ): String =
        """{"version":"$version","download":"$download","signature":"${Base64.getEncoder().encodeToString("signature".encodeToByteArray())}","signatureDigest":"sha512","isNightly":$nightly}"""
}
