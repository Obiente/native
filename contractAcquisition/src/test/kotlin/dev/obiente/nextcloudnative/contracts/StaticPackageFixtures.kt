package dev.obiente.nextcloudnative.contracts

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertNotNull
import org.json.JSONObject

/** Synthetic package fixtures for static contract extraction. No content comes from a real app. */
internal object StaticPackageFixtures {
    fun synthesize(appId: String, version: String, vararg files: Pair<String, String>): VerifiedPackageContract? =
        synthesizeReadOnlyRouteContract(
            appId = appId,
            appVersion = version,
            files = files.associate { (path, source) -> "$appId/$path" to source.trimIndent().encodeToByteArray() },
        )

    fun contract(appId: String, version: String, vararg files: Pair<String, String>): VerifiedPackageContract =
        assertNotNull(synthesize(appId, version, *files), "no static contract was synthesized")

    fun document(contract: VerifiedPackageContract): JSONObject = JSONObject(contract.document)

    fun paths(contract: VerifiedPackageContract): JSONObject = document(contract).getJSONObject("paths")

    fun JSONObject.operation(path: String, verb: String): JSONObject =
        assertNotNull(optJSONObject(path)?.optJSONObject(verb), "missing $verb $path in ${keySet()}")

    fun JSONObject.jsonBody(): JSONObject = getJSONObject("requestBody").getJSONObject("content")
        .getJSONObject("application/json").getJSONObject("schema")

    fun JSONObject.parameter(name: String): JSONObject {
        val parameters = getJSONArray("parameters")
        return assertNotNull(
            (0 until parameters.length()).map(parameters::getJSONObject).firstOrNull { it.getString("name") == name },
            "missing parameter $name",
        )
    }

    fun JSONObject.responseSchema(status: String = "200"): JSONObject = getJSONObject("responses").getJSONObject(status)
        .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")

    fun JSONObject.staticRoute(method: String, path: String): JSONObject {
        val routes = getJSONArray(STATIC_ROUTES_EXTENSION)
        return assertNotNull(
            (0 until routes.length()).map(routes::getJSONObject)
                .firstOrNull { it.getString("method") == method && it.getString("path") == path },
            "missing static route evidence for $method $path",
        )
    }

    /** A gzip tar archive containing [files] under the [root] folder. */
    fun tarGz(root: String, vararg files: Pair<String, String>): ByteArray {
        val tar = ByteArrayOutputStream()
        files.forEach { (path, content) ->
            val bytes = content.trimIndent().encodeToByteArray()
            val header = ByteArray(512)
            "$root/$path".encodeToByteArray().copyInto(header, 0)
            "0000644".encodeToByteArray().copyInto(header, 100)
            "%011o".format(bytes.size).encodeToByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            tar.write(header)
            tar.write(bytes)
            tar.write(ByteArray((512 - bytes.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(tar.toByteArray()) }
        return compressed.toByteArray()
    }

    fun infoXml(appId: String, version: String): String =
        "<?xml version=\"1.0\"?><info><id>$appId</id><version>$version</version></info>"

    const val OCS_CONTROLLER_HEADER = """
        namespace OCA\Example\Controller;
        use OCP\AppFramework\OCSController;
        use OCP\AppFramework\Http;
        use OCP\AppFramework\Http\DataResponse;
        use OCP\AppFramework\Http\Attribute\ApiRoute;
        use OCP\AppFramework\Http\Attribute\NoAdminRequired;
    """
}
