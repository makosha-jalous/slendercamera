package com.example.slendercam.client;

import com.example.slendercam.SlenderCamMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
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
 *
 * The interface itself never shakes or trembles. It only BENDS and BLOATS: the frame is one closed,
 * smooth, anti-aliased line, and every other element (battery, zoom bar, REC, timer, cross) is
 * pushed through exactly the same bending of the frame, so they bend together with it.
 * The shaking of the picture is done in CameraEffects (the heroine's field of view).
 */
@Mod.EventBusSubscriber(modid = SlenderCamMod.ID, value = Dist.CLIENT)
public class CameraHud {

    // ---- tweakables
    private static final float GRAIN_ALPHA = 0.20F;      // film grain strength (0..1)
    private static final int GRAIN_PIXEL_SIZE = 1;       // 1 = one grain dot per screen pixel (finest)
    private static final int BLINK_MS = 500;             // REC text blink interval
    private static final float CELL = 3F;                // bending mesh cell size in reference pixels

    private static final int WHITE = 0xB4FFFFFF;
    private static final int FRAME = 0x70FFFFFF;
    private static final int FRAME_CLEAR = 0x00FFFFFF;
    private static final int RED = 0xFFE02020;
    private static final int TIMER_COLOR = 0xFFDCDCDC;

    private static final int REF_H = 415;
    private static final int TEX = 512;
    private static final Random RND = new Random();

    private static int recTicks = 0;
    private static DynamicTexture grain;
    private static ResourceLocation grainLoc;
    private static long lastNoise = 0;

    // ---- the deformed frame of the current render frame (reference pixels)
    private static float restL, restR, restT, restB;       // calm frame
    private static float defL, defR, defT, defB;           // deformed corners
    private static float vBend, hBend;                     // side / top-bottom bulge
    private static final float[] TMP_A = new float[2];
    private static final float[] TMP_B = new float[2];
    private static final float[] TMP_C = new float[2];

    // ---- one batch of coloured quads
    private static BufferBuilder qb;
    private static Matrix4f qm;

    // ---- frame outline points
    private static final int MAXP = 1400;
    private static final float[] FX = new float[MAXP], FY = new float[MAXP];
    private static final float[] OX = new float[MAXP], OY = new float[MAXP];

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

        // New frame deformation values (fast twitches are computed here, once per rendered frame).
        CameraEffects.frameUpdate();

        float gi = CameraEffects.intensity();
        float tg = CameraEffects.teleportGlitch();
        float zoom = CameraEffects.zoom(partialTick);

        drawGrain(ps, w, h, gi, tg);
        drawVignette(ps, w, h);

        float s = h / (float) REF_H;
        int refW = Math.round(w / s);
        float dpr = s * (float) mc.getWindow().getGuiScale();   // device pixels per reference pixel
        boolean blinkOn = (System.currentTimeMillis() / BLINK_MS) % 2 == 0;

        ps.pushPose();
        ps.scale(s, s, 1F);

        computeFrame(refW, tg);

        // 1) everything made of rectangles + the frame: one batch, one draw call
        beginQuads(ps);
        drawFrameLine(dpr);
        drawBattery();
        drawTopBar(refW, zoom);
        drawRecDot(mc, refW);
        drawCross(refW);
        endQuads();

        // 2) text, letter by letter, every letter follows the bending of the frame
        drawRecText(ps, mc, refW, blinkOn);
        drawTimer(ps, mc, refW);

        ps.popPose();
    }

    // ------------------------------------------------------------------------------------------
    //  Frame deformation
    // ------------------------------------------------------------------------------------------

    private static void computeFrame(int refW, float tg) {
        restL = 19F;
        restR = refW - 24F;
        restT = 21F;
        restB = 383F;

        float teleportExtra = Math.round(tg * 18F);
        float v = CameraEffects.frameVertical() + teleportExtra * CameraEffects.teleportSignX();
        float hz = CameraEffects.frameHorizontal() + teleportExtra * CameraEffects.teleportSignY();
        vBend = CameraEffects.frameVerticalBend() + tg * 16F * CameraEffects.teleportSignX();
        hBend = CameraEffects.frameHorizontalBend() + tg * 14F * CameraEffects.teleportSignY();

        defL = restL + v;
        defR = restR - v;
        defT = restT + hz;
        defB = restB - hz;

        // Safety: never let the shape fold over itself.
        if (defR - defL < 40F) { float m = (defL + defR) * 0.5F; defL = m - 20F; defR = m + 20F; }
        if (defB - defT < 40F) { float m = (defT + defB) * 0.5F; defT = m - 20F; defB = m + 20F; }
    }

    /**
     * Maps a point of the calm HUD to its place in the deformed HUD. The four edges of the frame are
     * the boundary; everything inside is blended between them (Coons patch), so a point near the top
     * edge bends exactly like the top edge, a point near the left edge bends like the left edge, and
     * the middle of the screen stays calm. The result is written to out[0], out[1].
     */
    private static void warp(float x, float y, float[] out) {
        float u = (x - restL) / (restR - restL);
        float w = (y - restT) / (restB - restT);
        float su = (float) Math.sin(Math.PI * Math.min(1F, Math.max(0F, u)));
        float sw = (float) Math.sin(Math.PI * Math.min(1F, Math.max(0F, w)));
        float wPx = defR - defL;
        float hPx = defB - defT;

        // X: the left/right sides bulge by vBend, fading towards the middle column.
        out[0] = defL + u * wPx + vBend * sw * (1F - 2F * u);
        // Y: the top/bottom sides bulge by hBend, fading towards the middle row.
        out[1] = defT + w * hPx + hBend * su * (1F - 2F * w);
    }

    // ------------------------------------------------------------------------------------------
    //  Batch of coloured quads
    // ------------------------------------------------------------------------------------------

    private static void beginQuads(PoseStack ps) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        qb = Tesselator.getInstance().getBuilder();
        qb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        qm = ps.last().pose();
    }

    private static void endQuads() {
        BufferUploader.drawWithShader(qb.end());
        RenderSystem.disableBlend();
        qb = null;
        qm = null;
    }

    private static void vtx(float x, float y, int argb) {
        qb.vertex(qm, x, y, 0F)
                .color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255)
                .endVertex();
    }

    /** A filled rectangle that is cut into small cells; every cell corner goes through warp(). */
    private static void wRect(float x0, float y0, float x1, float y1, int argb) {
        int nx = Math.max(1, (int) Math.ceil((x1 - x0) / CELL));
        int ny = Math.max(1, (int) Math.ceil((y1 - y0) / CELL));
        for (int j = 0; j < ny; j++) {
            float ya = y0 + (y1 - y0) * j / ny;
            float yb = y0 + (y1 - y0) * (j + 1) / ny;
            for (int i = 0; i < nx; i++) {
                float xa = x0 + (x1 - x0) * i / nx;
                float xb = x0 + (x1 - x0) * (i + 1) / nx;
                warp(xa, ya, TMP_A);
                vtx(TMP_A[0], TMP_A[1], argb);
                warp(xa, yb, TMP_A);
                vtx(TMP_A[0], TMP_A[1], argb);
                warp(xb, yb, TMP_A);
                vtx(TMP_A[0], TMP_A[1], argb);
                warp(xb, ya, TMP_A);
                vtx(TMP_A[0], TMP_A[1], argb);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    //  The frame: ONE closed, smooth, anti-aliased line
    // ------------------------------------------------------------------------------------------

    /**
     * The four edges are sampled into one closed loop of points. Every point gets a normal (corners
     * get a mitred one), and the line is drawn as a strip: a solid core with soft, transparent
     * edges on both sides. No pixel rounding, so no stair-steps ("ribs") and no popping corners.
     */
    private static void drawFrameLine(float dpr) {
        float wPx = defR - defL;
        float hPx = defB - defT;
        int nx = Math.min(300, Math.max(8, (int) Math.ceil(wPx / 5F)));
        int ny = Math.min(120, Math.max(8, (int) Math.ceil(hPx / 5F)));

        int n = 0;
        for (int i = 0; i < nx; i++) {            // top, left -> right
            float t = i / (float) nx;
            FX[n] = defL + t * wPx;
            FY[n] = defT + hBend * (float) Math.sin(Math.PI * t);
            n++;
        }
        for (int i = 0; i < ny; i++) {            // right, top -> bottom
            float t = i / (float) ny;
            FX[n] = defR - vBend * (float) Math.sin(Math.PI * t);
            FY[n] = defT + t * hPx;
            n++;
        }
        for (int i = 0; i < nx; i++) {            // bottom, right -> left
            float t = 1F - i / (float) nx;
            FX[n] = defL + t * wPx;
            FY[n] = defB - hBend * (float) Math.sin(Math.PI * t);
            n++;
        }
        for (int i = 0; i < ny; i++) {            // left, bottom -> top
            float t = 1F - i / (float) ny;
            FX[n] = defL + vBend * (float) Math.sin(Math.PI * t);
            FY[n] = defT + t * hPx;
            n++;
        }

        // normals (mitred at the corners)
        for (int i = 0; i < n; i++) {
            int a = (i + n - 1) % n, b = (i + 1) % n;
            float d1x = FX[i] - FX[a], d1y = FY[i] - FY[a];
            float d2x = FX[b] - FX[i], d2y = FY[b] - FY[i];
            float l1 = (float) Math.sqrt(d1x * d1x + d1y * d1y);
            float l2 = (float) Math.sqrt(d2x * d2x + d2y * d2y);
            if (l1 < 1e-4F) { d1x = d2x; d1y = d2y; l1 = Math.max(l2, 1e-4F); }
            if (l2 < 1e-4F) { d2x = d1x; d2y = d1y; l2 = Math.max(l1, 1e-4F); }
            float n1x = -d1y / l1, n1y = d1x / l1;
            float n2x = -d2y / l2, n2y = d2x / l2;
            float mx = n1x + n2x, my = n1y + n2y;
            float ml = (float) Math.sqrt(mx * mx + my * my);
            if (ml < 1e-4F) { mx = n1x; my = n1y; ml = 1F; }
            mx /= ml;
            my /= ml;
            float miter = 1F / Math.max(0.4F, mx * n1x + my * n1y);
            OX[i] = mx * miter;
            OY[i] = my * miter;
        }

        float hw = Math.max(0.5F, 0.75F / dpr);     // half thickness of the solid core
        float feather = Math.max(0.35F, 0.9F / dpr); // soft edge, about one screen pixel
        float[] off = {-(hw + feather), -hw, hw, hw + feather};
        int[] col = {FRAME_CLEAR, FRAME, FRAME, FRAME_CLEAR};

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            for (int band = 0; band < 3; band++) {
                float oa = off[band], ob = off[band + 1];
                vtx(FX[i] + OX[i] * oa, FY[i] + OY[i] * oa, col[band]);
                vtx(FX[j] + OX[j] * oa, FY[j] + OY[j] * oa, col[band]);
                vtx(FX[j] + OX[j] * ob, FY[j] + OY[j] * ob, col[band + 1]);
                vtx(FX[i] + OX[i] * ob, FY[i] + OY[i] * ob, col[band + 1]);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    //  HUD elements (all through warp())
    // ------------------------------------------------------------------------------------------

    /** Battery is always full: horizontal, checkerboard fill, nub on the right. */
    private static void drawBattery() {
        int x = 32, y = 33, bw = 58, bh = 24;
        wRect(x, y, x + bw, y + 1, WHITE);
        wRect(x, y + bh - 1, x + bw, y + bh, WHITE);
        wRect(x, y, x + 1, y + bh, WHITE);
        wRect(x + bw - 1, y, x + bw, y + bh, WHITE);
        wRect(x + bw, y + 7, x + bw + 4, y + 17, WHITE);
        int inner = bw - 6, cell = 3;
        int cols = (inner + cell - 1) / cell;
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < 6; j++) {
                if (((i + j) & 1) != 0) continue;
                int cx0 = x + 3 + i * cell;
                int cy0 = y + 3 + j * cell + 1;
                int cx1 = Math.min(cx0 + cell, x + 3 + inner);
                wRect(cx0, cy0, cx1, cy0 + cell, WHITE);
            }
        }
    }

    /** Long dotted bar at the top center; the solid marker slides along it as the zoom grows. */
    private static void drawTopBar(int refW, float zoom) {
        int cx = refW / 2;
        int x0 = cx - 80, x1 = cx + 80, y0 = 33, y1 = 42;
        for (int x = x0; x < x1; x += 2) {
            wRect(x, y0, x + 1, y0 + 1, WHITE);
            wRect(x, y1, x + 1, y1 + 1, WHITE);
        }
        for (int y = y0; y <= y1; y += 2) {
            wRect(x0, y, x0 + 1, y + 1, WHITE);
            wRect(x1, y, x1 + 1, y + 1, WHITE);
        }
        int mw = 10;
        int mx = x0 + 2 + Math.round(zoom * (x1 - x0 - 4 - mw));
        wRect(mx, y0 + 1, mx + mw, y1, WHITE);
    }

    private static void drawRecDot(Minecraft mc, int refW) {
        int right = refW - 34;
        float recW = mc.font.width("REC") * 2.4F;
        float recX = right - recW;
        int cx = Math.round(recX) - 19, cy = 43;
        for (int dy = -7; dy <= 7; dy++) {
            int dx = (int) Math.round(Math.sqrt(7.5 * 7.5 - dy * dy));
            wRect(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, RED);
        }
    }

    private static void drawCross(int refW) {
        int mx = refW / 2, my = REF_H / 2;
        int c = 0x60FFFFFF;
        wRect(mx - 15, my, mx + 16, my + 1, c);
        wRect(mx, my - 15, mx + 1, my + 16, c);
    }

    private static void drawRecText(PoseStack ps, Minecraft mc, int refW, boolean blinkOn) {
        if (!blinkOn) return;
        int right = refW - 34;
        float recSx = 2.4F, recSy = 2.7F;
        float recW = mc.font.width("REC") * recSx;
        drawWarpedText(ps, mc, "REC", right - recW, 34F, recSx, recSy, RED);
    }

    private static void drawTimer(PoseStack ps, Minecraft mc, int refW) {
        int right = refW - 34;
        int secs = recTicks / 20;
        String time = String.format("%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        float tSx = 1.35F, tSy = 1.7F;
        float tW = mc.font.width(time) * tSx;
        drawWarpedText(ps, mc, time, right - tW, 59F, tSx, tSy, TIMER_COLOR);
    }

    /**
     * Draws text letter by letter. Each letter is moved, turned and stretched the way the bending of
     * the frame moves its place, so the whole word bends like an arc together with the frame.
     * x, y = top-left corner of the text in calm reference pixels; sx, sy = text scale.
     */
    private static void drawWarpedText(PoseStack ps, Minecraft mc, String text, float x, float y,
                                       float sx, float sy, int color) {
        float cursor = 0F;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            float gw = mc.font.width(ch);
            float cx = x + (cursor + gw * 0.5F) * sx;
            float cy = y + 4F * sy;

            warp(cx, cy, TMP_A);        // where the letter centre goes
            warp(cx + 1F, cy, TMP_B);   // where its "right" direction goes
            warp(cx, cy + 1F, TMP_C);   // where its "down" direction goes

            float ex = TMP_B[0] - TMP_A[0], ey = TMP_B[1] - TMP_A[1];
            float fx = TMP_C[0] - TMP_A[0], fy = TMP_C[1] - TMP_A[1];
            float angle = (float) Math.atan2(ey, ex);
            float stretchX = (float) Math.sqrt(ex * ex + ey * ey);
            float stretchY = (float) Math.sqrt(fx * fx + fy * fy);

            ps.pushPose();
            ps.translate(TMP_A[0], TMP_A[1], 0F);
            ps.mulPose(Vector3f.ZP.rotation(angle));
            ps.scale(sx * stretchX, sy * stretchY, 1F);
            ps.translate(-gw * 0.5F, -4F, 0F);
            mc.font.draw(ps, ch, 0F, 0F, color);
            ps.popPose();

            cursor += gw;
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Vignette and grain (unchanged)
    // ------------------------------------------------------------------------------------------

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
