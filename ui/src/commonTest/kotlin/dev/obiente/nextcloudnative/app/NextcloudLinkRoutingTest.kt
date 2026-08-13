package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NextcloudLinkRoutingTest {
    private val session = NextcloudSession(
        serverUrl = "https://cloud.example.test/nextcloud",
        loginName = "person",
        appPassword = "secret",
    )

    @Test
    fun recommendedFileLinksResolveToStableFileIdentities() {
        val relative = nextcloudLinkDestination(session, "/f/904")
        val absolute = nextcloudLinkDestination(session, "https://cloud.example.test/nextcloud/f/905")

        assertEquals(904L, assertIs<NextcloudLinkDestination.FileId>(relative).value)
        assertEquals(905L, assertIs<NextcloudLinkDestination.FileId>(absolute).value)
    }

    @Test
    fun modernFilesRoutesResolveIdsAndDecodedFolders() {
        val file = nextcloudLinkDestination(
            session,
            "/index.php/apps/files/files/42?dir=%2FPhotos%2F2026",
        )
        val folder = nextcloudLinkDestination(
            session,
            "/index.php/apps/files/?dir=%2FProjects%2FDesign+work",
        )

        assertEquals(42L, assertIs<NextcloudLinkDestination.FileId>(file).value)
        assertEquals(
            "Projects/Design work",
            assertIs<NextcloudLinkDestination.FilesPath>(folder).value,
        )
        assertEquals(
            42L,
            assertIs<NextcloudLinkDestination.FileId>(
                nextcloudLinkDestination(
                    session,
                    "/index.php/apps/files/files/42?openfile=42&fileid=42",
                ),
            ).value,
        )
    }

    @Test
    fun installedAppRoutesRemainNativeHints() {
        assertEquals(
            "calendar",
            assertIs<NextcloudLinkDestination.App>(
                nextcloudLinkDestination(session, "/index.php/apps/calendar/dayGridMonth/now"),
            ).appId,
        )
        assertIs<NextcloudLinkDestination.Home>(
            nextcloudLinkDestination(session, "/index.php/apps/dashboard/"),
        )
    }

    @Test
    fun unknownSameAccountAndForeignHttpsLinksRetainBrowserFallbacks() {
        val sameAccount = assertIs<NextcloudLinkDestination.Browser>(
            nextcloudLinkDestination(session, "/s/share-token"),
        )
        assertEquals(
            "https://cloud.example.test/nextcloud/s/share-token",
            sameAccount.browserUrl,
        )
        assertEquals(true, sameAccount.sameAccount)
        val foreign = assertIs<NextcloudLinkDestination.Browser>(
            nextcloudLinkDestination(session, "https://www.example.test/article"),
        )
        assertEquals(
            "https://www.example.test/article",
            foreign.browserUrl,
        )
        assertEquals(false, foreign.sameAccount)
    }

    @Test
    fun unknownFilesRoutesKeepTheirBrowserFallback() {
        listOf(
            "/index.php/apps/files/search-result",
            "/index.php/apps/files/files/42/unsupported",
        ).forEach { link ->
            val destination = assertIs<NextcloudLinkDestination.Browser>(
                nextcloudLinkDestination(session, link),
            )
            assertEquals(true, destination.sameAccount)
        }
    }

    @Test
    fun originAndServerSubpathMustMatchExactly() {
        assertIs<NextcloudLinkDestination.Browser>(
            nextcloudLinkDestination(session, "https://cloud.example.test.evil/f/42"),
        )
        assertIs<NextcloudLinkDestination.Browser>(
            nextcloudLinkDestination(session, "https://cloud.example.test/other/f/42"),
        )
        assertEquals(
            42L,
            assertIs<NextcloudLinkDestination.FileId>(
                nextcloudLinkDestination(session, "https://CLOUD.EXAMPLE.TEST:443/nextcloud/f/42"),
            ).value,
        )
    }

    @Test
    fun explicitCustomLinksUnwrapWithoutAllowingRecursiveOrExtraPayloads() {
        assertEquals(
            42L,
            assertIs<NextcloudLinkDestination.FileId>(
                nextcloudLinkDestination(
                    session,
                    "nextcloudnative://open?url=https%3A%2F%2Fcloud.example.test%2Fnextcloud%2Ff%2F42",
                ),
            ).value,
        )
        assertIs<NextcloudLinkDestination.Rejected>(
            nextcloudLinkDestination(
                session,
                "nextcloudnative://open?url=https%3A%2F%2Fcloud.example.test%2Fnextcloud%2Ff%2F42&extra=1",
            ),
        )
        assertIs<NextcloudLinkDestination.Rejected>(
            nextcloudLinkDestination(
                session,
                "nextcloudnative://open?url=nextcloudnative%3A%2F%2Fopen%3Furl%3Dhttps%253A%252F%252Fexample.test",
            ),
        )
    }

    @Test
    fun malformedTraversalCredentialsAndFileIdsAreRejected() {
        listOf(
            "/index.php/apps/files/?dir=%2FPhotos%2F..%2FSecrets",
            "/f/0",
            "/f/not-a-number",
            "/index.php/apps/files/?openfile=42&openfile=43",
            "/index.php/apps/files/?openfile=bad&fileid=42",
            "/index.php/apps/files/?openfile=42&fileid=43",
            "/index.php/apps/files/files/42?openfile=43",
            "/index.php/apps/files/?dir=%2FPhotos&dir=%2FProjects",
            "/index.php/apps/files/files/not-a-number",
            "https://person@cloud.example.test/nextcloud/f/42",
            "javascript:alert(1)",
        ).forEach { link ->
            assertIs<NextcloudLinkDestination.Rejected>(nextcloudLinkDestination(session, link), link)
        }
    }

    @Test
    fun fragmentContentCannotSupplyQueryParameters() {
        val destination = nextcloudLinkDestination(
            session,
            "/index.php/apps/files/#view?openfile=42",
        )

        assertEquals("", assertIs<NextcloudLinkDestination.FilesPath>(destination).value)
    }

    @Test
    fun queryParameterNamesMatchOnlyTheirDocumentedCase() {
        val destination = nextcloudLinkDestination(
            session,
            "/index.php/apps/files/?OpenFile=42&DIR=%2FPrivate",
        )

        assertEquals("", assertIs<NextcloudLinkDestination.FilesPath>(destination).value)
    }
}
