package dev.obiente.nextcloudnative.contracts

import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.contract
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.document
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.jsonBody
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.operation
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.parameter
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.paths
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.staticRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/** Policy attributes, legacy annotations, hidden request inputs, and the merge hint. Synthetic fixtures. */
class StaticPolicyAndInputContractTest {
    @Test
    fun `policy attributes become typed facts and absence of NoAdminRequired means admin only`() {
        val paths = paths(
            contract(
                "policy_x",
                "1.0.0",
                "lib/Controller/ItemController.php" to """
                    <?php
                    namespace OCA\PolicyX\Controller;
                    use OCP\AppFramework\OCSController;
                    use OCP\AppFramework\Http\Attribute\ApiRoute;
                    use OCP\AppFramework\Http\Attribute\NoAdminRequired;
                    use OCP\AppFramework\Http\Attribute\SubAdminRequired;
                    use OCP\AppFramework\Http\Attribute\PasswordConfirmationRequired;
                    use OCP\AppFramework\Http\Attribute\UserRateLimit;
                    use OCP\AppFramework\Http\Attribute\AnonRateLimit;
                    use OCP\AppFramework\Http\Attribute\OpenAPI;
                    use OCP\AppFramework\Http\Attribute\RequestHeader;
                    class ItemController extends OCSController {
                        #[ApiRoute(verb: 'GET', url: '/admin')]
                        public function admin(): DataResponse { return new DataResponse([]); }

                        #[SubAdminRequired]
                        #[ApiRoute(verb: 'GET', url: '/subadmin')]
                        public function subadmin(): DataResponse { return new DataResponse([]); }

                        // Shaped like Mail v5.12.6 MessageApiController::send rate limit.
                        #[NoAdminRequired]
                        #[UserRateLimit(limit: 5, period: 100)]
                        #[AnonRateLimit(limit: 1, period: 60)]
                        #[PasswordConfirmationRequired(strict: true)]
                        #[OpenAPI(scope: OpenAPI::SCOPE_IGNORE)]
                        #[RequestHeader(name: 'x-example', description: 'synthetic')]
                        #[ApiRoute(verb: 'GET', url: '/limited')]
                        public function limited(): DataResponse { return new DataResponse([]); }
                    }
                """,
            ),
        )
        assertEquals("admin", paths.policy("/ocs/v2.php/apps/policy_x/admin").getString("access"))
        assertEquals("subadmin", paths.policy("/ocs/v2.php/apps/policy_x/subadmin").getString("access"))
        val limited = paths.policy("/ocs/v2.php/apps/policy_x/limited")
        assertEquals("user", limited.getString("access"))
        assertEquals(5, limited.getJSONObject("userRateLimit").getInt("limit"))
        assertEquals(100, limited.getJSONObject("userRateLimit").getInt("period"))
        assertEquals(60, limited.getJSONObject("anonRateLimit").getInt("period"))
        assertTrue(limited.getJSONObject("passwordConfirmation").getBoolean("strict"))
        assertEquals("ignore", limited.getString("openApiScope"))
        assertEquals(listOf("x-example"), limited.getJSONArray("requestHeaders").toList())
        assertTrue(limited.getBoolean("csrfRequired"))
    }

    @Test
    fun `legacy docblock annotations are read like attributes`() {
        val paths = paths(
            contract(
                "legacy_x",
                "1.0.0",
                "appinfo/routes.php" to """
                    <?php return ['routes' => [
                        ['name' => 'item#index', 'url' => '/items', 'verb' => 'GET'],
                        ['name' => 'item#mine', 'url' => '/mine', 'verb' => 'GET'],
                    ]];
                """,
                "lib/Controller/ItemController.php" to """
                    <?php
                    class ItemController extends ApiController {
                        /**
                         * @NoAdminRequired
                         * @NoCSRFRequired
                         * @CORS
                         * @PasswordConfirmationRequired
                         * @UserRateThrottle(limit=10, period=60)
                         * @BruteForceProtection(action=listing)
                         */
                        public function index(): array { return []; }

                        /**
                         * @PublicPage
                         */
                        public function mine(): array { return []; }
                    }
                """,
            ),
        )
        val index = paths.policy("/apps/legacy_x/items")
        assertEquals("user", index.getString("access"))
        assertFalse(index.getBoolean("csrfRequired"))
        assertTrue(index.getBoolean("cors"))
        assertFalse(index.getJSONObject("passwordConfirmation").getBoolean("strict"))
        assertEquals(10, index.getJSONObject("userRateLimit").getInt("limit"))
        assertEquals(listOf("listing"), index.getJSONArray("bruteForceActions").toList())
        assertEquals("public", paths.policy("/apps/legacy_x/mine").getString("access"))
    }

