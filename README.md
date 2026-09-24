# RTSP Module (`rtsp`)

Real-time streaming library for Android: RTSP client + RTP receiver, depacketization, and `MediaCodec`-based decoding.

- **Package:** `com.catanddev.rtsp`
- **Minimum SDK:** 28 (Android 9)
- **Features:** RTSP session control (Basic/Digest auth, SDP), RTP transport for H.264 / H.265 video and AAC audio, hardware-first decoding, live streaming statistics, and a plain-UDP (RTP-only) mode.

---

## 1. High-level data flow

```
 Camera / server ──► RtspClient (TCP RTSP) ──► RtspProcessor ──► VideoFrameQueue
      │                                                     │
      └────────► RtpServer (UDP RTP) ──► RtspProcessor ──────┘
                                                     │
                                                     ▼
                                          VideoDecoderSurfaceThread
                                                     │
                                                     ▼
                                                  Surface
```

`RtspController` is the façade. It owns an `RtspProcessor` (and, in RTP mode, an `RtpServer`), and feeds a `Surface` that the app provides (typically from `StreamView`).

---

## 2. Build configuration

| Setting      | Value                            |
|--------------|----------------------------------|
| `minSdk`     | 28 (Android 9)                   |
| Package      | `com.catanddev.rtsp`             |
| Dependencies | androidx.media3 (container, common, decoder) |
| BuildConfig  | enabled — drives `DEBUG` flags   |

Verbose logging (`RtspController.DEBUG`, `RtspClient.DEBUG`, `RtpServer.DEBUG`) is wired to `BuildConfig.DEBUG`, so per-packet logging is compiled out of release builds. **Functional code paths never depend on the DEBUG flag.**

---

## 3. Public API

### 3.1 `RtspController` (façade)

```kotlin
class RtspController(
    context: Context,
    val mode: OPERATION_MODE,     // RTSP or RTP
    val surface: Surface,         // rendering target, owned by the app's view
    viewWidth: Int,
    viewHeight: Int,
    val frameHandler: RtspControllerCallbacks?   // onFrameAvailable()
)
```

```kotlin
enum class OPERATION_MODE { RTSP, RTP }
enum class VideoCodec { H264, H265 }
```

| Member | Description |
|---|---|
| `var isInitialized: Boolean` | `true` after a successful `initRTSP()` / `initRtp()`, `false` after `destroy()`. Read-only for callers. |
| `var isActive: Boolean` | `true` while a stream is running between `start()` and `stop()`. |
| `var videoCodec: VideoCodec` | Codec for the current session (default `H264`). Public setter. |
| `var onVideoSizeChanged: ((width, height, rotation) -> Unit)?` | Fired on the main thread when the real resolution/rotation becomes known (SPS parsing or decoder output format). |
| `fun initRTSP(address: Uri, username: String, password: String): Boolean` | Prepare an RTSP session. Returns `false` if the controller is in RTP mode. |
| `fun initRtp(bindAddress, port: UShort, payloadType: Int, videoCodec = this.videoCodec): Boolean` | Prepare a UDP RTP listener. |
| `fun start(requestVideo = true, requestAudio = false, requestApplication = false)` | Start the pipeline, set `isActive = true`. |
| `fun stop()` | Stop server, session and decoders; set `isActive = false`. |
| `fun getStats(): RtpStats?` | Recalculate and return streaming metrics. |
| `fun updateViewSize(newWidth, newHeight)` | Propagate a view resize to the decoder. |
| `fun destroy()` | Stop and release internals. **Does not release the `Surface`** — ownership stays with the view. |

### 3.2 `RtspClient.Builder`

```kotlin
RtspClient.Builder(socket: Socket, uriRtsp: String, exitFlag: AtomicBoolean, listener: RtspClientListener)
    .withDebug(Boolean)                    // dump RTSP traffic to Logcat
    .withCredentials(username, password)   // Basic or Digest auth
    .withUserAgent(userAgent)
    .requestVideo(true)
    .requestAudio(false)
    .requestApplication(false)
    .build()
```

`RtspClientListener` callbacks:

- `onRtspConnecting / onRtspConnected(sdpInfo) / onRtspDisconnecting / onRtspDisconnected`
- `onRtspVideoNalUnitReceived(data, offset, length, timestamp, seq, marker)`
- `onRtspAudioSampleReceived(...)` / `onRtspApplicationDataReceived(...)`
- `onRtspFailedUnauthorized()` / `onRtspFailed(message)`

