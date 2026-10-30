package `in`.imdevops.simbridge.sms

/**
 * Message lifecycle. SENT and DELIVERED are different events:
 *  - SENT: the phone's radio layer accepted every part for transmission.
 *  - DELIVERED: the carrier network returned a delivery receipt (not all carriers do).
 *
 * QUEUED -> SUBMITTING -> SENT | FAILED;  SENT -> DELIVERED | DELIVERY_FAILED.
 */
enum class SmsStatus { QUEUED, SUBMITTING, SENT, FAILED, DELIVERED, DELIVERY_FAILED }

/** History entry. The message text is deliberately NOT stored; only its length. */
data class SmsRecord(
    val id: String,
    val to: String,
    val bodyChars: Int,
    val partsTotal: Int,
    val subscriptionId: Int,
    val status: SmsStatus,
    val partsSent: Int = 0,
    val partsDelivered: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SimInfo(val subscriptionId: Int, val slotIndex: Int, val displayName: String)

sealed interface SubmitResult {
    data object Submitted : SubmitResult
    data class Failed(val code: String, val message: String) : SubmitResult
}

interface SmsSender {
    /** How many SMS parts [body] needs (1 for a single message). Needs no permission. */
    fun partCount(body: String): Int

    /**
     * Hands the message to the Android SMS subsystem. Returning [SubmitResult.Submitted] only means
     * Android accepted the request; the outcome arrives later through [MessageService.onSentResult].
     */
    fun submit(
        messageId: String,
        to: String,
        body: String,
        subscriptionId: Int,
        requestDelivery: Boolean,
    ): SubmitResult
}

sealed interface SubscriptionLookup {
    data object PermissionMissing : SubscriptionLookup
    data class Available(val sims: List<SimInfo>, val defaultSmsSubscriptionId: Int?) : SubscriptionLookup
}

interface SubscriptionProvider {
    fun lookup(): SubscriptionLookup
}
