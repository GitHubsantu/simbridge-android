package `in`.imdevops.simbridge.sms

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

sealed interface SendOutcome {
    /** A new record exists; check its status (it may already be FAILED if Android refused the submission). */
    data class Created(val record: SmsRecord) : SendOutcome
    /** The same Idempotency-Key and payload was seen before; nothing new was sent. */
    data class Replayed(val record: SmsRecord) : SendOutcome
    /** Nothing was created or sent. */
    data class Rejected(val code: String, val message: String, val retryAfterSeconds: Int? = null) : SendOutcome
}

class MessageService(
    private val sender: SmsSender,
    private val subscriptions: SubscriptionProvider,
    private val store: MessageStore,
    private val idempotency: IdempotencyStore,
    private val smsPermissionGranted: () -> Boolean,
    private val settings: () -> ApiSettings = { ApiSettings.DEFAULT },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = SecureIds::newMessageId,
) {
    /** Sends are processed one at a time so "check limit, then insert" cannot race. */
    private val sendLock = Mutex()
    private val pending = AtomicInteger(0)

    suspend fun send(
        to: String,
        body: String,
        requestedSubscriptionId: Int? = null,
        requestDelivery: Boolean = true,
        idempotencyKey: String? = null,
    ): SendOutcome {
        val cfg = settings()
        val number = when (val n = PhoneNumbers.normalize(to)) {
            is PhoneNumberResult.Valid -> n.e164
            is PhoneNumberResult.Invalid -> return SendOutcome.Rejected("INVALID_NUMBER", n.reason)
        }
        if (body.isBlank()) return SendOutcome.Rejected("EMPTY_MESSAGE", "Message text is empty.")
        if (body.length > cfg.maxMessageChars) {
            return SendOutcome.Rejected("MESSAGE_TOO_LONG", "Message exceeds ${cfg.maxMessageChars} characters.")
        }

        // Bounded queue: at most MAX_PENDING requests running or waiting.
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            return SendOutcome.Rejected("BUSY", "Too many requests are waiting. Try again shortly.", 5)
        }
        try {
            return sendLock.withLock {
                sendLocked(number, body, requestedSubscriptionId, requestDelivery, idempotencyKey, cfg)
            }
        } finally {
            pending.decrementAndGet()
        }
    }

    private suspend fun sendLocked(
        number: String,
        body: String,
        requestedSubscriptionId: Int?,
        requestDelivery: Boolean,
        idempotencyKey: String?,
        cfg: ApiSettings,
    ): SendOutcome {
        val now = clock()
        var reserved = false
        // Once a record exists the SMS may go out, so the key must never be released after that point.
        var mayRelease = true
        try {
            if (idempotencyKey != null) {
                idempotency.purgeOlderThan(now - IDEMPOTENCY_TTL_MS)
                val fingerprint = fingerprint(number, body, requestedSubscriptionId, requestDelivery)
                when (val r = idempotency.reserve(idempotencyKey, fingerprint, now)) {
                    Reservation.Reserved -> reserved = true
                    is Reservation.Existing -> {
                        if (r.fingerprint != fingerprint) {
                            return SendOutcome.Rejected(
                                "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used with a different request.",
                            )
                        }
                        val record = r.messageId?.let { store.get(it) }
                            ?: return SendOutcome.Rejected(
                                "IDEMPOTENCY_IN_PROGRESS", "The original request is still being processed.", 2,
                            )
                        return SendOutcome.Replayed(record)
                    }
                }
            }

            if (!smsPermissionGranted()) {
                return SendOutcome.Rejected("PERMISSION_DENIED", "SMS permission has not been granted.")
            }
            val stamps = store.createdTimestampsSince(now - 86_400_000L)
            when (val d = RatePolicy.check(stamps, now, RateLimits(cfg.perMinute, cfg.perDay))) {
                RateDecision.Allowed -> Unit
                is RateDecision.Limited -> return SendOutcome.Rejected(
                    "RATE_LIMITED",
                    "Sending limit reached (${if (d.scope == "day") cfg.perDay.toString() + " per day" else cfg.perMinute.toString() + " per minute"}).",
                    d.retryAfterSeconds,
                )
            }
            val sim = when (val r = SubscriptionResolver.resolve(requestedSubscriptionId, subscriptions.lookup(), cfg.preferredSubscriptionId)) {
                is SimResolution.Chosen -> r.sim
                is SimResolution.Rejected -> return SendOutcome.Rejected(r.code, r.message)
            }

            val id = newId()
            val record = SmsRecord(
                id = id,
                to = number,
                bodyChars = body.length,
                partsTotal = sender.partCount(body),
                subscriptionId = sim.subscriptionId,
                status = SmsStatus.QUEUED,
                createdAt = now,
                updatedAt = now,
            )
            store.insert(record)
            mayRelease = false
            if (reserved && idempotencyKey != null) idempotency.complete(idempotencyKey, id)

            // Mark SUBMITTING before handing over, so a fast callback cannot arrive while still QUEUED.
            store.update(id) { SmsReducer.markSubmitting(it, clock()) }
            when (val result = sender.submit(id, number, body, sim.subscriptionId, requestDelivery)) {
                SubmitResult.Submitted -> Unit
                is SubmitResult.Failed ->
                    store.update(id) { SmsReducer.markSubmitFailed(it, result.code, result.message, clock()) }
            }
            // No automatic retry: after an ambiguous failure a retry could send the SMS twice.
            return SendOutcome.Created(store.get(id) ?: record)
        } finally {
            if (reserved && mayRelease && idempotencyKey != null) idempotency.release(idempotencyKey)
        }
    }

    suspend fun onSentResult(id: String, resultCode: Int) {
        store.update(id) { SmsReducer.applySentResult(it, resultCode, clock()) }
    }

    suspend fun onDeliveryStatus(id: String, tpStatus: Int) {
        store.update(id) { SmsReducer.applyDeliveryStatus(it, tpStatus, clock()) }
    }

    private fun fingerprint(number: String, body: String, sub: Int?, delivery: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$number\n${sub ?: ""}\n$delivery\n$body".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_PENDING = 16
        const val IDEMPOTENCY_TTL_MS = 24L * 60 * 60 * 1000
    }
}

object SecureIds {
    private val random = SecureRandom()

    /** 128 random bits as 32 lowercase hex characters. */
    fun newMessageId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
