# Nosmai + Agora — native Android example

Publishes a **Nosmai-filtered camera feed** to an Agora RTC channel from a plain
Kotlin app. AR effects and beauty at full frame rate, with **no readback and no
colour conversion** — the filtered frame never leaves the GPU.

> There is a matching [native iOS example](https://github.com/nosmai/nosmai-agora-ios-example).
> Using Flutter? The Nosmai Flutter plugin wraps this for you.

---

## The model: Nosmai owns the camera, Agora publishes

This is the one thing to understand before reading the code.

```
Camera2 (YUV) ──► Nosmai (filters + AR) ──┬──► on-screen preview
                                          └──► Agora encoder ──► channel ──► remote
```

Agora never opens a camera (`publishCameraTrack = false`). If you let it, you get
a second capture pipeline competing with Nosmai's for the GPU, and most of your
frame rate disappears.

Per frame, on the GPU only:

1. Nosmai renders the filtered result into a portrait 720×1280 texture.
2. Agora's encoder thread blits that into a texture it owns.
3. The encoder reads it.

No `glReadPixels`. No I420 convert. No CPU copies.

---

## The ordering constraint (read this one)

Agora's encoder samples the pushed texture **on its own GL thread, in its own EGL
context**. A texture id from Nosmai's context only means anything there if the two
contexts are in the same **EGL share group** — and a GL context cannot join a
share group after it has been created.

So the order is fixed:

```kotlin
AgoraManager.initialize(context, AGORA_APP_ID)   // creates the engine,
                                                 // registers its EGL context
NosmaiSDK.initialize(context, LICENSE_KEY)       // ...then Nosmai builds its
                                                 // context inside that group
```

**In the wrong order the share simply is not in effect**, the two contexts end
up unrelated, and the remote viewer sees black. `MainActivity` does this first,
and reports on screen which path it ended up on.

Two details inside that, both handled in `AgoraManager.setUpTextureStreaming`:

- **`System.loadLibrary("nosmai")` must come first.** `NosmaiSDK.initialize()`
  would load it for you, but we are deliberately running before that call, and
  `setAgoraShareContext` needs the native binding to already exist.
- **Use Agora's *root* EGL context**, not a local one:
  `EglBaseProvider.instance().rootEglBase`. The encoder shares with the root, and
  `TextureBufferHelper` seeds its own GL thread from it. A local context compiles
  and runs and produces a black remote.

---

## Setup

**1. Drop in the SDK**

```
app/libs/nosmai-release.aar
```

Download the latest build from the releases page:

**https://github.com/nosmai/camera-sdk-android/releases**

The AAR is not committed here — it is ~36 MB and would go stale the moment a new
SDK build ships, so always take it from releases. Nothing else resolves it, so
the build fails until you drop your copy in.

**2. Set your licence key and applicationId**

Keys are bound to an application id — they must match.

```kotlin
// app/build.gradle.kts
applicationId = "com.your.app"

// MainActivity.kt
private const val NOSMAI_LICENSE_KEY = "YOUR_NOSMAI_ANDROID_KEY"
```

**3. Set your Agora credentials**

```kotlin
// MainActivity.kt
private const val AGORA_APP_ID = "YOUR_AGORA_APP_ID"
private val AGORA_TOKEN: String? = null       // null for App ID auth
private const val AGORA_CHANNEL = "nosmai-demo"
```

A project in **App ID authentication** mode needs no token. A project in
**secured mode** needs a temporary token from the Agora Console (or one your
server issues) whose channel name matches `AGORA_CHANNEL` exactly — a mismatch
fails the join with no obvious clue why.

**4. Add effects (optional)**

```
app/src/main/assets/filters/your_effect.nosmai
```

The selector is built at runtime from whatever is in that directory, so adding an
effect needs no code change. Effects apply live — switch while streaming and
remote viewers see it immediately.

**5. Run**

```bash
./gradlew :app:installDebug
```

Then join the same channel from any Agora client to see the stream. A receiver is
bundled:

```
open tools/agora-portrait-viewer/index.html
```

Fill in the same App ID and channel, click Join, and it shows the decoded
resolution, orientation and true aspect ratio alongside the video.

Use it rather than the stock
[Agora web demo](https://webdemo.agora.io/basicVideoCall/index.html) when you are
checking orientation. That page renders into a landscape tile with
`object-fit: cover`, so a correct portrait stream *looks* cropped — a layout
artifact of the page, not a problem with your stream. The bundled viewer sizes
to the stream's real aspect and never crops.

---

## What you need

| | |
|---|---|
| JDK | 17 |
| NDK | `29.0.14206865` — what the Nosmai SDK is built with |
| minSdk | 21 |
| ABI | **arm64-v8a only** — the SDK ships one ABI |
| Device | A real arm64 device. The camera and GPU paths do not work on an emulator. |
| Nosmai SDK | `nosmai-release.aar` — from [releases](https://github.com/nosmai/camera-sdk-android/releases) |
| Nosmai licence key | Bound to an `applicationId` — see above |
| Agora | An App ID, and a token if your project is in secured mode |

---

## The files

| File | What it does |
|---|---|
| `MainActivity.kt` | UI, Nosmai setup, streaming lifecycle. Start here. |
| `AgoraManager.kt` | The RTC engine, the EGL share group, join/leave, frame push. |
| `AgoraTextureBridge.java` | The zero-readback texture path. **Port it whole.** |
| `Camera2Helper.java` | Plain Camera2 capture. Nothing Nosmai-specific — swap in your own. |
| `tools/agora-portrait-viewer/` | A one-file browser receiver that reports the stream's true resolution and orientation. |

### Two things about `startProcessing`

**It does not open a camera.** This surprises people coming from the Flutter
plugin, which does it for you. The SDK gives you the filter pipeline and a
preview surface; the app owns capture and feeds frames in via `processYuvFrame`.

**Mirroring is automatic.** Front mirrors, back does not, derived from
`setCameraFacing` — no call needed, and it survives camera switches. The Mirror
button in this example is optional and exists only to show `setMirrorOverride`,
which is display-only: it does not touch camera facing, so face landmarks stay
aligned, and it does not change what remote viewers see.

---

## Notes from the field

**The texture bridge is the fragile part.** `AgoraTextureBridge` documents five
rules, each of which cost a black remote or a frozen preview when broken — push
inside `TextureBufferHelper.invoke()`, never from the render thread, push a copy
rather than the live foreign texture, flip Y in the blit and push an identity
matrix, and `glFlush()` rather than `glFinish()`. If you port this file, port it
whole rather than reimplementing from the description.

**Arm the frame callback on join success, not before.** Producing frames before
you are in-channel makes frames that have nowhere to go, and the stream does not
start cleanly. `MainActivity.startStreaming` arms it inside `onJoined`.

**Being in a share group costs a few fps — even when you are not streaming.**
Shared contexts make the driver serialise GPU access and disable some fast paths.
This example always pays it, because streaming is the whole point. If streaming
is one feature among many in your app, gate `AgoraManager.initialize` on a
persisted "wants to stream" preference read at startup, and require a relaunch to
turn it on. You cannot defer the decision — by the time the user taps a button,
Nosmai's context already exists.

**There is a CPU fallback.** If the share group cannot be established, the app
uses `setFrameCallback` and pushes I420 instead. It works on any driver and costs
a readback plus a convert per frame. `MainActivity` says on screen which path is
live.

**API vintage.** This uses `setExternalVideoSource` + `pushExternalVideoFrame`,
which RTC 4.6.0 marks deprecated but still supports. The modern equivalent is
`createCustomVideoTrack()` → `ChannelMediaOptions.customVideoTrackId` →
`pushExternalVideoFrameById(frame, trackId)`, which you want if you ever publish
more than one custom track. Nothing on the Nosmai side changes.

---

## Licence

MIT — see [LICENSE](LICENSE). The Nosmai SDK itself is licensed separately.
