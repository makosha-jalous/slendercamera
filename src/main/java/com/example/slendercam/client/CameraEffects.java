package com.example.slendercam.client;

import com.example.slendercam.SlenderCamMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
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
    private static final float FALL = 0.03F;
    private static final float MASTER_VOLUME = 0.9F;

    private static float zoomPrev, zoomNow, intensity, flash;
    private static boolean zoomKeyPrev = false;
    private static final float ZOOM_SOUND_VOLUME = 0.5F; // zoom sounds loudness (0..1)

    // HUD distortion state
    private static final RandomSource HUD_RNG = RandomSource.create();
    private static float hudPressure;
    private static float frameVertical;
    private static float frameHorizontal;
    private static float frameVerticalBend;
    private static float frameHorizontalBend;
    private static int frameVerticalHold;
    private static int frameHorizontalHold;
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
        // The existing grain renderer uses this value to become very strong during the burst.
        return 1F;
    }

    public static float teleportSignX() { return teleportSignX; }
    public static float teleportSignY() { return teleportSignY; }

    /** Horizontal displacement of an HUD point caused by the left/right frame group. */
    public static float frameWarpX(float x, float y) {
        float t = Mth.clamp((y - 21F) / (383F - 21F), 0F, 1F);
        float wave = (float) Math.sin(Math.PI * t);
        float leftShift = frameVertical + frameVerticalBend * wave;
        float rightShift = -frameVertical - frameVerticalBend * wave;
        float n = Mth.clamp(x / 739F, 0F, 1F);
        float result = Mth.lerp(n, leftShift, rightShift);
        return result + (teleportGlitchTicks > 0 ? 10F * teleportSignX : 0F);
    }

    /** Vertical displacement of an HUD point caused by the top/bottom frame group. */
    public static float frameWarpY(float x, float y) {
        float t = Mth.clamp((x - 19F) / (739F - 43F), 0F, 1F);
        float wave = (float) Math.sin(Math.PI * t);
        float topShift = frameHorizontal + frameHorizontalBend * wave;
        float bottomShift = -frameHorizontal - frameHorizontalBend * wave;
        float n = Mth.clamp(y / 415F, 0F, 1F);
        float result = Mth.lerp(n, topShift, bottomShift);
        return result + (teleportGlitchTicks > 0 ? 8F * teleportSignY : 0F);
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
                    // force both frame groups into a violent, synchronized displacement
                    frameVerticalHold = 0;
                    frameHorizontalHold = 0;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.TELEPORT.get(), 1.0F, 1.0F));
                }
            }
        } else {
            lastId = null;
            lookTicks = Math.max(0, lookTicks - 4);
        }

        float target = 0F;
        if (cam && observed && dist < RANGE) {
            float prox = 1F - (float) Mth.clamp((dist - 3.0) / (RANGE - 3.0), 0.0, 1.0);
            target = Math.max(0.10F, (float) Math.pow(prox, 1.6));
        }
        intensity += (target - intensity) * (target > intensity ? RISE : FALL);
        if (!cam) intensity *= 0.8F;
        if (intensity < 0.002F) intensity = 0F;

        float targetHud = 0F;
        if (cam && observed && dist < RANGE + 32) {
            float proxHud = 1F - (float) Mth.clamp((dist - 2.5) / 55.0, 0.0, 1.0);
            float gaze = gazePressure();
            // Starts visibly as soon as Slender is watched; proximity and gaze then push it hard.
            targetHud = Mth.clamp(0.16F + proxHud * 0.58F + gaze * 0.42F, 0F, 1F);
        }
        hudPressure += (targetHud - hudPressure) * (targetHud > hudPressure ? 0.22F : 0.08F);
        if (!cam) hudPressure *= 0.72F;
        if (hudPressure < 0.002F) hudPressure = 0F;

        updateHudDistortion(cam);
        manageSounds(mc);
    }

    private static void updateHudDistortion(boolean cam) {
        // Calm state: no Slenderman in view (pressure ~0) and no teleport glitch.
        // The frame must be perfectly STATIC, so every distortion value is zeroed.
        if (!cam || (hudPressure < 0.01F && teleportGlitchTicks <= 0)) {
            frameVertical = 0F;
            frameHorizontal = 0F;
            frameVerticalBend = 0F;
            frameHorizontalBend = 0F;
            frameVerticalHold = 0;
            frameHorizontalHold = 0;
            return;
        }

        float p = Mth.clamp(hudPressure, 0F, 1F);
        float tg = teleportGlitch();

        if (frameVerticalHold > 0) frameVerticalHold--;
        if (frameHorizontalHold > 0) frameHorizontalHold--;

        int minHold = Math.max(10, Math.round(60F - 42F * p));
        int maxHold = Math.max(minHold + 1, Math.round(180F - 105F * p));

        // Fade the distortion in smoothly so the frame never twitches at very low pressure.
        float fade = Mth.clamp(p / 0.12F, 0F, 1F);
        float spread = (1.5F + 12F * p + 16F * p * p) * fade;
        float bend = (0.8F + 5F * p + 10F * p * p) * fade;

        boolean vExpired = frameVerticalHold <= 0;
        boolean hExpired = frameHorizontalHold <= 0;
        boolean together = (vExpired || hExpired)
                && HUD_RNG.nextFloat() < (0.25F + 0.35F * p);

        // Usually the two groups work independently. Sometimes they snap together.
        if (together || (vExpired && hExpired)) {
            frameVertical = signedKick(spread);
            frameVerticalBend = signedKick(bend);
            frameHorizontal = signedKick(spread * 0.82F);
            frameHorizontalBend = signedKick(bend * 0.90F);
            int holdV = minHold + HUD_RNG.nextInt(Math.max(1, maxHold - minHold + 1));
            int holdH = minHold + HUD_RNG.nextInt(Math.max(1, maxHold - minHold + 1));
            frameVerticalHold = holdV;
            frameHorizontalHold = holdH;
        } else {
            if (vExpired) {
                frameVertical = signedKick(spread);
                frameVerticalBend = signedKick(bend);
                frameVerticalHold = minHold + HUD_RNG.nextInt(Math.max(1, maxHold - minHold + 1));
            }
            if (hExpired) {
                frameHorizontal = signedKick(spread * 0.82F);
                frameHorizontalBend = signedKick(bend * 0.90F);
                frameHorizontalHold = minHold + HUD_RNG.nextInt(Math.max(1, maxHold - minHold + 1));
            }
        }

        if (tg > 0F) {
            // Teleport: the two groups are forced into one violent pose for the whole one-second
            // grain burst. When the timer reaches zero, this block disappears on the next tick.
            frameVertical = 34F * teleportSignX;
            frameHorizontal = 28F * teleportSignY;
            frameVerticalBend = 19F * teleportSignX;
            frameHorizontalBend = 17F * teleportSignY;
            frameVerticalHold = 1;
            frameHorizontalHold = 1;
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

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!CameraHud.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        float bobX = 0;
        float bobY = 0;

        // ---- ИМИТАЦИЯ ЖИВОГО ДЕРЖАНИЯ КАМЕРЫ И ДИНАМИКА БЕГА ----
        if (mc.player.isSprinting()) {
            // При беге: размашистое, видимое покачивание влево-вправо (синусоида бега)
            bobX = (float) Math.sin(now * 0.008) * 1.8F;
            bobY = (float) Math.abs(Math.cos(now * 0.008)) * 0.9F;
        } else if (mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.001) {
            // При обычной ходьбе: среднее покачивание
            bobX = (float) Math.sin(now * 0.005) * 0.8F;
            bobY = (float) Math.abs(Math.cos(now * 0.005)) * 0.4F;
        } else {
            // В покое: медленное волнообразное дыхание живого человека
            bobX = (float) Math.sin(now * 0.002) * 0.25F;
            bobY = (float) Math.cos(now * 0.0015) * 0.15F;
        }

        // Базовая тряска от страха Слендера
        float amp = intensity * 0.5F + flash * 3F;
        
        // Объединяем живые покачивания рук с цифровыми помехами Слендера
        e.setRoll(e.getRoll() + bobX + (amp * 1.6F * noise(21)));
        e.setYaw(e.getYaw() + (bobX * 0.5F) + (amp * 0.5F * noise(22)));
        e.setPitch(e.getPitch() + bobY + (amp * 0.5F * noise(23)));
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        if (!CameraHud.active() || !e.usedConfiguredFov()) return;
        float z = zoom((float) e.getPartialTick());
        if (z > 0.001F) {
            e.setFOV(e.getFOV() / (1.0 + (ZOOM_MAX - 1.0) * z));
        }
    }
}
