package `in`.imdevops.simbridge.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendRequestParserTest {
    @Test fun parsesMinimalRequest() {
        val r = SendRequestParser.parse("""{"to":"+919876543210","message":"hi"}""") as ParsedSend.Ok
        assertEquals("+919876543210", r.to)
        assertEquals("hi", r.message)
        assertEquals(null, r.subscriptionId)
        assertEquals(true, r.requestDelivery)
    }

    @Test fun parsesOptionalFields() {
        val r = SendRequestParser.parse("""{"to":"+1","message":"m","subscription_id":4,"request_delivery":false}""") as ParsedSend.Ok
        assertEquals(4, r.subscriptionId)
        assertEquals(false, r.requestDelivery)
    }

    @Test fun rejectsBadShapes() {
        listOf(
            "not json", "[]", "{}",
            """{"to":5,"message":"x"}""",
            """{"to":"+919876543210"}""",
            """{"to":"+919876543210","message":null}""",
            """{"to":"+919876543210","message":"x","subscription_id":"4"}""",
            """{"to":"+919876543210","message":"x","request_delivery":"yes"}""",
        ).forEach { assertTrue("should reject: $it", SendRequestParser.parse(it) is ParsedSend.Bad) }
    }
}