`SdpInfo` exposes `VideoTrack` (codec, SPS/PPS/VPS), `AudioTrack` (sample rate, channels, config) and `ApplicationTrack`.

### 3.3 `RtspProcessor` (`com.catanddev.rtsp.widget`)

| Property | Description |
|---|---|
| `videoWidth`, `videoHeight` | Last known resolution (from SPS). |
| `videoRotation` | Stream rotation 0/90/180/270. |
| `videoMimeType` | `"video/avc"` or `"video/hevc"`. |
| `videoFrameQueue` / `audioFrameQueue` | Bounded queues (capacity 60) feeding the decoders. |
| `rtpStats` / `getRtpStats()` | Live metrics (see §5). |
| `statusListener: RtspStatusListener?` | Status + size callbacks. |
| `dataListener: RtspDataListener?` | Raw stream passthrough (recording). |
| `videoDecoderType` | `DecoderType.HARDWARE` or `SOFTWARE`. |

Key functions: `init(uri, username, password, userAgent)`, `initRtp(...)`, `start(...)`, `stop()`, `isStarted()`, `stopDecoders()`, `updateDecoderSurface(surface)`, `onRtpPacketReceived(...)`.

### 3.4 Listeners

```kotlin
interface RtspStatusListener {                       // all methods optional
    fun onRtspStatusConnecting() {}
    fun onRtspStatusConnected() {}
    fun onRtspFirstFrameRendered() {}
    fun onRtspFrameSizeChanged(width, height) {}     // decoder output, rotation applied
    fun onRtspVideoSizeChanged(width, height, rotation) {} // from SPS parsing
    fun onRtspStatusFailed(message: String?) {}
    fun onRtspStatusFailedUnauthorized() {}
    ...
}

interface RtspDataListener {                         // raw data for recorders
    fun onRtspDataVideoNalUnitReceived(data, offset, length, timestamp) {}
    fun onRtspAudioSampleReceived(...) {}
    fun onRtspNoFrames() {}
}
```

---

## 4. Pipeline internals

| Class | Role |
|---|---|
| `RtspClient` | RTSP session: `OPTIONS` → `DESCRIBE` (SDP) → `SETUP` → `PLAY`, keep-alive, Basic/Digest auth, socket I/O, NAL-unit reading. |
| `RtspProcessor` | Glue: frame assembly, keyframe detection, SPS parse, size/rotation events, queueing. |
| `RtpServer` | UDP listener for RTP-only mode. |
| `parser/RtpHeaderParser`, `RtpH264Parser`, `RtpH265Parser`, `AacParser` | RTP depacketization (FU-A/FU-B fragmentation, STAP-A, AAC ADTS). |
| `codec/VideoDecoderSurfaceThread` | `MediaCodec` decoder rendering into the provided `Surface`; tolerant of invalid surfaces. |
| `codec/VideoDecodeThread` | Surface-less decoding; computes real resolution/rotation on `INFO_OUTPUT_FORMAT_CHANGED`. |
| `codec/AudioDecodeThread` | AAC → PCM and `AudioTrack` playback. |
| `codec/FrameQueue` | Bounded thread-safe frame queues; drops frames when full. |
| `codec/MediaCodecHelper`, `MediaCodecUtils` | Decoder selection: hardware-first, low-latency detection, per-device workarounds. |
| `utils/VideoCodecUtils` | NAL parsing, SPS extraction, keyframe detection (androidx.media3 `NalUnitUtil`). |
| `stats/RtpStats` | Streaming metrics. |
| `utils/NetUtils` | TLS socket helper (trust-all — fixture only). |

**Decoding path (RTSP and RTP modes share the same handler):**

1. `RtspClient.execute()` (RTSP) or `RtpServer` (plain UDP) produces NAL units.
2. Both feed `RtspProcessor.handleVideoNalUnit()`: RTP statistics, keyframe detection, SPS parsing → resolution/rotation → `onRtspVideoSizeChanged` → `RtspController.onVideoSizeChanged`.
3. Frames go into `VideoFrameQueue`; `VideoDecoderSurfaceThread` decodes them and renders into the app-provided `Surface`.
4. The decoder's `INFO_OUTPUT_FORMAT_CHANGED` reports the true post-crop size via `onRtspFrameSizeChanged`.

