package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaInformationTest {
    @Test
    fun basicInformationPresentsUsefulMediaDetailsBeforeTechnicalIdentifiers() {
        val information = mediaFile().basicMediaInformation()

        assertEquals(listOf("overview", "file"), information.sections.map { it.key })
        assertEquals(
            listOf("format", "dimensions", "size", "captured", "duration"),
            information.sections.first().fields.map { it.key },
        )
        assertEquals("12.5 MiB", information.field("size").value)
        assertEquals("4032 x 3024 pixels", information.field("dimensions").value)
        assertEquals("2:05", information.field("duration").value)
        assertEquals(
            MediaInformationImportance.Technical,
            information.field("file-id").importance,
        )
    }

    @Test
    fun embeddedInformationReplacesMatchingServerFieldsWithoutLosingFileDetails() {
        val embedded = MediaInformation(
            sections = listOf(
                MediaInformationSection(
                    key = "overview",
                    title = "Overview",
                    fields = listOf(
                        MediaInformationField(
                            key = "dimensions",
                            label = "Dimensions",
                            value = "6048 x 4024 pixels",
                            importance = MediaInformationImportance.Primary,
                        ),
                        MediaInformationField(
                            key = "captured",
                            label = "Captured",
                            value = "2026:07:28 12:34:56",
                            importance = MediaInformationImportance.Primary,
                        ),
                    ),
                ),
                MediaInformationSection(
                    key = "camera",
                    title = "Camera",
                    fields = listOf(MediaInformationField("camera", "Camera", "Example Camera")),
                ),
            ),
        )

        val merged = mediaFile().basicMediaInformation().mergedWith(embedded)

        assertEquals("6048 x 4024 pixels", merged.field("dimensions").value)
        assertEquals("2026:07:28 12:34:56", merged.field("captured").value)
        assertEquals("Example Camera", merged.field("camera").value)
        assertEquals("/Photos/example.tiff", merged.field("path").value)
        assertEquals(1, merged.sections.flatMap { it.fields }.count { it.key == "captured" })
    }

    @Test
    fun byteAndDurationFormattingRemainBoundedAndReadable() {
        assertEquals("0 B", formatMediaInformationBytes(0))
        assertEquals("1 KiB", formatMediaInformationBytes(1_024))
        assertEquals("1.5 GiB", formatMediaInformationBytes(1_610_612_736))
        assertEquals("0:09", formatMediaDuration(9))
        assertEquals("1:01:01", formatMediaDuration(3_661))
        assertTrue(formatMediaInformationBytes(Long.MAX_VALUE).endsWith("TiB"))
    }

    private fun mediaFile() = NextcloudFile(
        path = "/Photos/example.tiff",
        name = "example.tiff",
        isDirectory = false,
        mimeType = "image/tiff",
        size = 13_107_200,
        lastModified = "Tue, 28 Jul 2026 10:00:00 GMT",
        fileId = 42,
        hasPreview = false,
        etag = "\"generation\"",
        mediaWidth = 4_032,
        mediaHeight = 3_024,
        capturedAtEpochSeconds = 1_775_000_000,
        mediaDurationSeconds = 125,
    )

    private fun MediaInformation.field(key: String): MediaInformationField =
        sections.flatMap(MediaInformationSection::fields).single { it.key == key }
}
