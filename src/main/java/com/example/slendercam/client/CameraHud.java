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

        // The normal camera grain is the teleport effect too. No artificial scanline/band overlay:
        // during a teleport the existing grain simply becomes brutally stronger for ~1 second.
        drawGrain(ps, w, h, gi, tg);
        drawVignette(ps, w, h);

        float s = h / (float) REF_H;
        int refW = Math.round(w / s);
        boolean blinkOn = (System.currentTimeMillis() / BLINK_MS) % 2 == 0;

        // One common HUD transform. This is deliberately small: the important distortion comes
        // from the frame deformation field below, which is also applied to every HUD element.
        float shove = hp * 1.8F + tg * 10F;
        float gx = shove * CameraEffects.noise(11);
        float gy = shove * 0.75F * CameraEffects.noise(12);

        ps.pushPose();
        ps.scale(s, s, 1F);
        ps.translate(gx, gy, 0F);

        drawWarpedFrame(ps, refW, hp, tg);

        // Every HUD element samples the SAME deformation field as the frame.
        // This is the important part: battery/REC/timer/zoom/crosshair are not separate random shakes.
        drawBatteryWarped(ps, hp, tg);
        drawTopBarWarped(ps, refW, zoom, hp, tg);
        drawRecWarped(ps, mc, refW, blinkOn, hp, tg);
        drawTimerWarped(ps, mc, refW, hp, tg);
        drawCrossWarped(ps, refW, hp, tg);

        ps.popPose();
    }

    /**
     * The frame is ONE closed shape. Its four corners are moved first (the whole rectangle shifts
     * as a group), then each edge is drawn as a continuous line that starts and ends exactly on
     * those shared corners (bend = sin() bulge, zero at the corners). So the frame can bulge and
     * jerk, but the sides never separate from each other and the lines are never broken.
     * In the calm state all distortion values are 0 and the frame is perfectly static.
     */
    private static void drawWarpedFrame(PoseStack ps, int refW, float hp, float tg) {
        int left = 19;
        int top = 21;
        int right = refW - 24;
        int bottom = 383;

        int teleportExtra = Math.round(tg * 18F);
        float v = CameraEffects.frameVertical() + teleportExtra * CameraEffects.teleportSignX();
        float h = CameraEffects.frameHorizontal() + teleportExtra * CameraEffects.teleportSignY();
        float vb = CameraEffects.frameVerticalBend() + tg * 16F * CameraEffects.teleportSignX();
        float hb = CameraEffects.frameHorizontalBend() + tg * 14F * CameraEffects.teleportSignY();

        // Shared corners (every edge uses these same four points).
        int xl = Math.round(left + v);
        int xr = Math.round(right - v);
        int yt = Math.round(top + h);
        int yb = Math.round(bottom - h);

        // Safety: never let the shape fold over itself.
        if (xr - xl < 40) { int m = (xl + xr) / 2; xl = m - 20; xr = m + 20; }
        if (yb - yt < 40) { int m = (yt + yb) / 2; yt = m - 20; yb = m + 20; }

        int wPx = xr - xl;
        int hPx = yb - yt;

        // Top and bottom edges: one pixel column at a time, no gaps between columns.
        int prevTop = yt, prevBottom = yb;
        for (int x = xl; x <= xr; x++) {
            float n = (x - xl) / (float) Math.max(1, wPx);
            float wave = (float) Math.sin(Math.PI * n);
            int cyTop = Math.round(yt + hb * wave);
            int cyBottom = Math.round(yb - hb * wave);
            if (x == xl) { prevTop = cyTop; prevBottom = cyBottom; }
            fillSpanY(ps, x, prevTop, cyTop, x == xl);
            fillSpanY(ps, x, prevBottom, cyBottom, x == xl);
            prevTop = cyTop;
            prevBottom = cyBottom;
        }

        // Left and right edges: one pixel row at a time (corner rows belong to top/bottom edges).
        int prevLeft = xl, prevRight = xr;
        for (int y = yt + 1; y <= yb - 1; y++) {
            float n = (y - yt) / (float) Math.max(1, hPx);
            float wave = (float) Math.sin(Math.PI * n);
            int cxLeft = Math.round(xl + vb * wave);
            int cxRight = Math.round(xr - vb * wave);
            fillSpanX(ps, y, prevLeft, cxLeft, y == yt + 1);
            fillSpanX(ps, y, prevRight, cxRight, y == yt + 1);
            prevLeft = cxLeft;
            prevRight = cxRight;
        }
    }

    /** Fills the pixels of one column between the previous row and the new row (no overlaps, no gaps). */
    private static void fillSpanY(PoseStack ps, int x, int prevY, int y, boolean first) {
        int a, b;
        if (first || y == prevY) { a = y; b = y; }
        else if (y > prevY) { a = prevY + 1; b = y; }
        else { a = y; b = prevY - 1; }
        GuiComponent.fill(ps, x, a, x + 1, b + 1, FRAME);
    }

    /** Same as fillSpanY, but for one row of the vertical edges. */
    private static void fillSpanX(PoseStack ps, int y, int prevX, int x, boolean first) {
        int a, b;
        if (first || x == prevX) { a = x; b = x; }
        else if (x > prevX) { a = prevX + 1; b = x; }
        else { a = x; b = prevX - 1; }
        GuiComponent.fill(ps, a, y, b + 1, y + 1, FRAME);
    }

    private static void drawBatteryWarped(PoseStack ps, float hp, float tg) {
        pushFieldWarp(ps, 32F, 45F, hp, tg, 0.82F);
        drawBattery(ps);
        ps.popPose();
    }

    private static void drawTopBarWarped(PoseStack ps, int refW, float zoom, float hp, float tg) {
        float cx = refW * 0.5F;
        pushFieldWarp(ps, cx, 38F, hp, tg, 0.90F);
        drawTopBar(ps, refW, zoom);
        ps.popPose();
    }

    private static void drawRecWarped(PoseStack ps, Minecraft mc, int refW, boolean blinkOn, float hp, float tg) {
        int right = refW - 34;
        float recSx = 2.4F, recSy = 2.7F;
        float recW = mc.font.width("REC") * recSx;
        float recX = right - recW;

        pushFieldWarp(ps, right - 35F, 43F, hp, tg, 0.92F);
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
    }

    private static void drawTimerWarped(PoseStack ps, Minecraft mc, int refW, float hp, float tg) {
        int right = refW - 34;
        int secs = recTicks / 20;
        String time = String.format("%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        float tSx = 1.35F, tSy = 1.7F;
        float tW = mc.font.width(time) * tSx;

        pushFieldWarp(ps, right - 34F, 64F, hp, tg, 0.88F);
        ps.pushPose();
        ps.translate(right - tW, 59, 0);
        ps.scale(tSx, tSy, 1F);
        mc.font.draw(ps, time, 0, 0, 0xFFDCDCDC);
        ps.popPose();
        ps.popPose();
    }

    private static void drawCrossWarped(PoseStack ps, int refW, float hp, float tg) {
        int mx = refW / 2, my = REF_H / 2;
        pushFieldWarp(ps, mx, my, hp, tg, 1.0F);
        int c = 0x60FFFFFF;

        // The cross follows the same field as the HUD, with only a tiny extra optical jitter.
        int extraX = Math.round((hp * 1.5F + tg * 7F) * CameraEffects.noise(84));
        int extraY = Math.round((hp * 1.2F + tg * 6F) * CameraEffects.noise(85));
        GuiComponent.fill(ps, mx - 15 + extraX, my + extraY, mx + 16 + extraX, my + extraY + 1, c);
        GuiComponent.fill(ps, mx + extraX, my - 15 + extraY, mx + extraX + 1, my + 16 + extraY, c);
        ps.popPose();
    }

    private static void pushFieldWarp(PoseStack ps, float x, float y, float hp, float tg, float strength) {
        ps.pushPose();
        float dx = CameraEffects.frameWarpX(x, y) * strength;
        float dy = CameraEffects.frameWarpY(x, y) * strength;

        // A little scale/shear-like optical response, but driven by the same frame groups.
        float sx = 1F + CameraEffects.frameWarpX(x, y) * 0.0012F
                + tg * 0.025F * CameraEffects.noise(91);
        float sy = 1F + CameraEffects.frameWarpY(x, y) * 0.0012F
                + tg * 0.022F * CameraEffects.noise(92);
        ps.translate(x + dx, y + dy, 0F);
        ps.scale(sx, sy, 1F);
        ps.translate(-x, -y, 0F);
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
        float alpha = GRAIN_ALPHA + 0.30F * gi;
        if (fl > 0F) {
            // Teleport uses the same camera grain, simply pushed to an almost opaque burst.
            alpha = 0.90F + 0.07F * RND.nextFloat();
        } else if (gi > 0.15F) {
            alpha *= 0.78F + 0.42F * RND.nextFloat();
        }
        alpha = Math.min(0.97F, alpha);

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
