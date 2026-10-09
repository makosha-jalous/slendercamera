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

import java.util.UUID;

@Mod.EventBusSubscriber(modid = SlenderCamMod.ID, value = Dist.CLIENT)
public class CameraEffects {

    private static final ResourceLocation SLENDER = new ResourceLocation("slenderman", "slenderman");

    private static final float ZOOM_MAX = 3.5F;
    private static final float ZOOM_SPEED = 0.14F;
    private static final double SEEN_COS = 0.55;
    private static final double RANGE = 48.0;
    private static final float RISE = 0.08F;
    private static final float MASTER_VOLUME = 0.9F;

    // ---- tweakables (all the "feel" numbers live here) ----

    /** Sounds (static far/mid, big static, breathing) die out this many ticks after he is out of sight: 130 = 6.5 s. */
    private static final int SOUND_FADE_TICKS = 130;
    /** Interface distortion and view shake last this long after he is out of sight: 190 = 9.5 s (3 s after the sounds). */
    private static final int VISUAL_FADE_TICKS = 190;
    /** The big static needs at least this many ticks to rise from silence to full: 60 = 3 s (never starts loud). */
    private static final int HEAVY_RISE_TICKS = 60;
    /** static_mid is gone this many ticks after he is out of sight (earlier than static_far): 80 = 4 s. */
    private static final int MID_FADE_TICKS = 80;
    /** Big static, he is 2-4 blocks away: it starts to rise after this many ticks of looking at him (40 = 2 s). */
    private static final int HEAVY_DELAY_NEAR_TICKS = 40;
    /** Big static, he is at the maximum distance: the delay is this long (1800 = 90 s); the farther, the longer. */
    private static final int HEAVY_DELAY_FAR_TICKS = 1800;
    /** When the creeping "worse and worse" reaches this, the big static starts at ANY distance. */
    private static final float HEAVY_CRITICAL_EXPOSURE = 0.80F;
    /** Loudness of the big static at its maximum (0..1, multiplied by MASTER_VOLUME). */
    private static final float HEAVY_VOLUME = 1.0F;

    /** Seconds of continuous looking at Slenderman until the "creeping worse" part is at its maximum. */
    private static final float EXPOSURE_SECONDS_UP = 120F;
    /** Seconds it takes the accumulated "worse and worse" to go away when he is not watched. */
    private static final float EXPOSURE_SECONDS_DOWN = 240F;
    /** How much of the sound/grain intensity the creeping exposure can add (even at far distance). */
    private static final float EXPOSURE_INTENSITY = 0.85F;
    /** How much of the HUD distortion the creeping exposure can add. */
    private static final float EXPOSURE_HUD = 0.30F;

    /** Short glitch sounds only play while intensity is below this (far / mid distance). */
    private static final float GLITCH_SOUND_MAX = 0.65F;
    private static final int GLITCH_PAUSE_MIN = 60;      // 3..5 seconds between short glitch sounds
    private static final int GLITCH_PAUSE_RANDOM = 41;

    /** Peak shake of the player's VIEW in degrees at maximum intensity (small, but very fast). */
    private static final float VIEW_SHAKE_DEG = 0.9F;
    /** Peak wobble of the field of view (FOV) in degrees at maximum intensity. */
    private static final float VIEW_FOV_WOBBLE = 1.0F;

    /** Loudness of the tiny static ticks that play in rhythm with every twitch of the interface. */
    private static final float TWITCH_VOLUME = 0.6F;

    /** Length of the distortion when he teleports: a short flash, a fraction of a second. */
    private static final int TELEPORT_GLITCH_MS = 220;
    /** Chance of the teleport distortion when the teleport happens out of the heroine's sight. */
    private static final float TELEPORT_UNSEEN_CHANCE = 0.40F;
    /** If the heroine has stood still this many ticks, a teleport in front of her always distorts. */
    private static final int STILL_TICKS = 100;

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

