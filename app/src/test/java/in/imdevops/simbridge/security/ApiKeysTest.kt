package `in`.imdevops.simbridge.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeysTest {
    @Test
    fun generatedKeysAreUniquePrefixedAndLongEnough() {
        val a = ApiKeys.generate()
        val b = ApiKeys.generate()
        assertNotEquals(a, b)
        assertTrue(a.startsWith(ApiKeys.PREFIX))
        // 32 bytes -> 43 URL-safe Base64 chars without padding
        assertEquals(ApiKeys.PREFIX.length + 43, a.length)
    }

    @Test
    fun matchesAcceptsOnlyTheOriginalKey() {
        val key = ApiKeys.generate()
        val stored = ApiKeys.hash(key)
        assertTrue(ApiKeys.matches(key, stored))
        assertFalse(ApiKeys.matches(key + "x", stored))
        assertFalse(ApiKeys.matches(ApiKeys.generate(), stored))
        assertFalse(ApiKeys.matches(null, stored))
        assertFalse(ApiKeys.matches(key, null))
    }

    @Test
    fun parseBearerHandlesValidAndMalformedHeaders() {
        assertEquals("abc", ApiKeys.parseBearer("Bearer abc"))
        assertEquals("abc", ApiKeys.parseBearer("bearer   abc  "))
        assertNull(ApiKeys.parseBearer(null))
        assertNull(ApiKeys.parseBearer(""))
        assertNull(ApiKeys.parseBearer("Bearer "))
        assertNull(ApiKeys.parseBearer("Basic abc"))
        assertNull(ApiKeys.parseBearer("abc"))
        assertNull(ApiKeys.parseBearer("Bearer " + "a".repeat(300)))
    }
}
