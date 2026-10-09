package com.joeykot.dictate.advanced_audio.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A small cancellation primitive shared by HTTP, streaming, polling, and the
 * future WebSocket session.  It intentionally has no Android lifecycle
 * dependency: the job controller will own one source per active job.
 */
class AdvancedCancellationSource {
    val token: AdvancedCancellationToken = AdvancedCancellationToken()

    fun cancel() {
        token.cancelInternal()
    }
}

class AdvancedCancellationToken internal constructor() {
    private val cancelled = AtomicBoolean(false)
    private val nextListenerId = AtomicLong()
    private val listeners = ConcurrentHashMap<Long, () -> Unit>()

    fun isCancelled(): Boolean = cancelled.get()

    @Throws(HttpTransportException.Cancelled::class)
    fun throwIfCancelled() {
        if (isCancelled()) throw HttpTransportException.Cancelled()
    }

    /**
     * Registers a callback such as `Call.cancel()`.  The callback may run on
     * the cancelling thread, so it must be quick and must not block.
     */
    fun onCancellation(callback: () -> Unit): CancellationRegistration {
        if (cancelled.get()) {
            callbackSafely(callback)
            return CancellationRegistration.None
        }

        val id = nextListenerId.incrementAndGet()
        listeners[id] = callback
        if (cancelled.get() && listeners.remove(id) != null) {
            callbackSafely(callback)
        }
        return CancellationRegistration { listeners.remove(id) }
    }

    internal fun cancelInternal() {
        if (!cancelled.compareAndSet(false, true)) return
        val pending = listeners.values.toList()
        listeners.clear()
        pending.forEach(::callbackSafely)
    }

    companion object {
        /** A fresh token avoids retaining callbacks between unrelated requests. */
        fun none(): AdvancedCancellationToken = AdvancedCancellationToken()

        private fun callbackSafely(callback: () -> Unit) {
            runCatching(callback)
        }
    }
}

fun interface CancellationRegistration : AutoCloseable {
    override fun close()

    data object None : CancellationRegistration {
        override fun close() = Unit
    }
}
