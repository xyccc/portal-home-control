package com.yvonna.portalhome.net

import com.yvonna.portalhome.Config
import com.yvonna.portalhome.model.DoorbellDevice
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Nest doorbell access via the Smart Device Management (SDM) REST API.
 * Setup: enable Device Access (one-time $5 fee), create a project, and complete
 * the OAuth consent once on desktop to obtain a refresh token (see tools/get_nest_token.py).
 * Docs: https://developers.google.com/nest/device-access/api
 *
 * SDM exposes doorbells as cameras: you get status and can mint a live stream URL,
 * but you cannot "ring" the doorbell. Event delivery (button presses) requires a
 * Pub/Sub subscription and is out of scope for this app.
 *
 * All methods are blocking; call them off the main thread.
 */
class NestSdmClient(private val cfg: Config) {

    private val client = Http.client
    private var token: String = ""
    private var tokenExpiresAt: Long = 0L

    fun listDoorbells(): List<DoorbellDevice> {
        val url = "$SDM_BASE/enterprises/${cfg.nestProjectId}/devices"
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("SDM list failed: ${resp.code} $body")
            val devices = JSONObject(body).optJSONArray("devices") ?: return emptyList()
            val out = mutableListOf<DoorbellDevice>()
            for (i in 0 until devices.length()) {
                val d = devices.getJSONObject(i)
                val type = d.optString("type")
                if (!type.contains("DOORBELL") && !type.contains("CAMERA")) continue
                val traits = d.optJSONObject("traits") ?: JSONObject()
                val name = traits.optJSONObject("sdm.devices.traits.Info")
                    ?.optString("customName").orEmpty().ifEmpty { type.substringAfterLast('.') }
                val online = traits.optJSONObject("sdm.devices.traits.Connectivity")
                    ?.optString("status") == "ONLINE"
                out += DoorbellDevice(d.getString("name"), name, type, online)
            }
            return out
        }
    }

    /**
     * Mints a live-stream URL for a wired camera/doorbell (RTSP). Battery-powered
     * Nest doorbells only support WebRTC and will return an error here.
     */
    fun generateRtspStream(resourceName: String): String {
        val payload = JSONObject()
            .put("command", "sdm.devices.commands.CameraLiveStream.GenerateRtspStream")
            .put("params", JSONObject())
            .toString()
        val req = Request.Builder()
            .url("$SDM_BASE/$resourceName:executeCommand")
            .header("Authorization", "Bearer ${accessToken()}")
            .post(payload.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Stream failed: ${resp.code} $body")
            return JSONObject(body).optJSONObject("results")
                ?.optJSONObject("streamUrls")?.optString("rtspUrl").orEmpty()
                .ifEmpty { error("No RTSP URL returned (battery doorbell? use WebRTC)") }
        }
    }

    /**
     * Starts a WebRTC live stream for battery-powered cameras/doorbells. Send the
     * local SDP offer; receive the answer SDP plus a media session id used to
     * extend or stop the stream. The session expires after ~5 minutes.
     */
    fun generateWebRtcStream(resourceName: String, offerSdp: String): WebRtcAnswer {
        val payload = JSONObject()
            .put("command", "sdm.devices.commands.CameraLiveStream.GenerateWebRtcStream")
            .put("params", JSONObject().put("offerSdp", offerSdp))
            .toString()
        val req = Request.Builder()
            .url("$SDM_BASE/$resourceName:executeCommand")
            .header("Authorization", "Bearer ${accessToken()}")
            .post(payload.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("WebRTC stream failed: ${resp.code} $body")
            val results = JSONObject(body).optJSONObject("results")
                ?: error("No results in WebRTC response: $body")
            val answer = results.optString("answerSdp")
            if (answer.isEmpty()) error("No answerSdp returned: $body")
            return WebRtcAnswer(
                answerSdp = answer,
                mediaSessionId = results.optString("mediaSessionId"),
                expiresAt = results.optString("expiresAt"),
            )
        }
    }

    /** Stops an active WebRTC session to free the camera/doorbell stream. */
    fun stopWebRtcStream(resourceName: String, mediaSessionId: String) {
        val payload = JSONObject()
            .put("command", "sdm.devices.commands.CameraLiveStream.StopWebRtcStream")
            .put("params", JSONObject().put("mediaSessionId", mediaSessionId))
            .toString()
        val req = Request.Builder()
            .url("$SDM_BASE/$resourceName:executeCommand")
            .header("Authorization", "Bearer ${accessToken()}")
            .post(payload.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Stop WebRTC failed: ${resp.code} ${resp.body?.string().orEmpty()}")
            }
        }
    }

    private fun accessToken(): String {
        if (token.isNotEmpty() && System.currentTimeMillis() < tokenExpiresAt) return token
        val form = FormBody.Builder()
            .add("client_id", cfg.nestClientId)
            .add("client_secret", cfg.nestClientSecret)
            .add("refresh_token", cfg.nestRefreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("Token refresh failed: ${resp.code} $body")
            token = body.getString("access_token")
            tokenExpiresAt = System.currentTimeMillis() + body.optLong("expires_in", 3600) * 1000 - 60_000
            return token
        }
    }

    companion object {
        private const val SDM_BASE = "https://smartdevicemanagement.googleapis.com/v1"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private val JSON = "application/json".toMediaType()
    }
}

/** Result of starting a WebRTC stream via SDM. */
class WebRtcAnswer(
    val answerSdp: String,
    val mediaSessionId: String,
    val expiresAt: String,
)
