package `in`.imdevops.simbridge.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager

class AndroidSubscriptionProvider(context: Context) : SubscriptionProvider {
    private val appContext = context.applicationContext

    override fun lookup(): SubscriptionLookup {
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return SubscriptionLookup.PermissionMissing
        }
        val manager = appContext.getSystemService(SubscriptionManager::class.java)
            ?: return SubscriptionLookup.Available(emptyList(), null)
        val infos = try {
            manager.activeSubscriptionInfoList
        } catch (_: SecurityException) {
            return SubscriptionLookup.PermissionMissing
        } ?: emptyList()
        val defaultSms = SubscriptionManager.getDefaultSmsSubscriptionId()
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        return SubscriptionLookup.Available(
            sims = infos.map {
                SimInfo(
                    subscriptionId = it.subscriptionId,
                    slotIndex = it.simSlotIndex,
                    displayName = it.displayName?.toString()?.takeIf { n -> n.isNotBlank() }
                        ?: "SIM ${it.simSlotIndex + 1}",
                )
            },
            defaultSmsSubscriptionId = defaultSms,
        )
    }
}
