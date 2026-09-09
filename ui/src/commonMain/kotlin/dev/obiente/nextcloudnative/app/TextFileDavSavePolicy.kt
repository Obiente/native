package dev.obiente.nextcloudnative.app

data class TextFileDavSaveRequest(
    val body: ByteArray,
    val headers: Map<String, String>,
    val contentType: String,
)

data class TextFileDavSaveConfirmation(
    val created: Boolean,
)

fun textFileDavSaveRequest(
    text: String,
    expectedEtag: String,
): TextFileDavSaveRequest {
    val body = text.encodeToByteArray()
    require(body.size.toLong() <= MAX_EDITABLE_TEXT_BYTES) {
        "Text files larger than ${MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)} MiB cannot be edited in the app."
    }
    require(expectedEtag.isNotBlank() && expectedEtag.none { it == '\r' || it == '\n' }) {
        "A valid file version is required before saving."
    }
    return TextFileDavSaveRequest(
        body = body,
        headers = mapOf(
            "Accept" to "*/*",
            "If-Match" to expectedEtag,
        ),
        contentType = "text/plain; charset=utf-8",
    )
}

fun confirmTextFileDavSave(status: Int): TextFileDavSaveConfirmation {
    check(status != 412) { "The file changed on the server. Reload it before saving your changes." }
    check(status in 200..299) { "Saving the text file failed (HTTP $status)." }
    return TextFileDavSaveConfirmation(created = status == 201)
}
