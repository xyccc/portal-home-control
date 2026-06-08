package com.yvonna.portalhome.net

import com.yvonna.portalhome.Config
import com.yvonna.portalhome.model.LightDevice
import com.yvonna.portalhome.model.Source
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Philips Hue control via the local CLIP v2 REST API on the bridge.
 * Docs: https://developers.meethue.com/develop/hue-api-v2/
 *
 * All methods are blocking; call them off the main thread.
 */
class HueClient(private val cfg: Config) {

    private val client = Http.insecureLocalClient
    private val base get() = "https://${cfg.hueBridgeIp}/clip/v2/resource/light"

    fun listLights(): List<LightDevice> {
        val req = Request.Builder()
            .url(base)
            .header("hue-application-key", cfg.hueAppKey)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Hue list failed: ${resp.code} $body")
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).map { i ->
                val light = data.getJSONObject(i)
                val name = light.optJSONObject("metadata")?.optString("name").orEmpty()
                    .ifEmpty { "Light ${i + 1}" }
                // The "dimming" object is only present on dimmable lights; its
                // "brightness" is a 0-100 percentage (float).
                val brightness = light.optJSONObject("dimming")
                    ?.optDouble("brightness")
                    ?.takeIf { !it.isNaN() }
                    ?.roundToInt()
                LightDevice(
                    id = light.getString("id"),
                    name = name,
                    on = light.optJSONObject("on")?.optBoolean("on") ?: false,
                    source = Source.HUE,
                    brightness = brightness,
                )
            }
        }
    }

    fun setOn(id: String, on: Boolean) {
        val payload = JSONObject().put("on", JSONObject().put("on", on)).toString()
        val req = Request.Builder()
            .url("$base/$id")
            .header("hue-application-key", cfg.hueAppKey)
            .put(payload.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Hue setOn failed: ${resp.code} ${resp.body?.string().orEmpty()}")
            }
        }
    }

    /**
     * Sets brightness as a percentage (1-100). Also turns the light on, so dragging
     * the slider always produces visible feedback even when the light was off.
     */
    fun setBrightness(id: String, brightness: Int) {
        val payload = JSONObject()
            .put("on", JSONObject().put("on", true))
            .put("dimming", JSONObject().put("brightness", brightness.coerceIn(1, 100)))
            .toString()
        val req = Request.Builder()
            .url("$base/$id")
            .header("hue-application-key", cfg.hueAppKey)
            .put(payload.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Hue setBrightness failed: ${resp.code} ${resp.body?.string().orEmpty()}")
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
