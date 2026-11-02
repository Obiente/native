package dev.obiente.nextcloudnative.contracts

import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.contract
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.document
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.operation
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.parameter
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.paths
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.staticRoute
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.synthesize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Route attributes are read like the server router reads them (server v33.0.10
 * lib/private/Route/Router.php). Fixtures are synthetic and only shaped like the cited upstream
 * files; no app identifier is special.
 */
class StaticAttributeRouteContractTest {
    @Test
    fun `an attribute only OCS controller mounts under the app OCS prefix`() {
        // Shaped like Forms v5.4.1 lib/Controller/ApiController.php: #[ApiRoute] on every method, no routes.php.
        val contract = contract(
            "surveys_x",
            "5.4.1",
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
                    public function getForms(string ${D}type = 'owned'): DataResponse { return new DataResponse([]); }

                    #[NoAdminRequired]
                    #[ApiRoute(
                        verb: 'GET',
                        url: '/api/v3/forms/{formId}',
                        requirements: ['formId' => '\d+'],
                    )]
                    public function getForm(int ${D}formId): DataResponse { return new DataResponse([]); }

                    #[NoAdminRequired]
                    #[ApiRoute(verb: 'POST', url: '/api/v3/forms')]
                    public function newForm(?int ${D}fromId = null): DataResponse { return new DataResponse([]); }
                }
            """,
        )
        val paths = paths(contract)

        assertEquals(setOf("/ocs/v2.php/apps/surveys_x/api/v3/forms", "/ocs/v2.php/apps/surveys_x/api/v3/forms/{formId}"), paths.keySet())
        val list = paths.operation("/ocs/v2.php/apps/surveys_x/api/v3/forms", "get")
        assertEquals("attribute", list.getString(ROUTE_SOURCE_EXTENSION))
        assertEquals("user", list.getJSONObject(POLICY_EXTENSION).getString("access"))
        val form = paths.operation("/ocs/v2.php/apps/surveys_x/api/v3/forms/{formId}", "get")
        assertEquals("""\d+""", form.parameter("formId").getString(ROUTE_REQUIREMENT_EXTENSION))
        assertEquals("integer", form.parameter("formId").getJSONObject("schema").getString("type"))
        // An unproven write name stays unpublished but is recorded as route evidence.
        val create = document(contract).staticRoute("post", "/ocs/v2.php/apps/surveys_x/api/v3/forms")
        assertFalse(create.getBoolean("published"))
        assertEquals("attribute", create.getString("source"))
        assertTrue(document(contract).staticRoute("get", "/ocs/v2.php/apps/surveys_x/api/v3/forms").getBoolean("published"))
    }

    @Test
    fun `aliased and fully qualified attribute spellings resolve through imports`() {
        // Shaped like Bookmarks v17.0.1 lib/Controller/BookmarkController.php: #[Http\Attribute\FrontpageRoute(...)].
        val contract = contract(
            "marks_x",
            "17.0.1",
            "lib/Controller/BookmarkController.php" to """
                <?php
                namespace OCA\MarksX\Controller;
                use OCP\AppFramework\ApiController;
                use OCP\AppFramework\Http;
                use OCP\AppFramework\Http\Attribute as Attr;
                class BookmarkController extends ApiController {
                    #[Http\Attribute\NoAdminRequired]
                    #[Http\Attribute\FrontpageRoute(verb: 'GET', url: '/bookmark')]
                    #[Http\Attribute\BruteForceProtection(action: 'bookmark')]
                    #[Http\Attribute\BruteForceProtection(action: 'listing')]
                    public function getBookmarks(int ${D}page = 0): array { return []; }

                    #[\OCP\AppFramework\Http\Attribute\FrontpageRoute('GET', '/bookmark/{id}')]
                    #[Attr\PublicPage]
                    public function getSingleBookmark(int ${D}id): array { return []; }

                    // A same-named attribute from another namespace is not a server route.
                    #[FrontpageRoute(verb: 'GET', url: '/not-a-route')]
                    public function decoy(): array { return []; }
                }
            """,
        )
        val paths = paths(contract)

        assertEquals(setOf("/apps/marks_x/bookmark", "/apps/marks_x/bookmark/{id}"), paths.keySet())
        val list = paths.operation("/apps/marks_x/bookmark", "get")
        assertEquals(listOf("bookmark", "listing"), list.getJSONObject(POLICY_EXTENSION).getJSONArray("bruteForceActions").toList())
        assertEquals("public", paths.operation("/apps/marks_x/bookmark/{id}", "get").getJSONObject(POLICY_EXTENSION).getString("access"))
    }

    @Test
    fun `attribute routes merge with a routes file that declares only preflight routes`() {
        // Shaped like Polls v9.3.0: #[ApiRoute] controllers plus appinfo/routes.php with CORS preflight entries.
        val contract = contract(
            "votes_x",
            "9.3.0",
            "appinfo/routes.php" to """
                <?php
                return ['routes' => [
                    ['name' => 'base_api#preflighted_cors', 'url' => '/api/v1.0/{path}', 'verb' => 'OPTIONS', 'requirements' => ['path' => '.+']],
                ]];
            """,
            "lib/Controller/PollApiController.php" to """
                <?php
                namespace OCA\VotesX\Controller;
                use OCP\AppFramework\ApiController;
                use OCP\AppFramework\Http\Attribute\ApiRoute;
                use OCP\AppFramework\Http\Attribute\CORS;
                use OCP\AppFramework\Http\Attribute\NoAdminRequired;
                use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
                use OCP\AppFramework\Http\JSONResponse;
                class PollApiController extends ApiController {
                    #[CORS]
                    #[NoAdminRequired]
                    #[NoCSRFRequired]
                    #[ApiRoute(verb: 'GET', url: '/api/v1.0/polls')]
                    public function list(): JSONResponse { return new JSONResponse([]); }
                }
            """,
        )
        val list = paths(contract).operation("/ocs/v2.php/apps/votes_x/api/v1.0/polls", "get")
        val policy = list.getJSONObject(POLICY_EXTENSION)
        assertTrue(policy.getBoolean("cors"))
        assertFalse(policy.getBoolean("csrfRequired"))
    }

    @Test
    fun `route type attribute mounts by type and literal maps become facts`() {
        val contract = contract(
            "typed_x",
            "1.0.0",
            "lib/Controller/ItemController.php" to """
                <?php
                namespace OCA\TypedX\Controller;
                use OCP\AppFramework\ApiController;
                use OCP\AppFramework\Http\Attribute\Route;
                use OCP\AppFramework\Http\Attribute\NoAdminRequired;
                class ItemController extends ApiController {
                    #[NoAdminRequired]
                    #[Route(Route::TYPE_API, verb: 'GET', url: '/items/{kind}', defaults: ['kind' => 'all'], postfix: 'byKind')]
                    #[Route(type: 'routes', verb: 'GET', url: '/legacy/items/{kind}')]
                    public function index(string ${D}kind): array { return []; }
                }
            """,
        )
        val paths = paths(contract)
        val ocs = paths.operation("/ocs/v2.php/apps/typed_x/items/{kind}", "get")
        assertEquals("all", ocs.parameter("kind").getString(ROUTE_DEFAULT_EXTENSION))
        assertEquals("byKind", ocs.getString(ROUTE_POSTFIX_EXTENSION))
        assertTrue(paths.has("/apps/typed_x/legacy/items/{kind}"))
    }

    @Test
    fun `non literal urls, custom roots, and unknown arguments never become routes`() {
        val contract = synthesize(
            "unsafe_x",
            "1.0.0",
            "lib/Controller/ItemController.php" to """
                <?php
                namespace OCA\UnsafeX\Controller;
                use OCP\AppFramework\ApiController;
                use OCP\AppFramework\Http\Attribute\ApiRoute;
                use OCP\AppFramework\Http\Attribute\FrontpageRoute;
                class ItemController extends ApiController {
                    #[ApiRoute(verb: 'GET', url: '/items/' . self::SUFFIX)]
                    public function concatenated(): array { return []; }
                    #[ApiRoute(verb: 'GET', url: "/items/{${D}kind}")]
                    public function interpolated(): array { return []; }
                    #[ApiRoute(verb: 'GET', url: self::URL)]
                    public function constant(): array { return []; }
                    #[FrontpageRoute(verb: 'GET', url: '/rooted', root: '/elsewhere')]
                    public function rooted(): array { return []; }
                    #[ApiRoute(verb: 'GET', url: '/extra', future: true)]
                    public function unknownArgument(): array { return []; }
                    #[ApiRoute(verb: 'GET', url: '/private')]
                    private function hidden(): array { return []; }
                    #[ApiRoute(verb: 'GET', url: '/fine')]
                    public function fine(): array { return []; }
                }
            """,
        )
        val document = document(requireNotNull(contract))
        assertEquals(setOf("/ocs/v2.php/apps/unsafe_x/fine"), document.getJSONObject("paths").keySet())
        val skipped = document.getJSONObject(SKIPPED_ROUTES_EXTENSION)
        assertEquals(1, skipped.getInt("customRoot"))
        assertEquals(5, skipped.getInt("unreadableAttribute"))
    }

    @Test
    fun `attribute text inside comments, docblocks, strings, and heredocs is inert`() {
        val contract = contract(
            "inert_x",
            "1.0.0",
            "lib/Controller/ItemController.php" to """
                <?php
                namespace OCA\InertX\Controller;
                use OCP\AppFramework\ApiController;
                use OCP\AppFramework\Http\Attribute\ApiRoute;
                class ItemController extends ApiController {
                    // #[ApiRoute(verb: 'GET', url: '/line-comment')]
                    /* #[ApiRoute(verb: 'GET', url: '/block-comment')] */
                    /**
                     * #[ApiRoute(verb: 'GET', url: '/docblock')]
                     */
                    #[ApiRoute(verb: 'GET', url: '/real')]
                    public function real(): array {
                        ${D}text = '#[ApiRoute(verb: "GET", url: "/string")]';
                        ${D}sql = <<<SQL
                            SELECT '#[ApiRoute(verb: "GET", url: "/heredoc")]' FROM x WHERE y = 'z
                            SQL;
                        return [];
                    }
                }
            """,
        )
        assertEquals(setOf("/ocs/v2.php/apps/inert_x/real"), paths(contract).keySet())
    }

    @Test
    fun `nested attribute arguments and unknown attributes do not break the method`() {
        val contract = contract(
            "nested_x",
            "1.0.0",
            "lib/Controller/ItemController.php" to """
                <?php
                namespace OCA\NestedX\Controller;
                use OCP\AppFramework\ApiController;
                use OCP\AppFramework\Http\Attribute\ApiRoute;
                use OCA\NestedX\Attribute\Audit;
                class ItemController extends ApiController {
                    #[Audit(new Policy(['a' => [1, 2]]), level: 3, label: 'items' . 'x'), ApiRoute(verb: 'GET', url: '/items')]
                    public function index(): array { return []; }
                }
            """,
        )
        val policy = paths(contract).operation("/ocs/v2.php/apps/nested_x/items", "get").getJSONObject(POLICY_EXTENSION)
        val audit = policy.getJSONArray("appAttributes").getJSONObject(0)
        assertEquals("OCA\\NestedX\\Attribute\\Audit", audit.getString("name"))
        // The constructed and concatenated arguments are not literals and are omitted.
        assertEquals(mapOf("level" to "3"), audit.getJSONObject("arguments").toMap())
    }

    @Test
    fun `a routes file and an attribute claiming one route name differently are both dropped`() {
        val contract = contract(
            "clash_x",
            "1.0.0",
            "appinfo/routes.php" to """
                <?php return ['routes' => [
                    ['name' => 'item#index', 'url' => '/items', 'verb' => 'GET'],
                    ['name' => 'item#show', 'url' => '/items/{id}', 'verb' => 'GET'],
                ]];
            """,
            "lib/Controller/ItemController.php" to """
                <?php
                namespace OCA\ClashX\Controller;
                use OCP\AppFramework\ApiController;
                use OCP\AppFramework\Http\Attribute\FrontpageRoute;
                class ItemController extends ApiController {
                    #[FrontpageRoute(verb: 'GET', url: '/all-items')]
                    public function index(): array { return []; }
                    #[FrontpageRoute(verb: 'GET', url: '/items/{id}')]
                    public function show(int ${D}id): array { return []; }
                }
            """,
        )
        val document = document(contract)
        assertEquals(setOf("/apps/clash_x/items/{id}"), document.getJSONObject("paths").keySet())
        assertEquals(2, document.getJSONObject(SKIPPED_ROUTES_EXTENSION).getInt("ambiguousName"))
    }

    @Test
    fun `resource declarations expand to the router's five actions as evidence`() {
        // Shaped like Mail v5.12.6 appinfo/routes.php: 'outbox' => ['url' => '/api/outbox'].
        val contract = contract(
            "post_x",
            "5.12.6",
            "appinfo/routes.php" to """
                <?php return [
                    'resources' => ['outbox' => ['url' => '/api/outbox']],
                    'ocs-resources' => ['share' => ['url' => '/api/shares']],
                ];
            """,
            "lib/Controller/OutboxController.php" to """
                <?php
                class OutboxController extends ApiController {
                    public function index(): JSONResponse { return new JSONResponse([]); }
                    public function show(int ${D}id): JSONResponse { return new JSONResponse([]); }
                    public function create(string ${D}subject): JSONResponse { return new JSONResponse([]); }
                    public function update(int ${D}id, string ${D}subject): JSONResponse { return new JSONResponse([]); }
                    public function destroy(int ${D}id): JSONResponse { return new JSONResponse([]); }
                }
            """,
            "lib/Controller/ShareController.php" to """
                <?php
                class ShareController extends OCSController {
                    public function index(): DataResponse { return new DataResponse([]); }
                }
            """,
        )
        val document = document(contract)
        assertEquals(
            setOf("/apps/post_x/api/outbox", "/apps/post_x/api/outbox/{id}", "/ocs/v2.php/apps/post_x/api/shares"),
            document.getJSONObject("paths").keySet(),
        )
        listOf("post" to "/apps/post_x/api/outbox", "put" to "/apps/post_x/api/outbox/{id}", "delete" to "/apps/post_x/api/outbox/{id}")
            .forEach { (method, path) -> assertFalse(document.staticRoute(method, path).getBoolean("published")) }
    }

    @Test
    fun `a same file route helper is unwrapped only when it cannot change the route identity`() {
        // Shaped like Memories v10.2.0 appinfo/routes.php: w($base, $param) merges requirements and defaults.
        val contract = contract(
            "photos_x",
            "10.2.0",
            "appinfo/routes.php" to """
                <?php
                function w(${D}base, ${D}param) {
                    return array_merge(${D}base, [
                        'requirements' => [${D}param => '.*'],
                        'defaults' => [${D}param => ''],
                    ]);
                }
                function moved(${D}base) { return array_merge(${D}base, ['url' => '/elsewhere']); }
                return ['routes' => [
                    w(['name' => 'folders#sub', 'url' => '/api/folders/{path}', 'verb' => 'GET'], 'path'),
                    moved(['name' => 'folders#other', 'url' => '/api/other', 'verb' => 'GET']),
                    ['name' => 'folders#root', 'url' => '/api/root'],
                ]];
            """,
            "lib/Controller/FoldersController.php" to """
                <?php
                class FoldersController extends ApiController {
                    public function sub(string ${D}path): JSONResponse { return new JSONResponse([]); }
                    public function other(): JSONResponse { return new JSONResponse([]); }
                    public function root(): JSONResponse { return new JSONResponse([]); }
                }
            """,
        )
        val paths = paths(contract)
        assertEquals(setOf("/apps/photos_x/api/folders/{path}", "/apps/photos_x/api/root"), paths.keySet())
        assertEquals(".*", paths.operation("/apps/photos_x/api/folders/{path}", "get").parameter("path").getString(ROUTE_REQUIREMENT_EXTENSION))
        // An omitted verb defaults to GET as in the router.
        assertTrue(paths.getJSONObject("/apps/photos_x/api/root").has("get"))
    }

    @Test
    fun `an unreadable routes file still yields no contract even with attribute routes`() {
        val contract = synthesize(
            "broken_x",
            "1.0.0",
            "appinfo/routes.php" to "<?php return ['routes' => [ ['name' => 'item#index', 'url' => ",
            "lib/Controller/ItemController.php" to """
                <?php
                use OCP\AppFramework\Http\Attribute\ApiRoute;
                class ItemController extends ApiController {
                    #[ApiRoute(verb: 'GET', url: '/items')]
                    public function index(): array { return []; }
                }
            """,
        )
        assertNull(contract)
    }

    private companion object {
        const val D = "$"
    }
}
