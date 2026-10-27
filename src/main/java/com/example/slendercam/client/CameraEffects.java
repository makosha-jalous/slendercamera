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
    static final float MASTER_VOLUME = 0.9F;

    private static final double SEEN_RANGE = 80.0D;
    private static final double SEEN_COS = 0.55D;
    private static final float SEEN_SHAKE = 0.25F;

    private static final double NEAR_DIST = 12.0D;
    private static final float FRENZY_SANITY = 0.65F;
    private static final float FRENZY_SHAKE = 0.85F;

    /** static_heavy starts very quietly at this sanity loss (40%) and is at full strength at 70%. */
    private static final float HEAVY_START_SANITY = 0.40F;
    /** ... when he is close (under NEAR_DIST) it starts earlier. */
    private static final float HEAVY_START_NEAR = 0.35F;
    /** without a Slenderman on the client the sanity still falls at the server speed (100 in 14 s). */
    private static final float SANITY_FALL_NO_ENTITY = 1.0F / 280.0F;
    /** and gets to full strength at this one. */
    private static final float HEAVY_FULL_SANITY = 0.70F;
    private static final int HEAVY_DELAY_TICKS = 40;
    private static final float HEAVY_NEAR_RELEASE = 0.40F;
    private static final int HEAVY_RISE_TICKS = 60;
    private static final float HEAVY_VOLUME = 1.0F;

    private static final int SOUND_FADE_TICKS = 130;
    private static final int MID_FADE_TICKS = 12;
    private static final int BREATH_FADE_TICKS = 24;
    private static final int HUD_FADE_TICKS = 12;
    private static final int FOV_LAG_TICKS = 50;

    private static final float GLITCH_SOUND_MAX = 0.65F;
    private static final int GLITCH_PAUSE_MIN = 60;
    private static final int GLITCH_PAUSE_RANDOM = 41;

    private static final float VIEW_SHAKE_DEG = 1.9F;
    private static final float VIEW_FOV_WOBBLE = 4.5F;
    /** radius of the shaking circle in degrees. It is the SAME at every stage; only the speed and the tremor change. */
    /** walking: the up-down bounce of the view (degrees, whole range) and the rocking of the picture left-right (degrees). */
    private static final float WALK_BOB_DEG = 1.2F;
    private static final float WALK_ROCK_DEG = 0.7F;
    /** running: stronger. */
    private static final float RUN_BOB_DEG = 2.8F;
    private static final float RUN_ROCK_DEG = 1.6F;
    /** the figure eight of the close shaking: size (degrees, always the same) and speed (figures per second, far .. almost no sanity left). */
    private static final float FIG_RADIUS = 1.1F;
    private static final float FIG_TURNS_FAR = 1.5F;
    private static final float FIG_TURNS_NEAR = 6.0F;
    /** closer than FIG_DIST_NEAR (blocks) it is the figure eight, further than FIG_DIST_FAR it is the swaying, in between a mix. */
    private static final float FIG_DIST_NEAR = 6F;
    private static final float FIG_DIST_FAR = 18F;
    /** the swaying far away: big slow layer and small faster layer (degrees). */
    private static final float SWAY_BIG = 0.9F;
    private static final float SWAY_SMALL = 0.22F;
    private static final float VIEW_RADIUS = 0.9F;
    /** how much the roll (tilt) follows the circle. */
    private static final float VIEW_ROLL = 0.9F;
    /** turns of the circle per second: far away / when the sanity is almost gone. */
    private static final float VIEW_TURNS_FAR = 0.30F;
    private static final float VIEW_TURNS_NEAR = 6.5F;
    /** the largest share of the fast tremor in the circle (0 = a clean circle, 1 = only random trembling). */
    private static final float VIEW_TREMBLE_MAX = 0.55F;
    /** size of the pulse of the field of view (degrees), also constant. */
    private static final float VIEW_FOV_PULSE = 1.2F;

    // ---- stutter (static_light), bend of the interface, medium static, breathing ----
    // SOUND: staticlight.ogg is cut into pieces of 120..150 ms (staticlight01..59). A piece plays, then a pause of
    //        120..180 ms, then the next piece. It plays ALWAYS while Slenderman is in the field of view and goes on until
    //        the sanity is back at zero.
    /** length of one piece of static_light (ms, the files are 120..150) and the pause after it (ms). */
    /** pieces follow each other WITHOUT pauses: one every 125..160 ms = 6-8 pieces per second. */
    private static final int PIECE_INTERVAL_MIN = 68;
    private static final int PIECE_INTERVAL_RANDOM = 16;
    /** below this sanity loss the frame bends in sync with the pieces of static_light; from it up the free bend (see BEND_*) runs. */
    private static final float SYNC_BEND_MAX_SANITY = 0.40F;
    /** a synchronised bend lasts this share of the piece interval, then the frame is straight until the next piece. */
    private static final float SYNC_BEND_SHARE = 0.70F;
    /** bends in the low stages: one every 150-205 ms = 5-6 a second. */
    private static final int SYNC_BEND_MIN_MS = 150, SYNC_BEND_RANDOM_MS = 56;
    private static final float LIGHT_VOLUME = 1.0F;
    /**
     * NEW technique of the original game for static_light: the WHOLE track (static_light_full.ogg) plays all the time and
     * every game tick a dice decides if it is heard or muted: it is heard with the chance (loss + 35) / 100, where
     * "loss" is the sanity loss in percent (the original: Random.value < (glitchIntensity + 35) / 100). Far away he
     * chops the sound a lot, from 65 % loss it is heard all the time. The bend of the frame starts with every new
     * "heard" piece. false = the old way: short cut pieces (staticlight001..113) one after another (kept, only switched off).
     */
    private static final boolean GATED_LIGHT = true;
    /** the stutter goes on while the sanity is not back at (almost) zero. */
    private static final float TWITCH_END_SANITY = 0.004F;

    // BEND: far away the frame twitches often and fast (about 200-270 ms of bend in every second) but weakly;
    //       the closer he is, the deeper the bend, but the twitches get slower (longer, with longer pauses).
    private static final float BEND_DUR_FAR = 55F, BEND_DUR_NEAR = 170F;     // ms
    private static final float BEND_GAP_FAR = 160F, BEND_GAP_NEAR = 420F;    // ms between two bends
    /** closeness: 0 at CLOSE_FAR blocks (or more), 1 at CLOSE_NEAR blocks (or less). */
    private static final double CLOSE_FAR = 40.0D, CLOSE_NEAR = 3.0D;
    /** depth of the bend: x BEND_DEPTH_FAR when far, x BEND_DEPTH_NEAR when very close. */
    private static final float BEND_DEPTH_FAR = 0.90F, BEND_DEPTH_NEAR = 1.70F;
    /** the frame freezes in one pose for about 1 s: only when closer than this (blocks) and at this sanity loss at least ... */
    private static final double HOLD_DIST = 8.0D;
    private static final float HOLD_MIN_SANITY = 0.50F;
    /** ... with this chance per bend, lasts 0.9..1.2 s, and then rests 2.5..6 s before the next freeze. */
    private static final float HOLD_CHANCE = 0.05F;
    private static final int HOLD_MS_MIN = 2000;
    private static final int HOLD_MS_RANDOM = 1001;      // freezes last 2-3 s
    private static final int HOLD_PAUSE_MIN = 6000;
    private static final int HOLD_PAUSE_RANDOM = 8000;
    /** we turn away / run: with this chance the frame stays frozen in its last pose for 2-3 s (only while the loss is still this high). */
    private static final float HOLD_AFTER_SANITY = 0.45F, HOLD_AFTER_CHANCE = 0.35F;
    /** a bend starts with a short "punch" (stronger for ~45 ms) and flickers like sparks while it lasts. */
    private static final float PUNCH = 0.40F, SPARK = 0.32F;

    // MEDIUM STATIC (staticmedium.ogg): starts quiet at about 40% sanity loss and grows, stuttering in pieces
    // (staticmedium_p01..63, 120..150 ms, no pauses); near 50% it turns into one continuous track.
    private static final float MEDIUM_START = 0.25F, MEDIUM_FULL = 0.45F;
    private static final float MEDIUM_CONT_FROM = 0.46F, MEDIUM_CONT_TO = 0.52F;
    private static final float MEDIUM_VOLUME = 1.0F;

    // BREATHING: starts after 3 s of seeing him, rises in about 5 s (faster when the sanity fills faster),
    // stays while the sanity is high, and fades in about 5 s once the sanity falls below BREATH_OFF_SANITY
    // (so it is already quiet about 4 s before the interface is completely calm).
    /** during the black screen the camera sounds die out after the echo (ticks into the black screen). */
    private static final int DEATH_CUT_TICKS = 36;
    private static final int BREATH_SEEN_TICKS = 1;
    private static final float BREATH_RISE_SLOW = 1F / 60F, BREATH_RISE_FAST = 1F / 60F, BREATH_FALL = 1F / 50F;
    private static final float BREATH_OFF_SANITY = 0.01F;

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
    private static long poseStartMs, holdAfterMs;
    private static float lvV, lvVB, lvH, lvHB;
    private static boolean hadBend;
    /** per rendered frame smoothed values for the shaking of the view (the game tick is only 20 steps a second). */
    private static float viewLevel, viewFov, viewProx;
    private static long lastViewNs;
    /** When the current bend of the frame ends. */
    private static long twitchEndMs;
    /** the frame is frozen in its pose until this time, and the next freeze is allowed after holdNextMs. */
    private static long holdEndMs, holdNextMs;
    /** stutter stream: when the next pulse is due and when the current 2 s cycle started. */
    private static long pulseNextMs, mediumNextMs, bendNextMs, syncNextMs;
    /** breathing envelope 0..1 (linear, 2 s up / 2 s down). */
    private static float breathEnv, prevSanity, midShare;
    private static boolean breathLatch;
    /** death scene of the Slenderman mod (read by reflection): 0 none, 1 the view turns to his face, 2 black screen. */
    private static int deathPhase, deathBlackTicks;
    /** loops that were started: used to notice a loop the sound engine dropped (e.g. after SoundManager.stop()). */
    private static final java.util.Map<SoundInstance, Long> STARTED = new java.util.IdentityHashMap<>();
    private static float noEntityRate;
    private static int breathSeenTicks;
    /** the watcher vanished in front of the player: a very short static (about 60 ms). */
    private static int lastBlip = -1;
    private static long blipEndMs;

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

    private static BreathingLoop breathingLoop;
    /** which of the two strongest layers (3 or 4) is the main one, when it flips next, and the tail after the big static. */
    private static int topChoice = 3, topSwitchTicks, topTailTicks;
    private static boolean topWasOn;
    private static float topThreshold;
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
            GlitchPort.reset();
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

        // death scene of the Slenderman mod: while the view turns to his face (3 s) every sound has to reach its
        // maximum quickly; during the black screen the camera sounds die out together with the echo
        int dp = 0;
        if (sl != null) {
            Number dn = callNumber(sl, "getDeathPhase");
            if (dn != null) dp = dn.intValue();
        }
        deathPhase = dp;
        // watcher mode (/slender watch): the Slenderman mod sends aggression -1. No noise, no bend, no breathing for him,
        // only the short glitch when he vanishes (see the blip below).
        boolean watcher = false;
        if (sl != null) {
            Number an = callNumber(sl, "getAggression");
            watcher = an != null && an.floatValue() < 0F;
        }
        deathBlackTicks = dp == 2 ? deathBlackTicks + 1 : 0;
        boolean turn = dp == 1 && cam;
        if (dp >= 2) cam = false;          // the black screen and everything after it: the Slenderman mod cuts the sounds, the camera is silent

        double dist = 0;
        slenderDist = 1.0E9D;
        boolean seen = false;
        if (sl != null) {
            dist = Math.sqrt(best);
            if (!watcher) slenderDist = dist;
            seen = !watcher && cam && dist < SEEN_RANGE && isLookingAt(mc.player, sl);
            rawSanity = watcher ? 0F : readSanity(sl);
            noEntityRate = 0F;
            checkTeleport(mc, sl, dist, cam && !watcher);
        } else {
            lastId = null;
            // he is not on the client (parked far away): the sanity keeps coming back at the server speed
            if (noEntityRate <= 0F) noEntityRate = rawSanity / ((10F + 5F * rawSanity) * 20F / 1.4F);   // same speed as on the server
            rawSanity = Math.max(0F, rawSanity - noEntityRate);
        }
        seenNow = seen;

        // watcher mode: when he vanishes because the player comes closer, a very short static (60 ms)
        if (sl != null) {
            Number bn = callNumber(sl, "getBlipCue");
            if (bn != null) {
                int b = bn.intValue();
                if (lastBlip < 0) lastBlip = b;
                else if (b != lastBlip) {
                    lastBlip = b;
                    if (cam && dist < 200.0D) {
                        blipEndMs = System.currentTimeMillis() + 60L;
                        // one short piece of the static glitch (glitch_short = pieces of staticglitch)
                        mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.GLITCH_SHORT.get(),
                                0.95F + HUD_RNG.nextFloat() * 0.1F, 0.9F));
                        newPose(1.0F);
                    }
                }
            }
        } else {
            lastBlip = -1;
        }

        // seen gate: rises quickly when we look at him, falls slowly when we turn away.
        float seenGoal = seen ? 1F : 0F;
        seenGate += (seenGoal - seenGate) * (seen ? 0.45F : 0.30F);
        if (seenGate < 0.001F) seenGate = 0F;

        // sanity 0..1 follows the value of the Slenderman mod (it already depends on distance and stare time)
        float rawForCam = cam ? rawSanity : 0F;
        sanity += (rawForCam - sanity) * 0.30F;
        if (sanity < 0.001F) sanity = 0F;

        boolean near = cam && sl != null && !watcher && dist < NEAR_DIST;

        // grain and breathing
        INTENSITY.update(sanity, RISE, 1F, BREATH_FADE_TICKS);

        // static_mid: comes in at ~12..40% sanity and gives way to the heavy static at ~62..85%
        // static medium: on from a sanity loss of 25% to 100%, off below 25% (also on the way down); audible at once
        float midGoal = sanity >= MEDIUM_START ? 0.55F + 0.45F * smooth(MEDIUM_START, 0.60F, sanity) : 0F;
        midShare = smooth(MEDIUM_CONT_FROM, MEDIUM_CONT_TO, sanity);
        MID.update(midGoal, turn ? 0.5F : 0.35F, 1F, MID_FADE_TICKS);

        float proxGoal = cam && sl != null && !watcher ? 1F - (float) Mth.clamp((dist - 2.5) / 60.0, 0.0, 1.0) : 0F;
        PROX.update(proxGoal, 0.10F, 1F, HUD_FADE_TICKS + FOV_LAG_TICKS);

        // static_heavy: grows with the sanity loss, and earlier when he is close
        float heavyGoal = smooth(HEAVY_START_SANITY, HEAVY_FULL_SANITY, sanity);
        HEAVY.update(heavyGoal, 0.35F, turn ? 1F / 30F : 1F / HEAVY_RISE_TICKS, SOUND_FADE_TICKS);   // during the 3 s turn: full in ~1.5 s

        // shake of the picture / bending of the frame: as strong as the sanity loss (a tiny bit when just seen)
        float shakeTarget = 0F;
        if (cam) {
            shakeTarget = sanity;                                // the distortions depend ONLY on the sanity loss
        }
        HUD.update(shakeTarget, 0.22F, 1F, HUD_FADE_TICKS);

        float delayed = SHAKE_DELAY[shakeDelayIdx];
        SHAKE_DELAY[shakeDelayIdx] = shakeTarget;
        shakeDelayIdx = (shakeDelayIdx + 1) % SHAKE_DELAY.length;
        FOV.update(shakeTarget, 0.22F, 1F, HUD_FADE_TICKS);

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
        if (turn) breathLatch = true;                       // the turn to his face: breathing comes in at once
        float breathRate = breathLatch ? (turn ? 1F / 50F : Mth.lerp(rising, BREATH_RISE_SLOW, BREATH_RISE_FAST)) : BREATH_FALL;
        breathEnv = Mth.clamp(breathEnv + (breathLatch ? breathRate : -breathRate), 0F, 1F);

        // ORIGINAL GLITCH (port): the dice and all effects live in GlitchPort, here only the inputs are handed over
        GlitchPort.setInputs(cam ? sanity * 100F : 0F, seen, deathPhase >= 1);
        GlitchPort.tickSounds(mc);
        manageBreathing(mc);
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

    /** the view shaking is driven by values that are smoothed every RENDERED frame (not only every game tick). */
    private static void updateView() {
        long ns = System.nanoTime();
        float dt = lastViewNs == 0L ? 0.016F : Math.min(0.05F, (ns - lastViewNs) / 1e9F);
        lastViewNs = ns;
        float k = 1F - (float) Math.exp(-dt * 9F);
        viewLevel += (hudPressure - viewLevel) * k;
        viewFov += (fovPressure - viewFov) * k;
        viewProx += (proxVol - viewProx) * k;
        if (viewLevel < 0.0005F) viewLevel = 0F;
        if (viewFov < 0.0005F) viewFov = 0F;
    }

    /** fast random flicker -1..1 that changes only when the bucket (26 ms) changes. */
    private static float sparkNoise(int seed, long bucket) {
        long h = bucket * 0x9E3779B97F4A7C15L + seed * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 31);
        h *= 0xD6E8FEB86659FD93L;
        h ^= (h >>> 29);
        return ((h & 0xFFFFL) / 32767.5F) - 1F;
    }

    /** a new random bent pose of the frame (strength 0..1): used only by the short blip of the watcher. */
    private static void newPose(float strength) {
        poseStartMs = System.currentTimeMillis();
        float j = jitter(24);
        twV = Math.signum(j == 0F ? 1F : j) * Mth.clamp(0.5F + Math.abs(j), 0.5F, 1F) * strength;
        twVB = HUD_RNG.nextFloat() < 0.85F ? signedKick(strength) : 0F;
        twH = HUD_RNG.nextFloat() < 0.85F ? signedKick(strength) : 0F;
        twHB = HUD_RNG.nextFloat() < 0.85F ? signedKick(strength) : 0F;
    }

    /**
     * Called by CameraHud once per rendered frame. The normal bending of the picture AND of the frame is the FISHEYE of the
     * original game (GlitchPort.strengthX / strengthY, see CameraHud), so here the frame* values are only used by the two
     * short special cases: the blip of the watcher and the teleport glitch.
     */
    public static void frameUpdate() {
        long now = System.currentTimeMillis();
        updateView();
        frameVertical = frameHorizontal = frameVerticalBend = frameHorizontalBend = 0F;

        if (now < blipEndMs) {
            // the short static: one strong bend of the frame for about 60 ms, whatever the sanity is
            frameVertical = twV * 26F;
            frameVerticalBend = twVB * 14F;
            frameHorizontal = twH * 22F;
            frameHorizontalBend = twHB * 12F;
            return;
        }

        if (tpActive()) {
            frameVertical = 34F * teleportSignX;
            frameHorizontal = 28F * teleportSignY;
            frameVerticalBend = 19F * teleportSignX;
            frameHorizontalBend = 17F * teleportSignY;
        }
    }

    /** checked every rendered frame (not only every game tick), so the sound is cut as early as the frame rate allows. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        stopFinishedTicks(System.currentTimeMillis());
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) GlitchPort.advance(System.nanoTime(), mc.isPaused());   // 50 steps a second like FixedUpdate
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

    /** Breathing: see the BREATHING constants; its volume follows breathEnv (about 5 s up, about 5 s down). */
    private static class BreathingLoop extends AbstractTickableSoundInstance {
        BreathingLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true; this.delay = 0; this.volume = 0.001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            // original: the volume of "scared" is simply GlitchPort.scared() (0..1), it is updated 50 times a second
            this.volume = Math.max(0.0001F, GlitchPort.scared() * MASTER_VOLUME);
            if (GlitchPort.scared() <= 0F) this.stop();
        }
    }

    private static void manageBreathing(Minecraft mc) {
        SoundManager sm = mc.getSoundManager();
        if (GlitchPort.scared() > 0.001F && needsStart(sm, breathingLoop)) {
            breathingLoop = new BreathingLoop(SlenderCamMod.BREATHING.get());
            startLoop(sm, breathingLoop);
        }
    }

    /**
     * True when a loop has to be (re)started: it was never started, it stopped itself, or the sound engine dropped it
     * (SoundManager.stop() - for example in a death scene - kills every sound but does NOT mark the instances as
     * stopped, so "isStopped()" alone would never notice and the loop would stay silent for the rest of the game).
     */
    static boolean needsStart(SoundManager sm, SoundInstance inst) {
        if (inst == null) return true;
        if (inst instanceof AbstractTickableSoundInstance t && t.isStopped()) return true;
        Long started = STARTED.get(inst);
        if (started == null) return false;
        if (System.currentTimeMillis() - started < 600L) return false;   // it has just been started: give the engine time
        if (sm.isActive(inst)) return false;
        sm.stop(inst);
        return true;
    }

    static void startLoop(SoundManager sm, SoundInstance inst) {
        if (STARTED.size() > 64) STARTED.clear();
        STARTED.put(inst, System.currentTimeMillis());
        sm.play(inst);
    }

    /** smooth continuous noise -1..1 (three sines of different speeds): no jumps between frames. */
    private static float smoothShake(int seed, double tSec, float speed) {
        double f1 = 1.7 + (seed % 7) * 0.31, f2 = 3.1 + (seed % 5) * 0.47, f3 = 5.3 + (seed % 3) * 0.6;
        double tt = tSec * speed * Math.PI * 2.0;
        return (float) (0.55 * Math.sin(tt * f1 + seed * 1.3) + 0.32 * Math.sin(tt * f2 + seed * 2.9)
                + 0.13 * Math.sin(tt * f3 + seed * 4.1));
    }

    private static float ownPhase, ownMove, ownRun;
    private static long ownNs;
    private static java.lang.reflect.Method gaitM;
    private static boolean gaitTried;

    /** {phase, move 0..1, run 0..1}: from the flashlight mod when it is live (so the hand and the view are in sync), else our own. */
    private static float[] gaitState(Minecraft mc) {
        try {
            if (!gaitTried) {
                gaitTried = true;
                try {
                    gaitM = Class.forName("com.example.slenderflashlight.client.FlashlightClient").getMethod("gaitState");
                } catch (Throwable ignored) { gaitM = null; }
            }
            if (gaitM != null) {
                float[] r = (float[]) gaitM.invoke(null);
                if (r != null && r.length >= 4 && r[3] > 0.5F) return r;
            }
        } catch (Throwable ignored) { }
        long ns = System.nanoTime();
        float dt = ownNs == 0 ? 0.016F : Math.min(0.05F, (ns - ownNs) / 1e9F);
        ownNs = ns;
        double sp = mc.player.getDeltaMovement().horizontalDistance();
        boolean moving = mc.player.isOnGround() && sp > 0.04;
        boolean running = moving && mc.player.isSprinting();
        float k = Math.min(1F, dt * 8F);
        ownMove += ((moving ? 1F : 0F) - ownMove) * k;
        ownRun += ((running ? 1F : 0F) - ownRun) * k;
        // fixed pace: a step every 0.8 s walking, every 0.6 s running (a swing = two steps)
        if (moving) ownPhase += dt * Mth.lerp(ownRun, 6.2831853F / 1.6F, 6.2831853F / 1.2F);
        if (ownPhase > 6.2831853F) ownPhase -= 6.2831853F;
        return new float[]{ownPhase, ownMove, ownRun, 1F};
    }

    private static int lastStepK;
    /**
     * Colour split (chromatic aberration) of the picture 0..1, like the original's glitch: it flickers in short random
     * bursts, the chance and the strength grow with the lost sanity.
     */
    public static float chromatic() {
        return Mth.clamp(GlitchPort.chromaNorm() + flash * 0.8F, 0F, 1F);
    }

    private static float hash01(long n) {
        n = (n ^ (n >>> 15)) * 0x2C1B3C6DL;
        n = (n ^ (n >>> 12)) * 0x297A2D39L;
        n ^= n >>> 15;
        return (n & 0xFFFFFFL) / (float) 0x1000000;
    }

    /** smooth fast trembling -1..1 (three fast sines, 17-40 Hz): different for every seed, no jumps between frames. */
    private static float fastNoise(double tSec, int seed) {
        double a = tSec * Math.PI * 2.0;
        return (float) (0.50 * Math.sin(a * (17.0 + seed % 5) + seed * 1.7)
                + 0.32 * Math.sin(a * (26.0 + seed % 7) + seed * 2.3)
                + 0.18 * Math.sin(a * (38.0 + seed % 3) + seed * 3.1));
    }

    private static float circPhase, circSample, tremX, tremY;
    private static long circNextMs;
    private static long circNs;
    private static boolean ownStep;

    /** Our own footsteps: one step per swing (walking 1.5 s, running 0.5 s); the vanilla steps near the player are muted. */
    @SubscribeEvent
    public static void onClientTickSteps(net.minecraftforge.event.TickEvent.ClientTickEvent e) {
        if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused() || !CameraHud.active()) return;
        float[] g = gaitState(mc);
        int k = (int) Math.floor((g[0] + 1.5707963F) / 3.1415927F);   // 0,1,2: the steps are at 90 and 270 degrees
        boolean stepNow = k > lastStepK;
        lastStepK = k;
        if (!stepNow || g[1] < 0.5F || !mc.player.isOnGround() || mc.player.isInWater()) return;
        net.minecraft.core.BlockPos pos = mc.player.getOnPos();
        net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(pos);
        if (st.isAir()) return;
        net.minecraft.world.level.block.SoundType t = st.getSoundType(mc.level, pos, mc.player);
        ownStep = true;
        try {
            mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), t.getStepSound(),
                    SoundSource.PLAYERS, t.getVolume() * 0.15F * (mc.player.isCrouching() ? 0.5F : 1F), t.getPitch(), false);
        } finally {
            ownStep = false;
        }
    }

    @SubscribeEvent
    public static void onPlaySoundMuteSteps(net.minecraftforge.client.event.sound.PlaySoundEvent e) {
        if (ownStep || !CameraHud.active()) return;
        net.minecraft.client.resources.sounds.SoundInstance si = e.getSound();
        Minecraft mc = Minecraft.getInstance();
        if (si == null || mc.player == null || !si.getLocation().getPath().endsWith(".step")) return;
        double dx = si.getX() - mc.player.getX(), dy = si.getY() - mc.player.getY(), dz = si.getZ() - mc.player.getZ();
        if (dx * dx + dy * dy + dz * dz < 4.0) e.setSound(null);
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        // the vanilla view bobbing was switched off for this frame's world render (see onFov): give the option back at once
        if (vanillaBobSwitched) {
            vanillaBobSwitched = false;
            mc.options.bobView().set(true);
        }
        if (!CameraHud.active()) return;
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        float bobX;   // yaw (left-right)
        float bobY;   // pitch (up-down)
        float bobR;   // roll

        // Step-driven view: the centre of the screen moves along the same arc as the hand, but in the opposite direction
        // (the hand goes right - the view goes left). The pace follows the real speed (Slowness 1 / 2 slows it down).
        float[] g = gaitState(mc);
        float gp = g[0], gMove = g[1], gRun = g[2];
        float idleX = (float) Math.sin(now * 0.002) * 0.25F;
        float idleY = (float) Math.cos(now * 0.0015) * 0.15F;
        float idleK = 1F - gMove;
        // The CENTRE of the screen never moves sideways. While walking / running only
        //  - the view bobs up and down (a small bounce at every step: two per full stride), and
        //  - the picture rocks a little left and right (a tilt around the middle of the screen, once per stride).
        // Both follow the real step phase (so Slowness 1 / 2 slows them down) and are stronger when running.
        float s1 = (float) Math.sin(gp);
        float bounce = s1 * s1 - 0.5F;                                       // -0.5..0.5, two bounces per stride
        float walkY = bounce * WALK_BOB_DEG;
        float runY = bounce * RUN_BOB_DEG;
        float walkR = s1 * WALK_ROCK_DEG;
        float runR = s1 * RUN_ROCK_DEG;
        bobX = 0F;                                                           // no sideways turning of the view
        bobY = Mth.lerp(gRun, walkY, runY) * gMove + idleY * idleK;
        bobR = Mth.lerp(gRun, walkR, runR) * gMove + idleX * idleK;

        // ---- shaking of the view (the picture only, no zoom): its shape depends on the distance to him, the strength on the sanity
        //   CLOSE: the picture shakes very fast along a VERTICAL FIGURE EIGHT (the infinity sign standing up);
        //   FAR:   while he is in view the camera just sways a little more: two layers of smooth noise, a big slow one
        //          and a small faster one (like the CameraShake of the original game).
        // The size of the figure is always the same, only the speed grows with the sanity loss. It dies away with the sanity.
        float sev = Mth.clamp(sanity, 0F, 1F);
        float on = smooth(0.0F, 0.04F, sev);                    // no jump when it starts or ends
        long nowNs = System.nanoTime();
        float cdt = circNs == 0 ? 0.016F : Math.min(0.05F, (nowNs - circNs) / 1e9F);
        circNs = nowNs;
        float turns = Mth.lerp((float) Math.pow(sev, 1.2), FIG_TURNS_FAR, FIG_TURNS_NEAR);   // figures per second
        circPhase += cdt * 6.2831853F * turns;
        if (circPhase > 6.2831853F) circPhase -= 6.2831853F;
        double t = now / 1000.0;
        float closeK = 1F - smooth(FIG_DIST_NEAR, FIG_DIST_FAR, (float) slenderDist);       // 1 = close, 0 = far
        // close: x = sin(2 phase), y = sin(phase) = a figure eight that stands up
        float figX = (float) Math.sin(2.0 * circPhase) * FIG_RADIUS * on;
        float figY = (float) Math.sin(circPhase) * FIG_RADIUS * on;
        // far: the swaying, only while he is in view
        float sway = seenGate * (0.40F + 0.60F * sev);
        float swayX = (SWAY_BIG * smoothShake(31, t, 0.5F) + SWAY_SMALL * smoothShake(32, t, 2.0F)) * sway;
        float swayY = (SWAY_BIG * smoothShake(33, t, 0.5F) + SWAY_SMALL * smoothShake(34, t, 2.0F)) * sway;
        tremX = figX;
        tremY = figY;
        float nx = fastNoise(t, 11), ny = fastNoise(t, 23);
        float extra = (tpActive() ? 0.6F : 0F) + flash * 1.5F;   // short jolts (teleport, flash), not part of the shaking
        e.setYaw(e.getYaw() + bobX + Mth.lerp(closeK, swayX, figX) + extra * 0.5F * nx);
        e.setPitch(e.getPitch() + bobY + Mth.lerp(closeK, swayY, figY) + extra * 0.5F * ny);
        e.setRoll(e.getRoll() + bobR + extra * 0.4F * fastNoise(t, 37));
    }

    /** true between onFov and onCameraAngles of one frame: the player's option "view bobbing" is switched off just for the world render. */
    private static boolean vanillaBobSwitched;

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        if (!CameraHud.active() || !e.usedConfiguredFov()) return;
        Minecraft mc = Minecraft.getInstance();
        // The vanilla view bobbing (the lift and the tilt of every step) is blocked while the camera is up: it would
        // work against our own bobbing. The game asks for the option AFTER this event and BEFORE onCameraAngles, so it is
        // switched off here and given back there (the player's setting is never changed for good).
        if (!vanillaBobSwitched && mc.options.bobView().get()) {
            vanillaBobSwitched = true;
            mc.options.bobView().set(false);
        }
        // the vanilla "FOV effects" stay: the view widens a little when running (and narrows with Slowness), as in the game
        double fov = e.getFOV();
        float z = zoom((float) e.getPartialTick());
        if (z > 0.001F) {
            fov = fov / (1.0 + (ZOOM_MAX - 1.0) * z);
        }
        e.setFOV(fov);
    }
}