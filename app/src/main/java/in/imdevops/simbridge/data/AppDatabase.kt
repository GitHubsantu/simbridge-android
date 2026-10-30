package `in`.imdevops.simbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SmsEntity::class, IdempotencyEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun idempotency(): IdempotencyDao

    companion object {
        /** v1 -> v2: adds the idempotency table. Existing message history is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `idempotency` (" +
                        "`idempotency_key` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, " +
                        "`message_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`idempotency_key`))",
                )
            }
        }

        fun create(context: Context, name: String = "simbridge.db"): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
