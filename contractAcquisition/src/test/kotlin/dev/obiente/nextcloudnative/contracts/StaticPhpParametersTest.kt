package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class StaticPhpParametersTest {
    @Test
    fun scalarUnionsAndNullableSyntaxAreReadOnly() {
        val parameter = assertNotNull(parseSerializableParameter(tokens("string|int|bool|null"), true))
        val schema = parameter.toOpenApiSchema().getJSONArray("anyOf")
        assertEquals(listOf("string", "integer", "boolean", "null"),
            (0 until schema.length()).map { schema.getJSONObject(it).getString("type") })
        assertNull(parseSerializableParameter(tokens("string|int|bool|null")))
        assertNull(singleSerializableParameter(
            listOf(PhpToken.Word("setMode"), PhpToken.Symbol('(')) +
                tokens("string|bool") + PhpToken.Symbol(')'), 0,
        ))
        val nullable = assertNotNull(parseSerializableParameter(tokens("?bool"), true))
        assertEquals(listOf("bool", "null"), nullable.readUnionTypes)
        assertTrue(assertNotNull(parseSerializableParameter(tokens("?bool"))).readUnionTypes.isEmpty())
    }

    @Test
    fun unsupportedAndMalformedTypesDoNotBecomeScalarEvidence() {
        for (type in listOf("Widget|string", "array|string", "string&int", "string||int",
            "|string", "string|", "?string|int", "??bool", "null", "bool|bool", "string int")) {
            assertNull(parseSerializableParameter(tokens(type), true), type)
            assertNull(parseSerializableParameter(tokens(type)), type)
        }
    }

    @Test
    fun queryUnionDoesNotExpandCrudOrSettingsWrites() {
        val files = mapOf(
            "example/appinfo/routes.php" to """
                <?php return ['routes' => [
                  ['name'=>'items#index','url'=>'/api/items','verb'=>'GET'],
                  ['name'=>'items#create','url'=>'/api/items','verb'=>'POST'],
                  ['name'=>'items#setMode','url'=>'/api/items/mode','verb'=>'PUT'],
                ]];
            """.trimIndent().encodeToByteArray(),
            "example/lib/Controller/ItemsController.php" to """
                <?php class ItemsController extends ApiController {
                  public function index(string|int|bool|null ${'$'}fulltree = null) {}
                  public function create(string|int ${'$'}name) {}
                  public function setMode(string|bool ${'$'}mode) {}
                }
            """.trimIndent().encodeToByteArray(),
        )
        val contract = assertNotNull(synthesizeReadOnlyRouteContract("example", "1.0.0", files))
        val paths = JSONObject(contract.document).getJSONObject("paths")
        val route = paths.getJSONObject("/apps/example/api/items")
        val parameters = route.getJSONObject("get").getJSONArray("parameters")
        val fulltree = (0 until parameters.length()).map { parameters.getJSONObject(it) }
            .single { it.getString("name") == "fulltree" }
        assertFalse(fulltree.getBoolean("required"))
        assertEquals(4, fulltree.getJSONObject("schema").getJSONArray("anyOf").length())
        assertFalse(route.has("post"))
        assertFalse(paths.has("/apps/example/api/items/mode"))
    }

    private fun tokens(type: String): List<PhpToken> {
        val result = mutableListOf<PhpToken>()
        Regex("[A-Za-z_]+|[^\\s]").findAll(type).forEach { match ->
            val value = match.value
            result += if (value.first().isLetter()) PhpToken.Word(value) else PhpToken.Symbol(value.single())
        }
        return result + listOf(PhpToken.Symbol('$'), PhpToken.Word("value"))
    }
}
