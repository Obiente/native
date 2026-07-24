package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeMailMarkupTest {
    @Test
    fun `mail html keeps readable structure and safe links`() {
        val clean = sanitizeNativeMailHtml(
            """
            <h1>Release notes</h1>
            <p><strong>Ready</strong> for <a href="https://example.test/review">review</a>.</p>
            <ul><li>Android</li><li>Desktop</li></ul>
            """.trimIndent(),
        )

        assertTrue(clean.contains("<h1>Release notes</h1>"))
        assertTrue(clean.contains("<strong>Ready</strong>"))
        assertTrue(clean.contains("https://example.test/review"))
        assertTrue(clean.contains("<li>Android</li>"))
    }

    @Test
    fun `mail html cannot execute content or load tracking resources`() {
        val clean = sanitizeNativeMailHtml(
            """
            <p onclick="steal()">Hello</p>
            <script>stealCredentials()</script>
            <style>body { display: none }</style>
            <img src="https://tracker.example.test/open.gif">
            <a href="javascript:steal()">bad link</a>
            <a href="file:///etc/passwd">local link</a>
            """.trimIndent(),
        )

        assertTrue(clean.contains("Hello"))
        assertTrue(clean.contains("bad link"))
        assertFalse(clean.contains("stealCredentials"))
        assertFalse(clean.contains("display: none"))
        assertFalse(clean.contains("<img"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("javascript:"))
        assertFalse(clean.contains("file:"))
    }
}