    @Test
    fun `an app permission attribute is kept as an opaque name and literal arguments`() {
        // Shaped like Cospend v4.3.0 lib/Controller/ApiController.php #[CospendUserPermissions(minimumLevel: ...)].
        val paths = paths(
            contract(
                "ledger_x",
                "4.3.0",
                "lib/Controller/ApiController.php" to """
                    <?php
                    namespace OCA\LedgerX\Controller;
                    use OCA\LedgerX\AppInfo\Application;
                    use OCA\LedgerX\Attribute\LedgerUserPermissions;
                    use OCP\AppFramework\OCSController;
                    use OCP\AppFramework\Http\Attribute\ApiRoute;
                    use OCP\AppFramework\Http\Attribute\NoAdminRequired;
                    class ApiController extends OCSController {
                        #[NoAdminRequired]
                        #[LedgerUserPermissions(minimumLevel: Application::ACCESS_LEVEL_PARTICIPANT)]
                        #[ApiRoute(verb: 'GET', url: '/api/v1/projects/{projectId}/bills')]
                        public function getBills(string ${D}projectId): DataResponse { return new DataResponse([]); }
                    }
                """,
            ),
        )
        val attribute = paths.policy("/ocs/v2.php/apps/ledger_x/api/v1/projects/{projectId}/bills")
            .getJSONArray("appAttributes").getJSONObject(0)
        assertEquals("OCA\\LedgerX\\Attribute\\LedgerUserPermissions", attribute.getString("name"))
        assertEquals(
            "OCA\\LedgerX\\AppInfo\\Application::ACCESS_LEVEL_PARTICIPANT",
            attribute.getJSONObject("arguments").getString("minimumLevel"),
        )
    }

    @Test
    fun `a route placeholder read through getParam binds as that path parameter`() {
        // Shaped like Deck v1.19.1 lib/Controller/CardApiController.php assignLabel reading cardId from the request.
        val document = document(
            contract(
                "kanban_x",
                "1.19.1",
                "appinfo/routes.php" to """
                    <?php return ['routes' => [
                        ['name' => 'card_api#get', 'url' => '/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}', 'verb' => 'GET'],
                        ['name' => 'card_api#assignLabel', 'url' => '/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}/assignLabel', 'verb' => 'PUT'],
                        ['name' => 'card_api#removeLabel', 'url' => '/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}/removeLabel', 'verb' => 'PUT'],
                        ['name' => 'card_api#assignUser', 'url' => '/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}/assignUser', 'verb' => 'PUT'],
                    ]];
                """,
                "lib/Controller/CardApiController.php" to """
                    <?php
                    class CardApiController extends ApiController {
                        public function get(): DataResponse { return new DataResponse([]); }
                        /**
                         * @NoAdminRequired
                         */
                        public function assignLabel(int ${D}labelId): DataResponse {
                            ${D}this->cardService->assignLabel(${D}this->request->getParam('cardId'), ${D}labelId);
                            return new DataResponse([]);
                        }
                        public function removeLabel(int ${D}labelId): DataResponse {
                            ${D}this->cardService->removeLabel(${D}this->request->getParam('cardIdentifier'), ${D}labelId);
                            return new DataResponse([]);
                        }
                        public function assignUser(string ${D}userId): DataResponse {
                            ${D}this->cardService->assignUser(${D}this->request->getParams()['cardId'], ${D}userId);
                            return new DataResponse([]);
                        }
                    }
                """,
            ),
        )
        val paths = document.getJSONObject("paths")
        val assign = paths.operation("/apps/kanban_x/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}/assignLabel", "put")
        assertEquals("request-parameter", assign.parameter("cardId").getString(REQUEST_INPUT_EXTENSION))
        assertFalse(assign.parameter("boardId").has(REQUEST_INPUT_EXTENSION))
        assertEquals(setOf("labelId"), assign.jsonBody().getJSONObject("properties").keySet())
        // A key that is not a placeholder, or any other request access, keeps the rejection.
        assertFalse(paths.has("/apps/kanban_x/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}/removeLabel"))
        assertFalse(paths.has("/apps/kanban_x/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}/assignUser"))
        val rejected = document.staticRoute("put", "/apps/kanban_x/api/v1.0/boards/{boardId}/stacks/{stackId}/cards/{cardId}/assignUser")
        assertTrue(rejected.getJSONObject("requestInputs").getBoolean("unclassified"))
    }

