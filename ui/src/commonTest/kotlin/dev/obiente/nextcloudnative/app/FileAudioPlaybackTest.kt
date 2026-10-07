package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class FileAudioPlaybackTest {
    private val account = NextcloudSession("https://fixture.invalid/nextcloud", "synthetic-user", "synthetic-password")
    private val file = NextcloudFile("Folder/Tone sample.mp3", "Tone sample.mp3", false, "audio/mpeg", 12000L,
        null, 7L, false, etag = "revision-one")

    @Test
    fun supportedAuthoritativeAudioUsesEncodedAccountDavPathAndRevisionIdentity() {
        val source = assertNotNull(fileAudioPlaybackSource(account, "synthetic-user", file))
        assertEquals("/remote.php/dav/files/synthetic-user/Folder/Tone%20sample.mp3", source.relativePath)
        assertEquals("https://fixture.invalid/nextcloud" + source.relativePath, nativeAudioPlaybackUrl(account, source))
        assertNotEquals(source.id, fileAudioPlaybackSource(account.copy(loginName = "other-user"), "other-user", file)?.id)
        assertNotEquals(source.id, fileAudioPlaybackSource(account, "synthetic-user", file.copy(etag = "revision-two"))?.id)
        // Without any version, each source is distinct so a replacement never resumes an old stream.
        val unversioned = file.copy(etag = null, lastModified = null)
        assertNotEquals(
            fileAudioPlaybackSource(account, "synthetic-user", unversioned)?.id,
            fileAudioPlaybackSource(account, "synthetic-user", unversioned)?.id,
        )
        assertNotNull(fileAudioPlaybackSource(account, "synthetic-user", file.copy(size = null)))
        assertEquals(512, fileAudioPlaybackSource(account, "synthetic-user", file.copy(name = "x".repeat(600)))?.title?.length)
    }

    @Test
    fun unsupportedUnreadableAndUnsafeFilesCannotProducePlaybackSources() {
        listOf(file.copy(isDirectory = true), file.copy(mimeType = "image/jpeg"), file.copy(mimeType = "audio/unknown"),
            file.copy(originalAccessAllowed = false), file.copy(davPathAuthoritative = false), file.copy(size = 0),
            file.copy(path = "../outside.mp3"), file.copy(path = "/"), file.copy(path = "Folder/../outside.mp3"))
            .forEach { assertNull(fileAudioPlaybackSource(account, "synthetic-user", it)) }
        assertNull(fileAudioPlaybackSource(account, "", file))
    }

    @Test
    fun lifecycleStopsOwnPlaybackAndPendingStartButNeverUnrelatedPlayback() {
        val source = assertNotNull(fileAudioPlaybackSource(account, "synthetic-user", file))
        val engine = FileAudioTestEngine()
        val unused = FileAudioPlaybackSession(account, source, engine)
        unused.close()
        assertEquals(0, engine.stops)
        val pending = FileAudioPlaybackSession(account, source, engine)
        pending.toggle()
        assertEquals(1, engine.plays)
        pending.close()
        assertEquals(1, engine.stops)
        pending.toggle()
        pending.seek(200)
        assertEquals(1, engine.plays)
        assertEquals(0, engine.seeks)
        val active = FileAudioPlaybackSession(account, source, engine)
        active.toggle()
        engine.state.value = NativeAudioEngineState(source.id, NativeAudioEngineStatus.Playing)
        active.toggle()
        assertEquals(1, engine.pauses)
        engine.state.value = NativeAudioEngineState(source.id, NativeAudioEngineStatus.Paused)
        active.toggle()
        assertEquals(1, engine.resumes)
        active.seek(200)
        assertEquals(1, engine.seeks)
        engine.state.value = NativeAudioEngineState("another-source", NativeAudioEngineStatus.Playing)
        active.close()
        assertEquals(1, engine.stops)
    }
}

internal class FileAudioTestEngine : PlatformAudioPlaybackEngine {
    override val state = MutableStateFlow(NativeAudioEngineState())
    var plays = 0
    var pauses = 0
    var resumes = 0
    var stops = 0
    var seeks = 0
    override fun play(session: NextcloudSession, source: NativeAudioPlaybackSource) { plays++ }
    override fun pause() { pauses++ }
    override fun resume() { resumes++ }
    override fun seekTo(positionMillis: Long) { seeks++ }
    override fun stop() { stops++ }
    override fun release() = Unit
}
