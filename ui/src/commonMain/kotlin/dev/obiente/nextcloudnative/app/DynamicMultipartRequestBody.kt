package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Builds a contract-declared multipart body: exactly one required binary part plus optional
 * scalar text parts, each validated against the declared schema before it is sent.
 */
internal fun buildDynamicMultipartBody(
    schema: JsonElement,
    values: Map<String, String>,
): NextcloudMultipartBody {
    val objectSchema = schema as? JsonObject
        ?: error("Only object-shaped multipart request bodies are supported.")
    require((objectSchema["type"] as? JsonPrimitive)?.contentOrNull == "object") {
        "Only exact object-shaped multipart request bodies are supported."
    }
    val properties = objectSchema["properties"] as? JsonObject
        ?: error("A multipart request must declare its fields.")
    require(properties.size <= MAX_DYNAMIC_MULTIPART_FIELDS) {
        "The multipart request declares too many fields."
    }
    val fileFields = properties.entries.filter { (_, element) ->
        (element as? JsonObject)?.let { property ->
            property["type"]?.let { it as? JsonPrimitive }?.contentOrNull == "string" &&
                property["format"]?.let { it as? JsonPrimitive }?.contentOrNull == "binary"
        } == true
    }
    require(fileFields.size == 1) {
        "A dynamic multipart request must declare exactly one binary file field."
    }
    val fileFieldName = fileFields.single().key
    require(fileFieldName.isSafeDynamicMultipartFieldName()) {
        "The multipart file field name is invalid."
    }
    val required = (objectSchema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    require(required.all(properties::containsKey)) {
        "The multipart request has invalid required fields."
    }
    require(fileFieldName in required) {
        "Optional multipart file fields are not supported."
    }
    required.forEach { property ->
        require(!values[property].isNullOrBlank()) { "$property is required." }
    }
    val file = decodeDynamicLocalUploadSelection(
        values[fileFieldName]?.takeIf(String::isNotBlank)
            ?: error("$fileFieldName is required."),
    )
    val textFields = properties.entries
        .asSequence()
        .filter { (name, _) -> name != fileFieldName }
        .mapNotNull { (name, element) ->
            require(name.isSafeDynamicMultipartFieldName()) {
                "The multipart text field name is invalid."
            }
            val property = element as? JsonObject
                ?: error("Multipart fields require exact scalar schemas.")
            val type = (property["type"] as? JsonPrimitive)?.contentOrNull
            require(type in DYNAMIC_MULTIPART_SCALAR_TYPES) {
                "Multipart arrays and objects require an exact encoding contract."
            }
            val value = values[name]
            if (value.isNullOrBlank() && name !in required) {
                null
            } else {
                value.orEmpty().requireDynamicMultipartScalar(type)
                MultipartTextField(name, value.orEmpty())
            }
        }
        .toList()
    return NextcloudMultipartBody(
        file = file,
        fileFieldName = fileFieldName,
        textFields = textFields,
    ).requireSafe()
}

private fun String.requireDynamicMultipartScalar(type: String?) {
    when (type) {
        "boolean" -> require(toBooleanStrictOrNull() != null) { "Enter true or false." }
        "integer" -> require(toLongOrNull() != null) { "Enter a whole number." }
        "number" -> require(toDoubleOrNull()?.isFinite() == true) { "Enter a valid number." }
    }
}

private fun String.isSafeDynamicMultipartFieldName(): Boolean =
    length in 1..64 &&
        first().let { it in 'A'..'Z' || it in 'a'..'z' } &&
        all { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character.isDigit() ||
                character in "_.-"
        }

private const val MAX_DYNAMIC_MULTIPART_FIELDS = 17
private val DYNAMIC_MULTIPART_SCALAR_TYPES = setOf("string", "integer", "number", "boolean")
