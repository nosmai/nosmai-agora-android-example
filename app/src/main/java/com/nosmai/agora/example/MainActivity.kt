package com.nosmai.agora.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.nosmai.agora.example.databinding.ActivityMainBinding
import com.nosmai.effect.NosmaiEffects
import com.nosmai.effect.api.NosmaiPreviewView
import com.nosmai.effect.api.NosmaiSDK

/**
 * Nosmai + Agora on Android, natively.
 *
 * **Nosmai owns the camera and the preview; Agora only publishes.**
 *
 * Most of this file is UI. The integration is four things:
 *
 *  1. Create the Agora engine and register its EGL context with Nosmai
 *     **before** `NosmaiSDK.initialize()`. See [AgoraManager] — this ordering is
 *     the whole ballgame, and the wrong order shows up as a black remote rather
 *     than as an exception.
 *  2. Let Nosmai own capture: [NosmaiSDK.startProcessing] against a mounted
 *     [NosmaiPreviewView], fed by [Camera2Helper].
 *  3. Switch to `DUAL_OUTPUT` and hand Nosmai's texture callback to Agora.
 *  4. Never let Agora open a camera (`publishCameraTrack = false`).
 *
 * The filtered frame never leaves the GPU: Nosmai renders a portrait texture,
 * Agora's encoder thread copies it inside the shared EGL group, and the encoder
 * reads it. No `glReadPixels`, no colour conversion, no CPU copies.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NosmaiAgora"
        private const val PERM_REQUEST = 100

        // ── Fill these in ───────────────────────────────────────────────
        // Set these in local.properties (git-ignored) rather than here, so a
        // real key cannot reach a commit — see app/build.gradle.kts for the
        // key names. Hardcoding them here works too if you prefer.

        // Nosmai licence keys are bound to your applicationId.
        private val NOSMAI_LICENSE_KEY = BuildConfig.NOSMAI_LICENSE_KEY

        // From Agora Console -> your project.
        private val AGORA_APP_ID = BuildConfig.AGORA_APP_ID

        // A project using App ID authentication needs no token — leave it empty.
        // A project in "secured mode" needs a temporary or server-issued token
        // whose channel name matches AGORA_CHANNEL exactly.
        private val AGORA_TOKEN: String? = BuildConfig.AGORA_TOKEN.ifEmpty { null }
        private val AGORA_CHANNEL = BuildConfig.AGORA_CHANNEL
    }

    private lateinit var binding: ActivityMainBinding

    private var previewView: NosmaiPreviewView? = null
    private var camera2Helper: Camera2Helper? = null
    private var nosmaiStarted = false
    private var streaming = false
    private var isFrontCamera = true
    private var shareContextOk = false

    /** One of [NosmaiSDK.MIRROR_AUTO] / [NosmaiSDK.MIRROR_ON] / [NosmaiSDK.MIRROR_OFF]. */
    private var mirrorOverride = NosmaiSDK.MIRROR_AUTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.goLiveButton.setOnClickListener {
            if (streaming) stopStreaming() else startStreaming()
        }
        binding.switchCameraButton.setOnClickListener {
            // Camera switching goes through NOSMAI — Agora does not own the
            // camera, and its switch APIs would try to open one.
            isFrontCamera = !isFrontCamera
            // The app owns capture, so switching means restarting the helper.
            // startCamera() reports the new facing to the SDK.
            runCatching { camera2Helper?.stopCamera() }
            startCamera()
            status("Camera: ${if (isFrontCamera) "front" else "back"}")
        }

        // OPTIONAL — most apps should delete this button.
        //
        // The display mirror is automatic: front mirrors, back does not, with no
        // call at all, and that survives camera switches. setMirrorOverride is
        // only for an app that wants to hand the user a deliberate toggle.
        //
        // It is DISPLAY ONLY — it does not touch camera facing, so face
        // landmarks and the segmentation mask stay aligned with the real camera
        // whichever way it is set. It also does not affect what remote viewers
        // see, which is why the Switch button above does not reset it.
        binding.mirrorButton.setOnClickListener {
            mirrorOverride = when (mirrorOverride) {
                NosmaiSDK.MIRROR_AUTO -> NosmaiSDK.MIRROR_ON
                NosmaiSDK.MIRROR_ON -> NosmaiSDK.MIRROR_OFF
                else -> NosmaiSDK.MIRROR_AUTO
            }
            NosmaiSDK.setMirrorOverride(mirrorOverride)

            val label = when (mirrorOverride) {
                NosmaiSDK.MIRROR_ON -> "ON"
                NosmaiSDK.MIRROR_OFF -> "OFF"
                else -> "AUTO"
            }
            binding.mirrorButton.text = "Mirror: $label"
            status("Display mirror: $label")
        }

        buildFilterBar()

        if (hasPermissions()) initNosmai() else requestPermissions()
    }

    // ── Nosmai ───────────────────────────────────────────────────────────

    private fun initNosmai() {
        // STEP 1 — Agora FIRST, so its EGL context is registered while Nosmai's
        // GL context is still unborn. A context cannot join a share group
        // retroactively, so there is no recovering from doing this second.
        shareContextOk = AgoraManager.initialize(applicationContext, AGORA_APP_ID)
            && AgoraManager.isTextureMode

        // STEP 2 — now Nosmai can build its context, joining Agora's share group.
        NosmaiSDK.initialize(applicationContext, NOSMAI_LICENSE_KEY)

        // STEP 3 — mount the preview and start the pipeline.
        val pv = NosmaiPreviewView(this)
        previewView = pv
        binding.previewContainer.addView(pv)
        pv.initializePipeline()
        NosmaiSDK.startProcessing(pv)

        // STEP 3b — DRIVE THE CAMERA YOURSELF.
        //
        // This is the part that surprises people coming from the Flutter plugin:
        // startProcessing() does NOT open a camera. The SDK gives you the filter
        // pipeline and a preview surface, but the app owns capture and feeds
        // frames in. (The Flutter plugin hides this by doing it for you.)
        //
        // Camera2Helper is a plain Camera2 wrapper with nothing Nosmai-specific
        // in it — size selection, the 30fps range and sensor orientation.
        // Swap in your own capture if you already have one.
        startCamera()

        nosmaiStarted = true
        status(
            when {
                !AgoraManager.isInitialized ->
                    "Nosmai ready, but Agora failed to start — check your App ID"
                shareContextOk -> "Ready — tap Go Live"
                // Not fatal: streaming still works, it just reads every frame
                // back to the CPU and converts it, which costs frame rate.
                else -> "Ready — EGL share unavailable, using the slower CPU path"
            },
        )
    }

    private fun startCamera() {
        val helper = Camera2Helper(this, isFrontCamera)
        camera2Helper = helper
        helper.setInputMode(Camera2Helper.InputMode.YUV)

        // Facing drives the SDK's own processing; orientation drives the
        // preview's display transform (including the selfie mirror).
        NosmaiSDK.setCameraFacing(isFrontCamera)
        applyCameraOrientation(helper)

        // Runs on Camera2's background thread. Hand the planes straight to
        // Nosmai; it does the filtering and drives both the preview and the
        // streaming pass from the same frame.
        helper.setFrameCallback { y, u, v, width, height,
                                  yStride, uStride, vStride,
                                  uPixelStride, vPixelStride ->
            previewView?.processYuvFrame(
                y, u, v, width, height,
                yStride, uStride, vStride,
                uPixelStride, vPixelStride,
                frameRotation(helper),
            )
        }
        helper.startCamera()
        Log.i(TAG, "camera started (front=$isFrontCamera)")
    }

    /**
     * Tell the preview which camera is running, for display orientation.
     *
     * Note there is NO mirror call here. Mirroring is derived from camera facing
     * inside the SDK — [NosmaiSDK.setCameraFacing] above is all it needs, and
     * the render sink reads that per frame. Front is mirrored (selfie
     * convention), back is not.
     */
    private fun applyCameraOrientation(helper: Camera2Helper) {
        previewView?.setCameraOrientation(isFrontCamera, helper.sensorOrientation)
    }

    /**
     * Rotation Nosmai should apply to the incoming camera frame.
     *
     * Values are the SDK's RotationMode enum (1 = RotateLeft, 2 = RotateRight),
     * not degrees.
     */
    private fun frameRotation(helper: Camera2Helper): Int {
        val sensor = helper.sensorOrientation
        return if (isFrontCamera) {
            when (sensor) {
                90 -> 2
                270 -> 1
                else -> 0
            }
        } else {
            if (sensor == 90) 2 else 1
        }
    }

    // ── Agora ────────────────────────────────────────────────────────────

    private fun startStreaming() {
        if (!nosmaiStarted || !AgoraManager.isInitialized) {
            status("Not ready to stream yet")
            return
        }

        // DUAL_OUTPUT = keep the local preview AND produce streaming frames.
        // STREAMING_ONLY drops the preview, which saves a little GPU if you do
        // not need to show the broadcaster their own feed.
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)

        AgoraManager.channelEvents = object : AgoraManager.ChannelEvents {
            override fun onJoined(channel: String, uid: Int) {
                // Arm frame production ONLY now that we are in-channel.
                //
                // Arming it at join *request* time produces frames that have
                // nowhere to go yet, and the stream does not start cleanly.
                if (AgoraManager.isTextureMode) {
                    NosmaiSDK.setTextureFrameCallback { texId, w, h, ts, fence ->
                        AgoraManager.pushTextureFrame(texId, w, h, ts, fence)
                    }
                } else {
                    NosmaiSDK.setFrameCallback { frame ->
                        frame.pixelBuffer?.let {
                            AgoraManager.pushPixelFrame(
                                it, frame.width, frame.height, frame.timestampNs, frame.format,
                            )
                        }
                    }
                }
                streaming = true
                runOnUiThread { binding.goLiveButton.text = "Stop" }
                status("Live in '$channel' as uid $uid")
            }

            override fun onLeft() {
                status("Left the channel")
            }
        }

        status("Joining '$AGORA_CHANNEL'…")
        AgoraManager.joinChannel(AGORA_TOKEN, AGORA_CHANNEL)
    }

    /** Teardown order: stop producing frames, then leave. */
    private fun stopStreaming() {
        // Clear both callbacks before leaving, so no frame is produced for a
        // channel we are on the way out of.
        runCatching { NosmaiSDK.setTextureFrameCallback(null) }
        runCatching { NosmaiSDK.setFrameCallback(null) }
        runCatching { NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.PREVIEW_ONLY) }
        runCatching { AgoraManager.leaveChannel() }

        streaming = false
        runOnUiThread { binding.goLiveButton.text = "Go Live" }
        status("Stopped")
    }

    // ── Filters ──────────────────────────────────────────────────────────

    /** Effects discovered under assets/filters/, in listing order. */
    private val filters = mutableListOf<String>()
    private var selectedFilter: String? = null
    private val filterButtons = mutableMapOf<String?, MaterialButton>()

    /**
     * Discover bundled effects and build the selector from what is actually
     * there.
     *
     * Enumerating assets rather than hardcoding names means dropping a new
     * .nosmai into assets/filters/ is the only step needed to add an effect —
     * no code change, and no button that points at a file that isn't shipped.
     */
    private fun buildFilterBar() {
        filters.clear()
        filters += runCatching {
            assets.list("filters")
                ?.filter { it.endsWith(".nosmai") }
                ?.sorted()
                .orEmpty()
        }.getOrElse {
            Log.e(TAG, "asset listing failed", it)
            emptyList()
        }

        val row = binding.filterRow
        row.removeAllViews()
        filterButtons.clear()

        // "None" first, so clearing is always reachable.
        row.addView(filterChip(null, "None") { selectFilter(null) })
        filters.forEach { asset ->
            row.addView(filterChip(asset, displayName(asset)) { selectFilter(asset) })
        }

        selectedFilter = null
        refreshFilterSelection()
        Log.i(TAG, "discovered ${filters.size} filter(s): $filters")
    }

    private fun filterChip(key: String?, label: String, onTap: () -> Unit): MaterialButton {
        val button = MaterialButton(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
        }
        filterButtons[key] = button
        return button
    }

    /** "reindeer_face.nosmai" -> "Reindeer Face" */
    private fun displayName(asset: String) = asset
        .removeSuffix(".nosmai")
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    /**
     * Apply an effect, or clear when [asset] is null.
     *
     * applyEffect takes a FILE path, not an asset, so the package is copied out
     * of assets/ into the cache on first use. Effects apply live — change them
     * mid-stream and remote viewers see it immediately.
     */
    private fun selectFilter(asset: String?) {
        if (asset == null) {
            runCatching { NosmaiEffects.removeEffect() }
            selectedFilter = null
            refreshFilterSelection()
            status("Filters cleared")
            return
        }
        try {
            val target = java.io.File(externalCacheDir ?: cacheDir, asset)
            if (!target.exists()) {
                assets.open("filters/$asset").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            NosmaiEffects.applyEffect(target.absolutePath, object : NosmaiEffects.EffectCallback {
                override fun onSuccess() {
                    selectedFilter = asset
                    runOnUiThread { refreshFilterSelection() }
                    status("Applied ${displayName(asset)}")
                }

                override fun onError(error: String?) = status("Filter failed: $error")
            })
        } catch (t: Throwable) {
            Log.e(TAG, "selectFilter failed", t)
            status("Filter failed: ${t.message}")
        }
    }

    /** Highlight whichever effect is currently applied. */
    private fun refreshFilterSelection() {
        filterButtons.forEach { (key, button) ->
            button.alpha = if (key == selectedFilter) 1.0f else 0.55f
        }
    }

    // ── Lifecycle / plumbing ─────────────────────────────────────────────

    /**
     * Release the camera and pause the GL view while backgrounded.
     *
     * Both halves matter. Another app claiming the camera would otherwise tear
     * ours out from under us, and [NosmaiPreviewView] wraps a GL view whose
     * onPause/onResume the framework does not drive — the Activity has to proxy
     * them, or its render thread keeps running against a surface that is going
     * away.
     *
     * Stop the camera BEFORE pausing the GL view, so no late frame can render
     * into a paused pipeline.
     *
     * Nosmai's pipeline and the Agora channel are deliberately left up. The
     * pipeline is starved rather than stopped, so publishing merely stalls and
     * picks up again on return, and the streaming render mode and texture
     * callback do not have to be re-armed. Nothing renders meanwhile because
     * nothing is feeding it.
     */
    override fun onPause() {
        runCatching { camera2Helper?.stopCamera() }
        camera2Helper = null
        runCatching { previewView?.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Nothing to do on the first pass: initNosmai() has already started
        // capture by the time onResume runs, and while permissions are still
        // pending there is nothing to resume.
        if (!nosmaiStarted) return
        runCatching { previewView?.onResume() }
        if (camera2Helper == null) startCamera()
    }

    override fun onDestroy() {
        if (streaming) stopStreaming()
        runCatching { camera2Helper?.stopCamera() }
        camera2Helper = null
        // cleanup() is the full teardown: it clears the frame callbacks, shuts
        // the effects engine down and calls stopProcessing() itself.
        // stopProcessing() on its own deliberately leaves the SDK initialized.
        runCatching { NosmaiSDK.cleanup() }
        // Nosmai first, then Agora: the bridge must stop reading Nosmai textures
        // before the engine that owns its GL thread goes away.
        runCatching { AgoraManager.cleanup() }
        super.onDestroy()
    }

    private fun status(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { binding.statusText.text = msg }
    }

    private fun hasPermissions() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() = ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        PERM_REQUEST,
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (hasPermissions()) initNosmai()
            else Toast.makeText(this, "Camera and microphone are required", Toast.LENGTH_LONG).show()
        }
    }
}