    private static final Env INTENSITY = new Env();   // static far/mid, breathing, grain
    private static final Env HUD = new Env();         // interface distortion, view shake
    private static final Env HEAVY = new Env();       // big static
    private static final Env FAR = new Env();         // static_far: plays as soon as he is in view
    private static final Env MID = new Env();         // static_mid: middle distances
    private static final Env PROX = new Env();        // how near he is (for the strength of the view shake)

    // mirrors of the envelopes above (used by the HUD and the sounds)
    private static float intensity;
    private static float hudPressure;
    private static float heavyVol;
    private static float proxVol;
    private static int heavyWait;                     // ticks of looking at him since the big static was last silent
    private static float closeness;                   // 0..1: how near he is (decides how fast heavy rises)

    private static final RandomSource HUD_RNG = RandomSource.create();
    private static float exposure;                    // 0..1, slowly creeps up while he is watched
    private static int glitchCooldown = 60;

    // Output values read by CameraHud (frame deformation, in reference pixels)
    private static float frameVertical;
    private static float frameHorizontal;
    private static float frameVerticalBend;
    private static float frameHorizontalBend;

    // Slow pose (changes every 0.3 - 2 s, smoothed), values -1..1
    private static float baseV, baseVB, baseH, baseHB;
    private static float smV, smVB, smH, smHB;
    private static int baseVHold, baseHHold;

    // Fast twitches (a few milliseconds each, many per second), values -1..1
    private static float twV, twVB, twH, twHB;
    private static long twitchStartMs, twitchEndMs, nextTwitchMs, lastFrameMs;

    private static int lookTicks;
    private static int observedAgo = 100;             // ticks since he was last in view
    private static int stillTicks;                    // how long the heroine has stood still
    private static Vec3 lastPlayerPos;
    private static long teleportGlitchEndMs;
    private static float teleportSignX = 1F, teleportSignY = 1F;
    private static UUID lastId;
    private static final Vec3[] HIST = new Vec3[4];
    private static int histIdx, jumpCooldown;

    private static StaticLoop far, mid;
    private static HeavyLoop heavyLoop;
    private static BreathingLoop breathingLoop;

    // Looping glitch layers on top of the big static: 1 weakest, 2 medium, 3 strongest
    private static final GlitchLoop[] GLITCH = new GlitchLoop[4];
    private static final float[] GLITCH_GAIN = new float[4];
    private static int heavyTicks;                    // how long the big static has been audible
    private static int g2StartTicks = 100;            // layer 2 takes over 4-6 s after the big static started
    private static int lastMode, altTicks;

    public static float intensity() { return intensity; }
    public static float flash() { return flash; }
    public static float zoom(float pt) { return Mth.lerp(pt, zoomPrev, zoomNow); }
    public static float hudPressure() { return hudPressure; }
    public static float frameVertical() { return frameVertical; }
    public static float frameHorizontal() { return frameHorizontal; }
    public static float frameVerticalBend() { return frameVerticalBend; }
    public static float frameHorizontalBend() { return frameHorizontalBend; }
    public static float gazePressure() { return Mth.clamp(lookTicks / 80.0F, 0F, 1F); }

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

