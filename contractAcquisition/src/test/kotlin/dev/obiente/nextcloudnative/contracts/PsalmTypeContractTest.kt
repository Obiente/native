package dev.obiente.nextcloudnative.contracts

import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.contract
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.jsonBody
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.operation
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.parameter
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.paths
import dev.obiente.nextcloudnative.contracts.StaticPackageFixtures.responseSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Psalm types map to OpenAPI schemas for the subset openapi-extractor v1.9.1 supports. Fixtures
 * are synthetic and shaped like ResponseDefinitions.php files in Forms v5.4.1 and Cospend v4.3.0.
 */
class PsalmTypeContractTest {
    private val context = PsalmSchemaContext(aliases = emptyMap(), resolveClass = { it.removePrefix("\\") })

    private fun schema(type: String): JSONObject? = parsePsalmType(type)?.let(context::schema)

    @Test
    fun `scalars, nullability, literal unions, ranges, lists, and maps map like the extractor`() {
        assertEquals("""{"type":"string"}""", schema("string").toString())
        assertEquals(true, schema("?int")?.getBoolean("nullable"))
        assertEquals(true, schema("int|null")?.getBoolean("nullable"))
        assertEquals(listOf("n", "d", "w"), schema("'n'|'d'|'w'")?.getJSONArray("enum")?.toList())
        assertEquals(listOf(0, 1, 2), schema("0|1|2")?.getJSONArray("enum")?.map { (it as Number).toInt() })
        val range = assertNotNull(schema("int<1, 100>"))
        assertEquals(1, range.getInt("minimum"))
        assertEquals(100, range.getInt("maximum"))
        assertFalse(assertNotNull(schema("int<0, max>")).has("maximum"))
        assertEquals(1, schema("non-empty-list<string>")?.getInt("minItems"))
        assertEquals("array", schema("string[]")?.getString("type"))
        val map = assertNotNull(schema("array<string, list<string>>"))
        assertEquals("object", map.getString("type"))
        assertEquals("array", map.getJSONObject("additionalProperties").getString("type"))
        // A PHP array with integer or unspecified keys may be a list or a map; nothing is claimed.
        assertEquals(0, schema("array<int, string>")?.length())
        val shape = assertNotNull(schema("array{id: int, title?: string, 'odd-key': bool}"))
        assertEquals(setOf("id", "odd-key"), shape.getJSONArray("required").map(Any::toString).toSet())
        assertEquals(0, schema("array{}")?.getInt("maxItems"))
        assertEquals(true, schema("true")?.getJSONArray("enum")?.getBoolean(0))
    }

    @Test
    fun `malformed, unsupported, oversized, and adversarial types are dropped`() {
        listOf(
            "array{id: int",
            "array{...}",
            "int&string",
            "callable",
            "class-string<Foo>",
            "Foo<int>",
            "1.5",
            "'unterminated",
            "array{id: int, id: string}",
            "Http::STATUS_*",
            "",
        ).forEach { type -> assertNull(schema(type), type) }
        assertNull(parsePsalmType("list<".repeat(40) + "int" + ">".repeat(40)))
        assertNull(parsePsalmType("array{" + (1..300).joinToString(", ") { "k$it: int" } + "}"))
        assertNull(parsePsalmType("x".repeat(MAX_PSALM_TYPE_CHARS + 1)))
    }

    @Test
    fun `recursive aliases are dropped instead of expanding forever`() {
        val node = assertNotNull(parsePsalmType("array{child: Node}"))
        val recursive = PsalmSchemaContext(mapOf("Node" to node), resolveClass = { it })
        assertNull(recursive.schema(PsalmType.Named("Node")))
    }

    @Test
    fun `docblock tags split multi line types and prefer psalm overrides`() {
        val doc = PhpToken.DocComment(
            """
             * @param string ${D}order
             * @psalm-param 'asc'|'desc' ${D}order sort direction
             * @param array<string, list<string>> ${D}answers the answers
             * @param int ${D}broken
             * @psalm-param array{oops ${D}broken
             * @return DataResponse<Http::STATUS_OK, array{
             *     id: int,
             *     title: string,
             * }, array{}>
            """,
        )
        val parameters = doc.parameterTypes()
        assertEquals(setOf("order", "answers"), parameters.keys)
        assertTrue(parameters.getValue("order") is PsalmType.Union)
        assertNotNull(doc.returnType())
    }

