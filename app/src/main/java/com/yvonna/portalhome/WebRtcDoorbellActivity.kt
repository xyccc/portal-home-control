package com.yvonna.portalhome

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yvonna.portalhome.net.NestSdmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Plays a battery Nest doorbell/camera's WebRTC live stream inside a WebView.
 *
 * Portal has no Google Play Services, so the native libwebrtc stack isn't a good
 * fit. Instead the Chromium WebView does the WebRTC work: webrtc.html creates the
 * SDP offer, this activity relays it to SDM's GenerateWebRtcStream command, then
 * feeds the answer SDP back to the page.
 *
 * The stream is view-and-listen (receive-only). RECORD_AUDIO is still requested
 * because Chromium plays WebRTC remote audio through the native audio device in
 * MODE_IN_COMMUNICATION, which won't initialize without the mic permission — even
 * though we never transmit audio.
 */
class WebRtcDoorbellActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var nest: NestSdmClient
    private lateinit var resourceName: String

    @Volatile
    private var mediaSessionId: String = ""

    private var audioManager: AudioManager? = null
    private var useCommunicationAudio = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { loadStream() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resourceName = intent.getStringExtra(EXTRA_RESOURCE).orEmpty()
        if (resourceName.isEmpty()) {
            finish()
            return
        }
        nest = NestSdmClient(Config(this))

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // Route the volume keys to the media stream. Portal's Chromium WebView can
        // play remote WebRTC audio either as call audio or media audio depending on
        // the engine path, so routeAudioToSpeaker() raises both streams.
        volumeControlStream = AudioManager.STREAM_MUSIC

        webView = WebView(this)
        setContentView(webView)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webChromeClient = WebChromeClient()
        // Recover gracefully if the WebView renderer crashes instead of taking the
        // whole app down (returning true tells the system we've handled it).
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                runOnUiThread {
                    toast("Stream crashed — reopen to retry")
                    finish()
                }
                return true
            }
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        // RECORD_AUDIO is needed for WebRTC audio playout (communication mode),
        // not for transmitting. Load the stream once the prompt is answered;
        // load anyway if already granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            loadStream()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadStream() {
        webView.loadUrl("file:///android_asset/webrtc.html")
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    /** Bridge methods are invoked by webrtc.html on a background (binder) thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onOfferReady(offerSdp: String) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        nest.generateWebRtcStream(resourceName, offerSdp)
                    }
                }
                result.onSuccess { answer ->
                    mediaSessionId = answer.mediaSessionId
                    // setAnswer(sdp) — quote() escapes the SDP into a JS string literal.
                    webView.evaluateJavascript(
                        "setAnswer(${JSONObject.quote(answer.answerSdp)})", null
                    )
                }.onFailure { e ->
                    showStatus("Error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread { showStatus("Error: $message") }
        }

        @JavascriptInterface
        fun onConnected() {
            // Connection is up — now safe to switch audio to the speaker without
            // disrupting WebRTC negotiation.
            runOnUiThread { routeAudioToSpeaker(useCommunicationAudio) }
        }

        @JavascriptInterface
        fun toggleAudioRoute() {
            runOnUiThread {
                useCommunicationAudio = !useCommunicationAudio
                routeAudioToSpeaker(useCommunicationAudio)
                val route = if (useCommunicationAudio) "call" else "media"
                webView.evaluateJavascript("setAudioRoute(${JSONObject.quote(route)})", null)
            }
        }

        @JavascriptInterface
        fun onReconnect() {
            // The page is re-establishing after a drop — stop the stale media
            // session so we don't leak Nest stream slots.
            val sid = mediaSessionId
            val name = resourceName
            mediaSessionId = ""
            if (sid.isNotEmpty()) {
                Thread { runCatching { nest.stopWebRtcStream(name, sid) } }.start()
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebRtcDoorbell", message)
        }
    }

    private fun routeAudioToSpeaker(useCommunicationMode: Boolean) {
        audioManager?.apply {
            mode = if (useCommunicationMode) {
                AudioManager.MODE_IN_COMMUNICATION
            } else {
                AudioManager.MODE_NORMAL
            }
            isSpeakerphoneOn = true
            requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            raiseStreamVolume(AudioManager.STREAM_VOICE_CALL)
            raiseStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private fun AudioManager.raiseStreamVolume(stream: Int) {
        val max = getStreamMaxVolume(stream)
        if (getStreamVolume(stream) < max / 2) {
            setStreamVolume(stream, (max * 0.75).toInt(), 0)
        }
    }

    private fun showStatus(text: String) {
        webView.evaluateJavascript("setStatus(${JSONObject.quote(text)})", null)
    }

    override fun onDestroy() {
        // Best-effort release of the media session so the doorbell stops streaming.
        val sid = mediaSessionId
        val name = resourceName
        if (sid.isNotEmpty()) {
            Thread { runCatching { nest.stopWebRtcStream(name, sid) } }.start()
        }
        // Restore normal audio routing for the rest of the system.
        audioManager?.apply {
            mode = AudioManager.MODE_NORMAL
            isSpeakerphoneOn = false
            abandonAudioFocus(null)
        }
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESOURCE = "resource_name"
    }
}