    private static boolean isSlender(Entity e) {
        return SLENDER.equals(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()));
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
            HEAVY.kill(0F);
            FAR.kill(0F);
            MID.kill(0F);
            PROX.kill(0F);
            syncMirrors();
            lookTicks = 0;
            return;
        }

        // how long has the heroine stood still?
        Vec3 pp = mc.player.position();
        if (lastPlayerPos != null && pp.distanceToSqr(lastPlayerPos) < 0.0004) stillTicks = Math.min(1000, stillTicks + 1);
        else stillTicks = 0;
        lastPlayerPos = pp;

        Entity sl = null;
        double best = Double.MAX_VALUE;
        for (Entity en : mc.level.entitiesForRendering()) {
            if (isSlender(en)) {
                double d = en.distanceToSqr(mc.player);
                if (d < best) { best = d; sl = en; }
            }
        }

        boolean observed = false;
        double dist = 0;
        if (sl != null) {
            dist = Math.sqrt(best);
            observed = cam && dist < RANGE + 32 && isLookingAt(mc.player, sl);

            if (observed) lookTicks = Math.min(120, lookTicks + 1);
            else lookTicks = Math.max(0, lookTicks - 4);

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
                if (cam) onTeleport(mc, sl, observed, dist);
            }
        } else {
            lastId = null;
            lookTicks = Math.max(0, lookTicks - 4);
        }
        if (observed) observedAgo = 0;
        else observedAgo = Math.min(100, observedAgo + 1);

        // "Worse and worse": while he is watched (at ANY distance) this creeps up slowly and surely.
        if (cam && observed) exposure = Math.min(1F, exposure + 1F / (20F * EXPOSURE_SECONDS_UP));
        else exposure = Math.max(0F, exposure - 1F / (20F * EXPOSURE_SECONDS_DOWN));

        float target = 0F;
        if (cam && observed && dist < RANGE) {
            float prox = 1F - (float) Mth.clamp((dist - 3.0) / (RANGE - 3.0), 0.0, 1.0);
            float base = Math.max(0.10F, (float) Math.pow(prox, 1.6));
            target = Mth.clamp(base + EXPOSURE_INTENSITY * exposure * (1F - base), 0F, 1F);
        }
        INTENSITY.update(target, RISE, 1F, SOUND_FADE_TICKS);

        // static_far plays at once when he is in view, even far away; it fades in and out smoothly.
        FAR.update(cam && observed && dist < RANGE + 32 ? 1F : 0F, 0.12F, 1F, SOUND_FADE_TICKS);
        // static_mid flows in from static_far at middle distances and is gone before static_far is.
        float midGoal = 0F;
        if (cam && observed && dist < RANGE) {
            midGoal = smooth(0.20F, 0.50F, target) * (1F - smooth(0.65F, 0.90F, target));
        }
        MID.update(midGoal, 0.08F, 1F, MID_FADE_TICKS);
        // how near he is: the view shake grows with it
        float proxGoal = cam && observed ? 1F - (float) Mth.clamp((dist - 2.5) / 60.0, 0.0, 1.0) : 0F;
        PROX.update(proxGoal, 0.10F, 1F, VISUAL_FADE_TICKS);

        // Big static. It only starts after a delay that grows with the distance: 2 s at 2-4 blocks,
        // up to HEAVY_DELAY_FAR_TICKS at the maximum distance. Far away it starts when the creeping
        // interference gets critical (exposure). Once it has started it stays on while he is watched.
        // Then it rises for at least HEAVY_RISE_TICKS (3 s) and dies out in SOUND_FADE_TICKS after.
        float heavyGoal = 0F;
        if (cam && observed && dist < RANGE) {
            closeness = 1F - (float) Mth.clamp((dist - 4.0) / 40.0, 0.0, 1.0);
            heavyWait++;
            float t = (float) Mth.clamp((dist - 4.0) / (RANGE - 4.0), 0.0, 1.0);
            float delay = Mth.lerp(t, HEAVY_DELAY_NEAR_TICKS, HEAVY_DELAY_FAR_TICKS);
            boolean gateOpen = heavyWait >= delay || exposure >= HEAVY_CRITICAL_EXPOSURE || HEAVY.v > 0.01F;
            if (gateOpen) heavyGoal = smooth(0.40F, 0.95F, target);
        } else {
            heavyWait = Math.max(0, heavyWait - 3);
        }
        HEAVY.update(heavyGoal, 0.008F + 0.35F * closeness * closeness, 1F / HEAVY_RISE_TICKS, SOUND_FADE_TICKS);

        float targetHud = 0F;
        if (cam && observed && dist < RANGE + 32) {
            float proxHud = 1F - (float) Mth.clamp((dist - 2.5) / 55.0, 0.0, 1.0);
            float gaze = gazePressure();
            targetHud = Mth.clamp(0.16F + proxHud * 0.58F + gaze * 0.42F + exposure * EXPOSURE_HUD, 0F, 1F);
        }
        HUD.update(targetHud, 0.22F, 1F, VISUAL_FADE_TICKS);

        if (!cam) {            // camera lowered: everything stops quickly
            INTENSITY.kill(0.8F);
            HUD.kill(0.72F);
            HEAVY.kill(0.85F);
            FAR.kill(0.8F);
            MID.kill(0.8F);
            PROX.kill(0.72F);
        }
        if (INTENSITY.v < 0.002F) INTENSITY.kill(0F);
        syncMirrors();

        updateSlowPose(cam);
        manageSounds(mc);
        manageGlitchSounds(mc, cam);
        manageGlitchLayers(mc, observed);
    }

    private static void syncMirrors() {
        intensity = INTENSITY.v;
        hudPressure = HUD.v;
        heavyVol = HEAVY.v;
        proxVol = PROX.v;
    }

    /** He just jumped to a new place. Decide whether the camera shows the short distortion. */
    private static void onTeleport(Minecraft mc, Entity sl, boolean observed, double dist) {
        boolean inView = observed || observedAgo <= 8 || isLookingAt(mc.player, sl);

        // in front of a heroine who has been standing still for a long time
        boolean frontOfStill = false;
        if (stillTicks >= STILL_TICKS && dist < 24) {
            Vec3 look = mc.player.getViewVector(1.0F).normalize();
            Vec3 to = sl.position().subtract(mc.player.position()).normalize();
            frontOfStill = look.dot(to) > 0.5;
        }

        boolean distort = inView || frontOfStill || HUD_RNG.nextFloat() < TELEPORT_UNSEEN_CHANCE;
        if (distort) {
            teleportSignX = HUD_RNG.nextBoolean() ? 1F : -1F;
            teleportSignY = HUD_RNG.nextBoolean() ? 1F : -1F;
            teleportGlitchEndMs = System.currentTimeMillis() + TELEPORT_GLITCH_MS + HUD_RNG.nextInt(60);
            baseVHold = 0;
            baseHHold = 0;
        }
        if (distort || dist < 18) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.TELEPORT.get(), 1.0F, 1.0F));
        }
    }

    /** Slow part of the frame pose (changes every 0.3 - 2 seconds, then gets smoothed every frame). */
    private static void updateSlowPose(boolean cam) {
        if (!cam || (hudPressure < 0.01F && !tpActive())) {
            baseV = baseVB = baseH = baseHB = 0F;
            baseVHold = 0;
            baseHHold = 0;
            return;
        }
        float p = Mth.clamp(hudPressure, 0F, 1F);

        if (baseVHold > 0) baseVHold--;
        if (baseHHold > 0) baseHHold--;

        int minHold = Math.max(6, Math.round(40F - 28F * p));
        int maxHold = Math.max(minHold + 1, Math.round(110F - 70F * p));

        boolean vExpired = baseVHold <= 0;
        boolean hExpired = baseHHold <= 0;
        boolean together = (vExpired || hExpired) && HUD_RNG.nextFloat() < (0.25F + 0.35F * p);

        if (vExpired || together) {
            baseV = signedKick(1F);
            baseVB = signedKick(1F);
            baseVHold = minHold + HUD_RNG.nextInt(Math.max(1, maxHold - minHold + 1));
        }
        if (hExpired || together) {
            baseH = signedKick(1F);
            baseHB = signedKick(1F);
            baseHHold = minHold + HUD_RNG.nextInt(Math.max(1, maxHold - minHold + 1));
        }
    }

    /**
     * Called by CameraHud once per rendered frame (not once per tick), so the twitches can be
     * only a few milliseconds long. Produces frameVertical / frameHorizontal / ...Bend.
     * Every new twitch also plays a tiny slice of static (twitch_tick), so the sound beats with the frame.
     */
    public static void frameUpdate() {
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0L ? 0.016F : Mth.clamp((now - lastFrameMs) / 1000F, 0F, 0.1F);
        lastFrameMs = now;

        if (tpActive()) {
            // Teleport: both groups are forced into one violent pose for the short flash.
            frameVertical = 34F * teleportSignX;
            frameHorizontal = 28F * teleportSignY;
            frameVerticalBend = 19F * teleportSignX;
            frameHorizontalBend = 17F * teleportSignY;
            return;
        }

        if (hudPressure < 0.01F) {
            smV = smVB = smH = smHB = 0F;
            frameVertical = frameHorizontal = frameVerticalBend = frameHorizontalBend = 0F;
            nextTwitchMs = 0L;
            twitchEndMs = 0L;
            return;
        }

        float p = Mth.clamp(hudPressure, 0F, 1F);
        // Fade the distortion in smoothly so nothing pops at very low pressure.
        float fade = Mth.clamp(p / 0.12F, 0F, 1F);

        // 1) slow pose, smoothed so it never "tears"
        float k = 1F - (float) Math.exp(-dt / 0.07F);
        smV += (baseV - smV) * k;
        smVB += (baseVB - smVB) * k;
        smH += (baseH - smH) * k;
        smHB += (baseHB - smHB) * k;

        // 2) fast twitches: 25..80 ms each, pause between them shrinks from ~0.3 s to ~0.07 s with pressure
        if (nextTwitchMs == 0L) nextTwitchMs = now + 80L;
        if (now >= nextTwitchMs) {
            long dur = 25L + HUD_RNG.nextInt(56);
            twitchStartMs = now;
            twitchEndMs = now + dur;
            twV = signedKick(1F);
            twVB = HUD_RNG.nextFloat() < 0.75F ? signedKick(1F) : 0F;
            twH = HUD_RNG.nextFloat() < 0.75F ? signedKick(1F) : 0F;
            twHB = HUD_RNG.nextFloat() < 0.75F ? signedKick(1F) : 0F;
            int gapMin = Math.round(Mth.lerp(p, 90F, 25F));
            int gapMax = Math.round(Mth.lerp(p, 380F, 110F));
            nextTwitchMs = twitchEndMs + gapMin + HUD_RNG.nextInt(Math.max(1, gapMax - gapMin));
            playTwitchTick(p, fade);
        }
        float env = 0F;
        if (now < twitchEndMs) {
            float t = (now - twitchStartMs) / (float) Math.max(1L, twitchEndMs - twitchStartMs);
            env = (float) Math.sin(Math.PI * t); // smooth in and out, so it is a twitch, not a tear
        }

        float slowSpread = (1.5F + 12F * p + 16F * p * p) * fade * 0.7F;
        float slowBend = (0.8F + 5F * p + 10F * p * p) * fade * 0.7F;
        float fastSpread = (1.2F + 8F * p + 10F * p * p) * fade;
        float fastBend = (0.8F + 5F * p + 8F * p * p) * fade;

        frameVertical = smV * slowSpread + env * twV * fastSpread;
        frameVerticalBend = smVB * slowBend + env * twVB * fastBend;
        frameHorizontal = (smH * slowSpread + env * twH * fastSpread) * 0.82F;
        frameHorizontalBend = (smHB * slowBend + env * twHB * fastBend) * 0.90F;
    }

    /** One tiny slice of the far static (a few tens of milliseconds) exactly when the interface twitches. */
    private static void playTwitchTick(float p, float fade) {
        float vol = MASTER_VOLUME * TWITCH_VOLUME * (0.30F + 0.70F * p) * fade;
        if (vol < 0.01F) return;
        float pitch = 0.90F + HUD_RNG.nextFloat() * 0.20F;
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SlenderCamMod.TWITCH_TICK.get(), pitch, Mth.clamp(vol, 0F, 1F)));
    }

    private static float signedKick(float magnitude) {
        float x = HUD_RNG.nextFloat() * 2F - 1F;
        float shaped = Math.signum(x) * (0.35F + 0.65F * Math.abs(x));
        return shaped * magnitude;
    }

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

    /** Loudness of static_far (layer 0) and static_mid (layer 1). */
    static float layerVolume(int layer) {
        if (layer == 0) {
            // static_far gives way a little to static_mid and comes back when static_mid fades out
            return FAR.v * 0.7F * (1F - 0.65F * smooth(0.35F, 0.70F, intensity)) * MASTER_VOLUME;
        }
        return MID.v * MASTER_VOLUME;
    }

    private static class StaticLoop extends AbstractTickableSoundInstance {
        private final int layer;

        StaticLoop(SoundEvent ev, int layer) {
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
            this.volume = layerVolume(layer);
            float env = layer == 0 ? FAR.v : MID.v;
            if (env < 0.002F && this.volume < 0.002F) {
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

    /** Breathing works as a team with the static: it follows the same envelopes and fades with them. */
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
            this.volume += (targetVol - this.volume) * 0.15F;
            if (level < 0.01F && this.volume < 0.004F) {
                this.stop();
            }
        }
    }

    private static StaticLoop ensure(StaticLoop cur, SoundEvent ev, int layer, SoundManager sm) {
        if (cur == null || cur.isStopped()) {
            cur = new StaticLoop(ev, layer);
            sm.play(cur);
        }
        return cur;
    }

    private static void manageSounds(Minecraft mc) {
        SoundManager sm = mc.getSoundManager();
        if (FAR.v > 0.01F) far = ensure(far, SlenderCamMod.STATIC_FAR.get(), 0, sm);
        if (MID.v > 0.01F) mid = ensure(mid, SlenderCamMod.STATIC_MID.get(), 1, sm);
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
     * Short glitch sounds (shortglitch*.ogg): one random file every 3-5 seconds while the distortion
     * is at far/mid level. They keep playing (quieter and quieter) while the glitch fades out.
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
     *            background when we turn away (sometimes layer 1 replaces it for a moment);
     *  layer 3 - strongest: only while he is in view and the static is very strong.
     * When we turn away, layer 3 is cut at once, layers 1/2 fade out about 2 s before the big static.
     */
    private static void manageGlitchLayers(Minecraft mc, boolean observed) {
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
        if (observed && h > 0.70F) {
            want = 3;
        } else if (early > 0F) {
            if (heavyTicks >= g2StartTicks || lastMode >= 2) want = 2;
            else if (h > 0.10F) want = 1;

            // turned away: now and then the weak layer replaces layer 2 for a moment
            if (!observed && want == 2) {
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
     * It follows the same envelopes as the sounds and lasts 3 seconds longer than them.
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

        float level = shakeLevel();
        float amp = 0F;
        if (level > 0.002F) {
            // stronger with the interference level AND the nearer he is (0.5x far away .. 1.35x next to us)
            amp = (0.12F + 0.78F * level) * Mth.clamp(level / 0.08F, 0F, 1F) * VIEW_SHAKE_DEG
                    * (0.50F + 0.85F * proxVol);
        }
        if (tpActive()) amp += 1.2F;
        amp += flash * 3F;

        e.setRoll(e.getRoll() + bobX + amp * 0.8F * jitter(21));
        e.setYaw(e.getYaw() + (bobX * 0.5F) + amp * jitter(22));
        e.setPitch(e.getPitch() + bobY + amp * jitter(23));
    }

    private static float shakeLevel() {
        return Math.max(Math.max(intensity, heavyVol * 0.9F), hudPressure * 0.85F);
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        if (!CameraHud.active() || !e.usedConfiguredFov()) return;
        double fov = e.getFOV();
        float z = zoom((float) e.getPartialTick());
        if (z > 0.001F) {
            fov = fov / (1.0 + (ZOOM_MAX - 1.0) * z);
        }
        float level = shakeLevel();
        if (level > 0.002F) {
            fov += VIEW_FOV_WOBBLE * level * Mth.clamp(level / 0.08F, 0F, 1F) * jitter(24);
        }
        e.setFOV(fov);
    }
}
