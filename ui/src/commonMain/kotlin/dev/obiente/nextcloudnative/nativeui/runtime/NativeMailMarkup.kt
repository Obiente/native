package dev.obiente.nextcloudnative.nativeui.runtime

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.safety.Safelist

/**
 * Sanitizes untrusted email HTML before it reaches the native rich-text renderer.
 *
 * The safelist retains readable document structure and ordinary HTTP(S) links. It removes scripts,
 * styles, forms, embeds and every image, so merely opening a message cannot execute content or
 * contact a tracking server.
 */
internal fun sanitizeNativeMailHtml(html: String): String {
    val bounded = html.take(MAX_NATIVE_MAIL_HTML_CHARACTERS)
    val safelist = Safelist.basic()
        .addTags("div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6")
        .removeProtocols("a", "href", "ftp", "mailto")
    return Ksoup.clean(bounded, safelist = safelist)
}

private const val MAX_NATIVE_MAIL_HTML_CHARACTERS = 256_000
