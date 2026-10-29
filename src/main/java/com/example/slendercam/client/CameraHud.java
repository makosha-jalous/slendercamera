package com.example.slendercam.client;

import com.example.slendercam.SlenderCamMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
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

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

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
    /** Film grain strength when the monster is calm (aggression = 0): 40% lower than before (0.20 -> 0.12). */
    private static final float GRAIN_ALPHA_CALM = 0.12F;
    /** Film grain strength when the action starts: the grain smoothly returns to the original value. */
    private static final float GRAIN_ALPHA_MAX = 0.20F;
    private static final int GRAIN_PIXEL_SIZE = 1;       // 1 = one grain dot per screen pixel (finest)
    private static final int BLINK_MS = 500;             // REC text blink interval
    private static final float CELL = 3F;                // bending mesh cell size in reference pixels

    private static final int GRAY_EDGE = 0xB0FFFFFF;   // outlines of battery and zoom bar (original: white, about 69% opaque)
    private static final int GRAY_LIT = 0xB8FFFFFF;    // lit pixels of the zoom marker, timer digits (original: about 72%)
    private static final int BATT_LIT = 0xB8E7E5E7;    // the checkerboard of the battery (the original picture is light grey 231,229,231)
    private static final int FRAME = 0x3AFFFFFF;        // the frame of the original is thin and faint (about 22%)
    private static final int FRAME_CLEAR = 0x00FFFFFF;
    private static final int RED = 0x96FF0000;         // REC and the dot: red 255 at about 59% (looks like 150,0,6 on a dark picture)

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
    private static float curDpr = 1F;                       // screen pixels per reference pixel
    private static boolean snapVtx;                         // true while the pixel-art elements are drawn: every corner lands on a whole screen pixel
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
                || mc.player.getOffhandItem().is(SlenderCamMod.CAMERA.get())
                || focusHidesCamera(mc));
    }

    private static java.lang.reflect.Method savedOffM;
    private static boolean savedOffTried;

    /** While the long-range light of the flashlight is held, the flashlight mod parks a marker in the off hand; the camera is behind it. */
    private static boolean focusHidesCamera(Minecraft mc) {
        if (mc.player.getOffhandItem().isEmpty()) return false;
        try {
            if (!savedOffTried) {
                savedOffTried = true;
                try {
                    savedOffM = Class.forName("com.example.slenderflashlight.client.FlashlightFocus").getMethod("savedOffhand");
                } catch (Throwable ignored) { savedOffM = null; }
            }
            if (savedOffM == null) return false;
            Object o = savedOffM.invoke(null);
            return o instanceof net.minecraft.world.item.ItemStack st && st.is(SlenderCamMod.CAMERA.get());
        } catch (Throwable t) {
            return false;
        }
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

        float s = h / (float) REF_H;
        int refW = Math.round(w / s);
        float dpr = s * (float) mc.getWindow().getGuiScale();   // device pixels per reference pixel
        boolean blinkOn = (System.currentTimeMillis() / BLINK_MS) % 2 == 0;

        // the picture of the game is bent together with the frame (first, so the grain, the vignette and the
        // interface are drawn on top of the bent picture)
        ps.pushPose();
        ps.scale(s, s, 1F);
        computeFrame(refW, tg);
        drawBentScene(ps, refW);
        ps.popPose();

        drawGrain(ps, w, h, gi, tg);
        drawVignette(ps, w, h);

        ps.pushPose();
        ps.scale(s, s, 1F);

        // Everything (frame, battery, zoom bar, REC, timer, cross) is made of small squares / lines,
        // so it all goes into one batch and every square follows the bending of the frame.
        curDpr = dpr;
        beginQuads(ps);
        snapVtx = false;
        drawFrameLine(dpr);
        snapVtx = true;                       // the pixel-art elements: crisp, even cells on whole screen pixels
        drawBattery();
        drawTopBar(refW, zoom);
        drawRec(refW, blinkOn);
        drawTimer(refW);
        drawCross(refW);
        snapVtx = false;
        endQuads();

        ps.popPose();
    }

    // ------------------------------------------------------------------------------------------
    //  The picture of the game bends together with the frame
    // ------------------------------------------------------------------------------------------

    /** how much of the displacement of the frame the picture follows (1 = exactly as much as the frame). */
    private static final float SCENE_BEND = 0.75F;
    /**
     * FISHEYE of the original (Unity Fisheye image effect): the strengths come from the Glitch port (GlitchPort.strengthX/Y).
     * The shader of the original: offset.x = (1 - y*y) * strengthY * x * K, offset.y = (1 - x*x) * strengthX * aspect * K * y,
     * with x, y in -1..1 and K = 80/512. The picture is sampled at (uv - offset) and everything that is drawn before the effect
     * (the interface frame) is moved by +offset. FISHEYE_GAIN = 1 is the original, bigger = stronger.
     */
    private static final float FISHEYE_GAIN = 1.0F;
    private static final float FISH_K = 80F / 512F;
    /** the largest colour split (share of the half width at the edge of the picture; only the green channel moves, red and blue stay together = pink / magenta). */
    private static final float CA_MAX = 0.16F;
    /** a very light fish-eye of the frame, the cross and the other white elements only (the sides bow outwards). */
    private static final float LENS = 0.01F;
    /** the same lens for the HORIZONTAL lines of the frame (and everything else): 0 = straight (as before), > 0 = the top and bottom bow outwards, < 0 = inwards. */
    private static final float LENS_H = 0.01F;
    private static final int SCENE_COLS = 48, SCENE_ROWS = 28;
    private static RenderTarget sceneCopy;
    private static float caScale;
    private static int curRefW = 739;

    /**
     * Copies the finished picture of the game into a second buffer and draws it back as a net of small quads: every
     * corner of the net is moved by the same warp() that bends the frame, so the picture follows the frame.
     * Nothing is done while the frame is straight.
     */
    /** fisheye displacement (in reference pixels) of a source point (x, y): where it ends up on the screen is (x + dx, y + dy). */
    private static void fishOffset(float x, float y, int refW, float[] out) {
        float sx = GlitchPort.strengthX() * FISHEYE_GAIN, sy = GlitchPort.strengthY() * FISHEYE_GAIN;
        if (sx == 0F && sy == 0F) { out[0] = 0F; out[1] = 0F; return; }
        float cx = 2F * (x / refW) - 1F;
        float cy = 2F * (1F - y / REF_H) - 1F;                 // y up, like Unity
        float ar = refW / (float) REF_H;
        float offX = (1F - cy * cy) * sy * FISH_K * cx;
        float offY = (1F - cx * cx) * sx * ar * FISH_K * cy;
        out[0] = offX * refW;
        out[1] = -offY * REF_H;
    }

    private static void drawBentScene(PoseStack ps, int refW) {
        curRefW = refW;
        float moved = Math.abs(defL - restL) + Math.abs(defR - restR) + Math.abs(defT - restT) + Math.abs(defB - restB)
                + Math.abs(vBend) + Math.abs(hBend);
        float ca = CameraEffects.chromatic();
        if (moved < 0.5F && !GlitchPort.fisheyeActive() && ca <= 0.002F) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.getMainRenderTarget();
            int tw = main.width, th = main.height;
            if (sceneCopy == null) {
                sceneCopy = new TextureTarget(tw, th, false, Minecraft.ON_OSX);
                sceneCopy.setFilterMode(GL11.GL_LINEAR);
            } else if (sceneCopy.width != tw || sceneCopy.height != th) {
                sceneCopy.resize(tw, th, Minecraft.ON_OSX);
            }
            // copy of the picture
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneCopy.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, tw, th, 0, 0, tw, th, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            main.bindWrite(false);

            // the net: it reaches a bit beyond the screen, the picture edge is stretched there (no gaps)
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(0, sceneCopy.getColorTextureId());
            Matrix4f m = ps.last().pose();
            float x0 = -50F, x1 = refW + 50F, y0 = -35F, y1 = REF_H + 35F;
            // colour split: the red, green and blue channels are drawn one by one, a little bigger / same / smaller
            boolean split = ca > 0.002F;
            int passes = split ? 3 : 1;
            for (int pass = 0; pass < passes; pass++) {
                if (split) {
                    RenderSystem.colorMask(pass == 0, pass == 1, pass == 2, false);
                    caScale = (pass == 1 ? 1F : 0F) * ca * CA_MAX;     // only GREEN is shifted (like the original): the fringes are magenta and green
                } else {
                    RenderSystem.colorMask(true, true, true, false);
                    caScale = 0F;
                }
                BufferBuilder b = Tesselator.getInstance().getBuilder();
                b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                for (int j = 0; j < SCENE_ROWS; j++) {
                    float ya = y0 + (y1 - y0) * j / SCENE_ROWS;
                    float yb = y0 + (y1 - y0) * (j + 1) / SCENE_ROWS;
                    for (int i = 0; i < SCENE_COLS; i++) {
                        float xa = x0 + (x1 - x0) * i / SCENE_COLS;
                        float xb = x0 + (x1 - x0) * (i + 1) / SCENE_COLS;
                        sceneVertex(b, m, xa, ya, refW);
                        sceneVertex(b, m, xa, yb, refW);
                        sceneVertex(b, m, xb, yb, refW);
                        sceneVertex(b, m, xb, ya, refW);
                    }
                }
                BufferUploader.drawWithShader(b.end());
            }
            caScale = 0F;
        } catch (Throwable ignored) {
            // never break the game because of a graphics detail
        } finally {
            RenderSystem.colorMask(true, true, true, true);
        }
    }

    private static void sceneVertex(BufferBuilder b, Matrix4f m, float rx, float ry, int refW) {
        warp(rx, ry, TMP_C);
        float px = rx + (TMP_C[0] - rx) * SCENE_BEND;
        float py = ry + (TMP_C[1] - ry) * SCENE_BEND;
        // chromatic aberration of the original: the shift grows with the CUBE of the distance from the middle
        // (Unity: uv - texel * chromaticAberration * coords * dot(coords, coords)); here only the green channel is moved (red + blue together look pink)
        float dxn = (px - refW * 0.5F) / (refW * 0.5F), dyn = (py - REF_H * 0.5F) / (REF_H * 0.5F);
        float fe = 1F + caScale * (dxn * dxn + dyn * dyn);
        px = refW * 0.5F + (px - refW * 0.5F) * fe;
        py = REF_H * 0.5F + (py - REF_H * 0.5F) * fe;
        // the picture is sampled at (uv - fisheye offset)
        fishOffset(rx, ry, refW, TMP_B);
        float u = Mth.clamp((rx + 0F) / refW - TMP_B[0] / refW, 0F, 1F);
        float vv = (1F - ry / REF_H) + TMP_B[1] / REF_H;      // frame buffer textures are upside down
        float v = Mth.clamp(vv, 0F, 1F);
        b.vertex(m, px, py, 0F).uv(u, v).endVertex();
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
        if (LENS > 0F || LENS_H != 0F) {
            float cx = (restL + restR) * 0.5F, cy = (restT + restB) * 0.5F;
            float hx = Math.max(1F, (restR - restL) * 0.5F), hy = Math.max(1F, (restB - restT) * 0.5F);
            float dx = (x - cx) / hx, dy = (y - cy) / hy;
            // the vertical sides bow sideways (LENS); the horizontal sides bow up / down (LENS_H, 0 = they stay straight)
            x = cx + (x - cx) * (1F + LENS * (1F - dy * dy));
            y = cy + (y - cy) * (1F + LENS_H * (1F - dx * dx));
        }
        fishOffset(x, y, curRefW, TMP_B);
        x += TMP_B[0];
        y += TMP_B[1];
        if (snapVtx) {
            x = Math.round(x * curDpr) / curDpr;
            y = Math.round(y * curDpr) / curDpr;
        }
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

        float hw = Math.max(0.3F, 0.75F / dpr);     // half thickness of the solid core (the original line is 2 screen pixels)
        float feather = Math.max(0.25F, 0.4F / dpr); // soft edge
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

    // Pixel-art look of the original: every element is a grid of cells, 1 screen pixel apart. The sizes were measured on a
    // 1920x1080 screenshot of the original (1 reference pixel = 2.6 screen pixels); the cells of the original are not exactly square.
    private static final float BATT_CW = 2.49F, BATT_CH = 2.17F;   // 25 cells wide (23 + nub) x 11 high
    private static final float BAR_CW = 2.90F, BAR_CH = 2.18F;     // 55 x 5 cells
    private static final float REC_CW = 2.54F, REC_CH = 2.34F;     // letters: 17 x 7 cells
    private static final float DOT_CW = 2.57F, DOT_CH = 2.64F;     // 7 x 7 cells
    private static final float TIME_CELL = 1.88F;

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
            {".X.", "X.X", ".X.", "X.X", ".X."},   // 3 (copied from the original photo)
            {"X.X", "X.X", "XXX", "..X", "..X"},   // 4
            {"XXX", "X..", ".X.", "..X", "XX."},   // 5
            {".XX", "X..", "XX.", "X.X", ".X."},   // 6
            {"XXX", "..X", ".X.", ".X.", ".X."},   // 7
            {"XXX", "X.X", "XXX", "X.X", "XXX"},   // 8 (not seen in the photos: drawn so that it differs from 3)
            {".X.", "X.X", ".XX", "..X", "XX."}    // 9
    };

    /** a coordinate rounded to a whole screen pixel. */
    private static float snapPx(float v) {
        return Math.round(v * curDpr) / curDpr;
    }

    /** One pixel-art cell: grid origin (ox, oy), cell size (cw, ch), position/size in cells. 1 screen pixel is left free between cells. */
    private static void cell(float ox, float oy, float cw, float ch, float col, float row, float wc, float hc, int argb) {
        float seam = 1F / curDpr;
        float x0 = snapPx(ox + col * cw), x1 = snapPx(ox + (col + wc) * cw) - seam;
        float y0 = snapPx(oy + row * ch), y1 = snapPx(oy + (row + hc) * ch) - seam;
        if (x1 <= x0) x1 = x0 + seam;
        if (y1 <= y0) y1 = y0 + seam;
        wRect(x0, y0, x1, y1, argb);
    }

    private static void bitmap(float ox, float oy, float cw, float ch, float col0, String[] rows, int argb) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                if (rows[r].charAt(c) == 'X') cell(ox, oy, cw, ch, col0 + c, r, 1F, 1F, argb);
            }
        }
    }

    /** Battery: outline 23 x 11 cells, nub 2 x 5 cells, 21 x 9 checkerboard inside. */
    private static void drawBattery() {
        float x = 32.3F, y = 34.6F, cw = BATT_CW, ch = BATT_CH;
        int bodyW = 23, bodyH = 11;
        for (int c = 0; c < bodyW; c++) {
            cell(x, y, cw, ch, c, 0, 1, 1, GRAY_EDGE);
            cell(x, y, cw, ch, c, bodyH - 1, 1, 1, GRAY_EDGE);
        }
        for (int r = 1; r < bodyH - 1; r++) {
            cell(x, y, cw, ch, 0, r, 1, 1, GRAY_EDGE);
            cell(x, y, cw, ch, bodyW - 1, r, 1, 1, GRAY_EDGE);
        }
        for (int r = 3; r <= 7; r++) {
            cell(x, y, cw, ch, bodyW, r, 1, 1, GRAY_EDGE);
            cell(x, y, cw, ch, bodyW + 1, r, 1, 1, GRAY_EDGE);
        }
        for (int i = 0; i < bodyW - 2; i++) {
            for (int j = 0; j < bodyH - 2; j++) {
                if (((i + j) & 1) != 0) continue;
                cell(x, y, cw, ch, 1 + i, 1 + j, 1, 1, BATT_LIT);
            }
        }
    }

    /** Long bar at the top centre (55 x 5 cells); the small hollow marker slides along it as the zoom grows. */
    private static void drawTopBar(int refW, float zoom) {
        float cw = BAR_CW, ch = BAR_CH;
        int cols = 55;
        float x = refW * 0.5F - cols * cw * 0.5F + 1.1F, y = 32.7F;
        for (int c = 1; c < cols - 1; c++) {
            cell(x, y, cw, ch, c, 0, 1, 1, GRAY_EDGE);
            cell(x, y, cw, ch, c, 4, 1, 1, GRAY_EDGE);
        }
        for (int r = 1; r <= 3; r++) {
            cell(x, y, cw, ch, 0, r, 1, 1, GRAY_EDGE);
            cell(x, y, cw, ch, cols - 1, r, 1, 1, GRAY_EDGE);
        }
        int hc = 1 + Math.round(Mth.clamp(zoom, 0F, 1F) * (cols - 7));   // marker start column
        for (int c = 1; c <= 3; c++) {
            cell(x, y, cw, ch, hc + c, 1, 1, 1, GRAY_LIT);
            cell(x, y, cw, ch, hc + c, 3, 1, 1, GRAY_LIT);
        }
        cell(x, y, cw, ch, hc, 2, 1, 1, GRAY_LIT);
        cell(x, y, cw, ch, hc + 4, 2, 1, 1, GRAY_LIT);
    }

    /** The red dot (always on) and the pixel letters REC (blinking). */
    private static void drawRec(int refW, boolean blinkOn) {
        float pitch = 6F;
        float tx = refW - 77.6F, ty = 35.7F;
        float dx = refW - 100.3F, dy = 34.2F;
        bitmap(dx, dy, DOT_CW, DOT_CH, 0F, REC_DOT, RED);
        if (blinkOn) {
            for (int i = 0; i < 3; i++) bitmap(tx, ty, REC_CW, REC_CH, i * pitch, REC_LETTERS[i], RED);
        }
    }

    private static void drawTimer(int refW) {
        float right = refW - 36.7F;
        int secs = recTicks / 20;
        String time = String.format("%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        float cs = TIME_CELL;

        int total = 0;
        for (int i = 0; i < time.length(); i++) total += time.charAt(i) == ':' ? 2 : 4;
        total -= 1;
        float x = right - total * cs, y = 56.9F;

        int col = 0;
        for (int i = 0; i < time.length(); i++) {
            char ch = time.charAt(i);
            if (ch == ':') {
                cell(x, y, cs, cs, col, 1, 1, 1, GRAY_LIT);
                cell(x, y, cs, cs, col, 4, 1, 1, GRAY_LIT);
                col += 2;
            } else {
                bitmap(x, y, cs, cs, col, DIGITS[ch - '0'], GRAY_LIT);
                col += 4;
            }
        }
    }

    /** ONE plus: 3 screen pixels thick, 93 long (the original picture is 31 pixels x 3), faint. The middle is drawn only once. */
    private static void drawCross(int refW) {
        float mx = refW * 0.5F - 0.8F, my = REF_H * 0.5F - 3.1F;
        float half = 17.9F, t = 1.15F;
        int c = 0x26FFFFFF;
        float cx0 = snapPx(mx - t * 0.5F), cx1 = snapPx(mx + t * 0.5F);
        float cy0 = snapPx(my - t * 0.5F), cy1 = snapPx(my + t * 0.5F);
        if (cx1 <= cx0) cx1 = cx0 + 1F / curDpr;
        if (cy1 <= cy0) cy1 = cy0 + 1F / curDpr;
        wRect(snapPx(mx - half), cy0, snapPx(mx + half), cy1, c);        // horizontal bar, full length
        wRect(cx0, snapPx(my - half), cx1, cy0, c);                       // vertical: above the middle
        wRect(cx0, cy1, cx1, snapPx(my + half), c);                       // vertical: below the middle
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
    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    private static void drawGrain(PoseStack ps, int w, int h, float gi, float fl) {
        Minecraft mc = Minecraft.getInstance();
        if (grain == null) {
            grain = new DynamicTexture(TEX, TEX, false);
            grain.setFilter(false, false);
            grainLoc = mc.getTextureManager().register("slendercam_grain", grain);
        }
        long now = System.currentTimeMillis();
        long interval = fl > 0.05F ? 0 : (GlitchPort.dice() ? 40 : 120);
        if (now - lastNoise > interval) {
            lastNoise = now;
            NativeImage img = grain.getPixels();
            int chroma = 18 + Math.round(42F * Mth.clamp(GlitchPort.intensity() / 50F, 0F, 1F));    // the colour cast grows with the sanity loss
            if (img != null) {
                for (int y = 0; y < TEX; y++) {
                    for (int x = 0; x < TEX; x++) {
                        // grey grain with a soft colour cast: real signal noise, not just grey-white dots
                        int v = RND.nextInt(256);
                        int a = RND.nextInt(256);
                        int r = v, g2 = v, b = v;
                        if (RND.nextInt(100) < 60) {
                            r = clamp255(v + RND.nextInt(2 * chroma + 1) - chroma);
                            g2 = clamp255(v + RND.nextInt(2 * chroma + 1) - chroma);
                            b = clamp255(v + RND.nextInt(2 * chroma + 1) - chroma);
                        }
                        img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g2 << 8) | r);
                    }
                }
                grain.upload();
            }
        }
        // GRAIN OF THE ORIGINAL: grainIntensityMax = Random.Range(cache, 0.015 * I) while the dice is on, the cache value
        // otherwise (re-rolled 50 times a second in GlitchPort). The cache value gives the calm grain of before (0.084).
        float alpha = Math.min(0.95F, 0.42F * GlitchPort.grainMax());
        if (fl > 0F) {
            // Teleport uses the same camera grain, simply pushed to an almost opaque burst.
            alpha = 0.90F + 0.07F * RND.nextFloat();
        }

        double g = mc.getWindow().getGuiScale();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, grainLoc);
        // the scratches of the original are GAPS in the grain: horizontal bands over the whole width where the noise is missing
        int bands = updateScratches(h);
        float u0 = RND.nextInt(TEX), v0 = RND.nextInt(TEX);
        int y = 0;
        for (int i = 0; i < bands; i++) {
            int a = Math.max(y, BAND_A[i]), b = Math.max(a, BAND_B[i]);
            grainStrip(ps, w, y, a, u0, v0, g, alpha);
            grainStrip(ps, w, a, b, u0, v0, g, alpha * (1F - BAND_K[i]));
            y = b;
        }
        grainStrip(ps, w, y, h, u0, v0, g, alpha);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
    }

    /** one horizontal strip (gui rows ya..yb) of the grain, with the same texture offset as the other strips. */
    private static void grainStrip(PoseStack ps, int w, int ya, int yb, float u0, float v0, double g, float alpha) {
        if (yb <= ya || alpha <= 0.001F) return;
        RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
        int regionW = Math.max(1, (int) Math.round(w * g / GRAIN_PIXEL_SIZE));
        int regionH = Math.max(1, (int) Math.round((yb - ya) * g / GRAIN_PIXEL_SIZE));
        GuiComponent.blit(ps, 0, ya, w, yb - ya, u0, v0 + (float) (ya * g / GRAIN_PIXEL_SIZE), regionW, regionH, TEX, TEX);
    }

    private static final int MAX_SCRATCH = 3;
    private static final int[] BAND_A = new int[MAX_SCRATCH], BAND_B = new int[MAX_SCRATCH];
    private static final float[] BAND_K = new float[MAX_SCRATCH];
    private static final float[] SCR_Y = new float[MAX_SCRATCH], SCR_V = new float[MAX_SCRATCH], SCR_T = new float[MAX_SCRATCH];
    private static int scratchCount;
    private static long scratchUntil, scratchLastMs;

    /**
     * Scratches of the original (scratchIntensityMax = Random.Range(cache, 0.005 * I)): they are not lines that are drawn, but
     * GAPS in the grain: thin horizontal bands over the WHOLE width of the screen where the noise is missing, so they look like
     * dark scratches on the film. They jump to a new height about 10 times a second (scratchFPS) and drift while they live.
     * Only the part above the calm value counts, so the calm picture has none. Fills BAND_A/B (gui rows) and BAND_K (how empty
     * the gap is, 0..1), sorted from the top; returns how many.
     */
    private static int updateScratches(int h) {
        float excess = GlitchPort.scratchMax() - GlitchPort.SCRATCH_CACHE;
        if (excess <= 0.003F) { scratchCount = 0; scratchUntil = 0L; return 0; }
        long now = System.currentTimeMillis();
        float dt = scratchLastMs == 0L ? 0F : Math.min(0.1F, (now - scratchLastMs) / 1000F);
        scratchLastMs = now;
        if (now >= scratchUntil) {
            scratchCount = 1 + RND.nextInt(MAX_SCRATCH);
            for (int i = 0; i < scratchCount; i++) {
                SCR_Y[i] = RND.nextFloat();
                SCR_V[i] = (RND.nextFloat() - 0.5F) * 0.4F;         // drift, share of the height per second
                SCR_T[i] = 0.6F + 1.6F * RND.nextFloat();           // thickness: about 1-4 pixels of a 1080p screen
            }
            scratchUntil = now + 40L + (long) (RND.nextFloat() * 200F);
        }
        float k = Math.min(0.95F, 0.45F + excess * 3.5F);
        int n = 0;
        for (int i = 0; i < scratchCount; i++) {
            SCR_Y[i] += SCR_V[i] * dt;
            SCR_Y[i] -= (float) Math.floor(SCR_Y[i]);
            int th = Math.max(1, Math.round(SCR_T[i] * h / 1080F * 1.5F));
            int a = Math.round(SCR_Y[i] * (h - th));
            int p = n;
            while (p > 0 && BAND_A[p - 1] > a) { BAND_A[p] = BAND_A[p - 1]; BAND_B[p] = BAND_B[p - 1]; BAND_K[p] = BAND_K[p - 1]; p--; }
            BAND_A[p] = a; BAND_B[p] = a + th; BAND_K[p] = k;
            n++;
        }
        return n;
    }
}
