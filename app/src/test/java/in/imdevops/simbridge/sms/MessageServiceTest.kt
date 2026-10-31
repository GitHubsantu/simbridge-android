package `in`.imdevops.simbridge.sms

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSender(var parts: Int = 1, var result: SubmitResult = SubmitResult.Submitted) : SmsSender {
    var submitCalls = 0
    var lastSub = -1
    override fun partCount(body: String) = parts
    override fun submit(messageId: String, to: String, body: String, subscriptionId: Int, requestDelivery: Boolean): SubmitResult {
        submitCalls++
        lastSub = subscriptionId
        return result
    }
}

private class FakeSubs(var lookup: SubscriptionLookup) : SubscriptionProvider {
    override fun lookup() = lookup
}

class MessageServiceTest {
    private val sim = SimInfo(5, 0, "A")
    private val sender = FakeSender()
    private val store = InMemoryMessageStore()
    private val idem = InMemoryIdempotencyStore()
    private var granted = true
    private var now = 1_000_000L
    private var counter = 0
    private var cfg = ApiSettings(perMinute = 3, perDay = 5, maxMessageChars = 50)
    private val subs = FakeSubs(SubscriptionLookup.Available(listOf(sim), 5))
    private val service = MessageService(
        sender, subs, store, idem, { granted }, { cfg }, clock = { now }, newId = { "id${counter++}" },
    )
    private val num = "+919876543210"

    private fun rejected(o: SendOutcome) = (o as SendOutcome.Rejected)
    private fun created(o: SendOutcome) = (o as SendOutcome.Created).record

    @Test fun validationRejectsWithoutSending() = runBlocking {
        assertEquals("INVALID_NUMBER", rejected(service.send("12345", "hi")).code)
        assertEquals("EMPTY_MESSAGE", rejected(service.send(num, "  ")).code)
        assertEquals("MESSAGE_TOO_LONG", rejected(service.send(num, "a".repeat(51))).code)
        granted = false
        assertEquals("PERMISSION_DENIED", rejected(service.send(num, "hi")).code)
        subs.lookup = SubscriptionLookup.Available(emptyList(), null)
        granted = true
        assertEquals("NO_ACTIVE_SIM", rejected(service.send(num, "hi")).code)
        assertEquals(0, sender.submitCalls)
        assertTrue(store.records.value.isEmpty())
    }

    @Test fun destinationRestrictionsRejectBeforeAnythingHappens() = runBlocking {
        cfg = cfg.copy(allowedPrefixes = listOf("+91"))
        assertEquals("DESTINATION_NOT_ALLOWED", rejected(service.send("+14155552671", "hi", idempotencyKey = "d1")).code)
        assertEquals("DESTINATION_NOT_ALLOWED", rejected(service.send("+979123456789", "hi")).code)
        assertEquals(0, sender.submitCalls)
        assertTrue(store.records.value.isEmpty())
        // the rejected key was never reserved, so the same key works for an allowed number
        assertEquals(SmsStatus.SUBMITTING, created(service.send(num, "hi", idempotencyKey = "d1")).status)
    }

    @Test fun happyPathMovesThroughSubmittingSentDelivered() = runBlocking {
        val r = created(service.send("+91 98765 43210", "hello"))
        assertEquals(SmsStatus.SUBMITTING, r.status)
        assertEquals(num, r.to)
        assertEquals(5, sender.lastSub)
        service.onSentResult(r.id, SmsReducer.RESULT_OK)
        assertEquals(SmsStatus.SENT, store.get(r.id)!!.status)
        service.onDeliveryStatus(r.id, 0)
        assertEquals(SmsStatus.DELIVERED, store.get(r.id)!!.status)
    }

    @Test fun submitFailureIsRecordedAndNotRetried() = runBlocking {
        sender.result = SubmitResult.Failed("SUBMIT_FAILED", "boom")
        val r = created(service.send(num, "hello"))
        assertEquals(SmsStatus.FAILED, r.status)
        assertEquals(1, sender.submitCalls)
    }

    @Test fun multipartRecordsPartCount() = runBlocking {
        sender.parts = 3
        assertEquals(3, created(service.send(num, "long")).partsTotal)
    }

    @Test fun callbackForUnknownIdIsHarmless() = runBlocking {
        service.onSentResult("nope", SmsReducer.RESULT_OK)
        service.onDeliveryStatus("nope", 0)
        assertTrue(store.records.value.isEmpty())
    }

    @Test fun perMinuteLimitBlocksThenRecovers() = runBlocking {
        repeat(3) { created(service.send(num, "m$it")) }
        val r = rejected(service.send(num, "one more"))
        assertEquals("RATE_LIMITED", r.code)
        assertTrue((r.retryAfterSeconds ?: 0) in 1..60)
        assertEquals(3, sender.submitCalls)
        now += 61_000
        created(service.send(num, "after the window"))
        assertEquals(4, sender.submitCalls)
    }

    @Test fun perDayLimitApplies() = runBlocking {
        repeat(5) { now += 61_000; created(service.send(num, "m$it")) }
        now += 61_000
        assertEquals("RATE_LIMITED", rejected(service.send(num, "sixth")).code)
    }

    @Test fun sameKeySamePayloadIsSentOnce() = runBlocking {
        val first = created(service.send(num, "hello", idempotencyKey = "k1"))
        val again = service.send(num, "hello", idempotencyKey = "k1") as SendOutcome.Replayed
        assertEquals(first.id, again.record.id)
        assertEquals(1, sender.submitCalls)
        assertEquals(1, store.records.value.size)
    }

    @Test fun sameKeyDifferentPayloadIsRejected() = runBlocking {
        created(service.send(num, "hello", idempotencyKey = "k1"))
        assertEquals("IDEMPOTENCY_KEY_REUSED", rejected(service.send(num, "different", idempotencyKey = "k1")).code)
        assertEquals(1, sender.submitCalls)
    }

    @Test fun rejectedRequestReleasesItsKeySoItCanBeRetried() = runBlocking {
        granted = false
        assertEquals("PERMISSION_DENIED", rejected(service.send(num, "hello", idempotencyKey = "k2")).code)
        granted = true
        created(service.send(num, "hello", idempotencyKey = "k2"))
        assertEquals(1, sender.submitCalls)
    }

    @Test fun concurrentDuplicatesSendExactlyOnce() = runBlocking {
        val results = (1..10).map { async { service.send(num, "dup", idempotencyKey = "same") } }.awaitAll()
        assertEquals(1, sender.submitCalls)
        assertEquals(1, results.count { it is SendOutcome.Created })
        assertTrue(results.all { it is SendOutcome.Created || it is SendOutcome.Replayed })
    }

    @Test fun purgeRemovesOnlyOldRecords() = runBlocking {
        created(service.send(num, "a"))
        now += 1000
        created(service.send(num, "b"))
        assertEquals(1, store.purgeOlderThan(now))
        assertEquals(1, store.records.value.size)
    }
}
