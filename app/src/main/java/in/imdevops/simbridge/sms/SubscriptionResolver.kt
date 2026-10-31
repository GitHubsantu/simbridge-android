package `in`.imdevops.simbridge.sms

sealed interface SimResolution {
    data class Chosen(val sim: SimInfo) : SimResolution
    data class Rejected(val code: String, val message: String) : SimResolution
}

/** Decides which SIM to use. Never guesses when more than one SIM could be meant. */
object SubscriptionResolver {
    fun resolve(requested: Int?, lookup: SubscriptionLookup, preferred: Int? = null): SimResolution {
        val available = when (lookup) {
            SubscriptionLookup.PermissionMissing -> return SimResolution.Rejected(
                "PERMISSION_DENIED", "Phone-state permission is needed to list SIMs.",
            )
            is SubscriptionLookup.Available -> lookup
        }
        if (available.sims.isEmpty()) {
            return SimResolution.Rejected("NO_ACTIVE_SIM", "No active SIM was found.")
        }
        if (requested != null) {
            val match = available.sims.firstOrNull { it.subscriptionId == requested }
                ?: return SimResolution.Rejected("INVALID_SUBSCRIPTION", "The requested SIM is not active.")
            return SimResolution.Chosen(match)
        }
        // A remembered preference is used only while that SIM is still active; otherwise fall back to automatic.
        preferred?.let { id -> available.sims.firstOrNull { it.subscriptionId == id } }?.let { return SimResolution.Chosen(it) }
        available.sims.firstOrNull { it.subscriptionId == available.defaultSmsSubscriptionId }
            ?.let { return SimResolution.Chosen(it) }
        if (available.sims.size == 1) return SimResolution.Chosen(available.sims.first())
        return SimResolution.Rejected(
            "SIM_SELECTION_REQUIRED", "More than one SIM is active; choose one explicitly.",
        )
    }
}
