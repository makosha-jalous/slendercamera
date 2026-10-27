package com.example.slendercam.client;

import net.minecraft.client.Minecraft;
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
 *   static_medium_full  ONE track (not two): below 30 % it is the "staticglitch" slot of the original: volume 0.5 at any
 *                      loss above 0, started from the beginning when it is not playing, cut at once by every Revert (short
 *                      starts); from 30 % on the same track goes on as ONE straight track (no cuts), volume 0.5 rising to 1
 *   san3 = static_heavy_full    from 60 %: volume (I - 60) / 40, muted by the dice
 *   static glitch = static_glitch_orig  from 65 %: stutters at first (own chance, 35 % at 65 %), one straight track from 70 %,
 *                      and it thins out again below 70 % and is gone below 65 %; volume 0.5, muted by the dice
 * (This assignment of the files to the slots is what the ear says; the names of the slots are those of the original.)
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
    private static float glitchIntensity;
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
    /** loudness of the Static Glitch layer (from 65 %): 1.0 = volume 0.5 as in the original, 0 = off. */
    private static final float GLITCH_SCALE = 1.0F;

    private static GateLoop light, heavy, glitchLoop;
    private static SoundInstance glitchInst;
    private static long glitchStartMs;

    private static SoundEvent event(String name) {
        return new SoundEvent(new ResourceLocation(NS, name));
    }

    /** a looping whole track whose volume follows the dice. Loops are muted, not paused, like the audio sources of the original. */
    private static final class GateLoop extends AbstractTickableSoundInstance {
        private final int kind;      // 1 light, 2 medium, 3 heavy

        GateLoop(int kind, String file) {
            super(event(file), SoundSource.MASTER, RandomSource.create());
            this.kind = kind;
            this.looping = true; this.delay = 0; this.volume = 0.0001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override public boolean canStartSilent() { return true; }

        @Override public void tick() {
            float v;
            if (kind == 1) {
                if (glitchIntensity <= 0F) { this.stop(); return; }
                float level = Math.max(CameraEffects.sanity(), 0.05F);
                v = diceOn ? LIGHT_VOLUME * (0.55F + 0.45F * level) : 0F;
            } else if (kind == 2) {
                if (glitchIntensity <= 30F) { this.stop(); return; }
                v = vol2 * MEDIUM_VOLUME;
            } else if (kind == 3) {
                if (glitchIntensity <= 60F) { this.stop(); return; }
                v = vol3 * HEAVY_VOLUME;
            } else {
                // static glitch: from 65 %; its own chance (35 % at 65 %, 100 % from 70 %) AND the dice of the step
                if (glitchIntensity <= 65F) { this.stop(); return; }
                float pg = glitchIntensity >= 70F ? 1F : 0.35F + 0.65F * (glitchIntensity - 65F) / 5F;
                v = (diceOn && RNG.nextFloat() < pg) ? 0.5F * GLITCH_SCALE : 0F;
            }
            this.volume = Math.max(0.0001F, v * CameraEffects.MASTER_VOLUME);
        }
    }

    static void tickSounds(Minecraft mc) {
        SoundManager sm = mc.getSoundManager();

        if (glitchIntensity > 0F) {
            if (CameraEffects.needsStart(sm, light)) {
                light = new GateLoop(1, "static_light_full");
                CameraEffects.startLoop(sm, light);
            }
            if (glitchIntensity > 60F && CameraEffects.needsStart(sm, heavy)) {
                heavy = new GateLoop(3, "static_heavy_full");       // slot san3: from 60 %
                CameraEffects.startLoop(sm, heavy);
            }
        }

        if (glitchIntensity > 65F && GLITCH_SCALE > 0F && CameraEffects.needsStart(sm, glitchLoop)) {
            glitchLoop = new GateLoop(4, "static_glitch_orig");
            CameraEffects.startLoop(sm, glitchLoop);
        }

        // Static Medium, one track: below 30 % cut by every Revert and started again from the beginning; from 30 % it just goes on
        if (glitchIntensity <= 0F) {
            if (mediumInst != null) { sm.stop(mediumInst); mediumInst = null; }
            revertSinceTick = false;
            return;
        }
        boolean straight = glitchIntensity > 30F;
        if (revertSinceTick) {
            revertSinceTick = false;
            if (!straight && mediumInst != null) {
                sm.stop(mediumInst);
                mediumInst = null;
            }
        }
        boolean want = straight || (diceOn && volG > 0F);
        if (want && CameraEffects.needsStart(sm, mediumInst)) {
            mediumInst = new MediumTrack();
            CameraEffects.startLoop(sm, mediumInst);
        }
    }

    private static MediumTrack mediumInst;

    /** the one Static Medium track: volume 0.5, rising from 30 % to 1.0 at 100 %. It is stopped from outside (every Revert below 30 %). */
    private static final class MediumTrack extends AbstractTickableSoundInstance {
        MediumTrack() {
            super(event("static_medium_full"), SoundSource.MASTER, RandomSource.create());
            this.looping = true; this.delay = 0; this.volume = 0.5F * CameraEffects.MASTER_VOLUME;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override public void tick() {
            if (glitchIntensity <= 0F) { this.stop(); return; }
            float v = glitchIntensity > 30F ? Math.max(0.5F, Mth.clamp((glitchIntensity - 30F) / 70F, 0F, 1F)) : 0.5F;
            this.volume = Math.max(0.0001F, v * CameraEffects.MASTER_VOLUME);
        }
    }
}
