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

@Mod.EventBusSubscriber(modid = SlenderCamMod.ID, value = Dist.CLIENT)
public class CameraEffects {

    private static final ResourceLocation SLENDER = new ResourceLocation("slenderman", "slenderman");

    private static final java.util.Map<String, Method> METHODS = new java.util.HashMap<>();
    private static final java.util.Set<String> MISSING = new java.util.HashSet<>();

    private static boolean isSlender(Entity e) {
        return SLENDER.equals(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()));
    }

    /** calls a public no-argument method of the Slenderman entity by name; null if there is no such method. */
    private static Number callNumber(Entity e, String name) {
        if (MISSING.contains(name)) return null;
        try {
            Method m = METHODS.get(name);
            if (m == null || !m.getDeclaringClass().isInstance(e)) {
                m = e.getClass().getMethod(name);
                METHODS.put(name, m);
            }
            return (Number) m.invoke(e);
        } catch (NoSuchMethodException ex) {
            MISSING.add(name);
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Sanity LOSS of the player from the Slenderman mod: 0..100 -> 0..1. (An old Slenderman mod without sanity: sanity.) */
    private static float readSanity(Entity e) {
        Number n = callNumber(e, "getSanity");
        if (n != null) return Mth.clamp(n.floatValue() / 100.0F, 0F, 1F);
        Number a = callNumber(e, "getAggression");
        if (a == null) return 0F;
        float raw = a.floatValue();
        if (raw > 1.5F) raw /= 100.0F;
        return Mth.clamp(raw, 0F, 1F);
    }

    private static final float ZOOM_MAX = 3.5F;
    private static final float ZOOM_SPEED = 0.14F;
    private static final float RISE = 0.08F;
    private static final float MASTER_VOLUME = 0.9F;

    private static final double SEEN_RANGE = 80.0D;
    private static final double SEEN_COS = 0.55D;
    private static final float SEEN_SHAKE = 0.25F;

    private static final double NEAR_DIST = 12.0D;
    private static final float FRENZY_SANITY = 0.65F;
    private static final float FRENZY_SHAKE = 0.85F;

    /** static_heavy starts to rise at this sanity loss (60%) and is at full strength at 100%. */
    private static final float HEAVY_START_SANITY = 0.60F;
    /** ... when he is close (under NEAR_DIST) it starts earlier. */
    private static final float HEAVY_START_NEAR = 0.35F;
    /** without a Slenderman on the client the sanity still falls at the server speed (100 in 14 s). */
    private static final float SANITY_FALL_NO_ENTITY = 1.0F / 280.0F;
    /** and gets to full strength at this one. */
    private static final float HEAVY_FULL_SANITY = 1.0F;
    private static final int HEAVY_DELAY_TICKS = 40;
    private static final float HEAVY_NEAR_RELEASE = 0.40F;
    private static final int HEAVY_RISE_TICKS = 60;
    private static final float HEAVY_VOLUME = 1.0F;

    private static final int SOUND_FADE_TICKS = 130;
    private static final int MID_FADE_TICKS = 80;
    private static final int BREATH_FADE_TICKS = 150;
    private static final int HUD_FADE_TICKS = 150;
    private static final int FOV_LAG_TICKS = 50;

    private static final float GLITCH_SOUND_MAX = 0.65F;
    private static final int GLITCH_PAUSE_MIN = 60;
    private static final int GLITCH_PAUSE_RANDOM = 41;

    private static final float VIEW_SHAKE_DEG = 1.3F;
    private static final float VIEW_FOV_WOBBLE = 1.95F;

    // ---- stutter (static_light), bend of the interface, medium static, breathing ----
    // SOUND: staticlight.ogg is cut into pieces of 200..250 ms (staticlight01..36). A piece plays, then a pause of
    //        200..300 ms, then the next piece. It plays ALWAYS while Slenderman is in the field of view and goes on until
    //        the sanity is back at zero.
    /** length of one piece of static_light (ms, the files are 200..250) and the pause after it (ms). */
    private static final long PIECE_MS = 225L;
    private static final int PIECE_PAUSE_MIN = 200;
    private static final int PIECE_PAUSE_RANDOM = 101;
    private static final float LIGHT_VOLUME = 0.8F;
    /** the stutter goes on while the sanity is not back at (almost) zero. */
    private static final float TWITCH_END_SANITY = 0.004F;

    // BEND: far away the frame twitches often and fast (about 200-270 ms of bend in every second) but weakly;
    //       the closer he is, the deeper the bend, but the twitches get slower (longer, with longer pauses).
    private static final float BEND_DUR_FAR = 55F, BEND_DUR_NEAR = 170F;     // ms
    private static final float BEND_GAP_FAR = 160F, BEND_GAP_NEAR = 420F;    // ms between two bends
    /** closeness: 0 at CLOSE_FAR blocks (or more), 1 at CLOSE_NEAR blocks (or less). */
    private static final double CLOSE_FAR = 40.0D, CLOSE_NEAR = 3.0D;
    /** depth of the bend: x BEND_DEPTH_FAR when far, x BEND_DEPTH_NEAR when very close. */
    private static final float BEND_DEPTH_FAR = 0.45F, BEND_DEPTH_NEAR = 1.35F;
    /** the frame freezes in one pose for about 1 s: only when closer than this (blocks) and at this sanity loss at least ... */
    private static final double HOLD_DIST = 8.0D;
    private static final float HOLD_MIN_SANITY = 0.30F;
    /** ... with this chance per bend, lasts 0.9..1.2 s, and then rests 2.5..6 s before the next freeze. */
    private static final float HOLD_CHANCE = 0.08F;
    private static final int HOLD_MS_MIN = 900;
    private static final int HOLD_MS_RANDOM = 301;
    private static final int HOLD_PAUSE_MIN = 2500;
    private static final int HOLD_PAUSE_RANDOM = 3500;

    // MEDIUM STATIC (staticmedium.ogg): starts quiet at about 40% sanity loss and grows, stuttering in pieces
    // (staticmedium_p01..37, 200..250 ms, same pauses); near 50% it turns into one continuous track.
    private static final float MEDIUM_START = 0.36F, MEDIUM_FULL = 0.50F;
    private static final float MEDIUM_CONT_FROM = 0.46F, MEDIUM_CONT_TO = 0.52F;
    private static final float MEDIUM_VOLUME = 0.85F;

    // BREATHING: starts after 3 s of seeing him, rises in about 5 s (faster when the sanity fills faster),
    // stays while the sanity is high, and fades in about 5 s once the sanity falls below BREATH_OFF_SANITY
    // (so it is already quiet about 4 s before the interface is completely calm).
    private static final int BREATH_SEEN_TICKS = 60;
    private static final float BREATH_RISE_SLOW = 1F / 100F, BREATH_RISE_FAST = 1F / 70F, BREATH_FALL = 1F / 100F;
    private static final float BREATH_OFF_SANITY = 0.60F;

    private static final int TELEPORT_GLITCH_MS = 220;
    /** Jumpscare sound on teleport when he is out of view: only 10% of the time. */
    private static final float TELEPORT_UNSEEN_CHANCE = 0.10F;
    private static final double FRONT_RANGE = 30.0D;
    private static final double FRONT_COS = 0.30D;

    private static float zoomPrev, zoomNow, flash;
    private static boolean zoomKeyPrev = false;
    private static final float ZOOM_SOUND_VOLUME = 0.5F;

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

    private static final Env INTENSITY = new Env();
    private static final Env HUD = new Env();
    private static final Env FOV = new Env();
    private static final Env HEAVY = new Env();
    private static final Env MID = new Env();
    private static final Env PROX = new Env();

    private static final float[] SHAKE_DELAY = new float[FOV_LAG_TICKS];
    private static int shakeDelayIdx;

    private static float intensity;
    private static float hudPressure;
    private static float fovPressure;
    private static float heavyVol;
    private static float proxVol;
    private static float sanity;
    private static boolean seenNow;
    /** distance to the nearest Slenderman on the client (blocks), 1e9 when there is none. */
    private static double slenderDist = 1.0E9D;
    private static float rawSanity;
    private static UUID tpUuid;
    private static int tpSeq;
    private static int heavyWait;
    private static boolean heavyLatched;
    private static float seenGate;

    private static final RandomSource HUD_RNG = RandomSource.create();
    private static int glitchCooldown = 60;

    private static float frameVertical;
    private static float frameHorizontal;
    private static float frameVerticalBend;
    private static float frameHorizontalBend;

    private static float twV, twVB, twH, twHB;
    /** When the current bend of the frame ends. */
    private static long twitchEndMs;
    /** the frame is frozen in its pose until this time, and the next freeze is allowed after holdNextMs. */
    private static long holdEndMs, holdNextMs;
    /** stutter stream: when the next pulse is due and when the current 2 s cycle started. */
    private static long pulseNextMs, mediumNextMs, bendNextMs;
    /** breathing envelope 0..1 (linear, 2 s up / 2 s down). */
    private static float breathEnv, prevSanity, midShare;
    private static boolean breathLatch;
    private static int breathSeenTicks;
    private static int breathDeadTicks;

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

    private static final GlitchLoop[] GLITCH = new GlitchLoop[5];
    private static final float[] GLITCH_GAIN = new float[5];
    /** which of the two strongest layers (3 or 4) is the main one, when it flips next, and the tail after the big static. */
    private static int topChoice = 3, topSwitchTicks, topTailTicks;
    private static boolean topWasOn;
    private static int heavyTicks;
    private static int g2StartTicks = 100;
    private static int lastMode, altTicks;

    public static float intensity() { return intensity; }
    public static float sanity() { return sanity; }
    /** kept for other classes of the camera mod that still call aggression(): it is the sanity now. */
    public static float aggression() { return sanity; }
    public static float flash() { return flash; }
    public static float zoom(float pt) { return Mth.lerp(pt, zoomPrev, zoomNow); }
    public static float hudPressure() { return hudPressure; }
    public static float frameVertical() { return frameVertical; }
    public static float frameHorizontal() { return frameHorizontal; }
    public static float frameVerticalBend() { return frameVerticalBend; }
    public static float frameHorizontalBend() { return frameHorizontalBend; }
    public static float gazePressure() { return sanity; }
    public static float seenGate() { return seenGate; }

    private static boolean tpActive() {
        return System.currentTimeMillis() < teleportGlitchEndMs;
    }

    public static float teleportGlitch() { return tpActive() ? 1F : 0F; }
    public static float teleportSignX() { return teleportSignX; }
    public static float teleportSignY() { return teleportSignY; }

    public static float frameWarpX(float x, float y) {
        float t = Mth.clamp((y - 21F) / (383F - 21F), 0F, 1F);
        float wave = (float) Math.sin(Math.PI * t);
        float leftShift = frameVertical + frameVerticalBend * wave;
        float rightShift = -frameVertical - frameVerticalBend * wave;
        float n = Mth.clamp(x / 739F, 0F, 1F);
        return Mth.lerp(n, leftShift, rightShift);
    }

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
            sanity = 0F;
            rawSanity = 0F;
            seenNow = false;
            seenGate = 0F;
            heavyWait = 0;
            heavyLatched = false;
            breathEnv = 0F;
            breathLatch = false;
            breathSeenTicks = 0;
            syncMirrors();
            return;
        }

        Entity sl = null;
        double best = Double.MAX_VALUE;
        for (Entity en : mc.level.entitiesForRendering()) {
            if (isSlender(en)) {
                double d = en.distanceToSqr(mc.player);
                if (d < best) { best = d; sl = en; }
            }
        }

        double dist = 0;
        slenderDist = 1.0E9D;
        boolean seen = false;
        if (sl != null) {
            dist = Math.sqrt(best);
            slenderDist = dist;
            seen = cam && dist < SEEN_RANGE && isLookingAt(mc.player, sl);
            rawSanity = readSanity(sl);
            checkTeleport(mc, sl, dist, cam);
        } else {
            lastId = null;
            // he is not on the client (parked far away): the sanity keeps coming back at the server speed
            rawSanity = Math.max(0F, rawSanity - SANITY_FALL_NO_ENTITY);
        }
        seenNow = seen;

        // seen gate: rises quickly when we look at him, falls slowly when we turn away.
        float seenGoal = seen ? 1F : 0F;
        seenGate += (seenGoal - seenGate) * (seen ? 0.45F : 0.06F);
        if (seenGate < 0.001F) seenGate = 0F;

        // sanity 0..1 follows the value of the Slenderman mod (it already depends on distance and stare time)
        float rawForCam = cam ? rawSanity : 0F;
        sanity += (rawForCam - sanity) * 0.30F;
        if (sanity < 0.001F) sanity = 0F;

        boolean near = cam && sl != null && dist < NEAR_DIST;

        // grain and breathing
        INTENSITY.update(sanity, RISE, 1F, BREATH_FADE_TICKS);

        // static_mid: comes in at ~12..40% sanity and gives way to the heavy static at ~62..85%
        float midGoal = smooth(MEDIUM_START, MEDIUM_FULL, sanity) * (1F - smooth(0.62F, 0.85F, sanity));
        midShare = smooth(MEDIUM_CONT_FROM, MEDIUM_CONT_TO, sanity);
        MID.update(midGoal, 0.08F, 1F, MID_FADE_TICKS);

        float proxGoal = cam && sl != null ? 1F - (float) Mth.clamp((dist - 2.5) / 60.0, 0.0, 1.0) : 0F;
        PROX.update(proxGoal, 0.10F, 1F, HUD_FADE_TICKS + FOV_LAG_TICKS);

        // static_heavy: grows with the sanity loss, and earlier when he is close
        float heavyGoal = smooth(HEAVY_START_SANITY, HEAVY_FULL_SANITY, sanity);
        HEAVY.update(heavyGoal, 0.35F, 1F / HEAVY_RISE_TICKS, SOUND_FADE_TICKS);

        // shake of the picture / bending of the frame: as strong as the sanity loss (a tiny bit when just seen)
        float shakeTarget = 0F;
        if (cam) {
            boolean frenzy = near && sanity > FRENZY_SANITY;     // very close and high loss: strong shake at once
            shakeTarget = frenzy ? Math.max(sanity, FRENZY_SHAKE) : sanity;
            if (seen) shakeTarget = Math.max(shakeTarget, SEEN_SHAKE);
        }
        HUD.update(shakeTarget, 0.22F, 1F, HUD_FADE_TICKS);

        float delayed = SHAKE_DELAY[shakeDelayIdx];
        SHAKE_DELAY[shakeDelayIdx] = shakeTarget;
        shakeDelayIdx = (shakeDelayIdx + 1) % SHAKE_DELAY.length;
        FOV.update(Math.max(shakeTarget, delayed), 0.22F, 1F, HUD_FADE_TICKS);

        if (!cam) {
            INTENSITY.kill(0.8F);
            HUD.kill(0.72F);
            FOV.kill(0.72F);
            HEAVY.kill(0.85F);
            MID.kill(0.8F);
            PROX.kill(0.72F);
            Arrays.fill(SHAKE_DELAY, 0F);
            seenGate *= 0.7F;
            heavyWait = 0;
            heavyLatched = false;
        }
        if (INTENSITY.v < 0.002F) INTENSITY.kill(0F);
        syncMirrors();

        // breathing: starts after 3 s of seeing him, rises in ~5 s (a bit faster when the sanity fills faster),
        // fades in ~5 s once the sanity has fallen below BREATH_OFF_SANITY (or the camera is lowered)
        breathSeenTicks = seen ? Math.min(breathSeenTicks + 1, 200) : Math.max(breathSeenTicks - 1, 0);
        if (breathSeenTicks >= BREATH_SEEN_TICKS) breathLatch = true;
        if (!cam || (!seen && sanity < BREATH_OFF_SANITY)) {
            breathLatch = false;
            breathSeenTicks = 0;
        }
        float rising = Mth.clamp((sanity - prevSanity) / 0.0125F, 0F, 1F);
        prevSanity = sanity;
        float breathRate = breathLatch ? Mth.lerp(rising, BREATH_RISE_SLOW, BREATH_RISE_FAST) : BREATH_FALL;
        breathEnv = Mth.clamp(breathEnv + (breathLatch ? breathRate : -breathRate), 0F, 1F);

        manageSounds(mc);
        manageMediumPieces();
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
     * The Slenderman mod puts a "teleport cue" into his synced data at every real jump (together with the
     * information whether it happened in the view of the player / in front of him), so no jump is lost, even
     * when he was far away or was not on the client before. Old Slenderman mod without the cue: the jump is
     * recognised by his position, as before.
     */
    private static void checkTeleport(Minecraft mc, Entity sl, double dist, boolean cam) {
        UUID id = sl.getUUID();
        Number cue = callNumber(sl, "getTeleportCue");
        if (cue != null) {
            int v = cue.intValue();
            int seq = v >> 2;
            int flags = v & 3;
            if (!id.equals(tpUuid)) {
                tpUuid = id;
                tpSeq = seq;                    // a sound that was started before we saw him is not replayed
            } else if (seq != tpSeq) {
                tpSeq = seq;
                if (cam) onTeleport(mc, flags);
            }
            lastId = id;
            return;
        }

        Vec3 pos = sl.position();
        if (!id.equals(lastId)) {
            lastId = id;
            for (int i = 0; i < HIST.length; i++) HIST[i] = pos;
        }
        Vec3 old = HIST[histIdx];
        HIST[histIdx] = pos;
        histIdx = (histIdx + 1) % HIST.length;
        if (old != null && jumpCooldown == 0 && pos.distanceTo(old) > 4.0) {
            jumpCooldown = 10;
            if (cam) {
                Vec3 look = mc.player.getViewVector(1.0F).normalize();
                Vec3 to = pos.subtract(mc.player.position()).normalize();
                boolean inFront = dist < FRONT_RANGE && look.dot(to) > FRONT_COS;
                boolean inView = dist < SEEN_RANGE && isLookingAt(mc.player, sl);
                onTeleport(mc, (inView ? 1 : 0) | (inFront ? 2 : 0));
            }
        }
    }

    /**
     * He just jumped. In the view of the player or in front of him: the jumpscare sound and the glitch ALWAYS
     * come (one decision for both); out of view only 10% of the time.
     */
    private static void onTeleport(Minecraft mc, int flags) {
        boolean distort = flags != 0 || HUD_RNG.nextFloat() < TELEPORT_UNSEEN_CHANCE;
        if (!distort) return;
        teleportSignX = HUD_RNG.nextBoolean() ? 1F : -1F;
        teleportSignY = HUD_RNG.nextBoolean() ? 1F : -1F;
        teleportGlitchEndMs = System.currentTimeMillis() + TELEPORT_GLITCH_MS + HUD_RNG.nextInt(60);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.TELEPORT.get(), 1.0F, 1.0F));
    }

    /**
     * The stutter: pieces of static_light (200..250 ms each), a pause of 200..300 ms after every piece.
     * It runs ALWAYS while Slenderman is in the field of view and goes on until the sanity is back at zero.
     */
    private static void updateStutter(long now) {
        float s = Mth.clamp(sanity, 0F, 1F);
        if (!seenNow && s < TWITCH_END_SANITY) {
            pulseNextMs = 0L;
            return;
        }
        float level = Math.max(s, 0.05F);
        if (pulseNextMs == 0L || now - pulseNextMs > 400L) pulseNextMs = now;   // (re)start or a hiccup: no catching up
        if (now < pulseNextMs) return;
        playLightPiece(MASTER_VOLUME * LIGHT_VOLUME * (0.40F + 0.60F * level));
        pulseNextMs = now + PIECE_MS + PIECE_PAUSE_MIN + HUD_RNG.nextInt(PIECE_PAUSE_RANDOM);
    }

    /**
     * Called by CameraHud once per rendered frame. Runs the stutter and writes frameVertical / frameHorizontal /
     * ...Bend. The frame (with every element of the interface) bends in short twitches: far from him they are fast
     * and often but weak, the closer he is the deeper and the slower they get; very close the pose can freeze for
     * about a second now and then. While the seen gate is closed (we do not look at him) the frame stays calm.
     */
    public static void frameUpdate() {
        long now = System.currentTimeMillis();
        updateStutter(now);

        if (tpActive()) {
            frameVertical = 34F * teleportSignX;
            frameHorizontal = 28F * teleportSignY;
            frameVerticalBend = 19F * teleportSignX;
            frameHorizontalBend = 17F * teleportSignY;
            return;
        }

        if (seenGate < 0.02F || hudPressure < 0.01F) {
            holdEndMs = 0L;
            twitchEndMs = 0L;
            bendNextMs = 0L;
            frameVertical = frameHorizontal = frameVerticalBend = frameHorizontalBend = 0F;
            return;
        }

        float closeness = 1F - (float) Mth.clamp((slenderDist - CLOSE_NEAR) / (CLOSE_FAR - CLOSE_NEAR), 0.0, 1.0);

        if (now < holdEndMs) {
            twitchEndMs = holdEndMs;                // frozen in the same pose
        } else if (now >= twitchEndMs && now >= bendNextMs) {
            long dur = Math.round(Mth.lerp(closeness, BEND_DUR_FAR, BEND_DUR_NEAR) * (0.75F + 0.5F * HUD_RNG.nextFloat()));
            twitchEndMs = now + dur;
            // not every twitch is strong: a third of them are weak
            float strength = HUD_RNG.nextFloat() < 0.33F ? 0.25F + 0.30F * HUD_RNG.nextFloat() : 0.65F + 0.35F * HUD_RNG.nextFloat();
            float j = jitter(24);
            twV = Math.signum(j == 0F ? 1F : j) * Mth.clamp(0.5F + Math.abs(j), 0.5F, 1F) * strength;
            twVB = HUD_RNG.nextFloat() < 0.85F ? signedKick(strength) : 0F;
            twH = HUD_RNG.nextFloat() < 0.85F ? signedKick(strength) : 0F;
            twHB = HUD_RNG.nextFloat() < 0.85F ? signedKick(strength) : 0F;
            long gap = Math.round(Mth.lerp(closeness, BEND_GAP_FAR, BEND_GAP_NEAR) * (0.70F + 0.60F * HUD_RNG.nextFloat()));
            bendNextMs = twitchEndMs + gap;
            // too close: from time to time this pose freezes for about a second (then a pause before the next freeze)
            if (slenderDist < HOLD_DIST && sanity >= HOLD_MIN_SANITY && now >= holdNextMs
                    && HUD_RNG.nextFloat() < HOLD_CHANCE) {
                holdEndMs = now + HOLD_MS_MIN + HUD_RNG.nextInt(HOLD_MS_RANDOM);
                twitchEndMs = holdEndMs;
                bendNextMs = holdEndMs + 60L;
                holdNextMs = holdEndMs + HOLD_PAUSE_MIN + HUD_RNG.nextInt(HOLD_PAUSE_RANDOM);
            }
        }

        if (now >= twitchEndMs) {
            frameVertical = frameHorizontal = frameVerticalBend = frameHorizontalBend = 0F;
            return;
        }

        float p = Mth.clamp(hudPressure, 0F, 1F);
        float fade = Mth.clamp(p / 0.12F, 0F, 1F);
        float depth = Mth.lerp(closeness, BEND_DEPTH_FAR, BEND_DEPTH_NEAR);

        float spread = (7F + 16F * p + 14F * p * p) * fade * depth;
        float bend = (4F + 9F * p + 9F * p * p) * fade * depth;

        if (sanity > 0.80F) {
            float extra = (sanity - 0.80F) / 0.20F;
            spread *= 1F + 0.60F * extra;
            bend   *= 1F + 0.80F * extra;
        }

        float g = Mth.clamp(seenGate, 0F, 1F);

        frameVertical = twV * spread * g;
        frameVerticalBend = twVB * bend * g;
        frameHorizontal = twH * spread * 0.82F * g;
        frameHorizontalBend = twHB * bend * 0.90F * g;
    }

    /** One piece of static_light (the sound event picks one of the 36 files at random). */
    private static void playLightPiece(float volume) {
        float pitch = 0.96F + HUD_RNG.nextFloat() * 0.08F;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.STATIC_LIGHT.get(), pitch,
                Mth.clamp(volume, 0F, 1F)));
    }

    /** One piece of staticmedium (event static_medium_piece: 37 files, 200..250 ms). */
    private static void playMediumPiece(float volume) {
        float pitch = 0.97F + HUD_RNG.nextFloat() * 0.06F;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.STATIC_MEDIUM_PIECE.get(), pitch,
                Mth.clamp(volume, 0F, 1F)));
    }

    /** checked every rendered frame (not only every game tick), so the sound is cut as early as the frame rate allows. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) stopFinishedTicks(System.currentTimeMillis());
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

    // ---- loops ----
    private static class MidLoop extends AbstractTickableSoundInstance {
        MidLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true; this.delay = 0; this.volume = 0.001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            float v = MID.v * midShare;                      // the whole track: only near 50% sanity loss
            this.volume = Math.max(0.0001F, v * MEDIUM_VOLUME * MASTER_VOLUME);
            if (v < 0.002F) this.stop();
        }
    }

    private static class HeavyLoop extends AbstractTickableSoundInstance {
        HeavyLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true; this.delay = 0; this.volume = 0.001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            this.volume = heavyVol * HEAVY_VOLUME * MASTER_VOLUME;
            if (heavyVol < 0.003F) this.stop();
        }
    }

    private static class GlitchLoop extends AbstractTickableSoundInstance {
        private final int layer;
        GlitchLoop(SoundEvent ev, int layer) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.layer = layer; this.looping = true; this.delay = 0; this.volume = 0.001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            this.volume = GLITCH_GAIN[layer] * MASTER_VOLUME;
            if (GLITCH_GAIN[layer] < 0.002F && lastMode != layer) this.stop();
        }
    }

    /** Breathing: see the BREATHING constants; its volume follows breathEnv (about 5 s up, about 5 s down). */
    private static class BreathingLoop extends AbstractTickableSoundInstance {
        BreathingLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true; this.delay = 0; this.volume = 0.001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            float e = breathEnv;
            float ramp = e * e * (3F - 2F * e);
            float level = Math.max(sanity, heavyVol);
            this.volume = Math.max(0.0001F, ramp * (0.55F + 0.45F * level) * MASTER_VOLUME);
            if (e <= 0F) this.stop();
        }
    }

    /** below ~50% sanity loss the medium static stutters: a piece (200..250 ms), a pause of 200..300 ms, the next piece. */
    private static void manageMediumPieces() {
        float vol = MID.v * (1F - midShare);
        if (vol < 0.02F) {
            mediumNextMs = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (mediumNextMs == 0L || now - mediumNextMs > 400L) mediumNextMs = now;
        if (now < mediumNextMs) return;
        playMediumPiece(vol * MEDIUM_VOLUME * MASTER_VOLUME);
        mediumNextMs = now + PIECE_MS + PIECE_PAUSE_MIN + HUD_RNG.nextInt(PIECE_PAUSE_RANDOM);
    }

    private static void manageSounds(Minecraft mc) {
        SoundManager sm = mc.getSoundManager();
        if (MID.v * midShare > 0.01F && (mid == null || mid.isStopped())) {
            mid = new MidLoop(SlenderCamMod.STATIC_MEDIUM.get());
            sm.play(mid);
        }
        // breathing must never be missing while the distortions last: if the loop is gone (or the engine dropped it
        // for about a second) it is started again
        if (breathEnv > 0.001F) {
            boolean alive = breathingLoop != null && !breathingLoop.isStopped() && sm.isActive(breathingLoop);
            breathDeadTicks = alive ? 0 : breathDeadTicks + 1;
            if (breathingLoop == null || breathingLoop.isStopped() || breathDeadTicks > 20) {
                breathDeadTicks = 0;
                breathingLoop = new BreathingLoop(SlenderCamMod.BREATHING.get());
                sm.play(breathingLoop);
            }
        } else {
            breathDeadTicks = 0;
        }
        if (heavyVol > 0.01F && (heavyLoop == null || heavyLoop.isStopped())) {
            heavyLoop = new HeavyLoop(SlenderCamMod.STATIC_HEAVY.get());
            sm.play(heavyLoop);
        }
    }

    private static void manageGlitchSounds(Minecraft mc, boolean cam) {
        boolean active = cam && intensity > 0.02F && intensity < GLITCH_SOUND_MAX;
        if (!active) {
            if (glitchCooldown < 20) glitchCooldown = 20;
            return;
        }
        glitchCooldown--;
        if (glitchCooldown > 0) return;

        int pause = GLITCH_PAUSE_MIN + HUD_RNG.nextInt(GLITCH_PAUSE_RANDOM);
        glitchCooldown = Math.round(pause * Mth.lerp(smooth(0.02F, 0.65F, intensity), 1.3F, 0.55F));
        float vol = MASTER_VOLUME * Mth.clamp(0.35F + intensity, 0F, 1F) * smooth(0.02F, 0.12F, intensity);
        float pitch = 0.92F + HUD_RNG.nextFloat() * 0.16F;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.GLITCH_SHORT.get(), pitch, vol));
    }

    private static SoundEvent glitchEvent(int layer) {
        return switch (layer) {
            case 1 -> SlenderCamMod.STATIC_GLITCH_1.get();
            case 2 -> SlenderCamMod.STATIC_GLITCH_2.get();
            case 4 -> SlenderCamMod.STATIC_GLITCH_4.get();
            default -> SlenderCamMod.STATIC_GLITCH_3.get();
        };
    }

    /**
     * The staticglitch loops on top of the big static. Only ONE of them is the "main" layer at a time; when
     * another one takes over, the old one is cut off in a quarter of a second.
     *  layer 1 - weakest: starts early and grows together with the big static;
     *  layer 2 - takes over 4-6 s after the big static started, full strength at once; it is also the
     *            background when he is not close any more (sometimes layer 1 replaces it for a moment);
     *  layers 3 and 4 - the strongest pair: they NEVER play together. While he is close and the interference is
     *            very strong (also a little before the big static, and for 3-5 s after it is gone) one of them
     *            plays; every 4-9 s the other one interrupts it, so they take turns.
     * When he is not close any more and the tail is over, layers 3 / 4 are cut at once.
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

        // the strongest pair: zone = close and very strong (or a little before the big static), plus a 3-5 s tail
        boolean zone = near && (h > 0.70F || sanity >= 0.55F);
        if (zone) {
            topTailTicks = 60 + HUD_RNG.nextInt(41);          // 3-5 s after the zone ends
        } else if (topTailTicks > 0 && h < 0.03F) {
            topTailTicks--;
        } else if (h >= 0.03F) {
            topTailTicks = Math.max(topTailTicks, 1);          // the big static is still audible: keep waiting
        }
        boolean topOn = zone || (topWasOn && topTailTicks > 0);
        if (topOn && !topWasOn) {
            topChoice = HUD_RNG.nextBoolean() ? 3 : 4;
            topSwitchTicks = 80 + HUD_RNG.nextInt(101);        // 4-9 s
        } else if (topOn && --topSwitchTicks <= 0) {
            topChoice = topChoice == 3 ? 4 : 3;                // the other one interrupts
            topSwitchTicks = 80 + HUD_RNG.nextInt(101);
        }
        topWasOn = topOn;

        // layers 1 and 2 reach silence about 2 s (h = 0.068) before the big static is gone
        float early = smooth(0.068F, 0.20F, h);

        int want = 0;
        if (topOn) {
            want = topChoice;
        } else if (early > 0F) {
            if (heavyTicks >= g2StartTicks || lastMode >= 2) want = 2;
            else if (h > 0.10F) want = 1;

            if (!near && want == 2) {
                if (altTicks > 0) { altTicks--; want = 1; }
                else if (h > 0.15F && HUD_RNG.nextFloat() < 0.012F) altTicks = 40 + HUD_RNG.nextInt(40);
            } else {
                altTicks = 0;
            }
        }
        lastMode = want;

        float top = Math.max(h, sanity * 0.7F);
        float[] level = new float[5];
        level[1] = 0.80F * (0.30F + 0.70F * h) * early;
        level[2] = 0.85F * (0.50F + 0.50F * h) * early;
        level[3] = 1.00F * (0.60F + 0.40F * top);
        level[4] = level[3];

        for (int i = 1; i <= 4; i++) {
            float goal = want == i ? level[i] : 0F;
            float g = GLITCH_GAIN[i];
            if (goal > g) {
                float up = i == 1 ? 0.04F : 0.35F;       // layer 1 grows, the others play at once
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

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!CameraHud.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        float bobX;
        float bobY;

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
            amp = (0.12F + 0.78F * level) * Mth.clamp(level / 0.08F, 0F, 1F) * VIEW_SHAKE_DEG
                    * (0.50F + 0.85F * proxVol);
        }
        if (tpActive()) amp += 1.2F;
        amp += flash * 3F;

        e.setRoll(e.getRoll() + bobX + amp * 0.8F * jitter(21));
        e.setYaw(e.getYaw() + (bobX * 0.5F) + amp * jitter(22));
        e.setPitch(e.getPitch() + bobY + amp * jitter(23));
    }

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