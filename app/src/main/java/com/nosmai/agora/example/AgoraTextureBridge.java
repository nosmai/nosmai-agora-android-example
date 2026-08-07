package com.nosmai.agora.example;

import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.agora.base.TextureBuffer;
import io.agora.base.TextureBufferHelper;
import io.agora.base.VideoFrame;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.gl.EglBaseProvider;

/**
 * Turns a Nosmai-produced GL texture into an Agora {@link TextureBuffer} and
 * pushes it — without ever reading the pixels back to the CPU.
 *
 * <p><b>This class is the fiddly part of the integration.</b> Every rule below
 * is load-bearing; each one, when broken, produces a black remote or a frozen
 * preview rather than an error. If you port this file, port it whole.
 *
 * <h3>The five rules</h3>
 * <ol>
 *   <li><b>Do the work inside {@link TextureBufferHelper#invoke}.</b> It runs
 *       the body on Agora's own encoder GL thread with the helper's EGL context
 *       current. A plain {@code handler.post()} runs with no context bound, so
 *       the encoder samples an empty texture: black remote, and the release
 *       callback never fires.</li>
 *   <li><b>Never call {@code invoke()} from the render thread.</b> It blocks its
 *       caller. Called from Nosmai's delivery thread — which also drives the
 *       preview — it freezes the preview after one frame. Hence the dedicated
 *       worker below: it blocks that thread instead.</li>
 *   <li><b>Push a copy, never the live foreign texture.</b> Blit Nosmai's
 *       texture into a helper-owned one inside {@code invoke()} and push that.
 *       Agora needs a texture it owns; a live foreign one flickers or blacks.</li>
 *   <li><b>Flip Y in the blit, push an identity matrix.</b> GL is bottom-left
 *       origin, video is top-left. A non-identity transform matrix confuses
 *       Agora's own dimension and orientation handling.</li>
 *   <li><b>{@code glFlush()}, not {@code glFinish()}.</b> The encoder samples on
 *       this same helper thread, so command ordering already guarantees the blit
 *       lands first. A per-frame {@code glFinish()} stalls the GPU and costs
 *       around 10 fps for nothing.</li>
 * </ol>
 *
 * <p>Nosmai's texture is legible on Agora's thread only because the two GL
 * contexts are in one EGL share group — see {@link AgoraManager} for the
 * ordering constraint that establishes it.
 */
final class AgoraTextureBridge {
    private static final String TAG = "NosmaiAgoraBridge";

    private final TextureBufferHelper helper;
    private final RtcEngine engine;

    // Agora may still hold a pushed TextureBuffer after pushExternalVideoFrame
    // returns, so we cannot overwrite one destination texture every frame. A
    // small ring bounds latency instead: when the encoder falls behind we drop
    // the next stream frame rather than blocking the render thread.
    private static final int DST_RING_SIZE = 3;
    private final int[] tex = new int[DST_RING_SIZE];
    private final int[] fbo = new int[DST_RING_SIZE];
    private final AtomicBoolean[] dstBusy = new AtomicBoolean[DST_RING_SIZE];

    // uptimeMillis when each slot was taken; 0 = free. Frames pushed around a
    // leaveChannel are accepted but never released by Agora, which would leave
    // every slot marked busy forever — so a slot held longer than the stall
    // window is reclaimed, and a rejoin recovers on its own.
    private final long[] dstBusySince = new long[DST_RING_SIZE];
    private static final long DST_SLOT_STALL_MS = 500;

    private int ringW = 0;
    private int ringH = 0;

    // Binds the foreign (share-group) source texture as a read attachment so we
    // can blit out of it. Owned by the helper thread.
    private int srcFbo = 0;
    private volatile boolean released = false;
    private int pushCount = 0;

