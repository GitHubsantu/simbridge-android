package `in`.imdevops.simbridge.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** One Idempotency-Key. [messageId] is empty while the original request is still in flight. */
@Entity(tableName = "idempotency")
data class IdempotencyEntity(
    @PrimaryKey @ColumnInfo(name = "idempotency_key") val key: String,
    val fingerprint: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface IdempotencyDao {
    /** Returns the new row id, or -1 if the key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun reserve(entity: IdempotencyEntity): Long

    @Query("SELECT * FROM idempotency WHERE idempotency_key = :key")
    suspend fun get(key: String): IdempotencyEntity?

    @Query("UPDATE idempotency SET message_id = :messageId WHERE idempotency_key = :key")
    suspend fun complete(key: String, messageId: String)

    @Query("DELETE FROM idempotency WHERE idempotency_key = :key")
    suspend fun release(key: String)

    @Query("DELETE FROM idempotency WHERE created_at < :cutoff")
    suspend fun purge(cutoff: Long): Int
}
