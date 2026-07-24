package dev.obiente.nextcloudnative

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Decodes percent-escaped WebDAV hrefs without applying HTML form's `+`-as-space rule. */
internal fun decodeDavHref(value: String): String = URLDecoder.decode(
    value.replace("+", "%2B"),
    StandardCharsets.UTF_8.name(),
)
