package com.nosmai.agora.example

import android.content.Context
import android.util.Log
import com.nosmai.effect.api.NosmaiSDK
import io.agora.base.JavaI420Buffer
import io.agora.base.TextureBufferHelper
import io.agora.base.VideoFrame
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.gl.EglBaseProvider
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the Agora RTC engine and everything needed to publish Nosmai's filtered
 * output into a channel.
 *
 * ## The one constraint that governs the whole integration
 *
 * Agora's encoder samples the pushed texture **on its own GL thread, in its own
 * EGL context**. A texture id from Nosmai's context only means anything there if
 * the two contexts are in the same **EGL share group** — and a context cannot
 * join a share group after it exists.
 *
 * So [initialize] must run **before** `NosmaiSDK.initialize()`. It creates the
 * RTC engine, takes Agora's root EGL context, and hands it to
 * [NosmaiSDK.setAgoraShareContext] while Nosmai's GL context is still unborn.
 *
 * In the wrong order the share is simply not in effect: the two contexts end up
 * unrelated, texture ids from one mean nothing in the other, and the remote
 * viewer sees black. That is why [MainActivity] does this first and reports
 * which path it ended up on.
 *
 * ## Cost, and how to avoid paying it when you aren't streaming
 *
 * Being in a share group makes the driver serialise GPU access and disables some
 * fast paths, costing **a few fps on every frame — streaming or not**. This
 * example always establishes it, because streaming is the entire point.
 *
 * An app where streaming is one feature among many should gate this on a
 * persisted "wants to stream" preference read at startup, and require a relaunch
 * to turn it on. You cannot defer the decision: by the time the user taps a
 * button, Nosmai's context already exists.
 */
object AgoraManager {

    private const val TAG = "NosmaiAgora"

    // Nosmai renders a portrait 720x1280 texture, so the encoder is configured to
    // match exactly — no rotation and no scale in the encoder.
    private const val STREAM_WIDTH = 720
    private const val STREAM_HEIGHT = 1280
    private const val STREAM_BITRATE_KBPS = 2500
    private const val STREAM_MIN_BITRATE_KBPS = 1200

    interface ChannelEvents {
        fun onJoined(channel: String, uid: Int)
        fun onLeft()
    }

    var channelEvents: ChannelEvents? = null

    private var engine: RtcEngine? = null
    private var textureHelper: TextureBufferHelper? = null
    private var textureBridge: AgoraTextureBridge? = null
    private var pixelPushExecutor: ExecutorService? = null

    /** True when the zero-readback texture path is live (vs the CPU I420 fallback). */
    var isTextureMode = false
        private set

    /** Authoritative in-channel state, set by the RTC event handler. */
    @Volatile
    var isInChannel = false
        private set

    val isInitialized: Boolean get() = engine != null

