package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.Base64
import java.util.ServiceLoader
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.spi.AudioFileReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSoundAudioPlayerTest {
    @Test
    fun `bundled decoder service providers cover native library formats`() {
        val providers = ServiceLoader.load(AudioFileReader::class.java)
            .map { provider -> provider::class.qualifiedName.orEmpty() }
            .toList()

        listOf("Flac", "Vorbis", "Opus", "Mp3", "AAC").forEach { codec ->
            assertTrue(
                providers.any { provider -> codec in provider },
                "Missing bundled JavaSound $codec reader: $providers",
            )
        }
    }

    @Test
    fun `bundled flac provider round trips into bounded pcm stream`() {
        val path = Files.createTempFile("nextcloud-native-decoder-", ".flac")
        try {
            Files.write(path, Base64.getDecoder().decode(TINY_FLAC_FIXTURE))

            openDecodedPcmAudio(path).let { decoded ->
                decoded.stream.use { stream ->
                    val bytes = ByteArray(8_192)
                    val read = stream.read(bytes)
                    assertTrue(read > 0)
                    assertEquals(AudioFormat.Encoding.PCM_SIGNED, decoded.format.encoding)
                    assertEquals(16, decoded.format.sampleSizeInBits)
                    assertEquals(1, decoded.format.channels)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `flac artwork larger than provider mark limit remains playable`() {
        val path = Files.createTempFile("nextcloud-native-large-metadata-", ".flac")
        try {
            val original = Base64.getDecoder().decode(TINY_FLAC_FIXTURE)
            Files.write(path, original.withFlacPadding(600_000))

            openDecodedPcmAudio(path).let { decoded ->
                decoded.stream.use { stream ->
                    assertTrue(stream.read(ByteArray(8_192)) > 0)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `flac seek table that crashes bundled decoder is ignored for streaming playback`() {
        val path = Files.createTempFile("nextcloud-native-seek-table-", ".flac")
        try {
            val original = Base64.getDecoder().decode(TINY_FLAC_FIXTURE)
            Files.write(path, original.withFlacMetadataBlock(type = 3, payloadBytes = 18))

            openDecodedPcmAudio(path).let { decoded ->
                decoded.stream.use { stream ->
                    assertTrue(stream.read(ByteArray(8_192)) > 0)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `backend selection keeps compressed audio on bundled JavaSound`() {
        listOf(
            "audio/flac",
            "audio/x-flac",
            "audio/ogg",
            "audio/opus",
            "audio/mpeg",
            "audio/aac",
            "audio/wav",
        ).forEach { mime ->
            assertEquals(DesktopAudioBackend.JavaSound, desktopAudioBackend(mime))
        }
        assertEquals(DesktopAudioBackend.JavaFx, desktopAudioBackend("audio/mp4"))
        assertEquals(DesktopAudioBackend.JavaFx, desktopAudioBackend("audio/x-m4a"))
    }

    private companion object {
        // 50 ms, 8 kHz mono sine encoded with reference libFLAC 1.5.0, no tags or padding.
        const val TINY_FLAC_FIXTURE =
            "ZkxhQwAAACIQABAAAADeAADeAfQA8AAAAZDc4wSniVrUdsvJq5fhZrQqhAAAKCAAAAByZWZlcmVuY2UgbGliRkxBQyAxLjUuMCAyMDI1MDIxMQAAAAD/+HQIAAGPJEgBIgU/CkgNtg/BtK+1Yffx4PPYiAAAAAsZhrGfSxPE4yhWMZRHESEgpTJneWQkIiEqLGIzF4kJBFkT0Y90IoIkKU7bThQoEoLIhrexOIiISiEn2ctgiYREWW5+OoogoFoTQ8QclIhCgoq5+jFESCiF7EoNWUKILAtLhpsmJJCIJQsh2/LISIFiRWtmU0gsQSSJ6MY1MLIKIUp2/hMQpASYK9bamSIhQUhqbmyUQkQllufjqKIQkUkZM6sRQSyRSRRRwxSwwRxQ1U7arHJ8NQ6AzABUo0E="
    }
}

private fun ByteArray.withFlacPadding(paddingBytes: Int): ByteArray =
    withFlacMetadataBlock(type = 1, payloadBytes = paddingBytes)

private fun ByteArray.withFlacMetadataBlock(type: Int, payloadBytes: Int): ByteArray {
    require(size >= 8 && copyOfRange(0, 4).contentEquals("fLaC".encodeToByteArray()))
    require(type in 0..0x7f)
    var blockOffset = 4
    while (true) {
        val header = this[blockOffset].toInt() and 0xff
        val blockLength =
            ((this[blockOffset + 1].toInt() and 0xff) shl 16) or
                ((this[blockOffset + 2].toInt() and 0xff) shl 8) or
                (this[blockOffset + 3].toInt() and 0xff)
        val nextOffset = blockOffset + 4 + blockLength
        if (header and 0x80 != 0) {
            require(payloadBytes <= 0x00ff_ffff)
            val result = ByteArray(size + 4 + payloadBytes)
            copyInto(result, 0, 0, blockOffset)
            result[blockOffset] = (header and 0x7f).toByte()
            copyInto(result, blockOffset + 1, blockOffset + 1, nextOffset)
            val insertedOffset = nextOffset
            result[insertedOffset] = (0x80 or type).toByte()
            result[insertedOffset + 1] = (payloadBytes shr 16).toByte()
            result[insertedOffset + 2] = (payloadBytes shr 8).toByte()
            result[insertedOffset + 3] = payloadBytes.toByte()
            copyInto(result, insertedOffset + 4 + payloadBytes, nextOffset, size)
            return result
        }
        blockOffset = nextOffset
    }
}
