package `in`.imdevops.simbridge.api

import `in`.imdevops.simbridge.sms.SmsRecord
import java.time.Instant

fun SmsRecord.toJson(): String = buildString {
    append("{\"id\":").append(JsonText.quote(id))
    append(",\"to\":").append(JsonText.quote(to))
    append(",\"status\":").append(JsonText.quote(status.name))
    append(",\"parts_total\":").append(partsTotal)
    append(",\"parts_sent\":").append(partsSent)
    append(",\"parts_delivered\":").append(partsDelivered)
    append(",\"body_chars\":").append(bodyChars)
    append(",\"subscription_id\":").append(subscriptionId)
    if (errorCode != null) {
        append(",\"error\":{\"code\":").append(JsonText.quote(errorCode))
        append(",\"message\":").append(JsonText.quote(errorMessage ?: "")).append("}")
    } else {
        append(",\"error\":null")
    }
    append(",\"created_at\":").append(JsonText.quote(Instant.ofEpochMilli(createdAt).toString()))
    append(",\"updated_at\":").append(JsonText.quote(Instant.ofEpochMilli(updatedAt).toString()))
    append("}")
}