    @Test
    fun `response definitions, imports, and status constants type responses and inputs`() {
        val paths = paths(
            contract(
                "surveys_x",
                "5.4.1",
                "lib/ResponseDefinitions.php" to """
                    <?php
                    namespace OCA\SurveysX;
                    /**
                     * @psalm-type SurveysAccess = 0|1|2|3|4
                     * @psalm-type SurveysQuestion = array{
                     *     id: int,
                     *     text: string,
                     *     options?: list<string>,
                     * }
                     * @psalm-type SurveysForm = array{
                     *     id: int,
                     *     title: string,
                     *     access: SurveysAccess,
                     *     questions: list<SurveysQuestion>,
                     *     owner: \OCA\SurveysX\Db\Missing,
                     * }
                     * @psalm-type SurveysLoop = array{next: SurveysLoop}
                     */
                    class ResponseDefinitions {}
                """,
                "lib/Controller/ApiController.php" to """
                    <?php
                    namespace OCA\SurveysX\Controller;
                    use OCA\SurveysX\ResponseDefinitions;
                    use OCP\AppFramework\Http;
                    use OCP\AppFramework\Http\DataResponse;
                    use OCP\AppFramework\OCSController;
                    use OCP\AppFramework\Http\Attribute\ApiRoute;
                    /**
                     * @psalm-import-type SurveysForm from ResponseDefinitions
                     * @psalm-import-type SurveysLoop from ResponseDefinitions as Looping
                     */
                    class ApiController extends OCSController {
                        /**
                         * @return DataResponse<Http::STATUS_OK, list<SurveysForm>, array{}>|DataResponse<Http::STATUS_NOT_FOUND, array{message: string}, array{}>
                         */
                        #[ApiRoute(verb: 'GET', url: '/api/v3/forms')]
                        public function getForms(): DataResponse { return new DataResponse([]); }

                        /**
                         * @param 'asc'|'desc' ${D}order
                         * @param int<1, 50> ${D}limit
                         * @return DataResponse<Http::STATUS_OK, Looping, array{}>
                         */
                        #[ApiRoute(verb: 'GET', url: '/api/v3/loop')]
                        public function loop(string ${D}order = 'asc', int ${D}limit = 10): DataResponse { return new DataResponse([]); }

                        /**
                         * @param array<string, list<string>> ${D}answers
                         * @param bool ${D}title incompatible with the native string
                         */
                        #[ApiRoute(verb: 'PUT', url: '/api/v3/forms/{formId}')]
                        public function updateForm(int ${D}formId, array ${D}answers, string ${D}title): DataResponse { return new DataResponse([]); }
                    }
                """,
            ),
        )
        val forms = paths.operation("/ocs/v2.php/apps/surveys_x/api/v3/forms", "get")
        val item = forms.responseSchema().getJSONObject("items")
        assertEquals(listOf(0, 1, 2, 3, 4), item.getJSONObject("properties").getJSONObject("access").getJSONArray("enum").toList())
        val question = item.getJSONObject("properties").getJSONObject("questions").getJSONObject("items")
        assertEquals(setOf("id", "text"), question.getJSONArray("required").map(Any::toString).toSet())
        assertEquals(
            "OCA\\SurveysX\\Db\\Missing",
            item.getJSONObject("properties").getJSONObject("owner").getString(UNRESOLVED_TYPE_EXTENSION),
        )
        assertTrue(forms.responseSchema("404").getJSONObject("properties").has("message"))

        // A recursive alias is dropped; the operation keeps its untyped response.
        val loop = paths.operation("/ocs/v2.php/apps/surveys_x/api/v3/loop", "get")
        assertEquals("Successful JSON response; fields are learned from runtime data", loop.getJSONObject("responses").getJSONObject("200").getString("description"))
        assertEquals(listOf("asc", "desc"), loop.parameter("order").getJSONObject("schema").getJSONArray("enum").toList())
        assertEquals(50, loop.parameter("limit").getJSONObject("schema").getInt("maximum"))

        val update = paths.operation("/ocs/v2.php/apps/surveys_x/api/v3/forms/{formId}", "put")
        val properties = update.jsonBody().getJSONObject("properties")
        // The native `array` would have been a list of strings; psalm proves it is a map.
        assertEquals("object", properties.getJSONObject("answers").getString("type"))
        assertEquals("string", properties.getJSONObject("title").getString("type"))
    }

    private companion object {
        const val D = "$"
    }
}
