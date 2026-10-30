package `in`.imdevops.simbridge.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun acceptsAndNormalisesE164() {
        assertEquals(PhoneNumberResult.Valid("+919876543210"), PhoneNumbers.normalize(" +91 98765-43210 "))
        assertEquals(PhoneNumberResult.Valid("+14155552671"), PhoneNumbers.normalize("+1 (415) 555.2671"))
    }

    @Test
    fun rejectsEverythingElse() {
        listOf("", "   ", "9876543210", "+0123456789", "+12", "+1234567890123456", "+91abc4567890", "++919876543210", "00919876543210")
            .forEach { assertTrue("should reject '$it'", PhoneNumbers.normalize(it) is PhoneNumberResult.Invalid) }
    }
}
