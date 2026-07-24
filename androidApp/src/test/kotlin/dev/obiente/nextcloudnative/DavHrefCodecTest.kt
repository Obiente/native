package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DavHrefCodecTest {
    @Test
    fun decodesUtf8AndSpacesWithoutChangingLiteralPlus() {
        assertEquals(
            "/remote.php/dav/files/alice/Photos/A+B C/旅行.jpg",
            decodeDavHref("/remote.php/dav/files/alice/Photos/A+B%20C/%E6%97%85%E8%A1%8C.jpg"),
        )
    }

    @Test
    fun rejectsMalformedPercentEscapes() {
        assertFailsWith<IllegalArgumentException> { decodeDavHref("/files/alice/bad%2") }
    }
}
