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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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

    private static final int GRAY_EDGE = 0xA0FFFFFF;   // outlines of battery and zoom bar
    private static final int GRAY_LIT = 0xB4FFFFFF;    // lit pixels, timer digits
    private static final int FRAME = 0x70FFFFFF;
    private static final int FRAME_CLEAR = 0x00FFFFFF;
    private static final int RED = 0xFFB00000;         // dark red like in the game

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

        // Everything (frame, battery, zoom bar, REC, timer, cross) is made of small squares / lines,
        // so it all goes into one batch and every square follows the bending of the frame.
        beginQuads(ps);
        drawFrameLine(dpr);
        drawBattery();
        drawTopBar(refW, zoom);
        drawRec(refW, blinkOn);
        drawTimer(refW);
        drawCross(refW);
        endQuads();

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

    // Pixel-art look of Slender: The Arrival: every element is a grid of square "pixels" with thin seams.
    private static final float BATT_CELL = 2.55F;
    private static final float BAR_CELL = 2.05F;
    private static final float REC_CELL = 2.45F;
    private static final float DOT_CELL = 2.5F;
    private static final float TIME_CELL = 1.85F;

    private static final String[] REC_R = {"XXXX.", "X...X", "X...X", "XXXX.", "X.X..", "X..X.", "X...X"};
    private static final String[] REC_E = {"XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "XXXXX"};
    private static final String[] REC_C = {".XXX.", "X...X", "X....", "X....", "X....", "X...X", ".XXX."};
    private static final String[][] REC_LETTERS = {REC_R, REC_E, REC_C};
    private static final String[] REC_DOT = {"..XXX..", ".XXXXX.", "XXXXXXX", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX.."};

    // 3x5 pixel digits (0, 2 and 5 copied from the reference; the others drawn in the same style)
    private static final String[][] DIGITS = {
            {".X.", "X.X", "X.X", "X.X", ".X."},   // 0
            {".X.", "XX.", ".X.", ".X.", "XXX"},   // 1
            {"XXX", "..X", ".X.", "X..", "XXX"},   // 2
            {"XX.", "..X", ".X.", "..X", "XX."},   // 3
            {"X.X", "X.X", "XXX", "..X", "..X"},   // 4
            {"XXX", "X..", ".X.", "..X", "XX."},   // 5
            {".XX", "X..", "XX.", "X.X", ".X."},   // 6
            {"XXX", "..X", ".X.", ".X.", ".X."},   // 7
            {".X.", "X.X", ".X.", "X.X", ".X."},   // 8
            {".X.", "X.X", ".XX", "..X", "XX."}    // 9
    };

    /** One pixel-art cell: grid origin (ox, oy), cell size cs, position/size in cells, small seam inset. */
    private static void cell(float ox, float oy, float cs, float col, float row, float wc, float hc,
                             int argb, float inset) {
        float in = inset * cs;
        wRect(ox + col * cs + in, oy + row * cs + in, ox + (col + wc) * cs - in, oy + (row + hc) * cs - in, argb);
    }

    private static void bitmap(float ox, float oy, float cs, float col0, String[] rows, int argb, float inset) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                if (rows[r].charAt(c) == 'X') cell(ox, oy, cs, col0 + c, r, 1F, 1F, argb, inset);
            }
        }
    }

    /** Battery is always full: 1-cell outline, checkerboard of cells inside, small nub on the right. */
    private static void drawBattery() {
        float x = 33F, y = 34.5F, cs = BATT_CELL;
        int bodyW = 22, bodyH = 9;
        for (int c = 0; c < bodyW; c++) {
            cell(x, y, cs, c, 0, 1, 1, GRAY_EDGE, 0.05F);
            cell(x, y, cs, c, bodyH - 1, 1, 1, GRAY_EDGE, 0.05F);
        }
        for (int r = 1; r < bodyH - 1; r++) {
            cell(x, y, cs, 0, r, 1, 1, GRAY_EDGE, 0.05F);
            cell(x, y, cs, bodyW - 1, r, 1, 1, GRAY_EDGE, 0.05F);
        }
        cell(x, y, cs, bodyW, 2.5F, 1.5F, 4F, GRAY_EDGE, 0.05F);
        for (int i = 0; i < bodyW - 2; i++) {
            for (int j = 0; j < bodyH - 2; j++) {
                if (((i + j) & 1) != 0) continue;
                cell(x, y, cs, 1 + i, 1 + j, 1, 1, GRAY_LIT, 0.14F);
            }
        }
    }

    /** Long bar at the top center, a pixel-art frame; the small hollow marker slides along it as the zoom grows. */
    private static void drawTopBar(int refW, float zoom) {
        float cs = BAR_CELL;
        int cols = 72;
        float x = refW * 0.5F - cols * cs * 0.5F, y = 33F;
        for (int c = 1; c < cols - 1; c++) {
            cell(x, y, cs, c, 0, 1, 1, GRAY_EDGE, 0.05F);
            cell(x, y, cs, c, 4, 1, 1, GRAY_EDGE, 0.05F);
        }
        for (int r = 1; r <= 3; r++) {
            cell(x, y, cs, 0, r, 1, 1, GRAY_EDGE, 0.05F);
            cell(x, y, cs, cols - 1, r, 1, 1, GRAY_EDGE, 0.05F);
        }
        int hc = 1 + Math.round(Mth.clamp(zoom, 0F, 1F) * (cols - 7));   // marker start column, 1..65
        for (int c = 1; c <= 3; c++) {
            cell(x, y, cs, hc + c, 1, 1, 1, GRAY_LIT, 0.08F);
            cell(x, y, cs, hc + c, 3, 1, 1, GRAY_LIT, 0.08F);
        }
        cell(x, y, cs, hc, 2, 1, 1, GRAY_LIT, 0.08F);
        cell(x, y, cs, hc + 4, 2, 1, 1, GRAY_LIT, 0.08F);
    }

    /** Dark red pixel-art dot (always on) and the pixel letters REC (blinking, as before). */
    private static void drawRec(int refW, boolean blinkOn) {
        float right = refW - 34F;
        float cs = REC_CELL, pitch = 6.45F;
        float textW = (2F * pitch + 5F) * cs;
        float tx = right - textW, ty = 36F;

        float dcs = DOT_CELL;
        float dx = tx - 4.2F - 7F * dcs, dy = 34.1F;
        bitmap(dx, dy, dcs, 0F, REC_DOT, RED, 0.05F);

        if (blinkOn) {
            for (int i = 0; i < 3; i++) bitmap(tx, ty, cs, i * pitch, REC_LETTERS[i], RED, 0.06F);
        }
    }

    private static void drawTimer(int refW) {
        float right = refW - 34F;
        int secs = recTicks / 20;
        String time = String.format("%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        float cs = TIME_CELL;

        int total = 0;
        for (int i = 0; i < time.length(); i++) total += time.charAt(i) == ':' ? 2 : 4;
        total -= 1;
        float x = right - total * cs, y = 57F;

        int col = 0;
        for (int i = 0; i < time.length(); i++) {
            char ch = time.charAt(i);
            if (ch == ':') {
                cell(x, y, cs, col, 1, 1, 1, GRAY_LIT, 0.06F);
                cell(x, y, cs, col, 4, 1, 1, GRAY_LIT, 0.06F);
                col += 2;
            } else {
                bitmap(x, y, cs, col, DIGITS[ch - '0'], GRAY_LIT, 0.06F);
                col += 4;
            }
        }
    }

    private static void drawCross(int refW) {
        int mx = refW / 2, my = REF_H / 2;
        int c = 0x60FFFFFF;
        wRect(mx - 15, my, mx + 16, my + 1, c);
        wRect(mx, my - 15, mx + 1, my + 16, c);
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
