package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchivePathSafetyTest {
    @Test
    fun acceptsOnlyCanonicalRootDirectoryMarkers() {
        assertTrue(isCanonicalTarRootDirectory(".", '5'))
        assertTrue(isCanonicalTarRootDirectory("./", '5'))

        assertFalse(isCanonicalTarRootDirectory(".", '0'))
        assertFalse(isCanonicalTarRootDirectory("./appinfo/info.xml", '0'))
        assertFalse(isCanonicalTarRootDirectory("app/../other", '5'))
        assertFalse(isCanonicalTarRootDirectory("../", '5'))
    }

    @Test
    fun decodesNullTerminatedGnuLongPaths() {
        val path = "app/node_modules/dependency/with/a/long/path/openapi.json"

        assertEquals(path, decodeTarLongPath(path.encodeToByteArray() + byteArrayOf(0)))
    }

    @Test
    fun rejectsContentAfterLongPathTerminator() {
        assertFailsWith<IllegalArgumentException> {
            decodeTarLongPath("app/openapi.json\u0000other".encodeToByteArray())
        }
    }
}