In RTP mode the decoder is started **before** the UDP socket, so early packets are not dropped by the `VideoDecodeThread.started` gate. `RtpServer` forwards the real RTP `sequenceNumber`/`marker`, so loss/jitter metrics are meaningful.

---

## 5. Statistics (`RtpStats`)

`RtspController.getStats()` recalculates and returns `RtpStats.Stats`:

- **Stream:** `width`, `height`, `fps`, `bitrateMbps`, `inputBitrateMbps`.
- **Network:** `packetsLost`, `packetLossPercent`, `outOfOrderPackets`, `maxBurstLoss`, `totalPacketsReceived`, `bytesReceived`.
- **Timing:** `jitterMs` (RFC 3550), `clockRate` (auto-detected, default 90 kHz), packet gap min/avg/max.
- **Latency:** `networkLatencyMs`, `decodeLatencyMs`, `totalLatencyMs`.

Call `calculateStats()` (or `getStats()`) before reading values.

---

## 6. Compatibility notes (Android 9 / API 28)

- `minSdk = 28`. API-29+ methods (e.g. `MediaCodecInfo.isHardwareAccelerated()`) are guarded by `Build.VERSION.SDK_INT` checks, with a name-based heuristic on older devices.
- Inlined constants (e.g. `FEATURE_LowLatency`) are compile-time strings — safe on API 28.
- Vendor low-latency parameters are only queried on API 31+.
- With `BuildConfig.DEBUG == false` all per-packet logging is skipped.

---

## 7. Integration quick start

```kotlin
val controller = RtspController(
    context,
    RtspController.OPERATION_MODE.RTSP,
    streamView.getSurface()!!,
    streamView.width, streamView.height, null
)

controller.onVideoSizeChanged = { width, height, rotation ->
    runOnUiThread {
        if (controller.isActive) {
            streamView.setVideoSize(width, height)
            streamView.setVideoRotation(rotation)
        }
    }
}

controller.initRTSP(url.toUri(), login, password)
controller.start(true, false, false)
```

**Lifecycle:** on `onPause()` call `controller.stop()`; on `onResume()` restart **only if your own play-state flag is true** (not `controller.isActive`, which is `false` after `stop()`) and `streamView.isSurfaceReady()`.

**View resize:** `controller.updateViewSize(width, height)`.

**Metrics:** `val stats = controller.getStats()?.stats`.

---

## 8. Known limitations

- STAP-B / aggregated NAL units are not parsed by `RtpH264Parser`.
- `FrameQueue` drops frames silently when full (capacity 60) — no drop counter exposed.
- Audio: AAC (ADTS) only.
- `NetUtils` uses a trust-all `X509TrustManager` — replace for production TLS.
- Software decode can be forced per-device via `MediaCodecHelper` workarounds.

---

## 9. Debugging

- Use a **debug** variant so `BuildConfig.DEBUG` logging is active: RTSP request/response dumps, per-frame NAL listings, decoder selection logs.
- Watch Logcat for `MediaCodecHelper` device-specific workarounds and the chosen decoder (hardware vs. software).
- Log `RtpStats.Stats` to diagnose packet loss, jitter and latency.

---

## 10. Performance notes

Hot-path optimizations (all allocation-free per packet/frame):

- **`memcmp` (both `RtspClient` and `VideoCodecUtils`)** compares by index instead of `sliceArray`. This was the dominant allocation source: it is called for every byte position while searching for a NAL start code (thousands of arrays per keyframe check).
- **`RtspClient` input is wrapped in a `BufferedInputStream`** (16 KiB), so `readLine`/`readUntilBytesFound`/`readData` no longer issue a syscall per byte.
- **`RtpServer`** parses the datagram and payload from reusable buffers (`packetBuffer`, `payloadBuffer`) instead of per-packet `copyOfRange`, and sets a 4 MiB socket `receiveBufferSize` to survive bitrate bursts.
- **`RtpStats`** no longer keeps an unused per-packet `PacketInfo` history (which caused one allocation per packet plus an `O(n)` `removeAt(0)`).
- Debug logging is fully compiled out when `BuildConfig.DEBUG == false`; no functional path depends on the flag.