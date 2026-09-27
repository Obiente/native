package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class AdministrationAccessState(
    val result: NativeAppCatalogResult? = null,
    val checking: Boolean = false,
    val checkedAt: TimeMark? = null,
    val retired: Boolean = false,
) {
    val canAdminister: Boolean
        get() = isFresh && (result as? NativeAppCatalogResult.Available)?.catalog?.administratorAuthorized == true

    val isFresh: Boolean
        get() = checkedAt?.elapsedNow()?.let { it >= kotlin.time.Duration.ZERO && it < AdministrationAccessTtl } == true
}

internal val AdministrationAccessTtl = 5.minutes

/**
 * One instance per authenticated session. Nothing is persisted or shared between accounts.
 * The verified read-only catalog doubles as permission evidence and the management screen cache.
 */
internal class AdministrationAccessRepository(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private val mutableState = MutableStateFlow(AdministrationAccessState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    fun retire() {
        mutableState.value = AdministrationAccessState(retired = true)
    }

    suspend fun refresh(
        force: Boolean = false,
        execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    ) = mutex.withLock {
        val previous = mutableState.value
        if (previous.retired || (!force && previous.isFresh)) return@withLock
        // Expired or explicitly refreshed permission must not leave old admin controls actionable.
        val checking = AdministrationAccessState(checking = true)
        if (!mutableState.compareAndSet(previous, checking)) return@withLock
        try {
            val result = loadNativeAppCatalog(execute)
            currentCoroutineContext().ensureActive()
            mutableState.compareAndSet(checking, AdministrationAccessState(result = result, checkedAt = timeSource.markNow()))
        } catch (cancelled: CancellationException) {
            mutableState.compareAndSet(checking, AdministrationAccessState())
            throw cancelled
        } catch (_: Exception) {
            // Transport fault boundary: no account data or exception text enters the UI/cache.
            mutableState.compareAndSet(checking, AdministrationAccessState(
                result = NativeAppCatalogResult.Unavailable,
                checkedAt = timeSource.markNow(),
            ))
        }
    }
}
