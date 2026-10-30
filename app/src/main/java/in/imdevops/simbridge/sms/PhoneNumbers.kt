package `in`.imdevops.simbridge.sms

sealed interface PhoneNumberResult {
    data class Valid(val e164: String) : PhoneNumberResult
    data class Invalid(val reason: String) : PhoneNumberResult
}

/** Strict E.164 only: a leading '+', country code, 8-15 digits in total. No guessing of country. */
object PhoneNumbers {
    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

    fun normalize(input: String): PhoneNumberResult {
        val cleaned = input.trim().filter { it != ' ' && it != '-' && it != '(' && it != ')' && it != '.' }
        if (cleaned.isEmpty()) return PhoneNumberResult.Invalid("Phone number is empty.")
        if (!cleaned.startsWith("+")) {
            return PhoneNumberResult.Invalid("Use international format with country code, e.g. +91XXXXXXXXXX.")
        }
        if (!E164.matches(cleaned)) {
            return PhoneNumberResult.Invalid("Not a valid international phone number.")
        }
        return PhoneNumberResult.Valid(cleaned)
    }
}
