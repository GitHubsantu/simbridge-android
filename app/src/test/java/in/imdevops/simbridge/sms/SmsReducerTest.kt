package `in`.imdevops.simbridge.sms

import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsReducerTest {
    private fun record(parts: Int = 1, status: SmsStatus = SmsStatus.SUBMITTING) = SmsRecord(
        id = "x", to = "+919876543210", bodyChars = 5, partsTotal = parts, subscriptionId = 1,
        status = status, createdAt = 0, updatedAt = 0,
    )

    @Test fun singlePartSentThenDelivered() {
        val sent = SmsReducer.applySentResult(record(), SmsReducer.RESULT_OK, 1)
        assertEquals(SmsStatus.SENT, sent.status)
        val delivered = SmsReducer.applyDeliveryStatus(sent, 0, 2)
        assertEquals(SmsStatus.DELIVERED, delivered.status)
    }

    @Test fun multipartNeedsEveryPart() {
        var r = SmsReducer.applySentResult(record(parts = 2), SmsReducer.RESULT_OK, 1)
        assertEquals(SmsStatus.SUBMITTING, r.status)
        r = SmsReducer.applySentResult(r, SmsReducer.RESULT_OK, 2)
        assertEquals(SmsStatus.SENT, r.status)
        r = SmsReducer.applyDeliveryStatus(r, 0, 3)
        assertEquals(SmsStatus.SENT, r.status)
        r = SmsReducer.applyDeliveryStatus(r, 0, 4)
        assertEquals(SmsStatus.DELIVERED, r.status)
    }

    @Test fun failureIsTerminalAndLateSuccessIgnored() {
        var r = SmsReducer.applySentResult(record(parts = 2), SmsManager.RESULT_ERROR_NO_SERVICE, 1)
        assertEquals(SmsStatus.FAILED, r.status)
        assertEquals("NO_SERVICE", r.errorCode)
        r = SmsReducer.applySentResult(r, SmsReducer.RESULT_OK, 2)
        assertEquals(SmsStatus.FAILED, r.status)
    }

    @Test fun partialFailureMentionsPossiblePartialSend() {
        var r = SmsReducer.applySentResult(record(parts = 2), SmsReducer.RESULT_OK, 1)
        r = SmsReducer.applySentResult(r, SmsManager.RESULT_ERROR_GENERIC_FAILURE, 2)
        assertEquals(SmsStatus.FAILED, r.status)
        assertEquals(true, r.errorMessage?.contains("Some parts"))
    }

    @Test fun deliveryBeforeSentIsIgnored() {
        assertEquals(SmsStatus.SUBMITTING, SmsReducer.applyDeliveryStatus(record(), 0, 1).status)
    }

    @Test fun deliveryClassification() {
        assertEquals(SmsStatus.DELIVERY_FAILED, SmsReducer.applyDeliveryStatus(record(status = SmsStatus.SENT), 0x40, 1).status)
        assertEquals(SmsStatus.SENT, SmsReducer.applyDeliveryStatus(record(status = SmsStatus.SENT), 0x20, 1).status)
    }

    @Test fun submitFailureOnlyFromQueuedOrSubmitting() {
        assertEquals(SmsStatus.FAILED, SmsReducer.markSubmitFailed(record(), "C", "m", 1).status)
        assertEquals(SmsStatus.SENT, SmsReducer.markSubmitFailed(record(status = SmsStatus.SENT), "C", "m", 1).status)
    }
}
