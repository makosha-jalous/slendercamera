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

    // ---- tweakables (all the "feel" numbers of this update live here) ----
    /** How fast glitch + sounds die out after looking away. 0.03 = about 5 seconds. */
    private static final float FADE_OUT = 0.03F;
    /** Seconds of continuous looking at Slenderman until the "creeping worse" part is at its maximum. */
    private static final float EXPOSURE_SECONDS_UP = 90F;
    /** Seconds it takes the accumulated "worse and worse" to go away when he is not watched. */
    private static final float EXPOSURE_SECONDS_DOWN = 240F;
    /** How much of the sound/grain intensity the creeping exposure can add (even at far distance). */
    private static final float EXPOSURE_INTENSITY = 0.45F;
    /** How much of the HUD distortion the creeping exposure can add. */
    private static final float EXPOSURE_HUD = 0.30F;
    /** Short glitch sounds only play while intensity is below this (far / mid distance). */
    private static final float GLITCH_SOUND_MAX = 0.65F;
    /** Pause between short glitch sounds, in ticks (20 ticks = 1 second): 60..100 = 3..5 seconds. */
    private static final int GLITCH_PAUSE_MIN = 60;
    private static final int GLITCH_PAUSE_RANDOM = 41;
    /** Peak shake of the player's VIEW in degrees at maximum intensity (small, but very fast). */
    private static final float VIEW_SHAKE_DEG = 0.9F;
    /** Peak wobble of the field of view (FOV) in degrees at maximum intensity. */
    private static final float VIEW_FOV_WOBBLE = 1.0F;

    private static float zoomPrev, zoomNow, intensity, flash;
    private static boolean zoomKeyPrev = false;
    private static final float ZOOM_SOUND_VOLUME = 0.5F; // zoom sounds loudness (0..1)

    // HUD distortion state
    private static final RandomSource HUD_RNG = RandomSource.create();
    private static float hudPressure;
    private static float exposure;               // 0..1, slowly creeps up while he is watched
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
    private static int teleportGlitchTicks;
    private static float teleportSignX = 1F, teleportSignY = 1F;
    private static UUID lastId;
    private static final Vec3[] HIST = new Vec3[4];
    private static int histIdx, jumpCooldown;
    private static StaticLoop far, mid, rage;
    private static BreathingLoop breathingLoop;

    public static float intensity() { return intensity; }
    public static float flash() { return flash; }
    public static float zoom(float pt) { return Mth.lerp(pt, zoomPrev, zoomNow); }
    public static float hudPressure() { return hudPressure; }
    public static float frameVertical() { return frameVertical; }
    public static float frameHorizontal() { return frameHorizontal; }
    public static float frameVerticalBend() { return frameVerticalBend; }
    public static float frameHorizontalBend() { return frameHorizontalBend; }
    public static float gazePressure() { return Mth.clamp(lookTicks / 80.0F, 0F, 1F); }
    public static float teleportGlitch() {
        if (teleportGlitchTicks <= 0) return 0F;
        // Hard one-second gate. It is intentionally almost constant and then stops abruptly.
        return 1F;
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
        if (teleportGlitchTicks > 0) teleportGlitchTicks--;

        if (mc.player == null || mc.level == null) {
            intensity = 0F;
            hudPressure = 0F;
            lookTicks = 0;
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
            if (old != null && jumpCooldown == 0 && pos.distanceTo(old) > 7.0) {
                jumpCooldown = 30;
                if (cam && (observed || dist < 18)) {
                    teleportSignX = HUD_RNG.nextBoolean() ? 1F : -1F;
                    teleportSignY = HUD_RNG.nextBoolean() ? 1F : -1F;
                    teleportGlitchTicks = 20; // exactly ~1 second at 20 TPS
                    baseVHold = 0;
                    baseHHold = 0;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.TELEPORT.get(), 1.0F, 1.0F));
                }
            }
        } else {
            lastId = null;
            lookTicks = Math.max(0, lookTicks - 4);
        }

        // "Worse and worse": while he is watched (at ANY distance) this creeps up slowly and surely.
        // It also fades slowly when he is not watched.
        if (cam && observed) exposure = Math.min(1F, exposure + 1F / (20F * EXPOSURE_SECONDS_UP));
        else exposure = Math.max(0F, exposure - 1F / (20F * EXPOSURE_SECONDS_DOWN));

        float target = 0F;
        if (cam && observed && dist < RANGE) {
            float prox = 1F - (float) Mth.clamp((dist - 3.0) / (RANGE - 3.0), 0.0, 1.0);
            float base = Math.max(0.10F, (float) Math.pow(prox, 1.6));
            target = Mth.clamp(base + EXPOSURE_INTENSITY * exposure * (1F - base), 0F, 1F);
        }
        intensity += (target - intensity) * (target > intensity ? RISE : FADE_OUT);
        if (!cam) intensity *= 0.8F;
        if (intensity < 0.002F) intensity = 0F;

        float targetHud = 0F;
        if (cam && observed && dist < RANGE + 32) {
            float proxHud = 1F - (float) Mth.clamp((dist - 2.5) / 55.0, 0.0, 1.0);
            float gaze = gazePressure();
            targetHud = Mth.clamp(0.16F + proxHud * 0.58F + gaze * 0.42F + exposure * EXPOSURE_HUD, 0F, 1F);
        }
        // Rises fast, but fades slowly (about 5 seconds) after looking away.
        hudPressure += (targetHud - hudPressure) * (targetHud > hudPressure ? 0.22F : FADE_OUT);
        if (!cam) hudPressure *= 0.72F;
        if (hudPressure < 0.002F) hudPressure = 0F;

        updateSlowPose(cam);
        manageSounds(mc);
        manageGlitchSounds(mc, cam);
    }

    /** Slow part of the frame pose (changes every 0.3 - 2 seconds, then gets smoothed every frame). */
    private static void updateSlowPose(boolean cam) {
        if (!cam || (hudPressure < 0.01F && teleportGlitchTicks <= 0)) {
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
     */
    public static void frameUpdate() {
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0L ? 0.016F : Mth.clamp((now - lastFrameMs) / 1000F, 0F, 0.1F);
        lastFrameMs = now;

        if (teleportGlitchTicks > 0) {
            // Teleport: both groups are forced into one violent pose for the whole one-second burst.
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

    static float layerVolume(int layer) {
        float i = intensity;
        float v;
        switch (layer) {
            case 0 -> v = Mth.clamp(i / 0.12F, 0F, 1F) * (1F - smooth(0.35F, 0.70F, i)) * 0.7F;
            case 1 -> v = smooth(0.20F, 0.50F, i) * (1F - smooth(0.65F, 0.90F, i));
            default -> v = smooth(0.60F, 0.95F, i);
        }
        return v * MASTER_VOLUME;
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
            if (intensity < 0.005F && this.volume < 0.002F) {
                this.stop();
            }
        }
    }

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
            if (intensity > 0.05F) {
                float targetVol = intensity * intensity * 1.1F * MASTER_VOLUME;
                this.volume += (targetVol - this.volume) * 0.1F;
            } else {
                this.volume *= 0.88F;
                if (this.volume < 0.002F) {
                    this.stop();
                }
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
        if (intensity > 0.02F) {
            SoundManager sm = mc.getSoundManager();
            far = ensure(far, SlenderCamMod.STATIC_FAR.get(), 0, sm);
            mid = ensure(mid, SlenderCamMod.STATIC_MID.get(), 1, sm);
            rage = ensure(rage, SlenderCamMod.STATIC_RAGE.get(), 2, sm);

            if (intensity > 0.05F && (breathingLoop == null || breathingLoop.isStopped())) {
                breathingLoop = new BreathingLoop(SlenderCamMod.BREATHING.get());
                sm.play(breathingLoop);
            }
        }
    }

    /**
     * Short glitch sounds (shortglitch*.ogg): one random file every 3-5 seconds while the distortion
     * is at far/mid level. They keep playing (quieter and quieter) while the glitch fades out after
     * looking away, because the volume follows the fading intensity.
     */
    private static void manageGlitchSounds(Minecraft mc, boolean cam) {
        boolean active = cam && intensity > 0.02F && intensity < GLITCH_SOUND_MAX;
        if (!active) {
            // first sound comes at least 1 second after the glitch starts
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

    /**
     * Shaking of the heroine's VIEW (not of the interface): very fast, strong, but small in amplitude.
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

        // View shake: follows intensity AND the HUD pressure, so it also fades out with the glitch.
        float level = Math.max(intensity, hudPressure * 0.85F);
        float amp = 0F;
        if (level > 0.002F) {
            amp = (0.12F + 0.78F * level) * Mth.clamp(level / 0.08F, 0F, 1F) * VIEW_SHAKE_DEG;
        }
        if (teleportGlitchTicks > 0) amp += 1.2F;
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
        // tiny, fast wobble of the field of view itself
        float level = Math.max(intensity, hudPressure * 0.85F);
        if (level > 0.002F) {
            fov += VIEW_FOV_WOBBLE * level * Mth.clamp(level / 0.08F, 0F, 1F) * jitter(24);
        }
        e.setFOV(fov);
    }
}
