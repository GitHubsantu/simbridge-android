package `in`.imdevops.simbridge.api

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTextTest {
    @Test
    fun quotesAndEscapes() {
        assertEquals("\"plain\"", JsonText.quote("plain"))
        assertEquals("\"a\\\"b\"", JsonText.quote("a\"b"))
        assertEquals("\"a\\\\b\"", JsonText.quote("a\\b"))
        assertEquals("\"l1\\nl2\"", JsonText.quote("l1\nl2"))
        assertEquals("\"\\u0001\"", JsonText.quote("\u0001"))
    }
}
