package `in`.imdevops.simbridge.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationPolicyTest {
    @Test fun anyCountryAllowedWhenNoListButPremiumRangesBlocked() {
        assertNull(DestinationPolicy.check("+919876543210", emptyList()))
        assertNull(DestinationPolicy.check("+14155552671", emptyList()))
        listOf("+979123456789", "+88212345678", "+88123456789", "+883510012345").forEach {
            assertNotNull("should block $it", DestinationPolicy.check(it, emptyList()))
        }
    }

    @Test fun allowListRestrictsCountries() {
        val allowed = listOf("+91")
        assertNull(DestinationPolicy.check("+919876543210", allowed))
        assertNotNull(DestinationPolicy.check("+14155552671", allowed))
        assertNull(DestinationPolicy.check("+14155552671", listOf("+91", "+1")))
        // the built-in block wins even if the owner allows the prefix
        assertNotNull(DestinationPolicy.check("+979123456789", listOf("+979")))
    }

    @Test fun parsing() {
        assertEquals(DestinationPolicy.Parsed.Ok(listOf("+91", "+1")), DestinationPolicy.parse(" +91, +1 ; +91"))
        assertEquals(DestinationPolicy.Parsed.Ok(emptyList()), DestinationPolicy.parse("   "))
        listOf("91", "+", "+0123", "+91x", "+1234567").forEach {
            assertTrue("should reject '$it'", DestinationPolicy.parse(it) is DestinationPolicy.Parsed.Bad)
        }
    }
}
