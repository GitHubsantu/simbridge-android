package `in`.imdevops.simbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import `in`.imdevops.simbridge.SimBridgeApplication
import kotlinx.coroutines.launch

/** Receives sent/delivery callbacks. Declared in the manifest (not exported) so Android can wake the app. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext as SimBridgeApplication
        val service = app.messageService
        // Read everything from the Intent now; the database write happens off the main thread.
        val code = resultCode
        val deliveryStatus: Int? = if (intent.action == ACTION_DELIVERED) {
            val pdu = intent.getByteArrayExtra("pdu") ?: return
            try {
                SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status
            } catch (_: Exception) {
                return
            }
        } else null
        val action = intent.action
        val pending = goAsync()
        app.appScope.launch {
            try {
                when (action) {
                    ACTION_SENT -> service.onSentResult(id, code)
                    ACTION_DELIVERED -> deliveryStatus?.let { service.onDeliveryStatus(id, it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENT = "in.imdevops.simbridge.SMS_SENT"
        const val ACTION_DELIVERED = "in.imdevops.simbridge.SMS_DELIVERED"
        const val EXTRA_ID = "message_id"
    }
}
