package `in`.imdevops.simbridge.sms

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MessageStore {
    /** Most recent records, newest first, for the UI. */
    val recent: Flow<List<SmsRecord>>
    suspend fun insert(record: SmsRecord)
    suspend fun get(id: String): SmsRecord?
    /** Atomically transforms a record. Returns the new value, or null if the id is unknown. */
    suspend fun update(id: String, transform: (SmsRecord) -> SmsRecord): SmsRecord?
    suspend fun list(limit: Int, offset: Int): List<SmsRecord>
    /** Deletes records created before [cutoffMillis]; returns how many were removed. */
    suspend fun purgeOlderThan(cutoffMillis: Long): Int
    /** Creation times (newest first, capped at 10 000) of records newer than [sinceMillis]; used for rate limits. */
    suspend fun createdTimestampsSince(sinceMillis: Long): List<Long>
    suspend fun countWithStatus(statuses: Set<SmsStatus>): Int
}

/** Non-persistent store used by unit tests. The app itself uses RoomMessageStore. */
class InMemoryMessageStore(private val capacity: Int = 200) : MessageStore {
    private val lock = Any()
    private val state = MutableStateFlow<List<SmsRecord>>(emptyList())
    val records: StateFlow<List<SmsRecord>> = state.asStateFlow()
    override val recent: Flow<List<SmsRecord>> = records

    override suspend fun insert(record: SmsRecord) = synchronized(lock) {
        state.value = (listOf(record) + state.value).take(capacity)
    }

    override suspend fun get(id: String): SmsRecord? = state.value.firstOrNull { it.id == id }

    override suspend fun update(id: String, transform: (SmsRecord) -> SmsRecord): SmsRecord? = synchronized(lock) {
        val current = state.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = transform(current[index])
        state.value = current.toMutableList().also { it[index] = updated }
        updated
    }

    override suspend fun list(limit: Int, offset: Int): List<SmsRecord> =
        state.value.drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0))

    override suspend fun purgeOlderThan(cutoffMillis: Long): Int = synchronized(lock) {
        val kept = state.value.filter { it.createdAt >= cutoffMillis }
        val removed = state.value.size - kept.size
        state.value = kept
        removed
    }

    override suspend fun createdTimestampsSince(sinceMillis: Long): List<Long> =
        state.value.map { it.createdAt }.filter { it > sinceMillis }.take(10_000)

    override suspend fun countWithStatus(statuses: Set<SmsStatus>): Int = state.value.count { it.status in statuses }
}
