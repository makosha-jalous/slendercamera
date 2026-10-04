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

/**
 * Camera reactions to Slenderman (found by his registry name "slenderman:slenderman", so the two mods
 * need no code link): while you look at him the frame, picture and grain shake and hiss, stronger the
 * closer he is, and fade slowly when you look away. A teleport right in front of you gives a strong
 * one-second distortion and a flash of static. Also handles the smooth zoom.
 */
@Mod.EventBusSubscriber(modid = SlenderCamMod.ID, value = Dist.CLIENT)
public class CameraEffects {

    private static final ResourceLocation SLENDER = new ResourceLocation("slenderman", "slenderman");

    // ======== tweakables ========
    private static final float ZOOM_MAX = 3.5F;            // zoom factor with the key held
    private static final float ZOOM_SPEED = 0.14F;         // smoothing per tick (lower = slower)
    private static final double SEEN_COS = 0.55;           // view cone that counts as "looking at him"
    private static final double RANGE = 48.0;              // effects reach this far
    private static final float RISE = 0.08F;               // how fast the glitch builds up
    private static final float FALL = 0.03F;               // how slowly it fades when you look away
    private static final float MASTER_VOLUME = 0.9F;       // volume of the static layers

    private static float zoomPrev, zoomNow, intensity, flash;
    private static UUID lastId;
    private static final Vec3[] HIST = new Vec3[4];
    private static int histIdx, jumpCooldown;
    private static StaticLoop far, mid, rage;

    public static float intensity() { return intensity; }
    public static float flash() { return flash; }
    public static float zoom(float pt) { return Mth.lerp(pt, zoomPrev, zoomNow); }

    /** Stepped pseudo-random value in -1..1 (digital jitter that changes ~22 times a second). */
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

        // ---- smooth zoom
        boolean zoomKey = cam && mc.screen == null && ClientModEvents.ZOOM_KEY.isDown();
        zoomPrev = zoomNow;
        zoomNow += ((zoomKey ? 1F : 0F) - zoomNow) * ZOOM_SPEED;
        if (!zoomKey && zoomNow < 0.003F) zoomNow = 0F;

        flash *= 0.90F;
        if (flash < 0.01F) flash = 0F;
        if (jumpCooldown > 0) jumpCooldown--;

        if (mc.player == null || mc.level == null) {
            intensity = 0F;
            return;
        }

        // ---- nearest Slenderman
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

            // teleport = a jump of his position compared with 3 ticks ago (the client interpolates jumps)
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
                    flash = dist < 20 ? 1F : 0.6F;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SlenderCamMod.TELEPORT.get(), 1.0F, 1.0F));
                }
            }
        } else {
            lastId = null;
        }

        // ---- glitch level: stronger when he is close, only while you look at him
        float target = 0F;
        if (cam && observed && dist < RANGE) {
            float prox = 1F - (float) Mth.clamp((dist - 3.0) / (RANGE - 3.0), 0.0, 1.0);
            target = Math.max(0.10F, (float) Math.pow(prox, 1.6));
        }
        intensity += (target - intensity) * (target > intensity ? RISE : FALL);
        if (!cam) intensity *= 0.8F;
        if (intensity < 0.002F) intensity = 0F;

        manageSounds(mc);
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

    // ------------------------------------------------------------ sounds (three layers crossfaded by closeness)

    private static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0F, 1F);
        return t * t * (3F - 2F * t);
    }

    /** simple glitch (far) -> average static (mid) -> deadly static (point blank). */
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
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            this.volume = layerVolume(layer);
            if (intensity < 0.005F && this.volume < 0.002F) {
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
        if (intensity > 0.02F) {
            SoundManager sm = mc.getSoundManager();
            far = ensure(far, SlenderCamMod.STATIC_FAR.get(), 0, sm);
            mid = ensure(mid, SlenderCamMod.STATIC_MID.get(), 1, sm);
            rage = ensure(rage, SlenderCamMod.STATIC_RAGE.get(), 2, sm);
        }
    }

    // ------------------------------------------------------------ picture shake + zoom

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!CameraHud.active()) return;
        float amp = intensity * 0.5F + flash * 3F;
        if (amp < 0.01F) return;
        e.setRoll(e.getRoll() + amp * 1.6F * noise(21));
        e.setYaw(e.getYaw() + amp * 0.5F * noise(22));
        e.setPitch(e.getPitch() + amp * 0.5F * noise(23));
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
