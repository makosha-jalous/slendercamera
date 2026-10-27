package com.example.slendercam.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Port of the class "Glitch" of Slender: The Arrival (FixedUpdate / Apply / Revert / Visuals / SoundVolume / Mixer).
 *
 * The original runs FixedUpdate 50 times a second (0.02 s). This class runs the same step 50 times a second too
 * (a fixed time step that is advanced from the render tick), so the dice, the flicker and the "scared" breathing have the
 * original timing. One dice per step decides for EVERYTHING at once: the chromatic aberration, the grain, the scratches,
 * the fisheye (picture + interface frame) and the sound layers.
 *
 *   glitchIntensity (I) = sanity LOSS in percent, 0..100 (the original: 100 - Sanity)
 *   dice:   Random.value < (I + 35) / 100  and  I > 0  ->  Apply (Visuals + SoundVolume), else Revert
 *
 * Sounds (all in the own namespace "slendercamfx", the old files are not used):
 *   san1 = static_light_full  (as before: the whole track, muted / unmuted by the dice; its volume formula is unchanged)
 *   san2 = static_medium_full   volume (I - 30) / 70 while I is between 30 and 60
 *   san3 = static_heavy_full    volume (I - 60) / 40 while I is above 60
 *   staticglitch = static_glitch_orig  volume 0.5, started from the beginning when it is not playing, cut at once by Revert
 * The sound engine of Minecraft changes volumes only once per game tick (20 times a second), so the sound layers see the
 * state of the last step before the tick; if a Revert happened between two ticks, staticglitch is still cut and restarted.
 */
final class GlitchPort {
    private GlitchPort() { }

    private static final String NS = "slendercamfx";

    /** seconds per original FixedUpdate step (Unity's default fixed time step). */
    static final float STEP = 0.02F;

    /** the values of the Unity components at the start of the game (the "cache" values of the original). */
    static final float VIGNETTE_CACHE = 0.2F;      // chromaticAberration
    static final float NOISE_CACHE = 0.2F;         // grainIntensityMax
    static final float SCRATCH_CACHE = 0.25F;      // scratchIntensityMax
    static final float FISHEYE_CACHE = 0F;         // strengthX / strengthY of the fisheye

    /** the chromatic aberration of the original goes up to 1.5 * 100 + 50 = 200. */
    static final float CHROMA_FULL = 200F;

    private static final RandomSource RNG = RandomSource.create();

    // ---- inputs (set every game tick) ----
    private static float inLoss;              // sanity loss, 0..100 (0 when the camera is down)
    private static boolean inSeen, inLost;

    // ---- state of the original ----
    private static volatile float glitchIntensity;
    private static boolean diceOn;            // result of the last step: Apply (true) / Revert (false)
    private static boolean revertSinceTick;   // a Revert happened after the last game tick
    private static float chroma = VIGNETTE_CACHE;
    private static float grainMax = NOISE_CACHE;
    private static float scratchMax = SCRATCH_CACHE;
    private static float strengthX = FISHEYE_CACHE, strengthY = FISHEYE_CACHE;
    private static float scared;
    private static float san1, san2, san3, staticglitch;       // the fields of the original (san2 / san3 keep old values)
    private static float vol2, vol3, volG;                      // what the Mixer has set

    private static long lastNs;
    private static float acc;

    // ---- readers ----
    static float intensity() { return glitchIntensity; }
    static boolean dice() { return diceOn; }
    static float scared() { return scared; }
    static float strengthX() { return strengthX; }
    static float strengthY() { return strengthY; }
    static float grainMax() { return grainMax; }
    static float scratchMax() { return scratchMax; }
    /** 0..1: the chromatic aberration value of the original divided by its largest possible value. */
    static float chromaNorm() { return Mth.clamp((chroma - VIGNETTE_CACHE) / CHROMA_FULL, 0F, 1F); }
    static boolean fisheyeActive() { return strengthX != 0F || strengthY != 0F; }

