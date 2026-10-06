package com.example.slendercam.client;

import com.example.slendercam.SlenderCamMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

/**
 * Slender: The Arrival style camcorder HUD, laid out on a 739x415 reference grid measured from the
 * reference screenshot and scaled to the current screen height. Shown while the camera item is in
 * either hand (the camera itself is invisible in the hand).
 */
@Mod.EventBusSubscriber(modid = SlenderCamMod.ID, value = Dist.CLIENT)
public class CameraHud {

    // ---- tweakables
    private static final float GRAIN_ALPHA = 0.20F;      // film grain strength (0..1)
    private static final int GRAIN_PIXEL_SIZE = 1;       // 1 = one grain dot per screen pixel (finest)
    private static final int BLINK_MS = 500;             // REC text blink interval

    private static final int WHITE = 0xB4FFFFFF;
    private static final int FRAME = 0x70FFFFFF;
    private static final int RED = 0xFFE02020;

    private static final int REF_H = 415;
    private static final int TEX = 512;
    private static final Random RND = new Random();

    private static int recTicks = 0;
    private static DynamicTexture grain;
    private static ResourceLocation grainLoc;
    private static long lastNoise = 0;

    static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
                && mc.options.getCameraType().isFirstPerson()
                && (mc.player.getMainHandItem().is(SlenderCamMod.CAMERA.get())
                || mc.player.getOffhandItem().is(SlenderCamMod.CAMERA.get()));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END && active()) recTicks++;
    }

    @SubscribeEvent
    public static void hideCrosshair(RenderGuiOverlayEvent.Pre e) {
        if (active() && e.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id())) {
            e.setCanceled(true);
        }
    }

    /** The camera itself is invisible in the hand (like in Slender): only the interface is shown. */
    @SubscribeEvent
    public static void hideCameraInHand(RenderHandEvent e) {
        if (e.getItemStack().is(SlenderCamMod.CAMERA.get())) {
            e.setCanceled(true);
        }
    }

    public static void render(ForgeGui gui, PoseStack ps, float partialTick, int w, int h) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();

        float gi = CameraEffects.intensity();
        float hp = CameraEffects.hudPressure();
        float tg = CameraEffects.teleportGlitch();
        float zoom = CameraEffects.zoom(partialTick);

        // Teleport distortion deliberately does NOT use the old decaying flash value.
        // The heavy burst lasts ~1 second, then snaps back immediately.
        drawGrain(ps, w, h, gi, tg);
        drawVignette(ps, w, h);
        drawTears(ps, w, h, Math.max(gi, hp), tg);
        if (tg > 0F) drawTeleportStatic(ps, w, h, tg);

        float s = h / (float) REF_H;
        int refW = Math.round(w / s);
        boolean blinkOn = (System.currentTimeMillis() / BLINK_MS) % 2 == 0;

        // Whole HUD gets a common digital shove. Ordinary stalking is visible but controlled;
        // teleport is a brutal one-second burst.
        float shove = hp * 3.0F + tg * 22F;
        float gx = shove * CameraEffects.noise(11);
        float gy = shove * 0.75F * CameraEffects.noise(12);

        ps.pushPose();
        ps.scale(s, s, 1F);
        ps.translate(gx, gy, 0F);

        // Global elastic distortion affects frame AND every UI element.
        // It is intentionally asymmetric so the HUD feels bent rather than merely translated.
        float stretchX = 1F + hp * 0.010F * CameraEffects.noise(31)
                + tg * 0.075F * CameraEffects.noise(33);
        float stretchY = 1F + hp * 0.009F * CameraEffects.noise(32)
                + tg * 0.060F * CameraEffects.noise(34);
        ps.translate(refW * 0.5F, REF_H * 0.5F, 0F);
        ps.scale(stretchX, stretchY, 1F);
        ps.translate(-refW * 0.5F, -REF_H * 0.5F, 0F);

        // The frame itself is not four independently shaking lines anymore.
        // Vertical sides are one mirrored group, horizontals are another.
        drawWarpedFrame(ps, refW, hp, tg);

        // Each element has a small local warp on top of the common HUD distortion.
        pushElementWarp(ps, 32F, 45F, hp, tg, 41);
        drawBattery(ps);
        ps.popPose();

        pushElementWarp(ps, refW * 0.5F, 38F, hp, tg, 51);
        drawTopBar(ps, refW, zoom);
        ps.popPose();

        int right = refW - 34;
        float recSx = 2.4F, recSy = 2.7F;
        float recW = mc.font.width("REC") * recSx;
        float recX = right - recW;
        pushElementWarp(ps, right - 35F, 46F, hp, tg, 61);
        if (blinkOn) {
            ps.pushPose();
            ps.translate(recX, 34, 0);
            ps.scale(recSx, recSy, 1F);
            mc.font.draw(ps, "REC", 0, 0, RED);
            ps.popPose();
        }
        int cx = Math.round(recX) - 19, cy = 43;
        for (int dy = -7; dy <= 7; dy++) {
            int dx = (int) Math.round(Math.sqrt(7.5 * 7.5 - dy * dy));
            GuiComponent.fill(ps, cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, RED);
        }
        ps.popPose();

        int secs = recTicks / 20;
        String time = String.format("%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        float tSx = 1.35F, tSy = 1.7F;
        float tW = mc.font.width(time) * tSx;
        pushElementWarp(ps, right - 34F, 64F, hp, tg, 71);
        ps.pushPose();
        ps.translate(right - tW, 59, 0);
        ps.scale(tSx, tSy, 1F);
        mc.font.draw(ps, time, 0, 0, 0xFFDCDCDC);
        ps.popPose();
        ps.popPose();

        // Center cross is deliberately distorted independently; in the reference it does not feel
        // perfectly glued to the screen during interference.
        int mx = refW / 2, my = REF_H / 2;
        pushElementWarp(ps, mx, my, hp, tg, 81);
        int c = 0x60FFFFFF;
        int crossX = Math.round((hp * 4F + tg * 11F) * CameraEffects.noise(84));
        int crossY = Math.round((hp * 3F + tg * 9F) * CameraEffects.noise(85));
        GuiComponent.fill(ps, mx - 15 - crossX, my + crossY, mx + 16 + crossX, my + crossY + 1, c);
        GuiComponent.fill(ps, mx + crossX, my - 15 - crossY, mx + crossX + 1, my + 16 + crossY, c);
        ps.popPose();

        ps.popPose();
    }

    private static void drawWarpedFrame(PoseStack ps, int refW, float hp, float tg) {
        int fl0 = 19, ft0 = 21, fr0 = refW - 24, fb0 = 383;
        float v = CameraEffects.frameVertical();
        float h = CameraEffects.frameHorizontal();
        float vBend = CameraEffects.frameVerticalBend();
        float hBend = CameraEffects.frameHorizontalBend();

        int segments = 18;

        // Top and bottom: same group, mirrored expansion/compression plus a bent segmented contour.
        for (int i = 0; i < segments; i++) {
            float a = i / (float) segments;
            float b = (i + 1) / (float) segments;
            int x0 = Math.round(fl0 + (fr0 - fl0) * a);
            int x1 = Math.round(fl0 + (fr0 - fl0) * b) + 1;
            float wave = frameWave(a, 101);
            float local = hBend * wave + (hp * 2F + tg * 8F) * CameraEffects.noise(110 + i);
            int topY = Math.round(ft0 + h + local);
            int botY = Math.round(fb0 - h - local);
            GuiComponent.fill(ps, x0, topY, x1, topY + 1, FRAME);
            GuiComponent.fill(ps, x0, botY, x1, botY + 1, FRAME);
        }

        // Left and right: one mirrored vertical group.
        for (int i = 0; i < segments; i++) {
            float a = i / (float) segments;
            float b = (i + 1) / (float) segments;
            int y0 = Math.round(ft0 + (fb0 - ft0) * a);
            int y1 = Math.round(ft0 + (fb0 - ft0) * b) + 1;
            float wave = frameWave(a, 201);
            float local = vBend * wave + (hp * 2F + tg * 9F) * CameraEffects.noise(210 + i);
            int leftX = Math.round(fl0 + v + local);
            int rightX = Math.round(fr0 - v - local);
            GuiComponent.fill(ps, leftX, y0, leftX + 1, y1, FRAME);
            GuiComponent.fill(ps, rightX, y0, rightX + 1, y1, FRAME);
        }
    }

    private static float frameWave(float t, int seed) {
        // Broad bow + digital irregularity. Ends stay calmer than the middle so it reads as bending.
        float arch = (float) Math.sin(t * Math.PI);
        float jag = 0.55F * CameraEffects.noise(seed + (int) (t * 7F));
        return arch * (0.65F + jag);
    }

    private static void pushElementWarp(PoseStack ps, float anchorX, float anchorY, float hp, float tg, int seed) {
        ps.pushPose();
        float amount = hp * 2.2F + tg * 14F;
        float dx = amount * CameraEffects.noise(seed);
        float dy = amount * 0.7F * CameraEffects.noise(seed + 1);
        float sx = 1F + hp * 0.012F * CameraEffects.noise(seed + 2)
                + tg * 0.070F * CameraEffects.noise(seed + 3);
        float sy = 1F + hp * 0.010F * CameraEffects.noise(seed + 4)
                + tg * 0.055F * CameraEffects.noise(seed + 5);
        ps.translate(anchorX + dx, anchorY + dy, 0F);
        ps.scale(sx, sy, 1F);
        ps.translate(-anchorX, -anchorY, 0F);
    }

    private static void drawTeleportStatic(PoseStack ps, int w, int h, float tg) {
        // Near-opaque full-screen television snow, similar to the supplied reference clip.
        int veil = Math.min(225, 120 + Math.round(tg * 100F));
        GuiComponent.fill(ps, 0, 0, w, h, (veil << 24) | 0x101010);

        long step = System.currentTimeMillis() / 18L;
        Random rr = new Random(step * 7919L);
        int bands = 28 + Math.round(tg * 38F);
        for (int i = 0; i < bands; i++) {
            int y = rr.nextInt(Math.max(1, h));
            int bh = 1 + rr.nextInt(Math.max(2, 2 + Math.round(13F * tg)));
            int shade = 110 + rr.nextInt(146);
            int alpha = 40 + rr.nextInt(100);
            int col = (alpha << 24) | (shade << 16) | (shade << 8) | shade;
            GuiComponent.fill(ps, 0, y, w, Math.min(h, y + bh), col);
        }
    }

    /** Battery is always full: horizontal, checkerboard fill, nub on the right. */
    private static void drawBattery(PoseStack ps) {
        int x = 32, y = 33, bw = 58, bh = 24;
        GuiComponent.fill(ps, x, y, x + bw, y + 1, WHITE);
        GuiComponent.fill(ps, x, y + bh - 1, x + bw, y + bh, WHITE);
        GuiComponent.fill(ps, x, y, x + 1, y + bh, WHITE);
        GuiComponent.fill(ps, x + bw - 1, y, x + bw, y + bh, WHITE);
        GuiComponent.fill(ps, x + bw, y + 7, x + bw + 4, y + 17, WHITE);
        int inner = bw - 6, cell = 3;
        int cols = (inner + cell - 1) / cell;
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < 6; j++) {
                if (((i + j) & 1) != 0) continue;
                int cx0 = x + 3 + i * cell;
                int cy0 = y + 3 + j * cell + 1;
                int cx1 = Math.min(cx0 + cell, x + 3 + inner);
                GuiComponent.fill(ps, cx0, cy0, cx1, cy0 + cell, WHITE);
            }
        }
    }

    /** Long dotted bar at the top center; the solid marker slides along it as the zoom grows. */
    private static void drawTopBar(PoseStack ps, int refW, float zoom) {
        int cx = refW / 2;
        int x0 = cx - 80, x1 = cx + 80, y0 = 33, y1 = 42;
        for (int x = x0; x < x1; x += 2) {
            GuiComponent.fill(ps, x, y0, x + 1, y0 + 1, WHITE);
            GuiComponent.fill(ps, x, y1, x + 1, y1 + 1, WHITE);
        }
        for (int y = y0; y <= y1; y += 2) {
            GuiComponent.fill(ps, x0, y, x0 + 1, y + 1, WHITE);
            GuiComponent.fill(ps, x1, y, x1 + 1, y + 1, WHITE);
        }
        int mw = 10;
        int mx = x0 + 2 + Math.round(zoom * (x1 - x0 - 4 - mw));
        GuiComponent.fill(ps, mx, y0 + 1, mx + mw, y1, WHITE);
    }

    private static void drawVignette(PoseStack ps, int w, int h) {
        gradient(ps, w, h, (int) (h * 0.16F), 0x78, true);
        gradient(ps, w, h, (int) (h * 0.30F), 0xB4, false);
    }

    private static void gradient(PoseStack ps, int w, int h, int band, int maxAlpha, boolean top) {
        int steps = 20;
        int step = Math.max(1, band / steps);
        for (int i = 0; i < steps; i++) {
            int a = (int) (maxAlpha * (1F - i / (float) steps));
            int col = a << 24;
            if (top) GuiComponent.fill(ps, 0, i * step, w, (i + 1) * step, col);
            else GuiComponent.fill(ps, 0, h - (i + 1) * step, w, h - i * step, col);
        }
    }

    /** Rolling horizontal static bands; more of them the angrier the camera gets. */
    private static void drawTears(PoseStack ps, int w, int h, float gi, float fl) {
        int n = (int) (gi * 5F + fl * 16F);
        if (n <= 0) return;
        long step = System.currentTimeMillis() / 60;
        float lvl = Math.min(1F, gi + fl);
        for (int k = 0; k < n; k++) {
            Random rr = new Random(step * 31L + k * 977L);
            int y = rr.nextInt(Math.max(1, h));
            int bh = 1 + rr.nextInt(Math.max(2, (int) (3 + 8 * lvl)));
            int a = (int) (255 * (0.05F + 0.25F * lvl) * rr.nextFloat());
            GuiComponent.fill(ps, 0, y, w, y + bh, (a << 24) | 0xFFFFFF);
        }
    }

    /**
     * Fine film grain: a 512x512 noise texture re-rolled several times a second (every frame in a flash).
     * Under the glitch it gets stronger, flickers and refreshes faster ("hisses").
     */
    private static void drawGrain(PoseStack ps, int w, int h, float gi, float fl) {
        Minecraft mc = Minecraft.getInstance();
        if (grain == null) {
            grain = new DynamicTexture(TEX, TEX, false);
            grain.setFilter(false, false);
            grainLoc = mc.getTextureManager().register("slendercam_grain", grain);
        }
        long now = System.currentTimeMillis();
        long interval = fl > 0.05F ? 0 : (gi > 0.2F ? 40 : 120);
        if (now - lastNoise > interval) {
            lastNoise = now;
            NativeImage img = grain.getPixels();
            if (img != null) {
                for (int y = 0; y < TEX; y++) {
                    for (int x = 0; x < TEX; x++) {
                        int v = RND.nextInt(256);
                        int a = RND.nextInt(256);
                        img.setPixelRGBA(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                    }
                }
                grain.upload();
            }
        }
        float alpha = GRAIN_ALPHA + 0.30F * gi + 0.78F * fl;
        if (gi > 0.15F || fl > 0F) alpha *= 0.75F + 0.5F * RND.nextFloat();
        alpha = Math.min(1.0F, alpha);

        double g = mc.getWindow().getGuiScale();
        int regionW = Math.max(1, (int) Math.round(w * g / GRAIN_PIXEL_SIZE));
        int regionH = Math.max(1, (int) Math.round(h * g / GRAIN_PIXEL_SIZE));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
        RenderSystem.setShaderTexture(0, grainLoc);
        GuiComponent.blit(ps, 0, 0, w, h, RND.nextInt(TEX), RND.nextInt(TEX),
                regionW, regionH, TEX, TEX);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
    }
}
