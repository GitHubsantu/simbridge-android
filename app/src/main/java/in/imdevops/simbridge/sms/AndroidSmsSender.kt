package `in`.imdevops.simbridge.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager

class AndroidSmsSender(context: Context) : SmsSender {
    private val appContext = context.applicationContext

    override fun partCount(body: String): Int = baseManager().divideMessage(body).size

    override fun submit(
        messageId: String,
        to: String,
        body: String,
        subscriptionId: Int,
        requestDelivery: Boolean,
    ): SubmitResult = try {
        val manager = managerFor(subscriptionId)
        val parts = manager.divideMessage(body)
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered: ArrayList<PendingIntent>? = if (requestDelivery) ArrayList(parts.size) else null
        for (i in parts.indices) {
            sent.add(pending(SmsResultReceiver.ACTION_SENT, messageId, i))
            delivered?.add(pending(SmsResultReceiver.ACTION_DELIVERED, messageId, i))
        }
        if (parts.size == 1) {
            manager.sendTextMessage(to, null, parts[0], sent[0], delivered?.get(0))
        } else {
            manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
        }
        SubmitResult.Submitted
    } catch (e: SecurityException) {
        SubmitResult.Failed("PERMISSION_DENIED", "Android denied the SMS permission.")
    } catch (e: IllegalArgumentException) {
        SubmitResult.Failed("INVALID_REQUEST", "Android rejected the message arguments.")
    } catch (e: Exception) {
        // Deliberately not including e.message: it can contain the number or text.
        SubmitResult.Failed("SUBMIT_FAILED", "Android could not accept the message (${e.javaClass.simpleName}).")
    }

    private fun baseManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun managerFor(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            baseManager().createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    /** Explicit intent to a non-exported receiver; the unique data URI keeps every PendingIntent distinct. */
    private fun pending(action: String, messageId: String, part: Int): PendingIntent {
        val intent = Intent(appContext, SmsResultReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("simbridge://sms/$messageId/$action/$part"))
            .putExtra(SmsResultReceiver.EXTRA_ID, messageId)
        return PendingIntent.getBroadcast(
            appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
