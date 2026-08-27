package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledAppVersionTest {
    @Test
    fun `authenticated app capability supplies an exact installed version for ordinary users`() {
        val response = NextcloudApiResponse(
            status = 200,
            contentType = "application/json",
            etag = null,
            body = """
                {
                  "ocs": {
                    "data": {
                      "capabilities": {
                        "chores": {"version": "0.1.0", "apiVersions": ["1.0"]},
                        "other": {"version": "9.9.9"}
                      }
                    }
                  }
                }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals("0.1.0", installedAppVersionFromCapabilities("chores", response))
        assertEquals("9.9.9", installedAppVersionFromCapabilities("other", response))
        assertNull(installedAppVersionFromCapabilities("missing", response))
    }

    @Test
    fun `capability version fallback rejects arrays unsafe versions and failed responses`() {
        val body = """
            {"ocs":{"data":{"capabilities":{
              "array_app":{"version":[1,2,3]},
              "unsafe_app":{"version":"1.0/../../admin"}
            }}}}
        """.trimIndent().encodeToByteArray()

        assertNull(installedAppVersionFromCapabilities("array_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("unsafe_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("array_app", response(500, body)))
    }

    private fun response(status: Int, body: ByteArray) = NextcloudApiResponse(
        status = status,
        body = body,
        contentType = "application/json",
        etag = null,
    )
}
