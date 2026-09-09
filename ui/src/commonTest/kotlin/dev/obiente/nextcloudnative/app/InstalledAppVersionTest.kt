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
                        "pantry": {"version": {
                          "major": 0,
                          "minor": 29,
                          "micro": 0,
                          "string": "0.29.0",
                          "array": [0, 29, 0]
                        }},
                        "cookbook": {"version": [0, 11, 10]},
                        "other": {"version": "9.9.9"}
                      }
                    }
                  }
                }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals("0.1.0", installedAppVersionFromCapabilities("chores", response))
        assertEquals("0.29.0", installedAppVersionFromCapabilities("pantry", response))
        assertEquals("0.11.10", installedAppVersionFromCapabilities("cookbook", response))
        assertEquals("9.9.9", installedAppVersionFromCapabilities("other", response))
        assertNull(installedAppVersionFromCapabilities("missing", response))
    }

    @Test
    fun `capability version fallback rejects arrays unsafe versions and failed responses`() {
        val body = """
            {"ocs":{"data":{"capabilities":{
              "negative_array_app":{"version":[1,-2,3]},
              "mixed_array_app":{"version":[1,"2",3]},
              "long_array_app":{"version":[1,2,3,4,5]},
              "unsafe_app":{"version":"1.0/../../admin"},
              "inconsistent_app":{"version":{"major":2,"minor":0,"micro":0,"string":"1.0.0"}},
              "missing_string_app":{"version":{"major":1,"minor":0,"micro":0}}
            }}}}
        """.trimIndent().encodeToByteArray()

        assertNull(installedAppVersionFromCapabilities("negative_array_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("mixed_array_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("long_array_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("unsafe_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("inconsistent_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("missing_string_app", response(200, body)))
        assertNull(installedAppVersionFromCapabilities("negative_array_app", response(500, body)))
    }

    private fun response(status: Int, body: ByteArray) = NextcloudApiResponse(
        status = status,
        body = body,
        contentType = "application/json",
        etag = null,
    )
}
