package com.example.slendercam.client;

import com.example.slendercam.SlenderCamMod;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Client side of the camera effects.
 *
 * AGGRESSION (read from the server AI through getAggression()) drives the strength of everything:
 * static_heavy, breathing, grain, the strength of the interface bend, the view and the FOV shake.
 * It works from behind the player's back and through walls too.
 *
 * VISUAL CONTACT (the heroine really looks at him, even far away on the horizon) is used only for two things:
 *  - the base level of the twitches / ticks / short glitches that play even at zero aggression;
 *  - the decision whether a teleport shows the jumpscare glitch.
 */
@Mod.EventBusSubscriber(modid = SlenderCamMod.ID, value = Dist.CLIENT)
public class CameraEffects {

    /** Slenderman is found in the world by his registry name: the two mods stay fully independent. */
    private static final ResourceLocation SLENDER = new ResourceLocation("slenderman", "slenderman");

    private static Method aggressionMethod;

    private static boolean isSlender(Entity e) {
        return SLENDER.equals(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()));
    }

    /**
     * Reads the synced server aggression through reflection: it calls SlendermanEntity#getAggression()
     * without importing his class, so this mod compiles and runs without the Slenderman mod.
     */
    private static float readAggression(Entity e) {
        try {
            if (aggressionMethod == null || !aggressionMethod.getDeclaringClass().isInstance(e)) {
                aggressionMethod = e.getClass().getMethod("getAggression");
            }
            return Mth.clamp(((Number) aggressionMethod.invoke(e)).floatValue(), 0F, 1F);
        } catch (Exception ex) {
            return 0F;
        }
    }

    private static final float ZOOM_MAX = 3.5F;
    private static final float ZOOM_SPEED = 0.14F;
    private static final float RISE = 0.08F;
    private static final float MASTER_VOLUME = 0.9F;

    // ---- tweakables (all the "feel" numbers live here) ----

    /** "Visual contact": he is in front, in the field of view and not behind a wall, up to this distance (blocks). */
    private static final double SEEN_RANGE = 80.0D;
    private static final double SEEN_COS = 0.55D;
    /** Base shake level (interface twitches, FOV, view) while he is simply in sight, even at zero aggression. */
    private static final float SEEN_SHAKE = 0.30F;

    /** "Close" for the near mode of the heavy static and for the frenzy shake: closer than this many blocks. */
    private static final double NEAR_DIST = 12.0D;
    /** Aggression above this (while he is close) = frenzy: camera and FOV shake at once. */
    private static final float FRENZY_AGGRESSION = 0.65F;
    /** Shake level the frenzy pushes the interface / view / FOV to, at least (0..1). */
    private static final float FRENZY_SHAKE = 0.85F;

    /** FAR / MID mode: static_heavy reads the aggression directly. It starts when the aggression exceeds this ... */
    private static final float HEAVY_START_AGGRESSION = 0.65F;
    /** ... and gets louder together with the aggression until it is at full strength at 1.0. */
    private static final float HEAVY_FULL_AGGRESSION = 1.0F;
    /** NEAR mode (he is right behind the back, < 12 blocks): static_heavy starts after this many ticks of continuous pressure: 40 = 2 s. */
    private static final int HEAVY_DELAY_TICKS = 40;
    /** NEAR mode: once started, it stays on until the aggression falls below this (or he is not close any more). */
    private static final float HEAVY_NEAR_RELEASE = 0.40F;
    /** The big static needs at least this many ticks to rise from silence to full: 60 = 3 s (never starts loud). */
    private static final int HEAVY_RISE_TICKS = 60;
    /** Loudness of the big static at its maximum (0..1, multiplied by MASTER_VOLUME). */
    private static final float HEAVY_VOLUME = 1.0F;

    /** The big static dies out in this many ticks: 130 = 6.5 s. */
    private static final int SOUND_FADE_TICKS = 130;
    /** static_mid is gone this many ticks after the aggression is gone: 80 = 4 s. */
    private static final int MID_FADE_TICKS = 80;
    /** Breathing / grain fade out slowly and smoothly: 150 = 7.5 s. */
    private static final int BREATH_FADE_TICKS = 150;
    /** The interface frame bend fades out slowly and smoothly: 150 = 7.5 s. */
    private static final int HUD_FADE_TICKS = 150;
    /** The FOV wobble keeps shaking this many ticks AFTER the interface frame is completely calm: 50 = 2.5 s. */
    private static final int FOV_LAG_TICKS = 50;

    /** Short glitch sounds only play while intensity is below this (the big static takes over above it). */
    private static final float GLITCH_SOUND_MAX = 0.65F;
    private static final int GLITCH_PAUSE_MIN = 60;      // 3..5 seconds between short glitch sounds
    private static final int GLITCH_PAUSE_RANDOM = 41;

    /** Peak shake of the player's VIEW in degrees at maximum intensity (small, but very fast). */
    private static final float VIEW_SHAKE_DEG = 1.3F;
    /** Peak wobble of the field of view (FOV) in degrees at maximum intensity. */
    private static final float VIEW_FOV_WOBBLE = 1.95F;

    /** One twitch of the interface frame (and its twitch_tick sound) lasts 12..20 ms. */
    private static final long TWITCH_MS_MIN = 12L;
    private static final int TWITCH_MS_RANDOM = 9;
    /** Loudness of twitch_tick: always the same, it does not depend on the aggression. */
    private static final float TWITCH_TICK_VOLUME = 0.6F;

    /** Length of the distortion when he teleports: a short flash, a fraction of a second. */
    private static final int TELEPORT_GLITCH_MS = 220;
    /** Chance of the teleport glitch when he teleports OUT of the player's field of view and not in front of her. */
    private static final float TELEPORT_UNSEEN_CHANCE = 0.20F;
    /** "In front of the player": closer than this (blocks) and inside this view cone (dot product) = the glitch is always shown. */
    private static final double FRONT_RANGE = 30.0D;
    private static final double FRONT_COS = 0.30D;

    private static float zoomPrev, zoomNow, flash;
    private static boolean zoomKeyPrev = false;
    private static final float ZOOM_SOUND_VOLUME = 0.5F; // zoom sounds loudness (0..1)

    /** A value that rises fast and then falls along a straight line that always takes the same time. */
    private static final class Env {
        float v, peak;

        void update(float goal, float riseFactor, float maxRise, int fallTicks) {
            if (goal > v) {
                v += Math.min((goal - v) * riseFactor, maxRise);
                if (v > peak) peak = v;
            } else if (goal < v) {
                v = Math.max(goal, v - peak / fallTicks);
            }
            if (v < 0.0005F) { v = 0F; peak = 0F; }
        }

        void kill(float factor) {
            v *= factor;
            peak *= factor;
            if (v < 0.0005F) { v = 0F; peak = 0F; }
        }
    }

    private static final Env INTENSITY = new Env();   // static_mid, breathing, grain, short glitches
    private static final Env HUD = new Env();         // interface frame bend strength, view shake
    private static final Env FOV = new Env();         // FOV wobble: same as HUD, but 2.5 s longer
    private static final Env HEAVY = new Env();       // big static
    private static final Env MID = new Env();         // static_mid
    private static final Env PROX = new Env();        // how near he is (for the strength of the view shake)

    /** The shake target of the last FOV_LAG_TICKS ticks: the FOV follows it with a delay, so it calms down later. */
    private static final float[] SHAKE_DELAY = new float[FOV_LAG_TICKS];
    private static int shakeDelayIdx;

    // mirrors of the envelopes above (used by the HUD and the sounds)
    private static float intensity;
    private static float hudPressure;
    private static float fovPressure;
    private static float heavyVol;
    private static float proxVol;
    private static float aggression;                  // smoothed server aggression 0..1
    private static boolean seenNow;                   // the heroine looks at him right now
    private static int heavyWait;                     // NEAR mode: ticks of continuous pressure (close + aggression > 0.65)
    private static boolean heavyLatched;              // NEAR mode: the big static has started

    private static final RandomSource HUD_RNG = RandomSource.create();
    private static int glitchCooldown = 60;

    // Output values read by CameraHud (frame deformation, in reference pixels)
    private static float frameVertical;
    private static float frameHorizontal;
    private static float frameVerticalBend;
    private static float frameHorizontalBend;

    // Sharp twitch of the frame (12..20 ms), values -1..1. It starts together with a kick of the FOV shake.
    private static float twV, twVB, twH, twHB;
    private static long twitchEndMs, nextTwitchMs, lastJitterBucket = -1L;

    /** twitch_tick sounds that are playing: they are cut when their twitch ends. */
    private static final class TickSound {
        final SimpleSoundInstance inst;
        final long endMs;

        TickSound(SimpleSoundInstance inst, long endMs) {
            this.inst = inst;
            this.endMs = endMs;
        }
    }

    private static final List<TickSound> ACTIVE_TICKS = new ArrayList<>();

    private static long teleportGlitchEndMs;
    private static float teleportSignX = 1F, teleportSignY = 1F;
    private static UUID lastId;
    private static final Vec3[] HIST = new Vec3[4];
    private static int histIdx, jumpCooldown;

    private static MidLoop mid;
    private static HeavyLoop heavyLoop;
    private static BreathingLoop breathingLoop;

    // Looping glitch layers on top of the big static: 1 weakest, 2 medium, 3 strongest
    private static final GlitchLoop[] GLITCH = new GlitchLoop[4];
    private static final float[] GLITCH_GAIN = new float[4];
    private static int heavyTicks;                    // how long the big static has been audible
    private static int g2StartTicks = 100;            // layer 2 takes over 4-6 s after the big static started
    private static int lastMode, altTicks;

    public static float intensity() { return intensity; }
    public static float aggression() { return aggression; }
    public static float flash() { return flash; }
    public static float zoom(float pt) { return Mth.lerp(pt, zoomPrev, zoomNow); }
    public static float hudPressure() { return hudPressure; }
    public static float frameVertical() { return frameVertical; }
    public static float frameHorizontal() { return frameHorizontal; }
    public static float frameVerticalBend() { return frameVerticalBend; }
    public static float frameHorizontalBend() { return frameHorizontalBend; }
    /** Kept for compatibility with older code: it is now simply the server aggression. */
    public static float gazePressure() { return aggression; }

    private static boolean tpActive() {
        return System.currentTimeMillis() < teleportGlitchEndMs;
    }

    public static float teleportGlitch() {
        return tpActive() ? 1F : 0F;
    }

    public static float teleportSignX() { return teleportSignX; }
    public static float teleportSignY() { return teleportSignY; }

    /** Kept for compatibility with older code. The HUD now uses its own exact frame mapping. */
    public static float frameWarpX(float x, float y) {
        float t = Mth.clamp((y - 21F) / (383F - 21F), 0F, 1F);
        float wave = (float) Math.sin(Math.PI * t);
        float leftShift = frameVertical + frameVerticalBend * wave;
        float rightShift = -frameVertical - frameVerticalBend * wave;
        float n = Mth.clamp(x / 739F, 0F, 1F);
        return Mth.lerp(n, leftShift, rightShift);
    }

    /** Kept for compatibility with older code. The HUD now uses its own exact frame mapping. */
    public static float frameWarpY(float x, float y) {
        float t = Mth.clamp((x - 19F) / (739F - 43F), 0F, 1F);
        float wave = (float) Math.sin(Math.PI * t);
        float topShift = frameHorizontal + frameHorizontalBend * wave;
        float bottomShift = -frameHorizontal - frameHorizontalBend * wave;
        float n = Mth.clamp(y / 415F, 0F, 1F);
        return Mth.lerp(n, topShift, bottomShift);
    }

    public static float noise(int seed) {
        long now = System.currentTimeMillis();
        long h = (now / 45) * 1013904223L + seed * 2654435761L;
        h ^= (h >>> 13);
        h *= 0x5bd1e995L;
        h ^= (h >>> 15);
        float a = ((h & 0xFFFF) / 32767.5F) - 1F;
        float s = (float) Math.sin(now / 1000.0 * (9 + seed * 1.7) + seed);
        return a * 0.6F + s * 0.4F;
    }

    /** Fast, sharp jitter for the player's view: a new random value about every 16 ms. */
    public static float jitter(int seed) {
        long now = System.currentTimeMillis();
        long h = (now / 16) * 0x9E3779B97F4A7C15L + seed * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 32);
        h *= 0xD6E8FEB86659FD93L;
        h ^= (h >>> 32);
        float white = ((h & 0xFFFFL) / 32767.5F) - 1F;
        float wobble = (float) Math.sin(now / 1000.0 * (38 + seed * 3.1) + seed);
        return white * 0.65F + wobble * 0.35F;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        boolean cam = mc.player != null && mc.level != null && CameraHud.active();

        boolean zoomKey = cam && mc.screen == null && ClientModEvents.ZOOM_KEY.isDown();
        // Zoom sounds: one when Z is pressed (zoom in), one when it is released (zoom out).
        if (zoomKey && !zoomKeyPrev) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.ZOOM_ON.get(), 1.0F, ZOOM_SOUND_VOLUME));
        } else if (!zoomKey && zoomKeyPrev && zoomNow > 0.05F) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.ZOOM_OFF.get(), 1.0F, ZOOM_SOUND_VOLUME));
        }
        zoomKeyPrev = zoomKey;

        zoomPrev = zoomNow;
        zoomNow += ((zoomKey ? 1F : 0F) - zoomNow) * ZOOM_SPEED;
        if (!zoomKey && zoomNow < 0.003F) zoomNow = 0F;

        flash *= 0.90F;
        if (flash < 0.01F) flash = 0F;
        if (jumpCooldown > 0) jumpCooldown--;

        if (mc.player == null || mc.level == null) {
            INTENSITY.kill(0F);
            HUD.kill(0F);
            FOV.kill(0F);
            HEAVY.kill(0F);
            MID.kill(0F);
            PROX.kill(0F);
            Arrays.fill(SHAKE_DELAY, 0F);
            aggression = 0F;
            seenNow = false;
            heavyWait = 0;
            heavyLatched = false;
            syncMirrors();
            return;
        }

        // the nearest Slenderman
        Entity sl = null;
        double best = Double.MAX_VALUE;
        for (Entity en : mc.level.entitiesForRendering()) {
            if (isSlender(en)) {
                double d = en.distanceToSqr(mc.player);
                if (d < best) { best = d; sl = en; }
            }
        }

        double dist = 0;
        float rawAggression = 0F;
        boolean seen = false;
        if (sl != null) {
            dist = Math.sqrt(best);
            // THE source of the strength of everything: the real aggression, synced from the server AI.
            rawAggression = readAggression(sl);
            // visual contact (even far away on the horizon): only the base level of twitches uses it
            seen = cam && dist < SEEN_RANGE && isLookingAt(mc.player, sl);

            Vec3 pos = sl.position();
            if (!sl.getUUID().equals(lastId)) {
                lastId = sl.getUUID();
                for (int i = 0; i < HIST.length; i++) HIST[i] = pos;
            }
            Vec3 old = HIST[histIdx];
            HIST[histIdx] = pos;
            histIdx = (histIdx + 1) % HIST.length;
            if (old != null && jumpCooldown == 0 && pos.distanceTo(old) > 4.0) {
                jumpCooldown = 10;
                if (cam) onTeleport(mc, sl, dist);
            }
        } else {
            lastId = null;
        }
        seenNow = seen;

        // smoothed, so the effects never pop when a network update arrives
        float rawForCam = cam ? rawAggression : 0F;
        aggression += (rawForCam - aggression) * 0.30F;
        if (aggression < 0.001F) aggression = 0F;

        boolean near = cam && sl != null && dist < NEAR_DIST;
        boolean frenzy = near && aggression > FRENZY_AGGRESSION;

        // breathing and grain follow the aggression and fade out slowly
        INTENSITY.update(aggression, RISE, 1F, BREATH_FADE_TICKS);

        // static_mid flows in at middle aggression and is gone before the big static takes over
        float midGoal = smooth(0.15F, 0.45F, aggression) * (1F - smooth(0.65F, 0.90F, aggression));
        MID.update(midGoal, 0.08F, 1F, MID_FADE_TICKS);

        // how near he is: the view shake grows with it
        float proxGoal = cam && sl != null ? 1F - (float) Mth.clamp((dist - 2.5) / 60.0, 0.0, 1.0) : 0F;
        PROX.update(proxGoal, 0.10F, 1F, HUD_FADE_TICKS + FOV_LAG_TICKS);

        // ---- Big static (static_heavy) reads the aggression ----
        // NEAR (< 12 blocks, right behind the back): it starts after 2 s of continuous pressure
        //   (aggression > 0.65) and works as before. If the player runs away, the counter resets.
        // FAR: no delay at all, it starts when the aggression exceeds 0.65 and then gets louder exactly
        //   as the aggression grows (a long stare at a far monster fills the aggression slowly).
        if (frenzy) heavyWait++;
        else heavyWait = 0;
        if (heavyWait >= HEAVY_DELAY_TICKS) heavyLatched = true;
        if (!near || aggression < HEAVY_NEAR_RELEASE) heavyLatched = false;
        float heavyGoal;
        if (near) {
            heavyGoal = heavyLatched ? smooth(0.40F, 0.95F, aggression) : 0F;
        } else {
            heavyGoal = smooth(HEAVY_START_AGGRESSION, HEAVY_FULL_AGGRESSION, aggression);
        }
        HEAVY.update(heavyGoal, 0.35F, 1F / HEAVY_RISE_TICKS, SOUND_FADE_TICKS);

        // Interface bend and view shake: the strength comes from the aggression, in the frenzy it jumps
        // to a strong level at once, and while he is simply in sight there is a base level even at zero aggression.
        float shakeTarget = 0F;
        if (cam) {
            shakeTarget = frenzy ? Math.max(aggression, FRENZY_SHAKE) : aggression;
            if (seen) shakeTarget = Math.max(shakeTarget, SEEN_SHAKE);
        }
        HUD.update(shakeTarget, 0.22F, 1F, HUD_FADE_TICKS);

        // The FOV follows the same target, but it also remembers it for FOV_LAG_TICKS (2.5 s),
        // so when the interface frame is completely calm, the FOV keeps trembling for 2.5 s more.
        float delayed = SHAKE_DELAY[shakeDelayIdx];
        SHAKE_DELAY[shakeDelayIdx] = shakeTarget;
        shakeDelayIdx = (shakeDelayIdx + 1) % SHAKE_DELAY.length;
        FOV.update(Math.max(shakeTarget, delayed), 0.22F, 1F, HUD_FADE_TICKS);

        if (!cam) {            // camera lowered: everything stops quickly
            INTENSITY.kill(0.8F);
            HUD.kill(0.72F);
            FOV.kill(0.72F);
            HEAVY.kill(0.85F);
            MID.kill(0.8F);
            PROX.kill(0.72F);
            Arrays.fill(SHAKE_DELAY, 0F);
            heavyWait = 0;
            heavyLatched = false;
        }
        if (INTENSITY.v < 0.002F) INTENSITY.kill(0F);
        syncMirrors();

        manageSounds(mc);
        manageGlitchSounds(mc, cam);
        manageGlitchLayers(mc, near);
    }

    private static void syncMirrors() {
        intensity = INTENSITY.v;
        hudPressure = HUD.v;
        fovPressure = FOV.v;
        heavyVol = HEAVY.v;
        proxVol = PROX.v;
    }

    /**
     * He just jumped to a new place. The short jumpscare glitch is shown when he appears in the player's
     * field of view, ALWAYS when he appears in front of the player, and with 20% chance otherwise.
     */
    private static void onTeleport(Minecraft mc, Entity sl, double dist) {
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Vec3 to = sl.position().subtract(mc.player.position()).normalize();
        boolean inFront = dist < FRONT_RANGE && look.dot(to) > FRONT_COS;
        boolean inView = dist < SEEN_RANGE && isLookingAt(mc.player, sl);

        boolean distort = inView || inFront || HUD_RNG.nextFloat() < TELEPORT_UNSEEN_CHANCE;
        if (distort) {
            teleportSignX = HUD_RNG.nextBoolean() ? 1F : -1F;
            teleportSignY = HUD_RNG.nextBoolean() ? 1F : -1F;
            teleportGlitchEndMs = System.currentTimeMillis() + TELEPORT_GLITCH_MS + HUD_RNG.nextInt(60);
        }
        if (distort || dist < 18) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.TELEPORT.get(), 1.0F, 1.0F));
        }
    }

    /**
     * Twitches are born from the FOV shake itself: the FOV gets a new random kick every 16 ms
     * (jitter(24)). When that kick is strong enough, a sharp twitch of the interface starts in the very
     * same moment, and the tiny twitch_tick sound plays together with it. The twitch lasts 12..20 ms.
     * The strength of the twitch comes from the aggression; the base level exists even at zero aggression.
     */
    private static void updateTwitch(long now, float fp) {
        if (fp < 0.01F) {
            nextTwitchMs = 0L;
            return;
        }
        long bucket = now / 16L;
        if (bucket == lastJitterBucket) return;      // the FOV kick has not changed since the last frame
        lastJitterBucket = bucket;

        if (nextTwitchMs == 0L) nextTwitchMs = now + 30L;
        if (now < nextTwitchMs) return;

        float j = jitter(24);                        // exactly the value onFov uses in this moment
        float threshold = Mth.lerp(fp, 0.45F, 0.20F);
        if (Math.abs(j) < threshold) return;

        long dur = TWITCH_MS_MIN + HUD_RNG.nextInt(TWITCH_MS_RANDOM);
        twitchEndMs = now + dur;
        twV = Math.signum(j) * Mth.clamp(0.5F + Math.abs(j), 0.5F, 1F);
        twVB = HUD_RNG.nextFloat() < 0.85F ? signedKick(1F) : 0F;
        twH = HUD_RNG.nextFloat() < 0.85F ? signedKick(1F) : 0F;
        twHB = HUD_RNG.nextFloat() < 0.85F ? signedKick(1F) : 0F;
        int gapMin = Math.round(Mth.lerp(fp, 60F, 20F));
        int gapMax = Math.round(Mth.lerp(fp, 240F, 90F));
        nextTwitchMs = twitchEndMs + gapMin + HUD_RNG.nextInt(Math.max(1, gapMax - gapMin));

        // the tick plays while he is in sight or the aggression is above zero, always with the same loudness
        if (seenNow || aggression > 0.02F) playTwitchTick(now, dur);
    }

    /**
     * Called by CameraHud once per rendered frame (not once per tick), so the twitches can be
     * only a few milliseconds long. Produces frameVertical / frameHorizontal / ...Bend.
     * The frame is calm between twitches; during a twitch it jumps to the bent pose and back at once.
     */
    public static void frameUpdate() {
        long now = System.currentTimeMillis();

        stopFinishedTicks(now);
        // twitches + ticks follow the FOV shake (which lives longer than the interface bend)
        updateTwitch(now, Mth.clamp(fovPressure, 0F, 1F));

        if (tpActive()) {
            // Teleport: both groups are forced into one violent pose for the short flash.
            frameVertical = 34F * teleportSignX;
            frameHorizontal = 28F * teleportSignY;
            frameVerticalBend = 19F * teleportSignX;
            frameHorizontalBend = 17F * teleportSignY;
            return;
        }

        if (hudPressure < 0.01F || now >= twitchEndMs) {
            frameVertical = frameHorizontal = frameVerticalBend = frameHorizontalBend = 0F;
            return;
        }

        float p = Mth.clamp(hudPressure, 0F, 1F);
        // Fade the distortion in smoothly so nothing pops at very low pressure.
        float fade = Mth.clamp(p / 0.12F, 0F, 1F);

        // Strong already at the base level (he is far away), much stronger with the aggression.
        float spread = (7F + 16F * p + 14F * p * p) * fade;
        float bend = (4F + 9F * p + 9F * p * p) * fade;

        frameVertical = twV * spread;
        frameVerticalBend = twVB * bend;
        frameHorizontal = twH * spread * 0.82F;
        frameHorizontalBend = twHB * bend * 0.90F;
    }

    /** One tiny slice of static, exactly when the FOV gets a strong kick. It is cut when the twitch ends. */
    private static void playTwitchTick(long now, long durMs) {
        float pitch = 0.90F + HUD_RNG.nextFloat() * 0.20F;
        SimpleSoundInstance inst = SimpleSoundInstance.forUI(SlenderCamMod.TWITCH_TICK.get(), pitch,
                Mth.clamp(MASTER_VOLUME * TWITCH_TICK_VOLUME, 0F, 1F));
        Minecraft.getInstance().getSoundManager().play(inst);
        ACTIVE_TICKS.add(new TickSound(inst, now + durMs));
    }

    private static void stopFinishedTicks(long now) {
        if (ACTIVE_TICKS.isEmpty()) return;
        SoundManager sm = Minecraft.getInstance().getSoundManager();
        Iterator<TickSound> it = ACTIVE_TICKS.iterator();
        while (it.hasNext()) {
            TickSound t = it.next();
            if (now >= t.endMs) {
                sm.stop(t.inst);
                it.remove();
            }
        }
    }

    private static float signedKick(float magnitude) {
        float x = HUD_RNG.nextFloat() * 2F - 1F;
        float shaped = Math.signum(x) * (0.35F + 0.65F * Math.abs(x));
        return shaped * magnitude;
    }

    /** Is he in the field of view of the heroine and not hidden behind blocks? */
    private static boolean isLookingAt(Player p, Entity s) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F).normalize();
        double bh = s.getBbHeight();
        for (double f : new double[]{0.9, 0.65, 0.35, 0.1}) {
            Vec3 pt = new Vec3(s.getX(), s.getY() + bh * f, s.getZ());
            Vec3 d = pt.subtract(eye);
            double len = d.length();
            if (len < 0.5) return true;
            if (look.dot(d.scale(1.0 / len)) < SEEN_COS) continue;
            BlockHitResult r = p.level.clip(new ClipContext(eye, pt,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (r.getType() == HitResult.Type.MISS || r.getLocation().distanceToSqr(pt) < 0.25) return true;
        }
        return false;
    }

    private static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0F, 1F);
        return t * t * (3F - 2F * t);
    }

    /** static_mid: seamless loop, its loudness follows the MID envelope. */
    private static class MidLoop extends AbstractTickableSoundInstance {
        MidLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() {
            this.volume = MID.v * MASTER_VOLUME;
            if (MID.v < 0.002F) {
                this.stop();
            }
        }
    }

    /** staticheavy.ogg: seamless loop, its loudness is heavyVol. */
    private static class HeavyLoop extends AbstractTickableSoundInstance {
        HeavyLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() {
            this.volume = heavyVol * HEAVY_VOLUME * MASTER_VOLUME;
            if (heavyVol < 0.003F) this.stop();
        }
    }

    /** staticglitch / 2 / 3: seamless loops, each with its own gain (see manageGlitchLayers). */
    private static class GlitchLoop extends AbstractTickableSoundInstance {
        private final int layer;

        GlitchLoop(SoundEvent ev, int layer) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.layer = layer;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() {
            this.volume = GLITCH_GAIN[layer] * MASTER_VOLUME;
            if (GLITCH_GAIN[layer] < 0.002F && lastMode != layer) this.stop();
        }
    }

    /** The heroine's breathing follows the smooth envelopes and fades out slowly together with them. */
    private static class BreathingLoop extends AbstractTickableSoundInstance {
        BreathingLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() {
            float level = Math.max(intensity, heavyVol);
            float targetVol = level * level * 1.1F * MASTER_VOLUME;
            this.volume += (targetVol - this.volume) * 0.08F;   // slow and smooth, no sudden cut
            if (level < 0.01F && this.volume < 0.004F) {
                this.stop();
            }
        }
    }

    private static void manageSounds(Minecraft mc) {
        SoundManager sm = mc.getSoundManager();
        if (MID.v > 0.01F && (mid == null || mid.isStopped())) {
            mid = new MidLoop(SlenderCamMod.STATIC_MID.get());
            sm.play(mid);
        }
        if (Math.max(intensity, heavyVol) > 0.05F && (breathingLoop == null || breathingLoop.isStopped())) {
            breathingLoop = new BreathingLoop(SlenderCamMod.BREATHING.get());
            sm.play(breathingLoop);
        }
        if (heavyVol > 0.01F && (heavyLoop == null || heavyLoop.isStopped())) {
            heavyLoop = new HeavyLoop(SlenderCamMod.STATIC_HEAVY.get());
            sm.play(heavyLoop);
        }
    }

    /**
     * Short glitch sounds (shortglitch*.ogg): one random file every 3-5 seconds while the aggression
     * is at low / mid level. They keep playing (quieter and quieter) while the glitch fades out.
     */
    private static void manageGlitchSounds(Minecraft mc, boolean cam) {
        boolean active = cam && intensity > 0.02F && intensity < GLITCH_SOUND_MAX;
        if (!active) {
            if (glitchCooldown < 20) glitchCooldown = 20;
            return;
        }
        glitchCooldown--;
        if (glitchCooldown > 0) return;

        glitchCooldown = GLITCH_PAUSE_MIN + HUD_RNG.nextInt(GLITCH_PAUSE_RANDOM);
        float vol = MASTER_VOLUME * Mth.clamp(0.35F + intensity, 0F, 1F) * smooth(0.02F, 0.12F, intensity);
        float pitch = 0.92F + HUD_RNG.nextFloat() * 0.16F;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.GLITCH_SHORT.get(), pitch, vol));
    }

    private static SoundEvent glitchEvent(int layer) {
        return switch (layer) {
            case 1 -> SlenderCamMod.STATIC_GLITCH_1.get();
            case 2 -> SlenderCamMod.STATIC_GLITCH_2.get();
            default -> SlenderCamMod.STATIC_GLITCH_3.get();
        };
    }

    /**
     * The three staticglitch loops on top of the big static. Only ONE of them is the "main" layer at a
     * time; when another one takes over, the old one is cut off in a quarter of a second.
     *  layer 1 - weakest: starts early and grows together with the big static;
     *  layer 2 - takes over 4-6 s after the big static started, full strength at once; it is also the
     *            background when he is not close any more (sometimes layer 1 replaces it for a moment);
     *  layer 3 - strongest: only while he is close and the static is very strong.
     * When he is not close any more, layer 3 is cut at once, layers 1/2 fade out about 2 s before the big static.
     */
    private static void manageGlitchLayers(Minecraft mc, boolean near) {
        SoundManager sm = mc.getSoundManager();
        float h = heavyVol;

        if (h > 0.03F) {
            if (heavyTicks == 0) g2StartTicks = 80 + HUD_RNG.nextInt(41);   // 4-6 s
            heavyTicks++;
        } else {
            heavyTicks = 0;
        }

        // layers 1 and 2 reach silence about 2 s (h = 0.068) before the big static is gone
        float early = smooth(0.068F, 0.20F, h);

        int want = 0;
        if (near && h > 0.70F) {
            want = 3;
        } else if (early > 0F) {
            if (heavyTicks >= g2StartTicks || lastMode >= 2) want = 2;
            else if (h > 0.10F) want = 1;

            // he is not close any more: now and then the weak layer replaces layer 2 for a moment
            if (!near && want == 2) {
                if (altTicks > 0) { altTicks--; want = 1; }
                else if (h > 0.15F && HUD_RNG.nextFloat() < 0.012F) altTicks = 40 + HUD_RNG.nextInt(40);
            } else {
                altTicks = 0;
            }
        }
        lastMode = want;

        float[] level = new float[4];
        level[1] = 0.80F * (0.30F + 0.70F * h) * early;
        level[2] = 0.85F * (0.50F + 0.50F * h) * early;
        level[3] = 1.00F * (0.60F + 0.40F * h);

        for (int i = 1; i <= 3; i++) {
            float goal = want == i ? level[i] : 0F;
            float g = GLITCH_GAIN[i];
            if (goal > g) {
                float up = i == 1 ? 0.04F : 0.35F;       // layer 1 grows, layers 2 and 3 play at once
                g = Math.min(goal, g + up);
            } else {
                g = Math.max(goal, g - 0.20F);           // cut off in about a quarter of a second
            }
            GLITCH_GAIN[i] = g;

            if (g > 0.01F && (GLITCH[i] == null || GLITCH[i].isStopped())) {
                GLITCH[i] = new GlitchLoop(glitchEvent(i), i);
                sm.play(GLITCH[i]);
            }
        }
    }

    /**
     * Shaking of the heroine's VIEW (not of the interface): very fast, strong, but small in amplitude.
     * It follows the interface envelope (HUD), so it calms down together with the frame.
     */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!CameraHud.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        float bobX;
        float bobY;

        // ---- living hand-held camera feel ----
        if (mc.player.isSprinting()) {
            bobX = (float) Math.sin(now * 0.008) * 1.8F;
            bobY = (float) Math.abs(Math.cos(now * 0.008)) * 0.9F;
        } else if (mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.001) {
            bobX = (float) Math.sin(now * 0.005) * 0.8F;
            bobY = (float) Math.abs(Math.cos(now * 0.005)) * 0.4F;
        } else {
            bobX = (float) Math.sin(now * 0.002) * 0.25F;
            bobY = (float) Math.cos(now * 0.0015) * 0.15F;
        }

        float level = hudPressure >= 0.01F ? hudPressure : 0F;
        float amp = 0F;
        if (level > 0F) {
            // stronger with the level AND the nearer he is (0.5x far away .. 1.35x next to us)
            amp = (0.12F + 0.78F * level) * Mth.clamp(level / 0.08F, 0F, 1F) * VIEW_SHAKE_DEG
                    * (0.50F + 0.85F * proxVol);
        }
        if (tpActive()) amp += 1.2F;
        amp += flash * 3F;

        e.setRoll(e.getRoll() + bobX + amp * 0.8F * jitter(21));
        e.setYaw(e.getYaw() + (bobX * 0.5F) + amp * jitter(22));
        e.setPitch(e.getPitch() + bobY + amp * jitter(23));
    }

    /** FOV wobble: the same strength curve, but its own (longer) envelope: it calms down 2.5 s after the frame. */
    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        if (!CameraHud.active() || !e.usedConfiguredFov()) return;
        double fov = e.getFOV();
        float z = zoom((float) e.getPartialTick());
        if (z > 0.001F) {
            fov = fov / (1.0 + (ZOOM_MAX - 1.0) * z);
        }
        float level = fovPressure >= 0.01F ? fovPressure : 0F;
        if (level > 0F) {
            fov += VIEW_FOV_WOBBLE * level * Mth.clamp(level / 0.08F, 0F, 1F) * jitter(24);
        }
        e.setFOV(fov);
    }
}