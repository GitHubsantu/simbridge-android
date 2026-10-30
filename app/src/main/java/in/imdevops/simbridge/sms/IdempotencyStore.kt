package `in`.imdevops.simbridge.sms

sealed interface Reservation {
    data object Reserved : Reservation
    /** [messageId] is null while the original request is still being processed. */
    data class Existing(val fingerprint: String, val messageId: String?) : Reservation
}

interface IdempotencyStore {
    /** Atomically claims [key]. Exactly one concurrent caller gets [Reservation.Reserved]. */
    suspend fun reserve(key: String, fingerprint: String, now: Long): Reservation
    suspend fun complete(key: String, messageId: String)
    suspend fun release(key: String)
    suspend fun purgeOlderThan(cutoffMillis: Long): Int
}

class InMemoryIdempotencyStore : IdempotencyStore {
    private data class Entry(val fingerprint: String, var messageId: String?, val createdAt: Long)
    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    override suspend fun reserve(key: String, fingerprint: String, now: Long): Reservation = synchronized(lock) {
        val existing = entries[key]
        if (existing != null) return Reservation.Existing(existing.fingerprint, existing.messageId)
        entries[key] = Entry(fingerprint, null, now)
        Reservation.Reserved
    }

    override suspend fun complete(key: String, messageId: String) = synchronized(lock) {
        entries[key]?.messageId = messageId
    }

    override suspend fun release(key: String) = synchronized(lock) { entries.remove(key); Unit }

    override suspend fun purgeOlderThan(cutoffMillis: Long): Int = synchronized(lock) {
        val old = entries.filterValues { it.createdAt < cutoffMillis }.keys.toList()
        old.forEach { entries.remove(it) }
        old.size
    }
}