    static void setInputs(float lossPercent, boolean seen, boolean lost) {
        inLoss = Mth.clamp(lossPercent, 0F, 100F);
        sharedI = inLoss;
        inSeen = seen;
        inLost = lost;
    }

    static void reset() {
        inLoss = 0F; inSeen = false; inLost = false;
        glitchIntensity = 0F;
        diceOn = false;
        scared = 0F;
        revert();
        revertSinceTick = true;
        lastNs = 0L;
        acc = 0F;
    }

    private static float range(float a, float b) {
        return a + (b - a) * RNG.nextFloat();       // Random.Range(a, b)
    }

    /** runs as many 0.02 s steps as real time has passed (called once per rendered frame). */
    static void advance(long nowNs, boolean paused) {
        if (lastNs == 0L || paused) {
            lastNs = nowNs;
            return;
        }
        acc += Math.min(0.25F, (nowNs - lastNs) / 1e9F);
        lastNs = nowNs;
        int n = 0;
        while (acc >= STEP && n < 10) {
            acc -= STEP;
            fixedUpdate();
            n++;
        }
        if (acc >= STEP) acc = 0F;
    }

    // ------------------------------------------------------------------------------------------
    //  the original, step by step
    // ------------------------------------------------------------------------------------------

    private static void fixedUpdate() {
        glitchIntensity = inLoss;     // glitchDuration == 0 -> glitchIntensity = 100 - Sanity

        // scared (breathing): jumps up to I / 100, otherwise falls by 0.01 per step. (The original also checks !view.lost here;
        // in our death scene the breathing has to keep sounding, so that check is left out.)
        if (glitchIntensity / 100F > scared) {
            scared = glitchIntensity / 100F;
        } else {
            scared = Math.max(0F, scared - 0.01F);
        }

        if (RNG.nextFloat() < (glitchIntensity + 35F) / 100F && glitchIntensity > 0F) {
            apply();
        } else {
            revert();
        }
    }

    private static void apply() {
        diceOn = true;
        visuals();
        soundVolume();
    }

    private static void revert() {
        diceOn = false;
        revertSinceTick = true;
        chroma = VIGNETTE_CACHE;
        grainMax = NOISE_CACHE;
        scratchMax = SCRATCH_CACHE;
        strengthY = FISHEYE_CACHE;
        strengthX = FISHEYE_CACHE;
        mixer(0F, 0F, 0F, 0F);
    }

    private static void visuals() {
        float I = glitchIntensity;
        chroma = range(VIGNETTE_CACHE, 1.5F * I + 50F);
        grainMax = range(NOISE_CACHE, 0.015F * I);
        scratchMax = range(SCRATCH_CACHE, 0.005F * I);
        boolean see = inSeen || inLost;
        if (RNG.nextFloat() > 0.85F && see) {
            strengthY = range(-0.01F * (100F - I), 0.005F * (100F - I));
        } else if (RNG.nextFloat() > 0.75F && see) {
            strengthX = range(-0.01F * (100F - I), 0.005F * (100F - I));
        }
        // (the very rare flash of the original, 0.1 %, is not part of this port: the flash of our version stays as it is)
    }

    private static void soundVolume() {
        float I = glitchIntensity;
        if (I > 60F) {
            san3 = (I - 60F) / 40F;
        } else if (I > 30F) {
            san2 = (I - 30F) / 70F;
            san3 = 0F;
        } else {
            san2 = 0F;
            san3 = 0F;
        }
        san1 = I / 100F;
        staticglitch = 0.5F;
        mixer(san1, san2, san3, staticglitch);
    }

    private static void mixer(float a, float b, float c, float g) {
        // san1 is not stored: the static light keeps its own volume formula and only follows the dice (diceOn)
        vol2 = Mth.clamp(b, 0F, 1F);
        vol3 = Mth.clamp(c, 0F, 1F);
        volG = Mth.clamp(g, 0F, 1F);
    }

    // ------------------------------------------------------------------------------------------
    //  sounds (once per game tick)
    // ------------------------------------------------------------------------------------------

