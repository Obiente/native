package dev.obiente.nextcloudnative

import android.util.Base64
import dev.obiente.nextcloudnative.app.NextcloudFileRangeSession
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun <Result> withRetainedAndroidAccountFileRead(
    expectedSession: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    read: suspend () -> Result,
): Result = withContext(Dispatchers.IO) {
    guard.withExactAccountSession(
        expectedSession = expectedSession,
        resolveSession = resolveSession,
        unavailable = { error("The account changed before the file read could finish.") },
    ) { read() }
}

internal class AndroidFileRangeSessionActivity {
    private val monitor = Any()
    private val active = linkedSetOf<Operation>()
    private val drained = CompletableDeferred<Unit>()
    private var closed = false

    fun start(cancel: () -> Unit = {}): (() -> Unit)? = synchronized(monitor) {
        if (closed) return@synchronized null
        val operation = Operation(cancel)
        active += operation
        { finish(operation) }
    }

    private fun finish(operation: Operation) {
        val complete = synchronized(monitor) {
            active.remove(operation)
            closed && active.isEmpty()
        }
        if (complete) drained.complete(Unit)
    }

    fun close() {
        val operations = synchronized(monitor) {
            closed = true
            active.toList().also { if (it.isEmpty()) drained.complete(Unit) }
        }
        operations.forEach { operation -> operation.cancel() }
    }

    suspend fun awaitDrained() = drained.await()

    fun whenDrained(action: () -> Unit) {
        drained.invokeOnCompletion { action() }
    }

    private class Operation(val cancel: () -> Unit)
}

internal class AndroidFileRangeSessionCoordinator {
    private val monitor = Any()
    private val registrations = mutableMapOf<String, MutableSet<Registration>>()

    fun register(
        accountIdentity: String,
        activity: AndroidFileRangeSessionActivity,
        closeSource: () -> Unit,
    ): AutoCloseable {
        lateinit var registration: Registration
        registration = Registration(
            closeSource = closeSource,
            awaitDrained = activity::awaitDrained,
            whenDrained = activity::whenDrained,
            unregister = { unregister(accountIdentity, registration) },
        )
        synchronized(monitor) { registrations.getOrPut(accountIdentity, ::linkedSetOf) += registration }
        return registration
    }

    suspend fun quiesce(accountIdentity: String) {
        val current = synchronized(monitor) { registrations[accountIdentity]?.toList().orEmpty() }
        current.forEach(Registration::cancel)
        current.forEach { registration -> registration.awaitDrained() }
        synchronized(monitor) { registrations.remove(accountIdentity) }
    }

    private fun unregister(accountIdentity: String, registration: Registration) = synchronized(monitor) {
        registrations[accountIdentity]?.let { current ->
            current -= registration
            if (current.isEmpty()) registrations.remove(accountIdentity)
        }
    }

    private class Registration(
        private val closeSource: () -> Unit,
        val awaitDrained: suspend () -> Unit,
        private val whenDrained: ((() -> Unit) -> Unit),
        private val unregister: () -> Unit,
    ) : AutoCloseable {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) closeSource()
        }

        override fun close() {
            cancel()
            whenDrained(unregister)
        }
    }
}

internal val ANDROID_FILE_RANGE_SESSION_COORDINATOR = AndroidFileRangeSessionCoordinator()

internal suspend fun quiesceAndroidFileRangesBeforeCredentialReplacement(
    previousSession: NextcloudSession?,
    replacementSession: NextcloudSession,
    coordinator: AndroidFileRangeSessionCoordinator = ANDROID_FILE_RANGE_SESSION_COORDINATOR,
) {
    if (
        previousSession != null && previousSession.accountId == replacementSession.accountId &&
        previousSession != replacementSession
    ) {
        coordinator.quiesce(NextcloudDocumentIds.accountKey(previousSession))
    }
}

internal fun openTrackedAndroidFileRangeSession(
    expectedSession: NextcloudSession,
    resolveSession: () -> NextcloudSession?,
    activity: AndroidFileRangeSessionActivity,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    coordinator: AndroidFileRangeSessionCoordinator = ANDROID_FILE_RANGE_SESSION_COORDINATOR,
    openSource: () -> NextcloudFileRangeSession,
): NextcloudFileRangeSession {
    val lease = guard.acquireBlocking(NextcloudDocumentIds.accountKey(expectedSession))
    return try {
        if (resolveSession() != expectedSession) {
            throw FileNotFoundException("The account changed before the file range session could start.")
        }
        val source = openSource()
        val registration = coordinator.register(
            NextcloudDocumentIds.accountKey(expectedSession), activity, source::close,
        )
        NextcloudFileRangeSession(source.size, source::read, registration::close, activity::start)
    } catch (failure: Throwable) {
        activity.close()
        throw failure
    } finally {
        lease.close()
    }
}

internal fun androidFileRangeAuthorization(session: NextcloudSession): String = Base64.encodeToString(
    "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
    Base64.NO_WRAP,
)
