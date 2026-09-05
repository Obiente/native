package dev.obiente.nextcloudnative.app

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import ru.serce.jnrfuse.ErrorCodes

internal class LinuxVirtualMutationGate {
    private enum class State { Open, Draining, Quiesced }

    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var state = State.Open
    private var active = 0

    fun begin() = lock.withLock {
        if (state != State.Open) throw LinuxVirtualFileSystemException(ErrorCodes.EBUSY())
        active += 1
    }

    fun beginRelease(): Boolean = lock.withLock {
        if (state == State.Quiesced) return false
        active += 1
        true
    }

    fun end() = lock.withLock {
        check(active > 0)
        active -= 1
        if (active == 0) drained.signalAll()
    }

    fun tryQuiesce(canQuiesce: () -> Boolean): Boolean = lock.withLock {
        if (state == State.Quiesced) return true
        check(state == State.Open)
        state = State.Draining
        while (active > 0) drained.awaitUninterruptibly()
        if (canQuiesce()) {
            state = State.Quiesced
            true
        } else {
            state = State.Open
            false
        }
    }

    fun resume() = lock.withLock {
        check(state == State.Quiesced)
        state = State.Open
    }

    fun isAcceptingNewOperations(): Boolean = lock.withLock { state == State.Open }
}
