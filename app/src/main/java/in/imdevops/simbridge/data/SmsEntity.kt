package `in`.imdevops.simbridge.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import `in`.imdevops.simbridge.sms.SmsRecord
import `in`.imdevops.simbridge.sms.SmsStatus

/** Row for one SMS. Message text is never stored; only its length. */
@Entity(tableName = "messages")
data class SmsEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "to_number") val toNumber: String,
    @ColumnInfo(name = "body_chars") val bodyChars: Int,
    @ColumnInfo(name = "parts_total") val partsTotal: Int,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int,
    val status: String,
    @ColumnInfo(name = "parts_sent") val partsSent: Int,
    @ColumnInfo(name = "parts_delivered") val partsDelivered: Int,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

fun SmsRecord.toEntity() = SmsEntity(
    id, to, bodyChars, partsTotal, subscriptionId, status.name, partsSent, partsDelivered,
    errorCode, errorMessage, createdAt, updatedAt,
)

fun SmsEntity.toRecord() = SmsRecord(
    id = id, to = toNumber, bodyChars = bodyChars, partsTotal = partsTotal, subscriptionId = subscriptionId,
    // An unknown status string (e.g. from a future version) is surfaced as FAILED rather than crashing.
    status = SmsStatus.entries.firstOrNull { it.name == status } ?: SmsStatus.FAILED,
    partsSent = partsSent, partsDelivered = partsDelivered,
    errorCode = errorCode, errorMessage = errorMessage, createdAt = createdAt, updatedAt = updatedAt,
)
