// (полный файл — замените текущий CameraEffects.java на этот)
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
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = SlenderCamMod.ID, value = Dist.CLIENT)
public class CameraEffects {

    private static final ResourceLocation SLENDER = new ResourceLocation("slenderman", "slenderman");

    private static Method aggressionMethod;

    private static boolean isSlender(Entity e) {
        return SLENDER.equals(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()));
    }

    /** Server aggression: 0..1 (older AI) or 0..100 (new AI). Normalised to 0..1 for the camera. */
    private static float readAggression(Entity e) {
        try {
            if (aggressionMethod == null || !aggressionMethod.getDeclaringClass().isInstance(e)) {
                aggressionMethod = e.getClass().getMethod("getAggression");
            }
            float raw = ((Number) aggressionMethod.invoke(e)).floatValue();
            if (raw > 1.5F) raw /= 100.0F;
            return Mth.clamp(raw, 0F, 1F);
        } catch (Exception ex) {
            return 0F;
        }
    }

    private static final float ZOOM_MAX = 3.5F;
    private static final float ZOOM_SPEED = 0.14F;
    private static final float RISE = 0.08F;
    private static final float MASTER_VOLUME = 0.9F;

    private static final double SEEN_RANGE = 80.0D;
    private static final double SEEN_COS = 0.55D;
    private static final float SEEN_SHAKE = 0.30F;

    private static final double NEAR_DIST = 12.0D;
    private static final float FRENZY_AGGRESSION = 0.65F;
    private static final float FRENZY_SHAKE = 0.85F;

    /** static_heavy starts at this aggression (75%). */
    private static final float HEAVY_START_AGGRESSION = 0.75F;
    /** and gets to full strength at this one. */
    private static final float HEAVY_FULL_AGGRESSION = 1.0F;
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

    // ---- twitch timings ----
    /** The twitch sound base range: 10..14 ms */
    private static final int TWITCH_TICK_MS_MIN = 10;
    private static final int TWITCH_TICK_MS_RANDOM = 5; // 10 + [0..4] -> 10..14 ms

    /** Short twitch: 10..12 ms (visual bend, when the aggression is low). */
    private static final long TWITCH_MS_MIN = 10L;
    private static final int TWITCH_MS_RANDOM = 3;
    /** At aggression >= 50% the short twitch gets even faster: 5..8 ms. */
    private static final float TWITCH_FAST_AGGRESSION = 0.50F;
    private static final long TWITCH_FAST_MS_MIN = 5L;
    private static final int TWITCH_FAST_MS_RANDOM = 4;
    /** Twitches while looking at him always pass this lowered threshold. */
    private static final float TWITCH_SEEN_THRESHOLD = 0.05F;
    /** At high aggression the frame can freeze in the bent pose. */
    private static final float TWITCH_HOLD_AGGRESSION = 0.80F;
    /** Chance per twitch that a high-aggression frame will hold instead of snapping back at once. */
    private static final float TWITCH_HOLD_CHANCE = 0.10F;
    /** The hold itself lasts 1000..2000 ms. */
    private static final int TWITCH_HOLD_MS_MIN = 1000;
    private static final int TWITCH_HOLD_MS_RANDOM = 1001;

    private static final float TWITCH_TICK_VOLUME = 0.6F;

    private static final int TELEPORT_GLITCH_MS = 220;
    /** Jumpscare sound on teleport when he is out of view: only 10% of the time. */
    private static final float TELEPORT_UNSEEN_CHANCE = 0.10F;
    private static final double FRONT_RANGE = 30.0D;
    private static final double FRONT_COS = 0.30D;

    // ... (остальной код без изменений до полей для телепортации/истории)

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
    private static float aggression;
    private static boolean seenNow;
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
    /** When the FRAME BEND ends (variable, could be a few ms or up to 2 s). */
    private static long twitchEndMs;
    /** When the SOUND ends (variable, 10..14 ms). */
    private static long tickEndMs;
    private static long nextTwitchMs, lastJitterBucket = -1L;

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

    // Pending teleports: если прыжок обнаружен, но сущность помечена как скрытая,
    // отложим вызов onTeleport до тех пор, пока она не станет видима или не истечёт таймаут.
    private static final class Pending {
        final Entity entity;
        long deadlineMs;
        Pending(Entity e, long d) { entity = e; deadlineMs = d; }
    }
    private static final Map<UUID, Pending> PENDING_TELEPORTS = new HashMap<>();
    private static final long PENDING_TIMEOUT_MS = 800L; // ms

    private static MidLoop mid;
    private static HeavyLoop heavyLoop;
    private static BreathingLoop breathingLoop;

    private static final GlitchLoop[] GLITCH = new GlitchLoop[4];
    private static final float[] GLITCH_GAIN = new float[4];
    private static int heavyTicks;
    private static int g2StartTicks = 100;
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
    public static float gazePressure() { return aggression; }
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
            aggression = 0F;
            seenNow = false;
            seenGate = 0F;
            heavyWait = 0;
            heavyLatched = false;
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
        float rawAggression = 0F;
        boolean seen = false;
        if (sl != null) {
            dist = Math.sqrt(best);
            rawAggression = readAggression(sl);
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

                // Если сущность помечена как скрытая на клиенте (isTeleportHidden()), отложим обработку,
                // чтобы звук/глитч проигрались в момент, когда она появится (или через таймаут).
                boolean shouldCallNow = true;
                try {
                    Method m = sl.getClass().getMethod("isTeleportHidden");
                    Object res = m.invoke(sl);
                    if (res instanceof Boolean && ((Boolean) res)) {
                        // отложить; добавляем в очередь
                        PENDING_TELEPORTS.put(sl.getUUID(), new Pending(sl, System.currentTimeMillis() + PENDING_TIMEOUT_MS));
                        shouldCallNow = false;
                    }
                } catch (NoSuchMethodException ignored) {
                    // нет метода — просто падаем через (вызываем сразу)
                } catch (Exception ignored) {
                    // рефлексия бахнула — всё равно попробуем вызвать onTeleport
                }

                if (shouldCallNow && cam) onTeleport(mc, sl, dist);
            }
        } else {
            lastId = null;
        }
        seenNow = seen;

        // process pending teleports: если сущность стала видимой или время истекло -> вызвать onTeleport
        if (!PENDING_TELEPORTS.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, Pending>> it = PENDING_TELEPORTS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Pending> e = it.next();
                Pending p = e.getValue();
                Entity ent = p.entity;
                if (ent == null || ent.level == null || ent.isRemoved()) {
                    it.remove();
                    continue;
                }
                boolean call = false;
                try {
                    Method m = ent.getClass().getMethod("isTeleportHidden");
                    Object res = m.invoke(ent);
                    if (!(res instanceof Boolean) || !((Boolean) res)) {
                        call = true; // entity became visible
                    }
                } catch (NoSuchMethodException ex) {
                    call = true; // can't detect hidden -> call
                } catch (Exception ex) {
                    // reflection failure: fallback to deadline
                }
                if (now >= p.deadlineMs) call = true; // timeout
                if (call) {
                    double d = mc.player == null ? 0.0 : ent.distanceTo(mc.player);
                    if (cam) onTeleport(mc, ent, d);
                    it.remove();
                }
            }
        }

        // seen gate: rises quickly when we look at him, falls slowly when we turn away.
        float seenGoal = seen ? 1F : 0F;
        seenGate += (seenGoal - seenGate) * (seen ? 0.45F : 0.06F);
        if (seenGate < 0.001F) seenGate = 0F;

        float rawForCam = cam ? rawAggression : 0F;
        aggression += (rawForCam - aggression) * 0.30F;
        if (aggression < 0.001F) aggression = 0F;

        boolean near = cam && sl != null && dist < NEAR_DIST;
        boolean frenzy = near && aggression > FRENZY_AGGRESSION;

        INTENSITY.update(aggression, RISE, 1F, BREATH_FADE_TICKS);

        float midGoal = smooth(0.15F, 0.45F, aggression) * (1F - smooth(0.65F, 0.90F, aggression));
        MID.update(midGoal, 0.08F, 1F, MID_FADE_TICKS);

        float proxGoal = cam && sl != null ? 1F - (float) Mth.clamp((dist - 2.5) / 60.0, 0.0, 1.0) : 0F;
        PROX.update(proxGoal, 0.10F, 1F, HUD_FADE_TICKS + FOV_LAG_TICKS);

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

        float shakeTarget = 0F;
        if (cam) {
            shakeTarget = frenzy ? Math.max(aggression, FRENZY_SHAKE) : aggression;
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
     * He just jumped to a new place. The jumpscare sound plays only when he appears in the player's
     * field of view, or ALWAYS when he appears in front of the player; otherwise only 10% of the time.
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
        // Jumpscare / teleport sound: always when in view or in front, rarely (10%) when out of view.
        if (inView || inFront || HUD_RNG.nextFloat() < TELEPORT_UNSEEN_CHANCE) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.TELEPORT.get(), 1.0F, 1.0F));
        }
    }

    /**
     * Twitches fire whenever we look at him. The FRAME BEND duration is variable (a few ms, up to a
     * second, and rarely 1..2 s at high aggression). The SOUND now is 10..14 ms and always stops
     * exactly after its scheduled ms — even if the frame bend lasts longer.
     *
     * Important change:
     * - don't return early just because player isn't looking; allow twitch sounds to continue while
     *   aggression is decaying (so the "ticking" tension remains) — but stop everything if aggression
     *   and fovPressure are essentially zero.
     */
    private static void updateTwitch(long now, float fp) {
        // Previously: if (!seenNow && fp < 0.01F) return; -> blocks sounds during decay.
        // New rule: only bail out if neither seen nor any aggression present.
        if (!seenNow && aggression < 0.01F && fp < 0.01F) {
            nextTwitchMs = 0L;
            return;
        }

        long bucket = now / 16L;
        if (bucket == lastJitterBucket) return;
        lastJitterBucket = bucket;

        if (nextTwitchMs == 0L) nextTwitchMs = now + 30L;
        if (now < nextTwitchMs) return;

        float j = jitter(24);
        float threshold = seenNow ? TWITCH_SEEN_THRESHOLD : Mth.lerp(fp, 0.45F, 0.20F);
        if (Math.abs(j) < threshold) return;

        // ---- Frame bend duration roll ---- (unchanged)
        long dur;
        float roll = HUD_RNG.nextFloat();
        if (aggression >= TWITCH_HOLD_AGGRESSION && roll < TWITCH_HOLD_CHANCE) {
            dur = TWITCH_HOLD_MS_MIN + HUD_RNG.nextInt(TWITCH_HOLD_MS_RANDOM);
        } else if (roll < 0.35F) {
            dur = 200 + HUD_RNG.nextInt(800);
        } else if (roll < 0.55F) {
            dur = 30 + HUD_RNG.nextInt(170);
        } else if (aggression >= TWITCH_FAST_AGGRESSION) {
            dur = TWITCH_FAST_MS_MIN + HUD_RNG.nextInt(TWITCH_FAST_MS_RANDOM);
        } else {
            dur = TWITCH_MS_MIN + HUD_RNG.nextInt(TWITCH_MS_RANDOM);
        }

        // ensure frame bend lives long enough for the sound (we will also ensure tick sound duration <= bend)
        twitchEndMs = now + dur;

        // compute tick sound duration: 10..14 ms
        int tickMs = TWITCH_TICK_MS_MIN + HUD_RNG.nextInt(TWITCH_TICK_MS_RANDOM);
        tickEndMs = now + tickMs;
        // ensure bend is not shorter than sound (so bend and sound overlap)
        if (twitchEndMs < tickEndMs) twitchEndMs = tickEndMs;

        twV = Math.signum(j) * Mth.clamp(0.5F + Math.abs(j), 0.5F, 1F);
        twVB = HUD_RNG.nextFloat() < 0.85F ? signedKick(1F) : 0F;
        twH = HUD_RNG.nextFloat() < 0.85F ? signedKick(1F) : 0F;
        twHB = HUD_RNG.nextFloat() < 0.85F ? signedKick(1F) : 0F;

        int gapMin = seenNow ? 8 : Math.round(Mth.lerp(fp, 40F, 15F));
        int gapMax = seenNow ? 120 : Math.round(Mth.lerp(fp, 300F, 120F));
        nextTwitchMs = twitchEndMs + gapMin + HUD_RNG.nextInt(Math.max(1, gapMax - gapMin));

        if (seenNow || aggression > 0.02F) playTwitchTick(now, tickMs);
    }

    /** Play a single very short twitch tick and schedule its forced stop after exactly tickMs ms. */
    private static void playTwitchTick(long now, int tickMs) {
        float pitch = 0.90F + HUD_RNG.nextFloat() * 0.20F;
        SimpleSoundInstance inst = SimpleSoundInstance.forUI(SlenderCamMod.TWITCH_TICK.get(), pitch,
                Mth.clamp(MASTER_VOLUME * TWITCH_TICK_VOLUME, 0F, 1F));
        Minecraft.getInstance().getSoundManager().play(inst);
        ACTIVE_TICKS.add(new TickSound(inst, now + tickMs));
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

    // ---- loops and sound management unchanged (except playTwitchTick signature) ----
    private static class MidLoop extends AbstractTickableSoundInstance {
        MidLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true; this.delay = 0; this.volume = 0.001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            this.volume = MID.v * MASTER_VOLUME;
            if (MID.v < 0.002F) this.stop();
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

    private static class BreathingLoop extends AbstractTickableSoundInstance {
        BreathingLoop(SoundEvent ev) {
            super(ev, SoundSource.MASTER, RandomSource.create());
            this.looping = true; this.delay = 0; this.volume = 0.001F;
            this.relative = true; this.attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean canStartSilent() { return true; }
        @Override public void tick() {
            float level = Math.max(intensity, heavyVol);
            float targetVol = level * level * 1.1F * MASTER_VOLUME;
            this.volume += (targetVol - this.volume) * 0.08F;
            if (level < 0.01F && this.volume < 0.004F) this.stop();
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
        // NOTE: twitch ticks are short sounds managed via ACTIVE_TICKS and stopFinishedTicks()
        stopFinishedTicks(System.currentTimeMillis());
    }

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

    private static void manageGlitchLayers(Minecraft mc, boolean near) {
        SoundManager sm = mc.getSoundManager();
        float h = heavyVol;

        if (h > 0.03F) {
            if (heavyTicks == 0) g2StartTicks = 80 + HUD_RNG.nextInt(41);
            heavyTicks++;
        } else {
            heavyTicks = 0;
        }

        float early = smooth(0.068F, 0.20F, h);

        int want = 0;
        if (near && h > 0.70F) {
            want = 3;
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

        float[] level = new float[4];
        level[1] = 0.80F * (0.30F + 0.70F * h) * early;
        level[2] = 0.85F * (0.50F + 0.50F * h) * early;
        level[3] = 1.00F * (0.60F + 0.40F * h);

        for (int i = 1; i <= 3; i++) {
            float goal = want == i ? level[i] : 0F;
            float g = GLITCH_GAIN[i];
            if (goal > g) {
                float up = i == 1 ? 0.04F : 0.35F;
                g = Math.min(goal, g + up);
            } else {
                g = Math.max(goal, g - 0.20F);
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