    // Rule 2: invoke() blocks its caller, so give it a thread of its own.
    // Frames are coalesced — if the worker is still busy, the next frame is
    // dropped and its source freed immediately, so no backlog can build.
    private final ExecutorService pushWorker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "nosmai-agora-push"));
    private final AtomicBoolean workerBusy = new AtomicBoolean(false);

    AgoraTextureBridge(TextureBufferHelper helper, RtcEngine engine) {
        this.helper = helper;
        this.engine = engine;
        for (int i = 0; i < DST_RING_SIZE; i++) {
            dstBusy[i] = new AtomicBoolean(false);
        }
    }

    /**
     * Copy {@code srcTexId} into a helper-owned texture and push it to Agora.
     *
     * <p>Returns immediately. {@code onSourceConsumed} runs exactly once — after
     * the copy, or straight away if the frame is dropped — so the SDK is always
     * told the source texture is finished with.
     *
     * @param srcTexId a {@code GL_TEXTURE_2D} RGBA texture from Nosmai's
     *                 share-group context
     */
    void pushCopy(final int srcTexId, final int width, final int height,
                  final long timestampNs, final Runnable onSourceConsumed) {
        if (helper == null || engine == null || released) {
            if (onSourceConsumed != null) onSourceConsumed.run();
            return;
        }
        // Coalesce: at most one frame in flight on the worker.
        if (!workerBusy.compareAndSet(false, true)) {
            if (onSourceConsumed != null) onSourceConsumed.run();
            return;
        }
        try {
            pushWorker.execute(() -> {
                boolean sourceReleased = false;
                try {
                    // Rule 1 + 2: runs on the helper's GL thread with its context
                    // current, and blocks THIS worker until done.
                    helper.invoke((Callable<Void>) () -> {
                        ensureRing(width, height);
                        final int slot = acquireDstSlot();
                        if (slot < 0) {
                            if ((pushCount++ % 60) == 0) {
                                Log.w(TAG, "Dropping frame: Agora helper ring busy");
                            }
                            return null;
                        }

                        if (srcFbo == 0) {
                            int[] f = new int[1];
                            GLES20.glGenFramebuffers(1, f, 0);
                            srcFbo = f[0];
                        }

                        // Rule 3: blit the share-group source into our own texture.
                        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo);
                        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,
                                GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, srcTexId, 0);
                        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[slot]);
                        // Rule 4: dst top/bottom swapped = Y flip, so the owned
                        // texture is already top-left origin like video wants.
                        GLES30.glBlitFramebuffer(0, 0, width, height,
                                0, height, width, 0,
                                GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST);
                        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,
                                GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0);
                        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
                        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
                        // Rule 5.
                        GLES20.glFlush();

                        VideoFrame frame = null;
                        try {
                            // The context captured here is the helper thread's —
                            // the one the encoder shares with. Identity matrix,
                            // because the Y flip is already baked into the blit.
                            VideoFrame.Buffer buffer = new TextureBuffer(
                                    EglBaseProvider.getCurrentEglContext(),
                                    width, height,
                                    VideoFrame.TextureBuffer.Type.RGB,   // GL_TEXTURE_2D
                                    tex[slot],
                                    new Matrix(),
                                    helper.getHandler(),
                                    /* yuvConverter */ null,
                                    () -> { dstBusySince[slot] = 0; dstBusy[slot].set(false); });
                            frame = new VideoFrame(buffer, 0, timestampNs);
                            // A true return means QUEUED, not encoded — never
                            // read it as proof the frame reached the wire.
                            //
                            // Deprecated in RTC 4.6.0 but still supported; the
                            // modern form is pushExternalVideoFrameById(frame,
                            // trackId). See AgoraManager.joinChannel.
                            @SuppressWarnings("deprecation")
                            boolean pushed = engine.pushExternalVideoFrame(frame);
                            pushCount++;
                            if (!pushed && (pushCount % 60 == 0)) {
                                Log.w(TAG, "pushExternalVideoFrame rejected frame #" + pushCount);
                            }
                        } finally {
                            if (frame != null) frame.release();
                            // Free the slot here rather than waiting on the
                            // TextureBuffer release callback above: Agora accepts
                            // the frame but does not reliably fire that callback in
                            // this configuration, so waiting on it starves the ring.
                            // Round-robin over three textures still gives the
                            // encoder ~2 frames of grace before a slot is reused.
                            // The callback stays as an idempotent backstop.
                            dstBusySince[slot] = 0;
                            dstBusy[slot].set(false);
                        }
                        return null;
                    });
                    if (onSourceConsumed != null) onSourceConsumed.run();
                    sourceReleased = true;
                } catch (Throwable t) {
                    Log.e(TAG, "pushCopy failed", t);
                    if (!sourceReleased && onSourceConsumed != null) onSourceConsumed.run();
                } finally {
                    workerBusy.set(false);
                }
            });
        } catch (Throwable t) {
            // Executor rejected the task (shutting down): free the source anyway.
            if (onSourceConsumed != null) onSourceConsumed.run();
            workerBusy.set(false);
        }
    }

    /** Helper thread only. Create or resize the ring of owned destinations. */
    private void ensureRing(int width, int height) {
        if (ringW == width && ringH == height && tex[0] != 0) return;
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(DST_RING_SIZE, tex, 0);
            GLES20.glDeleteFramebuffers(DST_RING_SIZE, fbo, 0);
        }
        GLES20.glGenTextures(DST_RING_SIZE, tex, 0);
        GLES20.glGenFramebuffers(DST_RING_SIZE, fbo, 0);
        for (int i = 0; i < DST_RING_SIZE; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex[i], 0);
            dstBusy[i].set(false);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        ringW = width;
        ringH = height;
        Log.i(TAG, "Created helper destination ring " + width + "x" + height);
    }

    private int acquireDstSlot() {
        final long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < DST_RING_SIZE; i++) {
            if (dstBusy[i].compareAndSet(false, true)) {
                dstBusySince[i] = now;
                return i;
            }
        }
        // All busy — reclaim the oldest slot past the stall window so the ring
        // can never wedge permanently (see dstBusySince).
        int oldest = -1;
        long oldestSince = Long.MAX_VALUE;
        for (int i = 0; i < DST_RING_SIZE; i++) {
            long since = dstBusySince[i];
            if (since != 0 && (now - since) > DST_SLOT_STALL_MS && since < oldestSince) {
                oldestSince = since;
                oldest = i;
            }
        }
        if (oldest >= 0) {
            Log.w(TAG, "Reclaiming stalled ring slot " + oldest
                    + " (busy " + (now - oldestSince) + "ms, callback never fired)");
            dstBusySince[oldest] = now;   // ours now; dstBusy stays true
            return oldest;
        }
        return -1;
    }

    /** Stop the worker and free the GL objects on the helper thread. */
    void release() {
        released = true;
        pushWorker.shutdown();
        if (helper == null) return;
        try {
            helper.invoke((Callable<Void>) () -> {
                if (tex[0] != 0) {
                    GLES20.glDeleteTextures(DST_RING_SIZE, tex, 0);
                    GLES20.glDeleteFramebuffers(DST_RING_SIZE, fbo, 0);
                    for (int i = 0; i < DST_RING_SIZE; i++) {
                        tex[i] = 0;
                        fbo[i] = 0;
                        dstBusy[i].set(false);
                    }
                }
                if (srcFbo != 0) {
                    GLES20.glDeleteFramebuffers(1, new int[]{srcFbo}, 0);
                    srcFbo = 0;
                }
                ringW = 0;
                ringH = 0;
                return null;
            });
        } catch (Throwable ignored) {
        }
    }
}