    @Test
    fun `an uploaded file read stays route evidence and is never published as a write`() {
        val paths = paths(
            contract(
                "files_x",
                "1.0.0",
                "appinfo/routes.php" to """
                    <?php return ['routes' => [
                        ['name' => 'attachment#index', 'url' => '/cards/{cardId}/attachments', 'verb' => 'GET'],
                        ['name' => 'attachment#create', 'url' => '/cards/{cardId}/attachments', 'verb' => 'POST'],
                        ['name' => 'attachment#update', 'url' => '/cards/{cardId}/attachments/{attachmentId}', 'verb' => 'PUT'],
                        ['name' => 'attachment#createMany', 'url' => '/cards/{cardId}/attachments/many', 'verb' => 'POST'],
                    ]];
                """,
                "lib/Controller/AttachmentController.php" to """
                    <?php
                    class AttachmentController extends ApiController {
                        public function index(int ${D}cardId): array { return []; }
                        public function create(int ${D}cardId, string ${D}type, ?string ${D}data = null): DataResponse {
                            ${D}file = ${D}this->request->getUploadedFile('file');
                            return new DataResponse([]);
                        }
                        public function update(int ${D}cardId, int ${D}attachmentId): DataResponse {
                            ${D}file = ${D}this->request->getUploadedFile('file');
                            return new DataResponse([]);
                        }
                        public function createMany(int ${D}cardId): DataResponse {
                            ${D}this->request->getUploadedFile('first');
                            ${D}this->request->getUploadedFile('second');
                            return new DataResponse([]);
                        }
                    }
                """,
            ),
        )
        // The write path cannot verify a stored upload yet, so no upload is published.
        assertTrue(paths.getJSONObject("/apps/files_x/cards/{cardId}/attachments").has("get"))
        assertFalse(paths.getJSONObject("/apps/files_x/cards/{cardId}/attachments").has("post"))
        assertFalse(paths.has("/apps/files_x/cards/{cardId}/attachments/{attachmentId}"))
        assertFalse(paths.has("/apps/files_x/cards/{cardId}/attachments/many"))
    }

    @Test
    fun `all nullable null default update parameters record a merge hint, not a guarantee`() {
        // Shaped like Notes v6.1.0 lib/Controller/NotesApiController.php update().
        val document = document(
            contract(
                "jotter_x",
                "6.1.0",
                "appinfo/routes.php" to """
                    <?php return ['routes' => [
                        ['name' => 'notes_api#update', 'url' => '/api/v1/notes/{id}', 'verb' => 'PUT'],
                        ['name' => 'notes_api#updateStrict', 'url' => '/api/v1/strict/{id}', 'verb' => 'PUT'],
                    ]];
                """,
                "lib/Controller/NotesApiController.php" to """
                    <?php
                    class NotesApiController extends ApiController {
                        public function update(int ${D}id, ?string ${D}content = null, ?string ${D}category = null, ?bool ${D}favorite = null): DataResponse {
                            return new DataResponse([]);
                        }
                        public function updateStrict(int ${D}id, ?string ${D}content = null, string ${D}category = ''): DataResponse {
                            return new DataResponse([]);
                        }
                    }
                """,
            ),
        )
        val update = document.getJSONObject("paths").operation("/apps/jotter_x/api/v1/notes/{id}", "put")
        assertEquals(MERGE_HINT_KIND, update.getString(MERGE_HINT_EXTENSION))
        val content = update.jsonBody().getJSONObject("properties").getJSONObject("content")
        assertTrue(content.getBoolean("nullable"))
        assertFalse(update.jsonBody().has("required"))
        val strict = document.getJSONObject("paths").operation("/apps/jotter_x/api/v1/strict/{id}", "put")
        assertFalse(strict.has(MERGE_HINT_EXTENSION))
        assertEquals("", strict.jsonBody().getJSONObject("properties").getJSONObject("category").getString("default"))
    }

