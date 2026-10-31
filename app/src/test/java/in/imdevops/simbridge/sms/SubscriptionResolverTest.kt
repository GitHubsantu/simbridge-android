package `in`.imdevops.simbridge.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionResolverTest {
    private val sim1 = SimInfo(11, 0, "A")
    private val sim2 = SimInfo(22, 1, "B")

    private fun rejectedCode(r: SimResolution) = (r as SimResolution.Rejected).code

    @Test fun permissionMissing() =
        assertEquals("PERMISSION_DENIED", rejectedCode(SubscriptionResolver.resolve(null, SubscriptionLookup.PermissionMissing)))

    @Test fun noSim() =
        assertEquals("NO_ACTIVE_SIM", rejectedCode(SubscriptionResolver.resolve(null, SubscriptionLookup.Available(emptyList(), null))))

    @Test fun explicitSimIsHonoured() {
        val r = SubscriptionResolver.resolve(22, SubscriptionLookup.Available(listOf(sim1, sim2), 11))
        assertEquals(sim2, (r as SimResolution.Chosen).sim)
    }

    @Test fun unknownExplicitSimIsRejected() =
        assertEquals("INVALID_SUBSCRIPTION", rejectedCode(SubscriptionResolver.resolve(99, SubscriptionLookup.Available(listOf(sim1), null))))

    @Test fun defaultSmsSimUsedWhenNothingRequested() {
        val r = SubscriptionResolver.resolve(null, SubscriptionLookup.Available(listOf(sim1, sim2), 22))
        assertEquals(sim2, (r as SimResolution.Chosen).sim)
    }

    @Test fun singleSimUsedWithoutDefault() {
        assertTrue(SubscriptionResolver.resolve(null, SubscriptionLookup.Available(listOf(sim1), null)) is SimResolution.Chosen)
    }

    @Test fun ambiguousSimsAreNotGuessed() =
        assertEquals("SIM_SELECTION_REQUIRED", rejectedCode(SubscriptionResolver.resolve(null, SubscriptionLookup.Available(listOf(sim1, sim2), null))))

    @Test fun preferredSimBeatsDefaultButOnlyWhileActive() {
        val both = SubscriptionLookup.Available(listOf(sim1, sim2), 11)
        assertEquals(sim2, (SubscriptionResolver.resolve(null, both, preferred = 22) as SimResolution.Chosen).sim)
        // preferred SIM was removed: fall back to the default SMS SIM instead of failing
        val onlyOne = SubscriptionLookup.Available(listOf(sim1), 11)
        assertEquals(sim1, (SubscriptionResolver.resolve(null, onlyOne, preferred = 22) as SimResolution.Chosen).sim)
        // an explicit request still wins over the preference
        assertEquals(sim1, (SubscriptionResolver.resolve(11, both, preferred = 22) as SimResolution.Chosen).sim)
    }
}
