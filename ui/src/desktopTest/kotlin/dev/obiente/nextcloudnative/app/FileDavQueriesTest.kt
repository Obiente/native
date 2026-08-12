package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileDavQueriesTest {
    @Test
    fun searchRequestEscapesUserScopeAndLiteral() {
        val request = buildFileSearchDavRequest(
            userId = "morgan&lee",
            scopePath = "Projects & Planning",
            query = "Q3 <draft>",
            maximumResults = 80,
        )

        assertTrue("/files/morgan%26lee/Projects%20%26%20Planning" in request)
        assertTrue("%Q3 &lt;draft&gt;%" in request)
        assertTrue("<d:nresults>80</d:nresults>" in request)
        assertFalse("morgan&lee" in request)
        val predicate = request.substringAfter("<d:where>").substringBefore("</d:where>")
        assertTrue("<d:displayname/>" in predicate)
        assertTrue("<d:getcontenttype/>" in predicate)
        assertFalse("<oc:owner-display-name/>" in predicate)
        val selected = request.substringAfter("<d:select>").substringBefore("</d:select>")
        assertFalse("<oc:comments-unread/>" in selected)
    }

    @Test
    fun searchHttpFailureIdentifiesTheRejectedOperation() {
        val failure = NextcloudFileSearchHttpException(400)

        assertEquals(400, failure.status)
        assertEquals("File search failed (HTTP 400).", failure.message)
        assertFailsWith<IllegalArgumentException> { NextcloudFileSearchHttpException(207) }
    }

    @Test
    fun searchScopeEncodesPercentAndUnicodePerPathSegment() {
        val request = buildFileSearchDavRequest(
            userId = "morgan%team",
            scopePath = "Café/100% ready",
            query = "notes",
            maximumResults = 20,
        )

        assertTrue("/files/morgan%25team/Caf%C3%A9/100%25%20ready" in request)
    }

    @Test
    fun propstatStatusParsesTheWebDavProtocolToken() {
        assertTrue(parseDavStatusCode("HTTP/1.1 200 OK") == 200)
        assertTrue(parseDavStatusCode("HTTP/2 204") == 204)
        assertTrue(parseDavStatusCode("HTTP/1.1 409 Conflict") == 409)
        assertTrue(parseDavStatusCode("1.1 200 OK") == null)
    }

    @Test
    fun favoritePatchUsesDavBooleanValues() {
        assertTrue("<oc:favorite>1</oc:favorite>" in buildFileFavoritePropPatch(true))
        assertTrue("<oc:favorite>0</oc:favorite>" in buildFileFavoritePropPatch(false))
    }

    @Test
    fun favoritesReportRequestsRecursiveFileMetadata() {
        val request = buildFavoriteFilesDavReport()

        assertTrue("<oc:filter-files" in request)
        assertTrue("<oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules>" in request)
        assertTrue("<oc:owner-display-name/>" in request)
        assertTrue("<nc:has-preview/>" in request)
    }
}