    @Test
    fun `an oversized docblock leaves annotations unknown rather than granting access`() {
        val oversized = "x".repeat(MAX_PHP_DOC_COMMENT_CHARS + 1)
        val paths = paths(
            contract(
                "huge_x",
                "1.0.0",
                "appinfo/routes.php" to "<?php return ['routes' => [['name' => 'item#index', 'url' => '/items', 'verb' => 'GET']]];",
                "lib/Controller/ItemController.php" to """
                    <?php
                    class ItemController extends ApiController {
                        /**
                         * @PublicPage
                         * $oversized
                         */
                        public function index(): array { return []; }
                    }
                """,
            ),
        )
        val policy = paths.policy("/apps/huge_x/items")
        assertEquals("admin", policy.getString("access"))
        assertTrue(policy.getBoolean("annotationsUnreadable"))
    }

    @Test
    fun `request input classification accepts only the exact forms`() {
        fun inputs(body: String): StaticRequestInputs =
            PhpLiteralLexer("<?php $body", declarations = true).tokens().staticRequestInputs()

        assertEquals(listOf("cardId"), inputs("${D}this->request->getParam('cardId');").parameters)
        assertEquals(listOf("cardId"), inputs("${D}this->request->getParam('cardId', null);").parameters)
        assertTrue(inputs("${D}this->request->getParam(${D}key);").unclassified)
        assertTrue(inputs("${D}this->request->getParam('a' . 'b');").unclassified)
        assertTrue(inputs("${D}this->request->post['x'];").unclassified)
        assertTrue(inputs("${D}x = ${D}_POST['x'];").unclassified)
        assertTrue(inputs("${D}this->request?->getParam('cardId');").unclassified)
        assertNull(inputs("${D}this->service->find(1);").takeIf { !it.empty })
    }

    @Test
    fun `handoff and password confirmed methods are never published as writes`() {
        val paths = paths(
            contract(
                "admin_x",
                "1.0.0",
                "appinfo/routes.php" to """
                    <?php return ['routes' => [
                        ['name' => 'item#index', 'url' => '/items', 'verb' => 'GET'],
                        ['name' => 'item#create', 'url' => '/items', 'verb' => 'POST'],
                        ['name' => 'item#update', 'url' => '/items/{id}', 'verb' => 'PUT'],
                        ['name' => 'item#destroy', 'url' => '/items/{id}', 'verb' => 'DELETE'],
                    ]];
                """,
                "lib/Controller/ItemController.php" to """
                    <?php
                    use OCP\AppFramework\Http\Attribute\NoAdminRequired;
                    use OCP\AppFramework\Http\Attribute\PasswordConfirmationRequired;
                    use OCP\AppFramework\Http\Attribute\SubAdminRequired;
                    class ItemController extends ApiController {
                        #[NoAdminRequired]
                        public function index(): array { return []; }
                        #[SubAdminRequired]
                        public function create(string ${D}title): DataResponse { return new DataResponse([]); }
                        #[NoAdminRequired]
                        #[PasswordConfirmationRequired]
                        public function update(int ${D}id, string ${D}title): DataResponse { return new DataResponse([]); }
                        #[NoAdminRequired]
                        public function destroy(int ${D}id): DataResponse { return new DataResponse([]); }
                    }
                """,
            ),
        )
        assertFalse(paths.getJSONObject("/apps/admin_x/items").has("post"))
        assertFalse(paths.optJSONObject("/apps/admin_x/items/{id}")?.has("put") == true)
        assertTrue(paths.getJSONObject("/apps/admin_x/items/{id}").has("delete"))
    }

    @Test
    fun `annotations count only in the indented line form the server reads`() {
        val paths = paths(
            contract(
                "strict_x",
                "1.0.0",
                "appinfo/routes.php" to """
                    <?php return ['routes' => [
                        ['name' => 'item#one', 'url' => '/one', 'verb' => 'GET'],
                        ['name' => 'item#lower', 'url' => '/lower', 'verb' => 'GET'],
                    ]];
                """,
                "lib/Controller/ItemController.php" to """
                    <?php
                    class ItemController extends ApiController {
                        /** @NoAdminRequired */
                        public function one(): array { return []; }
                        /**
                         * @noAdminRequired
                         */
                        public function lower(): array { return []; }
                    }
                """,
            ),
        )
        assertEquals("admin", paths.policy("/apps/strict_x/one").getString("access"))
        assertEquals("admin", paths.policy("/apps/strict_x/lower").getString("access"))
    }

    private fun JSONObject.policy(path: String): JSONObject = operation(path, "get").getJSONObject(POLICY_EXTENSION)

    private companion object {
        const val D = "$"
    }
}