    private static final float LIGHT_VOLUME = 1.0F;
    private static final float MEDIUM_VOLUME = 1.0F;
    private static final float HEAVY_VOLUME = 1.0F;
    /** loudness of the plain staticglitch layer on top of everything (the original: 0.5 at any loss above 0). 1.0 = as in the original, 0 = off. */
    private static final float GLITCH_SCALE = 1.0F;

    // ------------------------------------------------------------------------------------------
    //  20 ms GATING INSIDE THE SOUND ITSELF
    //  Minecraft changes the volume of a sound only once per game tick (50 ms). So the four layers are played as STREAMS that
    //  are produced by this class: the samples of the track are copied one by one and in every 20 ms block (0.02 s = the
    //  FixedUpdate step of the original) the dice decides if the block is heard or silent. For staticglitch an "off" block
    //  also rewinds the sound to its beginning (Revert stops it, Apply plays it again from the start).
    //  All layers use the SAME dice for the same 20 ms block (audioDice), like the one dice of the original.
    // ------------------------------------------------------------------------------------------

    private static volatile float sharedI;
    private static final long AUDIO_EPOCH = System.nanoTime();
    private static final int RING = 4096;
    private static final long[] ringStep = new long[RING];
    private static final boolean[] ringVal = new boolean[RING];
    private static final java.util.Random AUDIO_RNG = new java.util.Random();

    /** the dice of the 20 ms block number "step" (the same answer for every layer that asks for the same block). */
    static synchronized boolean audioDice(long step) {
        int i = (int) (step & (RING - 1));
        if (ringStep[i] != step + 1) {
            float I = sharedI;
            ringVal[i] = I > 0F && AUDIO_RNG.nextFloat() < (I + 35F) / 100F;
            ringStep[i] = step + 1;
        }
        return ringVal[i];
    }

    private static long audioStepNow() {
        return (System.nanoTime() - AUDIO_EPOCH) / 20_000_000L;
    }

    /** san2 (static medium): (I - 30) / 70 up to 60 %; above 60 % the original keeps the last value, (60 - 30) / 70. */
    private static float mediumGain(float I) {
        if (I > 60F) return 30F / 70F;
        if (I > 30F) return (I - 30F) / 70F;
        return 0F;
    }

    /** san3 (static heavy): (I - 60) / 40 above 60 %. */
    private static float heavyGain(float I) {
        return I > 60F ? Mth.clamp((I - 60F) / 40F, 0F, 1F) : 0F;
    }

    /** layer kinds: 1 static light, 2 static medium, 3 static heavy, 4 plain staticglitch. */
    private static final class GateStream implements AudioStream {
        private final AudioFormat format;
        private final short[] pcm;
        private final ByteOrder order;
        private final int kind;
        private final int stepSamples;
        private long step;
        private int stepLeft;
        private boolean on;
        private int pos;
        private float gain;

        GateStream(AudioStream src, int kind) throws IOException {
            this.kind = kind;
            this.format = src.getFormat();
            this.stepSamples = Math.max(1, Math.round(format.getSampleRate() * STEP));
            List<short[]> chunks = new ArrayList<>();
            ByteOrder ord = ByteOrder.nativeOrder();
            boolean first = true;
            int total = 0;
            while (true) {
                ByteBuffer b = src.read(32768);
                if (b == null || !b.hasRemaining()) break;
                if (first) { ord = b.order(); first = false; }
                ShortBuffer sb = b.asShortBuffer();
                short[] a = new short[sb.remaining()];
                sb.get(a);
                chunks.add(a);
                total += a.length;
            }
            src.close();
            short[] all = new short[Math.max(1, total)];
            int p = 0;
            for (short[] a : chunks) { System.arraycopy(a, 0, all, p, a.length); p += a.length; }
            this.pcm = all;
            this.order = ord;
            this.step = audioStepNow();
        }

        @Override public AudioFormat getFormat() { return format; }

