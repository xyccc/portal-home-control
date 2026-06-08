package com.yvonna.portalhome.net

import com.yvonna.portalhome.Config
import com.yvonna.portalhome.model.LightDevice
import com.yvonna.portalhome.model.Source
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Feit bulbs are Tuya OEM devices, controlled through the Tuya Cloud API.
 * Requires a Tuya IoT Platform Cloud project (access id/secret) with the linked
 * Feit/SmartLife account UID. Signing follows Tuya's v2 HMAC-SHA256 scheme:
 * https://developer.tuya.com/en/docs/iot/api-request?id=Ka4a8uuo1j4t4
 *
 * All methods are blocking; call them off the main thread.
 */
class TuyaClient(private val cfg: Config) {

    private val client = Http.client
    private val host get() = "https://${cfg.tuyaRegion}"

    private var token: String = ""
    private var tokenExpiresAt: Long = 0L

    /** Remembers which DP code drives each bulb's on/off, learned at list time. */
    private val switchCodeById = mutableMapOf<String, String>()

    fun listLights(): List<LightDevice> {
        val path = "/v1.0/users/${cfg.tuyaUid}/devices"
        val resp = JSONObject(signedGet(path))
        if (!resp.optBoolean("success", false)) error("Tuya list failed: $resp")
        val result = resp.optJSONArray("result") ?: return emptyList()
        val lights = mutableListOf<LightDevice>()
        for (i in 0 until result.length()) {
            val d = result.getJSONObject(i)
            val status = d.optJSONArray("status") ?: JSONArray()
            val (code, value) = findSwitch(status) ?: continue
            switchCodeById[d.getString("id")] = code
            lights += LightDevice(
                id = d.getString("id"),
                name = d.optString("name", "Feit device"),
                on = value,
                source = Source.TUYA,
            )
        }
        return lights
    }

    fun setOn(id: String, on: Boolean) {
        val code = switchCodeById[id] ?: "switch_led"
        val body = JSONObject()
            .put("commands", JSONArray().put(JSONObject().put("code", code).put("value", on)))
            .toString()
        val resp = JSONObject(signedPost("/v1.0/devices/$id/commands", body))
        if (!resp.optBoolean("success", false)) error("Tuya setOn failed: $resp")
    }

    /** Returns the first boolean on/off DP and its current value, if present. */
    private fun findSwitch(status: JSONArray): Pair<String, Boolean>? {
        for (i in 0 until status.length()) {
            val s = status.getJSONObject(i)
            val code = s.optString("code")
            if (code in SWITCH_CODES && !s.isNull("value") && s.get("value") is Boolean) {
                return code to s.getBoolean("value")
            }
        }
        return null
    }

    // --- signing & transport ---

    private fun ensureToken() {
        if (token.isNotEmpty() && System.currentTimeMillis() < tokenExpiresAt) return
        val path = "/v1.0/token?grant_type=1"
        val t = System.currentTimeMillis().toString()
        val sign = sign(cfg.tuyaAccessId + t + stringToSign("GET", path, ""))
        val req = Request.Builder()
            .url(host + path)
            .header("client_id", cfg.tuyaAccessId)
            .header("sign", sign)
            .header("t", t)
            .header("sign_method", "HMAC-SHA256")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = JSONObject(resp.body?.string().orEmpty())
            if (!body.optBoolean("success", false)) error("Tuya token failed: $body")
            val result = body.getJSONObject("result")
            token = result.getString("access_token")
            tokenExpiresAt = System.currentTimeMillis() + result.getLong("expire_time") * 1000 - 60_000
        }
    }

    private fun signedGet(path: String): String = signed("GET", path, null)

    private fun signedPost(path: String, body: String): String = signed("POST", path, body)

    private fun signed(method: String, path: String, body: String?): String {
        ensureToken()
        val t = System.currentTimeMillis().toString()
        val sign = sign(cfg.tuyaAccessId + token + t + stringToSign(method, path, body ?: ""))
        val builder = Request.Builder()
            .url(host + path)
            .header("client_id", cfg.tuyaAccessId)
            .header("access_token", token)
            .header("sign", sign)
            .header("t", t)
            .header("sign_method", "HMAC-SHA256")
        val req = if (body == null) {
            builder.get().build()
        } else {
            builder.post(body.toRequestBody(JSON)).build()
        }
        client.newCall(req).execute().use { resp ->
            return resp.body?.string().orEmpty()
        }
    }

    private fun stringToSign(method: String, path: String, body: String): String =
        method + "\n" + sha256Hex(body) + "\n" + "" + "\n" + path

    private fun sign(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cfg.tuyaAccessSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02X".format(it) }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val SWITCH_CODES = setOf("switch_led", "switch_1", "switch")
    }
}
