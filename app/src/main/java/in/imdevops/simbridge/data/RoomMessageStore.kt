package `in`.imdevops.simbridge.data

import androidx.room.withTransaction
import `in`.imdevops.simbridge.sms.MessageStore
import `in`.imdevops.simbridge.sms.SmsRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMessageStore(private val db: AppDatabase, recentLimit: Int = 50) : MessageStore {
    private val dao = db.messages()

    override val recent: Flow<List<SmsRecord>> =
        dao.observeRecent(recentLimit).map { rows -> rows.map { it.toRecord() } }

    override suspend fun insert(record: SmsRecord) = dao.insert(record.toEntity())

    override suspend fun get(id: String): SmsRecord? = dao.get(id)?.toRecord()

    /** Read-modify-write inside one transaction so concurrent callbacks cannot lose updates. */
    override suspend fun update(id: String, transform: (SmsRecord) -> SmsRecord): SmsRecord? =
        db.withTransaction {
            val current = dao.get(id)?.toRecord() ?: return@withTransaction null
            val updated = transform(current)
            if (updated != current) dao.update(updated.toEntity())
            updated
        }

    override suspend fun list(limit: Int, offset: Int): List<SmsRecord> =
        dao.list(limit.coerceAtLeast(0), offset.coerceAtLeast(0)).map { it.toRecord() }

    override suspend fun purgeOlderThan(cutoffMillis: Long): Int = dao.deleteOlderThan(cutoffMillis)

    override suspend fun createdTimestampsSince(sinceMillis: Long): List<Long> = dao.createdSince(sinceMillis)
}