        @Override public ByteBuffer read(int size) {
            int n = Math.max(1, size / 2);
            ByteBuffer out = BufferUtils.createByteBuffer(n * 2).order(order);
            for (int i = 0; i < n; i++) {
                if (stepLeft <= 0) {
                    on = audioDice(step++);
                    stepLeft = stepSamples;
                    float I = sharedI;
                    gain = kind == 2 ? mediumGain(I) : kind == 3 ? heavyGain(I) : kind == 4 ? 0.5F : 1F;
                    if (kind == 4 && !on) pos = 0;          // Revert stops staticglitch, the next Apply plays it from the beginning
                }
                stepLeft--;
                short v = 0;
                if (on) v = (short) Math.round(pcm[pos] * gain);
                out.putShort(v);
                if (kind != 4 || on) {
                    pos++;
                    if (pos >= pcm.length) pos = 0;
                }
            }
            out.flip();
            return out;
        }

        @Override public void close() { }
    }

    private static GateLoop light, medium, heavy, glitch;

    private static SoundEvent event(String name) {
        return new SoundEvent(new ResourceLocation(NS, name));
    }

    /**
     * A looping layer. When the sound engine asks for its stream (getStream) it gets the GateStream, which gates the sound
     * every 20 ms. If for some reason that does not happen (streamed stays false), the layer falls back to the old way: the
     * volume is switched once per game tick.
     */
    private static final class GateLoop extends AbstractTickableSoundInstance {
        private final int kind;
        volatile boolean streamed;

        GateLoop(int kind, String file) {
            super(event(file), SoundSource.MASTER, RandomSource.create());
            this.kind = kind;
            this.looping = true; this.delay = 0; this.volume = 0.0001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override public boolean canStartSilent() { return true; }

        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary lib, Sound sound, boolean loop) {
            return lib.getStream(sound.getPath(), false).thenApply(src -> {
                try {
                    GateStream gs = new GateStream(src, kind);
                    streamed = true;
                    return (AudioStream) gs;
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
        }

        @Override public void tick() {
            float v;
            if (kind == 1) {
                if (glitchIntensity <= 0F) { this.stop(); return; }
                float level = Math.max(CameraEffects.sanity(), 0.05F);
                v = LIGHT_VOLUME * (0.55F + 0.45F * level);
                if (!streamed && !diceOn) v = 0F;
            } else if (kind == 2) {
                if (glitchIntensity <= 30F) { this.stop(); return; }
                v = MEDIUM_VOLUME;
                if (!streamed) v *= vol2;
            } else if (kind == 3) {
                if (glitchIntensity <= 60F) { this.stop(); return; }
                v = HEAVY_VOLUME;
                if (!streamed) v *= vol3;
            } else {
                if (glitchIntensity <= 0F) { this.stop(); return; }
                v = GLITCH_SCALE * (streamed ? 1F : (diceOn ? 0.5F : 0F));
            }
            this.volume = Math.max(0.0001F, v * CameraEffects.MASTER_VOLUME);
        }
    }

    static void tickSounds(Minecraft mc) {
        SoundManager sm = mc.getSoundManager();
        revertSinceTick = false;
        if (glitchIntensity <= 0F) return;
        if (CameraEffects.needsStart(sm, light)) {
            light = new GateLoop(1, "static_light_full");
            CameraEffects.startLoop(sm, light);
        }
        if (glitchIntensity > 30F && CameraEffects.needsStart(sm, medium)) {
            medium = new GateLoop(2, "static_medium_full");
            CameraEffects.startLoop(sm, medium);
        }
        if (glitchIntensity > 60F && CameraEffects.needsStart(sm, heavy)) {
            heavy = new GateLoop(3, "static_heavy_full");
            CameraEffects.startLoop(sm, heavy);
        }
        if (GLITCH_SCALE > 0F && CameraEffects.needsStart(sm, glitch)) {
            glitch = new GateLoop(4, "static_glitch_orig");
            CameraEffects.startLoop(sm, glitch);
        }
    }
}
