package dev.obiente.nextcloudnative.contracts

import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.contract
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.document
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.operation
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.responseSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Entity serialization in lib/Db and capability keys in lib/Capabilities.php. Fixtures are
 * synthetic and shaped like News 29.0.0 lib/Db/Item.php toAPI(), Mail v5.12.6
 * lib/Db/LocalMessage.php jsonSerialize(), and Deck v1.19.1 lib/Capabilities.php.
 */
class StaticEntityAndCapabilityContractTest {
    @Test
    fun `literal serialization arrays record typed entity fields`() {
        val document = document(contract("feeds_x", "29.0.0", ROUTES, CONTROLLER, OUTBOX, ITEM, MESSAGE, ACCOUNT, BASE))
        val serializations = document.getJSONArray(ENTITY_SERIALIZATIONS_EXTENSION).objects()

        val item = serializations.single { it.getString("class") == "OCA\\FeedsX\\Db\\Item" && it.getString("method") == "toAPI" }
        assertFalse(item.getBoolean("jsonSerializable"))
        val itemProperties = item.getJSONObject("schema").getJSONObject("properties")
        assertEquals("integer", itemProperties.getJSONObject("id").getString("type"))
        assertEquals("boolean", itemProperties.getJSONObject("unread").getString("type"))
        assertEquals("string", itemProperties.getJSONObject("title").getString("type"))
        assertEquals("integer", itemProperties.getJSONObject("pubDate").getString("type"))
        assertEquals("boolean", itemProperties.getJSONObject("rtl").getString("type"))
        assertEquals(0, itemProperties.getJSONObject("enclosure").length())

        val message = serializations.single { it.getString("class") == "OCA\\FeedsX\\Db\\LocalMessage" }
        assertTrue(message.getBoolean("jsonSerializable"))
        val messageProperties = message.getJSONObject("schema").getJSONObject("properties")
        // Inherited from a package base entity, then the message's own constructor.
        assertEquals("integer", messageProperties.getJSONObject("accountId").getString("type"))
        assertEquals("integer", messageProperties.getJSONObject("status").getString("type"))
        assertEquals("boolean", messageProperties.getJSONObject("isHtml").getString("type"))
        assertEquals("integer", messageProperties.getJSONObject("sendAt").getString("type"))
        assertEquals(0, messageProperties.getJSONObject("to").length())
        // Every literal key is serialized every time.
        assertEquals(messageProperties.keySet(), message.getJSONObject("schema").getJSONArray("required").map(Any::toString).toSet())

        // Conditional or computed shapes are never guessed.
        assertTrue(serializations.none { it.getString("class") == "OCA\\FeedsX\\Db\\Conditional" })
    }

    @Test
    fun `a response links to an entity only through a declared return type`() {
        val paths = document(contract("feeds_x", "29.0.0", ROUTES, CONTROLLER, OUTBOX, ITEM, MESSAGE, ACCOUNT, BASE)).getJSONObject("paths")
        val show = paths.operation("/apps/feeds_x/api/outbox/{id}", "get").responseSchema()
        assertTrue(show.getJSONObject("properties").has("subject"))
        val listed = paths.operation("/apps/feeds_x/api/outbox", "get").responseSchema()
        assertEquals("array", listed.getString("type"))
        assertTrue(listed.getJSONObject("items").getJSONObject("properties").has("subject"))
        // index(): array wraps toAPI() by hand; no shape is inferred for it.
        val items = paths.operation("/apps/feeds_x/api/v1-3/items", "get")
        assertTrue(items.responseSchema().getJSONObject("items").optBoolean("additionalProperties"))
        // Item is not JsonSerializable, so a native Item return type is not given toAPI()'s shape.
        assertFalse(paths.operation("/apps/feeds_x/api/v1-3/items/{itemId}", "get").responseSchema().has("properties"))
    }

    @Test
    fun `capability keys and literal api versions become facts`() {
        val document = document(
            contract(
                "boards_x",
                "1.19.1",
                ROUTES,
                CONTROLLER,
                "lib/Capabilities.php" to """
                    <?php
                    namespace OCA\BoardsX;
                    use OCP\Capabilities\ICapability;
                    class Capabilities implements ICapability {
                        public function getCapabilities() {
                            return [
                                'boards_x' => [
                                    'version' => ${D}this->appManager->getAppVersion('boards_x'),
                                    'canCreateBoards' => ${D}this->permissionService->canCreate(),
                                    'apiVersions' => ['1.0', '1.1'],
                                    'api_version' => ['0.2', '1.3'],
                                    'limits' => ['maxCards' => 500],
                                ],
                            ];
                        }
                    }
                """,
            ),
        )
        val facts = document.getJSONObject(CAPABILITY_KEYS_EXTENSION)
        val keys = facts.getJSONArray("keys").objects().associateBy { it.getString("path") }
        assertTrue(keys.getValue("boards_x.version").getBoolean("dynamic"))
        assertEquals(listOf("1.0", "1.1"), keys.getValue("boards_x.apiVersions").getJSONArray("values").toList())
        assertEquals(listOf("500"), keys.getValue("boards_x.limits.maxCards").getJSONArray("values").toList())
        assertEquals(listOf("boards_x.version"), facts.getJSONArray("versionKeys").toList())
        assertEquals(setOf("boards_x.apiVersions", "boards_x.api_version"), facts.getJSONArray("apiVersionKeys").toList().toSet())
    }

