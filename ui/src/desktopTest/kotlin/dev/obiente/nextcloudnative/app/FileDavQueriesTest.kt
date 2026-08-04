package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
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

        assertTrue("/files/morgan&amp;lee/Projects &amp; Planning" in request)
        assertTrue("%Q3 &lt;draft&gt;%" in request)
        assertTrue("<d:nresults>80</d:nresults>" in request)
        assertFalse("morgan&lee" in request)
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
