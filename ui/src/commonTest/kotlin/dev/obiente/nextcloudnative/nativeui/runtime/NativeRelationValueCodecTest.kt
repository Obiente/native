package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeRelationValueCodecTest {
    @Test
    fun `integer relation arrays retain only numeric JSON identities`() {
        val selected = "[1,2,\"3\",null]".nativeRelationSelectedValues(
            DYNAMIC_INTEGER_ARRAY_FORMAT,
        )

        assertEquals(listOf("1", "2"), selected)
        assertEquals("[1,2]", selected.toNativeRelationArray(DYNAMIC_INTEGER_ARRAY_FORMAT))
    }

    @Test
    fun `string relation arrays preserve escaped identity values`() {
        val selected = "[\"alpha\",\"team \\\"blue\\\"\",2]".nativeRelationSelectedValues(
            DYNAMIC_STRING_ARRAY_FORMAT,
        )

        assertEquals(listOf("alpha", "team \"blue\""), selected)
        assertEquals(
            "[\"alpha\",\"team \\\"blue\\\"\"]",
            selected.toNativeRelationArray(DYNAMIC_STRING_ARRAY_FORMAT),
        )
    }
}
