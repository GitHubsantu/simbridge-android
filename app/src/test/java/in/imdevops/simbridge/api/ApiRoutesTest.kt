package `in`.imdevops.simbridge.api

import `in`.imdevops.simbridge.security.ApiKeys
import `in`.imdevops.simbridge.sms.ApiSettings
import `in`.imdevops.simbridge.sms.SendOutcome
import `in`.imdevops.simbridge.sms.SmsRecord
import `in`.imdevops.simbridge.sms.SmsStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real routes in-process with a fake SMS backend. No SMS is ever sent. */
class ApiRoutesTest {
    private val key = ApiKeys.generate()
    private val keyHash = ApiKeys.hash(key)
    private val goodId = "a".repeat(32)
    private var nextOutcome: SendOutcome = SendOutcome.Rejected("INVALID_NUMBER", "bad")
    private var lastCommand: SendCommand? = null

    private fun record(status: SmsStatus = SmsStatus.SUBMITTING) = SmsRecord(
        id = goodId, to = "+919876543210", bodyChars = 2, partsTotal = 1, subscriptionId = 1,
        status = status, createdAt = 0, updatedAt = 0,
    )

    private val context = ApiContext(
        versionName = "test",
        sdkInt = 33,
        isSendSmsGranted = { true },
        isAuthorized = { ApiKeys.matches(it, keyHash) },
        getMessage = { if (it == goodId) record() else null },
        listMessages = { _, _ -> listOf(record()) },
        sendMessage = { c -> lastCommand = c; nextOutcome },
        limits = { ApiSettings.DEFAULT },
        tunnelStatus = { """{"success":true,"tunnel":{"state":"STOPPED"}}""" },
    )

    private fun api(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { installRoutes(context, FailedAuthThrottle(maxFailuresPerMinute = 3)) }
        block()
    }

    @Test fun missingOrWrongKeyIs401() = api {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/health").status)
        val wrong = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer nope") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("Bearer", wrong.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test fun validKeyHealthAndInfo() = api {
        val h = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertEquals(HttpStatusCode.OK, h.status)
        assertTrue(h.bodyAsText().contains("\"status\":\"ok\""))
        val i = client.get("/api/v1/info") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertTrue(i.bodyAsText().contains("\"sms_send_api\":true"))
    }

    @Test fun repeatedBadKeysAreThrottledButValidKeyStillWorks() = api {
        repeat(3) { client.get("/api/v1/health") }
        val throttled = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertTrue(throttled.headers[HttpHeaders.RetryAfter] != null)
        val ok = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postMessage(
        body: String, type: ContentType = ContentType.Application.Json, idem: String? = null, auth: Boolean = true,
    ) = client.post("/api/v1/messages") {
        if (auth) header(HttpHeaders.Authorization, "Bearer $key")
        if (idem != null) header("Idempotency-Key", idem)
        contentType(type)
        setBody(body)
    }

    private val validBody = """{"to":"+919876543210","message":"hi"}"""

    @Test fun postWithoutKeyIs401AndNeverReachesTheSender() = api {
        assertEquals(HttpStatusCode.Unauthorized, postMessage(validBody, auth = false).status)
        assertEquals(null, lastCommand)
    }

    @Test fun postAcceptedReturns202WithRealStatus() = api {
        nextOutcome = SendOutcome.Created(record())
        val r = postMessage(validBody, idem = "abc-1")
        assertEquals(HttpStatusCode.Accepted, r.status)
        val text = r.bodyAsText()
        assertTrue(text.contains("\"message_id\":\"$goodId\""))
        assertTrue(text.contains("\"status\":\"SUBMITTING\""))
        assertEquals("abc-1", lastCommand?.idempotencyKey)
        assertEquals("+919876543210", lastCommand?.to)
    }

    @Test fun postWhereAndroidRefusedReturns502() = api {
        nextOutcome = SendOutcome.Created(record(SmsStatus.FAILED).copy(errorCode = "SUBMIT_FAILED", errorMessage = "x"))
        assertEquals(HttpStatusCode.BadGateway, postMessage(validBody).status)
    }

    @Test fun replayReturns200() = api {
        nextOutcome = SendOutcome.Replayed(record(SmsStatus.SENT))
        val r = postMessage(validBody, idem = "k")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"idempotent_replay\":true"))
    }

    @Test fun rejectionsMapToHttpStatuses() = api {
        val cases = mapOf(
            "INVALID_NUMBER" to HttpStatusCode.BadRequest,
            "RATE_LIMITED" to HttpStatusCode.TooManyRequests,
            "IDEMPOTENCY_KEY_REUSED" to HttpStatusCode.UnprocessableEntity,
            "IDEMPOTENCY_IN_PROGRESS" to HttpStatusCode.Conflict,
            "BUSY" to HttpStatusCode.ServiceUnavailable,
        )
        for ((code, status) in cases) {
            nextOutcome = SendOutcome.Rejected(code, "m", 7)
            val r = postMessage(validBody)
            assertEquals(code, status, r.status)
            assertEquals("7", r.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test fun malformedBodiesAre400() = api {
        assertEquals(HttpStatusCode.BadRequest, postMessage("not json").status)
        assertEquals(HttpStatusCode.BadRequest, postMessage("""{"to":"+919876543210"}""").status)
        assertEquals(HttpStatusCode.BadRequest, postMessage(validBody, idem = "bad key!").status)
    }

    @Test fun wrongContentTypeIs415() = api {
        assertEquals(HttpStatusCode.UnsupportedMediaType, postMessage(validBody, type = ContentType.Text.Plain).status)
    }

    @Test fun oversizedBodyIs413() = api {
        val big = """{"to":"+919876543210","message":"${"a".repeat(MAX_BODY_BYTES.toInt())}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, postMessage(big).status)
        assertEquals(null, lastCommand)
    }

    @Test fun messageLookup() = api {
        val ok = client.get("/api/v1/messages/$goodId") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertEquals(HttpStatusCode.OK, ok.status)
        val unknown = client.get("/api/v1/messages/${"b".repeat(32)}") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        val junk = client.get("/api/v1/messages/not-an-id") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertEquals(HttpStatusCode.NotFound, junk.status)
    }

    @Test fun tunnelStatusNeedsAuthAndReportsState() = api {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/tunnel/status").status)
        val r = client.get("/api/v1/tunnel/status") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"state\":\"STOPPED\""))
    }

    @Test fun concurrentRequestsAreAllServed() = api {
        val codes = (1..25).map {
            async { client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer $key") }.status }
        }.awaitAll()
        assertTrue(codes.all { it == HttpStatusCode.OK })
    }
}
