package `in`.imdevops.simbridge.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.imdevops.simbridge.sms.SmsReducer
import `in`.imdevops.simbridge.sms.SmsRecord
import `in`.imdevops.simbridge.sms.SmsStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator. Uses a real database file so reopening it simulates process death. */
@RunWith(AndroidJUnit4::class)
class RoomMessageStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "simbridge-test.db"

    private fun record(id: String, created: Long = 1L) = SmsRecord(
        id = id, to = "+919876543210", bodyChars = 5, partsTotal = 1, subscriptionId = 1,
        status = SmsStatus.SUBMITTING, createdAt = created, updatedAt = created,
    )

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun recordSurvivesReopenAndLateCallbackStillApplies() = runBlocking {
        val first = AppDatabase.create(context, name)
        RoomMessageStore(first).insert(record("a"))
        first.close()

        val second = AppDatabase.create(context, name)
        val store = RoomMessageStore(second)
        val updated = store.update("a") { SmsReducer.applySentResult(it, SmsReducer.RESULT_OK, 5) }
        assertEquals(SmsStatus.SENT, updated?.status)
        assertEquals(SmsStatus.SENT, store.get("a")?.status)
        assertNull(store.update("missing") { it })
        second.close()
    }

    @Test fun listIsNewestFirstAndPurgeWorks() = runBlocking {
        val db = AppDatabase.create(context, name)
        val store = RoomMessageStore(db)
        store.insert(record("old", created = 1))
        store.insert(record("new", created = 100))
        assertEquals(listOf("new", "old"), store.list(10, 0).map { it.id })
        assertEquals(1, store.purgeOlderThan(50))
        assertEquals(listOf("new"), store.list(10, 0).map { it.id })
        db.close()
    }
}
