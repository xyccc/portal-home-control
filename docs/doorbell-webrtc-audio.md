# Nest Doorbell WebRTC — Audio Debugging Log

Status: **UNRESOLVED — video works, audio is received but inaudible.**

## Goal

Hear the visitor's audio from a **battery** Nest doorbell, streamed via the SDM
(Device Access) REST API's `GenerateWebRtcStream`, rendered in a Chromium
**WebView** on a **Meta Portal** (AOSP, no Google Play Services).

Files involved:
- `app/src/main/assets/webrtc.html` — the WebRTC peer (offer/answer, rendering)
- `app/src/main/java/com/yvonna/portalhome/WebRtcDoorbellActivity.kt` — WebView host, SDP relay, audio routing
- `app/src/main/java/com/yvonna/portalhome/net/NestSdmClient.kt` — `generateWebRtcStream` / `stopWebRtcStream`

## What works ✅

- **Video** streams and renders fine.
- The peer connection **establishes**: `ice: connected`, `conn: connected`.
- The **audio track arrives**: logcat shows `track: audio`, `answer tracks: audio, video`, `answer has m=audio: true`.
- **Audio media is flowing**: the on-screen `audio pkts` counter climbs steadily — so RTP audio is being received and decoded by Chromium.

So the problem is purely **audio OUTPUT/routing on the Portal**, not the stream.

## What does NOT work ❌

- No audible audio from the Portal speaker, in any configuration tried below.

## Key findings

1. **WebRTC remote audio does NOT play through the HTML `<video>` element.**
   Chromium routes WebRTC audio straight to the native Android audio device
   (AudioDeviceModule), bypassing the element. So `video.muted = false`,
   `video.volume = 1`, and the "Tap for sound" button are **irrelevant** to
   WebRTC audio — they were a red herring. (Left a "tap for sound" path in place
   but it does not control this audio.)

2. **Two-way talk is impossible via SDM.** `GenerateWebRtcStream` rejects any
   sendrecv audio offer: `400 ... offer audio must be recvonly`. The offer
   m-lines must be exactly **audio(recvonly), video(recvonly), application** in
   that order. Talkback needs the Google Home APIs SDK (GMS-only, unavailable on
   Portal). Reverted.

## Attempts (chronological)

| # | Change | Result |
|---|--------|--------|
| 1 | `<video>` autoplay muted, then unmute in JS; later a "Tap for sound" button | Still silent. Established that WebRTC audio bypasses the `<video>` element entirely (finding #1). |
| 2 | `AudioManager.mode = MODE_IN_COMMUNICATION` + `isSpeakerphoneOn = true` set in `onCreate` (before connect) | **Broke the connection** (`conn: failed`). Comm-mode audio-device init during ICE negotiation, with no mic permission, destabilized the peer connection. |
| 3 | Moved the comm-mode switch to **after** `conn: connected` (`routeAudioToSpeaker()` via an `onConnected` JS→Kotlin callback) | Connection works again, media flows ~45s. **Still silent.** Also observed: `ERR_ADDRESS_UNREACHABLE` flood, `No decodable frame ... requesting keyframe`, drop to `disconnected`/`failed` ~90s in, and a WebView **renderer crash** on repeated opens. |
| 4 | Re-added **`RECORD_AUDIO`** permission (+ `MODIFY_AUDIO_SETTINGS`) and request it at runtime — theory: comm-mode audio playout can't initialize without the mic permission | **Still silent.** |
| — | Added robustness alongside #3/#4: auto-reconnect on `failed` (≤3 tries), idempotent stats timer, `onRenderProcessGone` → finish gracefully, route volume keys to `STREAM_VOICE_CALL`, bump call volume to 70% if low | Connection more resilient; audio still silent. |

## Environmental observations (from logcat)

- `net::ERR_ADDRESS_UNREACHABLE` on `sendto()` — constant. Portal is trying to
  reach **IPv6** ICE candidates it can't route (`stun_port.cc ... Binding request
  timed out from [0:0:...]`). The connection still finds a working IPv4 path, but
  this is noise + may contribute to instability.
- Connection drops ~90s after connecting; Nest WebRTC sessions are also inherently
  time-limited (~5 min).
- WebView **renderer process crash** (`aw_browser_terminator ... crash detected`)
  after several reopen cycles — likely memory pressure across spawned processes.

## Leading hypothesis

The Portal's AOSP audio HAL + its bundled WebView build does not route the WebRTC
AudioDeviceModule output to the speaker, regardless of `AudioManager` mode. Audio
is decoded (packets climb) but never reaches an audible output device. This may be
a Portal-specific limitation we cannot fix from the app layer in a WebView.

## Untried next steps (in rough priority)

1. **Isolate Portal vs. code:** install the same APK on a normal Android phone and
   open the doorbell. If audio plays there → it's the Portal's audio HAL/WebView
   (likely unfixable in-WebView). If silent there too → it's our code/SDM and the
   bug is reproducible/fixable.
2. **Native WebRTC (`io.github.webrtc-sdk:android`)** instead of the WebView. The
   native stack exposes the remote `AudioTrack` and lets you explicitly create the
   Android audio output / set the stream type and routing. This is the most likely
   path to actually control audio output on Portal, at the cost of a ~native dep
   and ~250 lines of PeerConnection code. (Native libwebrtc has no GMS dependency,
   so it should run on Portal.)
3. **Audio focus:** try `requestAudioFocus` + `STREAM_MUSIC` in `MODE_NORMAL`
   (instead of comm mode) to see if Chromium will then output to the media stream.
4. **Disable IPv6 candidates / supply a TURN server** to clean up ICE and rule out
   the `ERR_ADDRESS_UNREACHABLE` instability affecting audio.

## Current state of the code

View-and-listen offer (`audio recvonly, video recvonly, application`), post-connect
`MODE_IN_COMMUNICATION` + speakerphone, `RECORD_AUDIO` requested, auto-reconnect and
renderer-crash recovery in place. On-screen diagnostics (green = events, blue =
packet counts) are still enabled and should be removed once audio is resolved.