    private val eventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            isInChannel = true
            Log.i(TAG, "joined channel '$channel' as uid $uid")
            channelEvents?.onJoined(channel, uid)
        }

        override fun onLeaveChannel(stats: RtcStats?) {
            isInChannel = false
            Log.i(TAG, "left channel")
            channelEvents?.onLeft()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.i(TAG, "remote user joined: $uid")
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.i(TAG, "remote user offline: $uid (reason $reason)")
        }

        override fun onError(err: Int) {
            Log.e(TAG, "RTC error $err: ${RtcEngine.getErrorDescription(err)}")
        }
    }

    /**
     * Create the engine and join Agora's EGL share group.
     *
     * **Call before `NosmaiSDK.initialize()`** — see the class docs.
     *
     * @return true if the engine was created. Texture mode is reported separately
     *         by [isTextureMode]; a false there is not fatal, it just means the
     *         slower CPU path.
     */
    fun initialize(context: Context, appId: String): Boolean {
        if (engine != null) return true
        if (appId.isEmpty()) {
            Log.e(TAG, "no Agora App ID set")
            return false
        }

        return try {
            val rtc = RtcEngine.create(RtcEngineConfig().apply {
                mContext = context.applicationContext
                mAppId = appId
                mEventHandler = eventHandler
            })
            engine = rtc
            rtc.enableVideo()

            setUpTextureStreaming(rtc)

            rtc.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoEncoderConfiguration.VideoDimensions(STREAM_WIDTH, STREAM_HEIGHT),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30,
                    STREAM_BITRATE_KBPS,
                    // FIXED_PORTRAIT, not ADAPTIVE: the frames really are portrait,
                    // and letting Agora adapt makes it re-orient them.
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT,
                ).apply { minBitrate = STREAM_MIN_BITRATE_KBPS },
            )
            Log.i(TAG, "engine ready — ${STREAM_WIDTH}x$STREAM_HEIGHT @30fps, textureMode=$isTextureMode")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to create the Agora engine", t)
            false
        }
    }

    /**
     * Join Agora's EGL share group and build the texture bridge.
     *
     * If any step fails we leave [isTextureMode] false and the app falls back to
     * the CPU I420 path, which works on any driver — just slower.
     */
    private fun setUpTextureStreaming(rtc: RtcEngine) {
        try {
            // The ROOT context, not a local one. Agora's encoder shares with the
            // root, and TextureBufferHelper seeds its own GL thread from it. Nosmai
            // has to share with this SAME context or the helper-thread blit cannot
            // read Nosmai's texture, and the remote shows black.
            val agoraCtx = EglBaseProvider.instance().rootEglBase.eglBaseContext
            val nativeHandle = agoraCtx.nativeEglContext

            // Load the native library explicitly. NosmaiSDK.initialize() would do
            // it, but we are deliberately running before that call, and
            // setAgoraShareContext needs the native binding to already exist.
            System.loadLibrary("nosmai")
            NosmaiSDK.setAgoraShareContext(nativeHandle)

            textureHelper = TextureBufferHelper.create("nosmai-agora", agoraCtx)
            isTextureMode = textureHelper != null && nativeHandle != 0L
            if (isTextureMode) {
                textureBridge = AgoraTextureBridge(textureHelper, rtc)
            }
            Log.i(TAG, "share context registered (handle=$nativeHandle) -> textureMode=$isTextureMode")
        } catch (t: Throwable) {
            Log.w(TAG, "texture streaming unavailable; falling back to CPU I420", t)
            isTextureMode = false
        }
    }

    fun joinChannel(token: String?, channelName: String, uid: Int = 0) {
        val rtc = engine ?: return

        // arg 2 (useTexture) MUST match the path in use. With texture mode on and
        // this false, Agora expects raw pixel buffers and silently drops every
        // texture frame.
        rtc.setExternalVideoSource(true, isTextureMode, Constants.ExternalVideoSourceType.VIDEO_FRAME)
        rtc.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)

        // Note on API vintage: this example uses the single-custom-track API
        // (setExternalVideoSource + pushExternalVideoFrame), which RTC 4.6.0
        // marks deprecated but still supports. The modern equivalent is
        // createCustomVideoTrack() -> ChannelMediaOptions.customVideoTrackId ->
        // pushExternalVideoFrameById(frame, trackId), which is what you want if
        // you ever publish more than one custom track. Nothing else about the
        // Nosmai side changes.
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            // The camera belongs to Nosmai — Agora must never open one.
            publishCameraTrack = false
            publishCustomVideoTrack = true
            // Audio is ordinary: the mic goes straight to Agora, unfiltered.
            publishMicrophoneTrack = true
            autoSubscribeVideo = true
            autoSubscribeAudio = true
        }
        rtc.joinChannel(token, channelName, uid, options)
    }

    fun leaveChannel() {
        engine?.leaveChannel()
    }

    /**
     * Push a filtered GL texture — the zero-readback path.
     *
     * Called from Nosmai's texture callback with a texId valid in the shared EGL
     * group. Returns immediately; the bridge does the work on its own thread.
     *
     * Every path here must reach [NosmaiSDK.releaseStreamSlot] — that is how the
     * SDK learns the frame is finished with. Miss one and frame production
     * stops.
     */
    fun pushTextureFrame(texId: Int, width: Int, height: Int, timestampNs: Long, fence: Long) {
        // `fence` is unused: the bridge copies on the helper thread, where driver
        // command ordering already guarantees the blit precedes the encoder's read.
        val bridge = textureBridge
        if (!isInChannel || bridge == null || !isTextureMode) {
            NosmaiSDK.releaseStreamSlot(texId)
            return
        }
        bridge.pushCopy(texId, width, height, timestampNs) {
            NosmaiSDK.releaseStreamSlot(texId)
        }
    }

    /**
     * CPU fallback: push an I420 frame that Nosmai read back for us.
     *
     * Only used when the EGL share group could not be established. It costs a
     * full readback plus a colour convert per frame, so expect a lower frame
     * rate — but it works on drivers where the texture path does not.
     */
    fun pushPixelFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long, format: Int) {
        val rtc = engine ?: return
        if (!isInChannel || format != 1) return   // 1 = I420

        val executor = pixelPushExecutor ?: Executors.newSingleThreadExecutor()
            .also { pixelPushExecutor = it }

        executor.execute {
            try {
                val ySize = width * height
                val uvWidth = (width + 1) / 2
                val uvHeight = (height + 1) / 2
                val uvSize = uvWidth * uvHeight

                val dataY = ByteBuffer.allocateDirect(ySize).put(data, 0, ySize).apply { rewind() }
                val dataU = ByteBuffer.allocateDirect(uvSize).put(data, ySize, uvSize).apply { rewind() }
                val dataV = ByteBuffer.allocateDirect(uvSize)
                    .put(data, ySize + uvSize, uvSize).apply { rewind() }

                val buffer = JavaI420Buffer.wrap(
                    width, height,
                    dataY, width,
                    dataU, uvWidth,
                    dataV, uvWidth,
                    null,
                )
                // rotation 0: Nosmai already delivers portrait.
                val frame = VideoFrame(buffer, 0, timestampNs)
                @Suppress("DEPRECATION")
                rtc.pushExternalVideoFrame(frame)
                frame.release()
            } catch (t: Throwable) {
                Log.e(TAG, "pushPixelFrame failed", t)
            }
        }
    }

    /** Full teardown. Order matters: bridge, then helper, then engine. */
    fun cleanup() {
        pixelPushExecutor?.shutdown()
        pixelPushExecutor = null

        textureBridge?.release()
        textureBridge = null

        textureHelper?.let { runCatching { it.dispose() } }
        textureHelper = null

        isTextureMode = false
        isInChannel = false
        channelEvents = null

        if (engine != null) {
            RtcEngine.destroy()
            engine = null
        }
    }
}
