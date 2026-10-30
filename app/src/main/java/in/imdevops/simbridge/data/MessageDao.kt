package `in`.imdevops.simbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SmsEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): SmsEntity?

    @Update
    suspend fun update(entity: SmsEntity)

    @Query("SELECT * FROM messages ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun list(limit: Int, offset: Int): List<SmsEntity>

    @Query("SELECT * FROM messages ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SmsEntity>>

    @Query("SELECT created_at FROM messages WHERE created_at > :since ORDER BY created_at DESC LIMIT 10000")
    suspend fun createdSince(since: Long): List<Long>

    @Query("DELETE FROM messages WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
