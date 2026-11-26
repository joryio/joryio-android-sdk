package io.joryio.sdk

/**
 * Returned by every `on...Updated` listener - `onCardsUpdated`,
 * `onPlacementsUpdated` and `onFlagsUpdated`: [cancel] stops the updates.
 * Cancelling twice is harmless.
 *
 * One type with the same name and the same `cancel()` on every Joryio SDK
 * (iOS, web, React Native; Unity `JoryioSubscription.Cancel()`), pinned by the
 * backend's SDK parity spec. It is also a [java.io.Closeable], so [close] (and
 * `use { }`) does the same.
 */
class JoryioSubscription internal constructor(onCancel: (() -> Unit)?) : java.io.Closeable {
    private var onCancel: (() -> Unit)? = onCancel

    /** Stop the updates to this listener. */
    fun cancel() {
        val cancel = synchronized(this) { onCancel.also { onCancel = null } }
        cancel?.invoke()
    }

    /** The same as [cancel]. */
    override fun close() = cancel()

    internal companion object {
        /**
         * A subscription made through the Joryio TYPE before initialize():
         * [register] runs once the SDK is ready (queued by [Joryio]'s
         * pre-initialization buffer); cancelling before then cancels it.
         */
        fun deferred(schedule: ((Joryio) -> Unit) -> Unit, register: (Joryio) -> JoryioSubscription): JoryioSubscription {
            val lock = Any()
            var cancelled = false
            var registered: JoryioSubscription? = null
            schedule { sdk ->
                synchronized(lock) {
                    if (!cancelled) registered = register(sdk)
                }
            }
            return JoryioSubscription {
                val r = synchronized(lock) {
                    cancelled = true
                    registered.also { registered = null }
                }
                r?.cancel()
            }
        }
    }
}
