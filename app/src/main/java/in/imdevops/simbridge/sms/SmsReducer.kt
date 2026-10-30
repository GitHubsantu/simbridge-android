package `in`.imdevops.simbridge.sms

import android.telephony.SmsManager

data class SmsError(val code: String, val message: String)

object SmsErrorMapper {
    fun map(resultCode: Int): SmsError = when (resultCode) {
        SmsManager.RESULT_ERROR_RADIO_OFF ->
            SmsError("RADIO_OFF", "The radio is off (airplane mode?).")
        SmsManager.RESULT_ERROR_NO_SERVICE ->
            SmsError("NO_SERVICE", "No cellular service.")
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
            SmsError("LIMIT_EXCEEDED", "Android's SMS sending limit was reached.")
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            SmsError("SHORT_CODE_BLOCKED", "Sending to this short code is not allowed.")
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE ->
            SmsError("FDN_BLOCKED", "Blocked by the SIM's fixed dialing numbers.")
        SmsManager.RESULT_ERROR_NULL_PDU ->
            SmsError("INTERNAL_ERROR", "The message could not be encoded.")
        SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
            SmsError("SEND_FAILED", "The phone or carrier rejected the message.")
        else -> SmsError("SEND_FAILED", "Sending failed (result code $resultCode).")
    }
}

enum class DeliveryClass { DELIVERED, FAILED, PENDING }

object DeliveryStatus {
    /**
     * Classifies the raw SMS-STATUS-REPORT status (TP-ST). Only 0x00 counts as delivered and only
     * 0x40..0x5F (permanent error) as failed; everything else is left PENDING on purpose.
     */
    fun classify(tpStatus: Int): DeliveryClass = when (tpStatus) {
        0 -> DeliveryClass.DELIVERED
        in 0x40..0x5F -> DeliveryClass.FAILED
        else -> DeliveryClass.PENDING
    }
}

/** Pure state transitions. Late or duplicate callbacks are ignored instead of corrupting history. */
object SmsReducer {
    /** Same value as android.app.Activity.RESULT_OK; kept as a constant so this stays JVM-testable. */
    const val RESULT_OK = -1

    fun markSubmitting(r: SmsRecord, now: Long): SmsRecord =
        if (r.status == SmsStatus.QUEUED) r.copy(status = SmsStatus.SUBMITTING, updatedAt = now) else r

    fun markSubmitFailed(r: SmsRecord, code: String, message: String, now: Long): SmsRecord =
        if (r.status == SmsStatus.QUEUED || r.status == SmsStatus.SUBMITTING) {
            r.copy(status = SmsStatus.FAILED, errorCode = code, errorMessage = message, updatedAt = now)
        } else r

    fun applySentResult(r: SmsRecord, resultCode: Int, now: Long): SmsRecord {
        if (r.status != SmsStatus.SUBMITTING) return r
        if (resultCode == RESULT_OK) {
            val sent = r.partsSent + 1
            return if (sent >= r.partsTotal) {
                r.copy(partsSent = sent, status = SmsStatus.SENT, updatedAt = now)
            } else {
                r.copy(partsSent = sent, updatedAt = now)
            }
        }
        val error = SmsErrorMapper.map(resultCode)
        val suffix = if (r.partsSent > 0) " Some parts may already have been sent." else ""
        return r.copy(
            status = SmsStatus.FAILED,
            errorCode = error.code,
            errorMessage = error.message + suffix,
            updatedAt = now,
        )
    }

    fun applyDeliveryStatus(r: SmsRecord, tpStatus: Int, now: Long): SmsRecord {
        if (r.status != SmsStatus.SENT) return r
        return when (DeliveryStatus.classify(tpStatus)) {
            DeliveryClass.DELIVERED -> {
                val delivered = r.partsDelivered + 1
                if (delivered >= r.partsTotal) {
                    r.copy(partsDelivered = delivered, status = SmsStatus.DELIVERED, updatedAt = now)
                } else {
                    r.copy(partsDelivered = delivered, updatedAt = now)
                }
            }
            DeliveryClass.FAILED -> r.copy(
                status = SmsStatus.DELIVERY_FAILED,
                errorCode = "DELIVERY_FAILED",
                errorMessage = "The carrier reported that delivery failed.",
                updatedAt = now,
            )
            DeliveryClass.PENDING -> r
        }
    }
}
