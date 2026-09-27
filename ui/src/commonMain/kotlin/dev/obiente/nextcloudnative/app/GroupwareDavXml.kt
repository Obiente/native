package dev.obiente.nextcloudnative.app

internal fun String.xmlElements(localName: String): List<String> {
    val results = mutableListOf<String>()
    var cursor = 0
    while (cursor < length) {
        val opening = indexOf('<', cursor)
        if (opening < 0) break
        val nameStart = opening + 1
        if (getOrNull(nameStart) in listOf('/', '!', '?')) {
            cursor = nameStart + 1
            continue
        }
        val nameEnd = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), nameStart)
        if (nameEnd < 0) break
        val qualifiedName = substring(nameStart, nameEnd)
        if (!qualifiedName.substringAfter(':').equals(localName, ignoreCase = true)) {
            cursor = nameEnd
            continue
        }
        val openingEnd = indexOf('>', nameEnd)
        if (openingEnd < 0) break
        if (getOrNull(openingEnd - 1) == '/') {
            results += substring(opening, openingEnd + 1)
            cursor = openingEnd + 1
            continue
        }
        val closingStart = indexOf("</$qualifiedName", openingEnd + 1, ignoreCase = true)
        if (closingStart < 0) {
            cursor = openingEnd + 1
            continue
        }
        val closingEnd = indexOf('>', closingStart + qualifiedName.length + 2)
        if (closingEnd < 0) break
        results += substring(opening, closingEnd + 1)
        cursor = closingEnd + 1
    }
    return results
}

internal fun String.xmlDirectChildText(localName: String): String? {
    var cursor = 0
    var depth = 0
    while (cursor < length) {
        val opening = indexOf('<', cursor)
        if (opening < 0) break
        val nameStart = opening + 1
        when {
            startsWith("<!--", opening) -> {
                cursor = indexOf("-->", nameStart + 3).takeIf { it >= 0 }?.plus(3) ?: break
                continue
            }
            startsWith("<![CDATA[", opening) -> {
                cursor = indexOf("]]>", nameStart + 8).takeIf { it >= 0 }?.plus(3) ?: break
                continue
            }
            getOrNull(nameStart) == '!' -> {
                cursor = indexOf('>', nameStart).takeIf { it >= 0 }?.plus(1) ?: break
                continue
            }
            getOrNull(nameStart) == '?' -> {
                cursor = indexOf("?>", nameStart).takeIf { it >= 0 }?.plus(2) ?: break
                continue
            }
            getOrNull(nameStart) == '/' -> {
                depth = (depth - 1).coerceAtLeast(0)
                cursor = indexOf('>', nameStart).takeIf { it >= 0 }?.plus(1) ?: break
                continue
            }
        }
        val nameEnd = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), nameStart)
        if (nameEnd < 0) break
        val qualifiedName = substring(nameStart, nameEnd)
        val openingEnd = indexOf('>', nameEnd)
        if (openingEnd < 0) break
        val selfClosing = getOrNull(openingEnd - 1) == '/'
        if (depth == 1 && qualifiedName.substringAfter(':').equals(localName, ignoreCase = true)) {
            if (selfClosing) return null
            val closingStart = indexOf("</$qualifiedName", openingEnd + 1, ignoreCase = true)
            return closingStart.takeIf { it >= 0 }?.let { substring(openingEnd + 1, it) }
        }
        if (!selfClosing) depth++
        cursor = openingEnd + 1
    }
    return null
}

internal fun String.xmlText(localName: String): String? = xmlElements(localName).firstOrNull()?.let { element ->
    val openingEnd = element.indexOf('>')
    val closingStart = element.lastIndexOf("</")
    if (openingEnd >= 0 && closingStart > openingEnd) element.substring(openingEnd + 1, closingStart) else null
}

internal fun String.containsXmlElement(localName: String): Boolean = xmlElements(localName).isNotEmpty()

internal fun String.xmlAttribute(name: String): String? {
    val openingEnd = indexOf('>').takeIf { it >= 0 } ?: return null
    val opening = substring(0, openingEnd)
    val marker = "$name="
    val markerIndex = opening.indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    val quote = opening.getOrNull(markerIndex + marker.length)?.takeIf { it == '"' || it == '\'' } ?: return null
    val valueStart = markerIndex + marker.length + 1
    val valueEnd = opening.indexOf(quote, valueStart)
    return valueEnd.takeIf { it >= 0 }?.let { opening.substring(valueStart, it) }
}

internal fun String.xmlElementNames(): List<String> {
    val names = mutableListOf<String>()
    var cursor = 0
    while (cursor < length) {
        val opening = indexOf('<', cursor)
        if (opening < 0) break
        val start = opening + 1
        if (getOrNull(start) in listOf('/', '!', '?')) {
            cursor = start + 1
            continue
        }
        val end = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), start)
        if (end < 0) break
        names += substring(start, end).substringAfter(':').lowercase()
        cursor = end
    }
    return names
}

internal fun String.xmlOpeningTags(localName: String): List<String> {
    val tags = mutableListOf<String>()
    var cursor = 0
    while (cursor < length) {
        val opening = indexOf('<', cursor)
        if (opening < 0) break
        val start = opening + 1
        if (getOrNull(start) in listOf('/', '!', '?')) {
            cursor = start + 1
            continue
        }
        val end = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), start)
        if (end < 0) break
        val qualifiedName = substring(start, end)
        val openingEnd = indexOf('>', end)
        if (openingEnd < 0) break
        if (qualifiedName.substringAfter(':').equals(localName, ignoreCase = true)) {
            tags += substring(opening, openingEnd + 1)
        }
        cursor = openingEnd + 1
    }
    return tags
}

internal fun String.decodeXmlEntities(): String {
    val numeric = buildString(length) {
        var cursor = 0
        while (cursor < this@decodeXmlEntities.length) {
            if (this@decodeXmlEntities[cursor] == '&' &&
                this@decodeXmlEntities.getOrNull(cursor + 1) == '#'
            ) {
                val end = this@decodeXmlEntities.indexOf(';', cursor + 2)
                    .takeIf { it in (cursor + 3)..(cursor + 10) }
                if (end != null) {
                    val encoded = this@decodeXmlEntities.substring(cursor + 2, end)
                    val codePoint = if (encoded.startsWith('x', ignoreCase = true)) {
                        encoded.drop(1).toIntOrNull(16)
                    } else {
                        encoded.toIntOrNull()
                    }
                    if (codePoint != null && codePoint in 0..0x10ffff && codePoint !in 0xd800..0xdfff) {
                        appendCodePoint(codePoint)
                        cursor = end + 1
                        continue
                    }
                }
            }
            append(this@decodeXmlEntities[cursor])
            cursor += 1
        }
    }
    return numeric.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xffff) {
        append(codePoint.toChar())
    } else {
        val adjusted = codePoint - 0x10000
        append(((adjusted shr 10) + 0xd800).toChar())
        append(((adjusted and 0x3ff) + 0xdc00).toChar())
    }
}
