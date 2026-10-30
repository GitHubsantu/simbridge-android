package `in`.imdevops.simbridge.api

import org.json.JSONException
import org.json.JSONObject

sealed interface ParsedSend {
    data class Ok(
        val to: String,
        val message: String,
        val subscriptionId: Int?,
        val requestDelivery: Boolean,
    ) : ParsedSend

    data class Bad(val message: String) : ParsedSend
}

/** Strict-typed reader for `POST /api/v1/messages` bodies. Unknown fields are ignored. */
object SendRequestParser {
    fun parse(text: String): ParsedSend {
        val obj = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return ParsedSend.Bad("Body must be a JSON object.")
        }
        val to = obj.opt("to") as? String ?: return ParsedSend.Bad("\"to\" must be a string.")
        if (to.length > 32) return ParsedSend.Bad("\"to\" is too long.")
        val message = obj.opt("message") as? String ?: return ParsedSend.Bad("\"message\" must be a string.")

        val subscriptionId: Int? = when (val raw = obj.opt("subscription_id")) {
            null, JSONObject.NULL -> null
            is Int -> raw
            is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt()
            else return ParsedSend.Bad("\"subscription_id\" is out of range.")
            else -> return ParsedSend.Bad("\"subscription_id\" must be an integer.")
        }
        val requestDelivery: Boolean = when (val raw = obj.opt("request_delivery")) {
            null, JSONObject.NULL -> true
            is Boolean -> raw
            else -> return ParsedSend.Bad("\"request_delivery\" must be true or false.")
        }
        return ParsedSend.Ok(to, message, subscriptionId, requestDelivery)
    }
}
