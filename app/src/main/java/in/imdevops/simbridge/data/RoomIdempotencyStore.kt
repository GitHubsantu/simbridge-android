package `in`.imdevops.simbridge.data

import `in`.imdevops.simbridge.sms.IdempotencyStore
import `in`.imdevops.simbridge.sms.Reservation

class RoomIdempotencyStore(db: AppDatabase) : IdempotencyStore {
    private val dao = db.idempotency()

    override suspend fun reserve(key: String, fingerprint: String, now: Long): Reservation {
        val rowId = dao.reserve(IdempotencyEntity(key, fingerprint, "", now))
        if (rowId != -1L) return Reservation.Reserved
        val existing = dao.get(key) ?: return Reservation.Existing(fingerprint, null)
        return Reservation.Existing(existing.fingerprint, existing.messageId.ifEmpty { null })
    }

    override suspend fun complete(key: String, messageId: String) = dao.complete(key, messageId)
    override suspend fun release(key: String) = dao.release(key)
    override suspend fun purgeOlderThan(cutoffMillis: Long): Int = dao.purge(cutoffMillis)
}