    @Test
    fun `a capability method without one literal return records nothing`() {
        val document = document(
            contract(
                "boards_x",
                "1.19.1",
                ROUTES,
                CONTROLLER,
                "lib/Capabilities.php" to """
                    <?php
                    class Capabilities {
                        public function getCapabilities() {
                            if (${D}this->enabled) { return ['a' => ['version' => '1']]; }
                            return ['a' => ['version' => '2']];
                        }
                    }
                """,
            ),
        )
        assertNull(document.optJSONObject(CAPABILITY_KEYS_EXTENSION))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private companion object {
        const val D = "$"

        val ROUTES = "appinfo/routes.php" to """
            <?php return [
                'routes' => [
                    ['name' => 'item_api#index', 'url' => '/api/{apiVersion}/items', 'verb' => 'GET', 'requirements' => ['apiVersion' => 'v1-3']],
                    ['name' => 'item_api#show', 'url' => '/api/{apiVersion}/items/{itemId}', 'verb' => 'GET', 'requirements' => ['apiVersion' => 'v1-3']],
                ],
                'resources' => ['outbox' => ['url' => '/api/outbox']],
            ];
        """

        val CONTROLLER = "lib/Controller/ItemApiController.php" to """
            <?php
            namespace OCA\FeedsX\Controller;
            use OCA\FeedsX\Db\Item;
            use OCP\AppFramework\ApiController;
            class ItemApiController extends ApiController {
                public function index(): array { return ['items' => []]; }
                public function show(int ${D}itemId): Item { return ${D}this->service->find(${D}itemId); }
            }
        """

        val ITEM = "lib/Db/Item.php" to """
            <?php
            namespace OCA\FeedsX\Db;
            use OCP\AppFramework\Db\Entity;
            use OCP\DB\Types;
            class Item extends Entity {
                public function __construct() {
                    ${D}this->addType('unread', 'boolean');
                    ${D}this->addType('pubDate', Types::INTEGER);
                    ${D}this->addType('title', 'string');
                }
                public function toAPI(): array {
                    return [
                        'id' => ${D}this->getId(),
                        'title' => ${D}this->getTitle(),
                        'pubDate' => ${D}this->getPubDate(),
                        'unread' => ${D}this->isUnread(),
                        'rtl' => (${D}this->getRtl() === true),
                        'enclosure' => ${D}this->enclosureFor(${D}this->getId()),
                    ];
                }
            }
        """

        val BASE = "lib/Db/Conditional.php" to """
            <?php
            namespace OCA\FeedsX\Db;
            use OCP\AppFramework\Db\Entity;
            class Conditional extends Entity implements \JsonSerializable {
                public function jsonSerialize(): array {
                    if (${D}this->getId() > 1) { return ['a' => 1]; }
                    return ['b' => 2];
                }
            }
        """

        val MESSAGE = "lib/Db/LocalMessage.php" to """
            <?php
            namespace OCA\FeedsX\Db;
            use JsonSerializable;
            class LocalMessage extends AccountEntity implements JsonSerializable {
                public function __construct() {
                    parent::__construct();
                    ${D}this->addType('status', 'integer');
                    ${D}this->addType('sendAt', 'integer');
                }
                public function jsonSerialize(): array {
                    ${D}recipients = ${D}this->getRecipients() ?? [];
                    return [
                        'id' => ${D}this->getId(),
                        'accountId' => ${D}this->getAccountId(),
                        'subject' => ${D}this->getSubject(),
                        'status' => ${D}this->getStatus(),
                        'sendAt' => ${D}this->getSendAt(),
                        'isHtml' => (bool) ${D}this->isHtml(),
                        'to' => array_values(array_filter(${D}recipients, static fn (${D}r) => ${D}r->getType() === 1)),
                    ];
                }
            }
        """

        val ACCOUNT = "lib/Db/AccountEntity.php" to """
            <?php
            namespace OCA\FeedsX\Db;
            class AccountEntity extends \OCP\AppFramework\Db\Entity {
                public function __construct() { ${D}this->addType('accountId', 'integer'); }
            }
        """

        val OUTBOX = "lib/Controller/OutboxController.php" to """
            <?php
            namespace OCA\FeedsX\Controller;
            use OCA\FeedsX\Db\LocalMessage;
            use OCP\AppFramework\ApiController;
            class OutboxController extends ApiController {
                /** @return list<LocalMessage> */
                public function index(): array { return []; }
                public function show(int ${D}id): LocalMessage { return ${D}this->service->find(${D}id); }
            }
        """
    }
}
