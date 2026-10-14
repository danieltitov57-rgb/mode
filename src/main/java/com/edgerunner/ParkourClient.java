package com.edgerunner;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.block.BlockState;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.PlayerRenderer;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Pose;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.HandSide;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.network.NetworkHooks;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = EdgeRunner.MODID, value = Dist.CLIENT)
public class ParkourClient {

    private enum State { NONE, WALLRUN, SLIDE, VAULT, HANG, CLIMB }

    /** Toggle parkour mode (default G). */
    public static final KeyBinding TOGGLE_KEY =
            new KeyBinding("key.edgerunner.toggle", GLFW.GLFW_KEY_G, "key.categories.edgerunner");
    /** Show / hide the help panel (default H). */
    public static final KeyBinding HELP_KEY =
            new KeyBinding("key.edgerunner.help", GLFW.GLFW_KEY_H, "key.categories.edgerunner");

    /** If the camera tilts TOWARD the wall instead of away, change 1.0F to -1.0F. */
    private static final float TILT_DIR = 1.0F;
    private static final UUID SPEED_ID = UUID.fromString("5b2d3f1e-7c41-4a0e-9a35-2f6f0d9b7c11");
    private static final UUID BALANCE_ID = UUID.fromString("a8c1e6d2-3b94-4f0a-8e57-91d4c2b6f003");

    private static boolean enabled = true;
    private static boolean helpVisible = true;

    // core state
    private static ClientPlayerEntity lastPlayer;
    private static State state = State.NONE;
    private static int stateTicks;
    private static int airTicks;
    private static int cooldown;
    private static int wallRunBudget;
    private static boolean wasJump, wasShift, wasGround;
    private static double lastVy;
    private static double momentum;
    private static double appliedBonus = -1;
    private static boolean appliedBalance;
    private static boolean balancing;
    private static int clientTicks;

    // wall run
    private static Vector3d wallNormal = Vector3d.ZERO;
    private static Vector3d runDir = Vector3d.ZERO;
    private static int wallSide;
    private static double runSpeed;

    // slide
    private static Vector3d slideDir = Vector3d.ZERO;
    private static double slideSpeed;
    private static boolean slideLow;

    // vault / pull-up
    private static Vector3d vaultDir = Vector3d.ZERO;
    private static double vaultTop;
    private static double vaultSpeed;
    private static int vaultFwdMax;
    private static boolean vaultRising;
    private static int vaultForwardTicks;

    // ledge hang / wall climb
    private static Direction climbDir = Direction.NORTH;
    private static double hangTop;

    // animation values (previous / current) for smooth interpolation
    private static float tilt, prevTilt;
    private static float slideAmt, prevSlide;
    private static float vaultAmt, prevVault;   // >0 reaching up, <0 pushing off the ledge
    private static float momAmt, prevMom;
    private static float wallAmt, prevWall;     // signed: +1 wall on right, -1 wall on left
    private static float kick, prevKick;
    private static float landAmt, prevLand;
    private static float fallAmt, prevFall;
    private static float balAmt, prevBal;
    private static float hangAmt, prevHang;
    private static float climbAmt, prevClimb;
    private static float climbPhase, prevClimbPhase;
    private static float gaitAmt, prevGaitAmt;
    private static float gaitPhase, prevGaitPhase;
    private static float lean, prevLean;
    private static float swayYaw, prevSwayYaw;
    private static float swayPitch, prevSwayPitch;
    private static float fallTarget;

    // first-person transform bookkeeping (so we can undo it between the two hand events)
    private static final List<double[]> applied = new ArrayList<>();

    // ------------------------------------------------------------ public info

    public static boolean isEnabled() { return enabled; }
    public static boolean isHelpVisible() { return helpVisible; }
    public static double getMomentum() { return momentum; }
    public static boolean isBusy() { return state != State.NONE; }

    public static String getStateLabel() {
        switch (state) {
            case WALLRUN: return "БЕГ ПО СТЕНЕ";
            case SLIDE:   return "ПОДКАТ";
            case VAULT:   return "ВСКАКИВАНИЕ";
            case HANG:    return "ЗАЦЕП";
            case CLIMB:   return "ЛАЗАНИЕ";
            default:
                if (balancing) return "БАЛАНС";
                if (momentum > 0.05D) return "РАЗГОН";
                return "ПАРКУР";
        }
    }

    public static void toggle() {
        enabled = !enabled;
        endState();
        momentum = 0;
        kick = 0;
        landAmt = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(new StringTextComponent(
                    enabled ? "\u00a7aПаркур-режим: ВКЛ" : "\u00a7cПаркур-режим: ВЫКЛ"), true);
        }
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        while (TOGGLE_KEY.consumeClick()) {
            toggle();
        }
        while (HELP_KEY.consumeClick()) {
            helpVisible = !helpVisible;
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!event.side.isClient()) return;
        if (!(event.player instanceof ClientPlayerEntity)) return;
        ClientPlayerEntity p = (ClientPlayerEntity) event.player;
        if (p != Minecraft.getInstance().player) return;

        if (event.phase == TickEvent.Phase.START) {
            tick(p);
        } else if (state == State.SLIDE && slideLow) {
            // vanilla resets the pose at the end of the tick; keep the low pose for rendering/camera
            p.setPose(Pose.SWIMMING);
        }
    }

    @SubscribeEvent
    public static void onCamera(EntityViewRenderEvent.CameraSetup e) {
        applied.clear(); // new frame, new matrix stack

        float pt = (float) e.getRenderPartialTicks();
        float roll = lerp(pt, prevTilt, tilt);
        float slide = lerp(pt, prevSlide, slideAmt);
        float vault = lerp(pt, prevVault, vaultAmt);
        float land = lerp(pt, prevLand, landAmt);
        float kk = lerp(pt, prevKick, kick);
        float bal = lerp(pt, prevBal, balAmt);
        float gait = lerp(pt, prevGaitAmt, gaitAmt);
        float gp = lerp(pt, prevGaitPhase, gaitPhase);
        float ln = lerp(pt, prevLean, lean);
        float cl = lerp(pt, prevClimb, climbAmt);
        float cp = lerp(pt, prevClimbPhase, climbPhase);
        float t = clientTicks + pt;

        roll += ln;                                        // lean into strafing
        roll += (float) Math.sin(gp) * 0.8F * gait;        // head sway of the stride
        roll += (float) Math.sin(t * 0.12F) * 1.5F * bal;  // balancing sway
        roll += (float) Math.sin(cp) * 1.2F * cl;          // climbing sway
        roll += KatanaClient.camRoll(pt);
        e.setRoll(e.getRoll() + roll);

        float pitch = slide * 2.5F + vault * 5.0F + land * 4.0F - kk * 1.5F;
        pitch += (float) Math.sin(gp * 2.0F) * 0.25F * gait;
        pitch += KatanaClient.camPitch(pt);
        e.setPitch(e.getPitch() + pitch);
    }

    /**
     * RenderHandEvent fires once per hand on one shared matrix stack. We never push/pop
     * (that unbalances the stack and breaks screen overlays); instead we undo our previous
     * transform before applying the next one.
     */
    @SubscribeEvent
    public static void onHand(RenderHandEvent e) {
        MatrixStack ms = e.getMatrixStack();
        undo(ms);

        boolean main = e.getHand() == Hand.MAIN_HAND;
        ClientPlayerEntity pl = Minecraft.getInstance().player;
        boolean rightMain = pl == null || pl.getMainArm() == HandSide.RIGHT;
        float s = (main == rightMain) ? 1.0F : -1.0F;
        float pt = e.getPartialTicks();

        animateHand(ms, pt, s);

        boolean katana = pl != null && pl.getMainHandItem().getItem() instanceof KatanaItem;
        if (main && katana) {
            e.setCanceled(true);
            renderKatana(ms, pt, s, pl);
            return;
        }

        // empty off-hand: vanilla draws no arm, so draw one ourselves while parkouring
        if (!main && pl != null && !katana && armsVisible() && !pl.isInvisible()
                && pl.getItemInHand(Hand.OFF_HAND).isEmpty()) {
            renderExtraArm(ms, pt, s, pl);
        }
    }

    // ------------------------------------------------------------ hand drawing

    private static Vector3f axisOf(int a) {
        return a == 0 ? Vector3f.XP : (a == 1 ? Vector3f.YP : Vector3f.ZP);
    }

    private static void tr(MatrixStack ms, double x, double y, double z) {
        ms.translate(x, y, z);
        applied.add(new double[] {0, x, y, z});
    }

    private static void rot(MatrixStack ms, int axis, float deg) {
        ms.mulPose(axisOf(axis).rotationDegrees(deg));
        applied.add(new double[] {1, axis, deg, 0});
    }

    private static void undo(MatrixStack ms) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            double[] o = applied.get(i);
            if (o[0] == 0) {
                ms.translate(-o[1], -o[2], -o[3]);
            } else {
                ms.mulPose(axisOf((int) o[1]).rotationDegrees((float) -o[2]));
            }
        }
        applied.clear();
    }

    private static boolean armsVisible() {
        return enabled && (gaitAmt > 0.15F || momAmt > 0.05F || slideAmt > 0.02F || Math.abs(wallAmt) > 0.02F
                || Math.abs(vaultAmt) > 0.02F || balAmt > 0.02F || kick > 0.05F || landAmt > 0.05F
                || fallAmt > 0.05F || hangAmt > 0.02F || climbAmt > 0.02F);
    }

    /*
     * Axis cheat-sheet (view space, hand on the right side, camera looks along -Z):
     *   rot X  +  lifts the far end of the arm UP,   -  points it DOWN
     *   rot Z  +s swings the hand OUTWARD and up (s = +1 right hand, -1 left hand)
     */
    private static void animateHand(MatrixStack ms, float pt, float s) {
        float slide = lerp(pt, prevSlide, slideAmt);
        float vault = lerp(pt, prevVault, vaultAmt);
        float mom = lerp(pt, prevMom, momAmt);
        float wa = lerp(pt, prevWall, wallAmt);
        float kk = lerp(pt, prevKick, kick);
        float land = lerp(pt, prevLand, landAmt);
        float fall = lerp(pt, prevFall, fallAmt);
        float bal = lerp(pt, prevBal, balAmt);
        float hang = lerp(pt, prevHang, hangAmt);
        float climb = lerp(pt, prevClimb, climbAmt);
        float cph = lerp(pt, prevClimbPhase, climbPhase) + (s > 0 ? 0.0F : (float) Math.PI);
        float gait = lerp(pt, prevGaitAmt, gaitAmt);
        float gph = lerp(pt, prevGaitPhase, gaitPhase) + (s > 0 ? 0.0F : (float) Math.PI);
        float sy = lerp(pt, prevSwayYaw, swayYaw);
        float sp = lerp(pt, prevSwayPitch, swayPitch);
        float t = clientTicks + pt;

        // locomotion: hands lag behind the camera (inertia)
        tr(ms, -sy * 0.004D, sp * 0.004D, 0.0D);
        rot(ms, 2, -sy * 0.25F);
        rot(ms, 0, sp * 0.2F);

        // locomotion: stride - arms swing at the sides, out and back, opposite phase
        if (gait > 0.001F) {
            tr(ms, s * (0.07D + 0.03D * Math.sin(gph * 0.5F + 1.0F)) * gait,
                    Math.sin(gph) * 0.035D * gait - 0.02D * gait - 0.02D * mom,
                    Math.cos(gph) * 0.07D * gait);
            rot(ms, 0, (float) Math.sin(gph) * 9.0F * gait);
            rot(ms, 2, s * (6.0F + 5.0F * (float) Math.sin(gph + 1.2F)) * gait);
        }

        // balancing on a fence: arms spread wide and wobble
        if (bal > 0.001F) {
            tr(ms, s * 0.30D * bal, 0.10D * bal, 0.0D);
            rot(ms, 2, s * 38.0F * bal + (float) Math.sin(t * 0.17F + s) * 4.0F * bal);
        }

        // wall run: hand on the wall side is pressed against it, the other hangs down
        float aw = Math.abs(wa);
        if (aw > 0.001F) {
            if (Math.signum(wa) == s) {
                tr(ms, s * 0.30D * aw, 0.15D * aw + Math.sin(t * 1.8F) * 0.01D * aw, -0.10D * aw);
                rot(ms, 2, s * 38.0F * aw);
            } else {
                tr(ms, -s * 0.05D * aw, -0.32D * aw, 0.05D * aw);
                rot(ms, 0, -40.0F * aw);
                rot(ms, 2, -s * 10.0F * aw);
            }
        }

        // slide: hands low and braced
        if (slide > 0.001F) {
            tr(ms, s * 0.10D * slide, -0.18D * slide + Math.sin(t * 2.2F) * 0.004D * slide, 0.10D * slide);
            rot(ms, 0, -20.0F * slide);
            rot(ms, 2, s * 14.0F * slide);
        }

        // vault / pull-up: reach up and forward, then push off the ledge
        if (vault > 0.001F) {
            tr(ms, s * 0.04D * vault, 0.18D * vault, -0.20D * vault);
            rot(ms, 0, 40.0F * vault);
        } else if (vault < -0.001F) {
            float k = -vault;
            tr(ms, 0.0D, -0.15D * k, 0.03D * k);
            rot(ms, 0, -20.0F * k);
        }

        // ledge hang: both arms straight up, gripping
        if (hang > 0.001F) {
            tr(ms, s * 0.06D * hang, 0.30D * hang, -0.10D * hang);
            rot(ms, 0, 70.0F * hang);
        }

        // wall climb: hands reach up alternately
        if (climb > 0.001F) {
            float reach = (float) Math.sin(cph);
            tr(ms, s * 0.06D * climb, (0.22D + 0.12D * reach) * climb, -0.08D * climb);
            rot(ms, 0, (55.0F + 12.0F * reach) * climb);
        }

        // jump / wall jump thrust
        if (kk > 0.001F) {
            tr(ms, 0.0D, 0.10D * kk, -0.20D * kk);
            rot(ms, 0, 18.0F * kk);
        }

        // landing impact
        if (land > 0.001F) {
            tr(ms, 0.0D, -0.30D * land, 0.06D * land);
            rot(ms, 0, -16.0F * land);
        }

        // falling fast: hands lift and shake in the wind
        if (fall > 0.001F) {
            tr(ms, Math.sin(t * 2.5F + s) * 0.01D * fall, 0.07D * fall + Math.cos(t * 2.9F) * 0.01D * fall,
                    -0.05D * fall);
        }
    }

    /** Draws the off-hand arm exactly like vanilla draws the main arm (no swing). */
    private static void renderExtraArm(MatrixStack ms, float pt, float f, ClientPlayerEntity pl) {
        Minecraft mc = Minecraft.getInstance();
        ms.pushPose();
        ms.translate(f * 0.64000005F, -0.6F, -0.71999997F);
        ms.mulPose(Vector3f.YP.rotationDegrees(f * 45.0F));
        ms.translate(f * -1.0F, 3.6F, 3.5F);
        ms.mulPose(Vector3f.ZP.rotationDegrees(f * 120.0F));
        ms.mulPose(Vector3f.XP.rotationDegrees(200.0F));
        ms.mulPose(Vector3f.YP.rotationDegrees(f * -135.0F));
        ms.translate(f * 5.6F, 0.0D, 0.0D);
        PlayerRenderer renderer = (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(pl);
        IRenderTypeBuffer buffer = mc.renderBuffers().bufferSource();
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(pl, pt);
        if (f > 0) {
            renderer.renderRightHand(ms, buffer, light, pl);
        } else {
            renderer.renderLeftHand(ms, buffer, light, pl);
        }
        ms.popPose();
    }

    /** Draws the held katana with our own animation instead of the vanilla swing. */
    private static void renderKatana(MatrixStack ms, float pt, float s, ClientPlayerEntity pl) {
        Minecraft mc = Minecraft.getInstance();
        float[] k = KatanaClient.pose(pt);
        float draw = KatanaClient.draw(pt);

        tr(ms, s * 0.56D, -0.52D - 0.6D * draw, -0.72D);
        tr(ms, k[0] * s, k[1], k[2]);
        rot(ms, 1, k[4] * s);
        rot(ms, 0, k[3]);
        rot(ms, 2, k[5] * s);

        ItemCameraTransforms.TransformType type = s > 0
                ? ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND
                : ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND;
        IRenderTypeBuffer buffer = mc.renderBuffers().bufferSource();
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(pl, pt);
        mc.getItemRenderer().renderStatic(pl, pl.getMainHandItem(), type, s < 0, ms, buffer, pl.level, light,
                OverlayTexture.NO_OVERLAY);
    }

    // ------------------------------------------------------------------- logic

    private static void capturePrev() {
        prevTilt = tilt;
        prevSlide = slideAmt;
        prevVault = vaultAmt;
        prevMom = momAmt;
        prevWall = wallAmt;
        prevKick = kick;
        prevLand = landAmt;
        prevFall = fallAmt;
        prevBal = balAmt;
        prevHang = hangAmt;
        prevClimb = climbAmt;
        prevClimbPhase = climbPhase;
        prevGaitAmt = gaitAmt;
        prevGaitPhase = gaitPhase;
        prevLean = lean;
        prevSwayYaw = swayYaw;
        prevSwayPitch = swayPitch;
    }

    private static void tick(ClientPlayerEntity p) {
        Minecraft mc = Minecraft.getInstance();
        capturePrev();

        if (p != lastPlayer) {
            lastPlayer = p;
            slideLow = false;
            state = State.NONE;
            stateTicks = 0;
            momentum = 0;
            appliedBonus = -1;
            appliedBalance = false;
            balancing = false;
            cooldown = 0;
            kick = 0;
            landAmt = 0;
            wasGround = p.isOnGround();
        }

        GameSettings o = mc.options;
        boolean fwd = o.keyUp.isDown();
        boolean jump = o.keyJump.isDown();
        boolean shift = o.keyShift.isDown();
        boolean jumpEdge = jump && !wasJump;
        boolean shiftEdge = shift && !wasShift;
        wasJump = jump;
        wasShift = shift;

        Vector3d m = p.getDeltaMovement();
        double hs = Math.sqrt(m.x * m.x + m.z * m.z);
        boolean ground = p.isOnGround();

        clientTicks++;
        if (cooldown > 0) cooldown--;
        if (ground) {
            airTicks = 0;
            wallRunBudget = 60;
        } else {
            airTicks++;
        }

        boolean disabled = !enabled || p.isSpectator() || p.abilities.flying || p.isFallFlying()
                || p.isPassenger() || p.isInWater() || p.isInLava() || p.onClimbable() || p.isSwimming();

        fallTarget = 0.0F;
        balancing = false;
        if (disabled) {
            endState();
            momentum = 0;
        } else {
            balancing = ground && isOnFence(p);
            if (balancing) {
                if (state != State.NONE) endState();
                p.setSprinting(false);
            }

            updateMomentum(p, fwd, ground);

            // landing impact (uses velocity from the previous tick)
            if (ground && !wasGround && lastVy < -0.5D && state == State.NONE) {
                landAmt = Math.max(landAmt, (float) MathHelper.clamp((-lastVy - 0.4D) / 0.8D, 0.15D, 1.0D));
            }
            if (state == State.NONE && !ground) {
                fallTarget = (float) MathHelper.clamp((-m.y - 0.3D) / 0.7D, 0.0D, 1.0D);
            }
            if (state == State.NONE && ground && jumpEdge && !balancing) {
                kick = Math.max(kick, 0.5F);
            }

            if (state == State.NONE && !balancing) {
                startMoves(p, m, hs, ground, fwd, jump, shiftEdge);
            }

            switch (state) {
                case WALLRUN: doWallRun(p, fwd, jumpEdge); break;
                case SLIDE:   doSlide(p, jumpEdge); break;
                case VAULT:   doVault(p); break;
                case HANG:    doHang(p, o, fwd, jump, shift); break;
                case CLIMB:   doClimb(p, o, fwd, jump, shift, jumpEdge); break;
                default: break;
            }
        }

        updateLocomotion(p, m, hs, ground, disabled);
        applyModifiers(p);
        updateAnim();

        wasGround = ground;
        lastVy = m.y;
    }

    /** Decides which parkour move (if any) starts this tick. */
    private static void startMoves(ClientPlayerEntity p, Vector3d m, double hs, boolean ground,
                                   boolean fwd, boolean jump, boolean shiftEdge) {
        Vector3d look = horizontalLook(p);
        if (look != null && cooldown == 0 && fwd) {
            Direction fd = Direction.getNearest((float) look.x, 0.0F, (float) look.z);
            Vector3d fv = new Vector3d(fd.getStepX(), 0.0D, fd.getStepZ());
            boolean facing = look.x * fv.x + look.z * fv.z > 0.7D;
            if (facing) {
                double top = findLedge(p.level, p.getX(), p.getY(), p.getZ(), fv, 2.6D);
                double rise = top - p.getY();
                if (ground) {
                    if (jump) {
                        if (!Double.isNaN(top)) startVault(fv, top, 0.5D, 3);
                        else if (wallAhead(p, fv, true)) startClimb(fd);
                    }
                } else if (airTicks >= 1) {
                    if (!Double.isNaN(top) && rise >= 1.4D && rise <= 2.35D && m.y <= 0.3D
                            && hasSupport(p.level, p.getX(), p.getZ(), top, fv)) {
                        startHang(fd, top);                       // jumped at a ledge: grab it
                    } else if (jump) {
                        if (!Double.isNaN(top) && rise < 1.4D) startVault(fv, top, 0.5D, 3);
                        else if (wallAhead(p, fv, true)) startClimb(fd);
                    }
                }
            }
        }
        // slide: ONE press of Shift while running; holding or releasing does not matter
        if (state == State.NONE && ground && shiftEdge && (p.isSprinting() || hs > 0.13D)) {
            startSlide(p, m, hs);
        }
        if (state == State.NONE && !ground && airTicks >= 2 && fwd && hs > 0.2D
                && wallRunBudget > 0 && cooldown == 0) {
            tryStartWallRun(m, hs);
        }
    }

    private static void updateMomentum(ClientPlayerEntity p, boolean fwd, boolean ground) {
        if (balancing) {
            momentum = 0.0D;
        } else if (state != State.NONE) {
            // keep momentum during parkour moves
        } else if (ground && fwd && p.isSprinting()) {
            momentum = Math.min(1.0D, momentum + 0.015D);
        } else if (ground) {
            momentum = Math.max(0.0D, momentum - 0.06D);
        } else {
            momentum = Math.max(0.0D, momentum - 0.002D);
        }
        if (p.horizontalCollision && state == State.NONE) {
            momentum = Math.max(0.0D, momentum - 0.1D);
        }
    }

    /** Procedural locomotion layer: stride phase, strafe lean and camera-inertia sway. */
    private static void updateLocomotion(ClientPlayerEntity p, Vector3d m, double hs, boolean ground,
                                         boolean disabled) {
        boolean active = enabled && !disabled;

        float gaitT = 0.0F;
        if (active && state == State.NONE && ground) {
            gaitT = (float) MathHelper.clamp(hs / 0.15D, 0.0D, 1.2D);
            if (balancing) gaitT *= 0.5F;
        } else if (active && state == State.NONE) {
            gaitT = 0.2F;
        }
        gaitAmt += (gaitT - gaitAmt) * 0.2F;
        gaitPhase += 0.28F * gaitAmt + 0.25F * (float) momentum;

        double yaw = Math.toRadians(p.yRot);
        double lat = m.x * -Math.cos(yaw) + m.z * -Math.sin(yaw); // right-hand component
        float leanT = active ? (float) MathHelper.clamp(lat * 25.0D, -4.0D, 4.0D) : 0.0F;
        lean += (leanT - lean) * 0.25F;

        float dyaw = MathHelper.wrapDegrees(p.yRot - p.yRotO);
        float dpit = p.xRot - p.xRotO;
        float syT = active ? MathHelper.clamp(dyaw, -25.0F, 25.0F) : 0.0F;
        float spT = active ? MathHelper.clamp(dpit, -20.0F, 20.0F) : 0.0F;
        swayYaw += (syT - swayYaw) * 0.35F;
        swayPitch += (spT - swayPitch) * 0.35F;
    }

    /** Standing on top of a fence / wall (their collision box is 1.5 high). */
    private static boolean isOnFence(ClientPlayerEntity p) {
        double frac = p.getY() - Math.floor(p.getY());
        if (Math.abs(frac - 0.5D) > 0.06D) return false;
        BlockPos pos = new BlockPos(p.getX(), p.getY() - 0.6D, p.getZ());
        BlockState bs = p.level.getBlockState(pos);
        return bs.is(BlockTags.FENCES) || bs.is(BlockTags.WALLS);
    }

    // --------------------------------------------------------------- wall run

    private static void tryStartWallRun(Vector3d m, double hs) {
        double mx = m.x / hs;
        double mz = m.z / hs;
        Vector3d wall = findWall(mx, mz);
        if (wall == null) return;

        double dot = mx * wall.x + mz * wall.z;
        double tx = mx - wall.x * dot;
        double tz = mz - wall.z * dot;
        double tl = Math.sqrt(tx * tx + tz * tz);
        if (tl < 1.0E-4D) return;

        wallNormal = wall;
        runDir = new Vector3d(tx / tl, 0.0D, tz / tl);
        wallSide = (mx * wall.z - mz * wall.x) > 0 ? 1 : -1;
        runSpeed = MathHelper.clamp(hs, 0.3D, 0.5D);
        state = State.WALLRUN;
        stateTicks = 0;
    }

    private static void doWallRun(ClientPlayerEntity p, boolean fwd, boolean jumpEdge) {
        stateTicks++;
        p.fallDistance = 0.0F;
        wallRunBudget--;

        boolean wallThere = solidAt(p, wallNormal.x * 0.55D, wallNormal.z * 0.55D, 0.3D)
                && solidAt(p, wallNormal.x * 0.55D, wallNormal.z * 0.55D, 1.2D);
        boolean blockedAhead = solidAt(p, runDir.x * 0.6D, runDir.z * 0.6D, 0.3D);

        if (jumpEdge && stateTicks >= 2) {
            // wall jump: away from the wall + forward
            p.setDeltaMovement(
                    -wallNormal.x * 0.42D + runDir.x * runSpeed * 0.8D,
                    0.52D,
                    -wallNormal.z * 0.42D + runDir.z * runSpeed * 0.8D);
            endState();
            cooldown = 10;
            kick = 1.0F;
            return;
        }

        runSpeed *= 0.994D;
        if (p.isOnGround() || !wallThere || blockedAhead || !fwd || stateTicks > 45 || runSpeed < 0.22D) {
            endState();
            cooldown = 6;
            return;
        }

        double y = stateTicks < 6 ? 0.1D : -0.03D - 0.002D * (stateTicks - 6);
        p.setDeltaMovement(
                runDir.x * runSpeed + wallNormal.x * 0.03D,
                y,
                runDir.z * runSpeed + wallNormal.z * 0.03D);
    }

    private static Vector3d findWall(double mx, double mz) {
        ClientPlayerEntity p = Minecraft.getInstance().player;
        if (p == null) return null;
        Vector3d best = null;
        double bestDot = 0.8D;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            double ox = d.getStepX() * 0.55D;
            double oz = d.getStepZ() * 0.55D;
            if (solidAt(p, ox, oz, 0.3D) && solidAt(p, ox, oz, 1.2D)) {
                double dot = Math.abs(mx * d.getStepX() + mz * d.getStepZ());
                if (dot < bestDot) {
                    bestDot = dot;
                    best = new Vector3d(d.getStepX(), 0.0D, d.getStepZ());
                }
            }
        }
        return best;
    }

    private static boolean solidAt(ClientPlayerEntity p, double ox, double oz, double h) {
        BlockPos pos = new BlockPos(p.getX() + ox, p.getY() + h, p.getZ() + oz);
        World w = p.level;
        return !w.getBlockState(pos).getCollisionShape(w, pos).isEmpty();
    }

    /** A wall right in front of the player (both body heights, or either one if requireBoth is false). */
    private static boolean wallAhead(ClientPlayerEntity p, Vector3d dir, boolean requireBoth) {
        boolean low = solidAt(p, dir.x * 0.55D, dir.z * 0.55D, 0.3D);
        boolean high = solidAt(p, dir.x * 0.55D, dir.z * 0.55D, 1.2D);
        return requireBoth ? (low && high) : (low || high);
    }

    // ------------------------------------------------------------------ slide

    private static void startSlide(ClientPlayerEntity p, Vector3d m, double hs) {
        if (hs > 0.03D) {
            slideDir = new Vector3d(m.x / hs, 0.0D, m.z / hs);
        } else {
            Vector3d look = horizontalLook(p);
            if (look == null) return;
            slideDir = look;
        }
        slideSpeed = 0.40D + 0.22D * momentum;
        state = State.SLIDE;
        stateTicks = 0;
        slideLow = sendSlide(true);
        if (slideLow) p.setPose(Pose.SWIMMING);
    }

    private static void doSlide(ClientPlayerEntity p, boolean jumpEdge) {
        stateTicks++;
        slideSpeed *= 0.955D;
        Vector3d m = p.getDeltaMovement();
        if (slideLow) p.setPose(Pose.SWIMMING);

        if (jumpEdge) {
            // slide-jump keeps the speed
            p.setDeltaMovement(slideDir.x * slideSpeed * 1.1D, m.y, slideDir.z * slideSpeed * 1.1D);
            endState();
            kick = 0.7F;
            return;
        }
        if (slideSpeed < 0.13D || p.horizontalCollision || airTicks > 6 || stateTicks > 45) {
            endState();
            return;
        }
        p.setDeltaMovement(slideDir.x * slideSpeed, m.y, slideDir.z * slideSpeed);
    }

    /** Tells the server we are sliding. Returns true if the server can understand it. */
    private static boolean sendSlide(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;
        if (NetworkHooks.isVanillaConnection(mc.getConnection().getConnection())) return false;
        ParkourNet.CHANNEL.sendToServer(new SlidePacket(on));
        return true;
    }

    // ------------------------------------------------- vault / pull-up (shared)

    private static void startVault(Vector3d dir, double top, double speed, int fwdTicks) {
        vaultDir = dir;
        vaultTop = top;
        vaultSpeed = speed;
        vaultFwdMax = fwdTicks;
        vaultRising = true;
        vaultForwardTicks = 0;
        state = State.VAULT;
        stateTicks = 0;
        kick = Math.max(kick, speed > 0.4D ? 0.6F : 0.2F);
    }

    private static void doVault(ClientPlayerEntity p) {
        stateTicks++;
        p.fallDistance = 0.0F;
        if (stateTicks > 30) {
            endState();
            cooldown = 8;
            return;
        }
        if (vaultRising && p.getY() >= vaultTop) vaultRising = false;

        double fwdSpeed = vaultSpeed > 0.4D ? 0.3D : 0.22D;
        if (vaultRising) {
            double vy = Math.min(vaultSpeed, vaultTop + 0.05D - p.getY());
            p.setDeltaMovement(vaultDir.x * 0.1D, vy, vaultDir.z * 0.1D);
        } else {
            vaultForwardTicks++;
            p.setDeltaMovement(vaultDir.x * fwdSpeed, 0.0D, vaultDir.z * fwdSpeed);
            if (vaultForwardTicks >= vaultFwdMax) {
                endState();
                cooldown = 8;
            }
        }
    }

    // ------------------------------------------------------------- ledge hang

    private static void startHang(Direction d, double top) {
        climbDir = d;
        hangTop = top;
        state = State.HANG;
        stateTicks = 0;
        kick = Math.max(kick, 0.4F);
    }

    private static void startPullUp(Direction d, double top) {
        startVault(new Vector3d(d.getStepX(), 0.0D, d.getStepZ()), top, 0.28D, 4);
    }

    private static void doHang(ClientPlayerEntity p, GameSettings o, boolean fwd, boolean jump, boolean shift) {
        stateTicks++;
        p.fallDistance = 0.0F;
        Vector3d n = new Vector3d(climbDir.getStepX(), 0.0D, climbDir.getStepZ());

        double top = findLedge(p.level, p.getX(), p.getY(), p.getZ(), n, 2.6D);
        if (Double.isNaN(top) || stateTicks > 600) {
            endState();
            cooldown = 6;
            return;
        }
        hangTop = top;

        if (shift || o.keyDown.isDown()) {          // let go
            endState();
            cooldown = 12;
            return;
        }
        if (stateTicks >= 6 && (fwd || jump)) {     // pull up
            startPullUp(climbDir, top);
            return;
        }

        // shimmy sideways along the ledge
        double rx = -climbDir.getStepZ();
        double rz = climbDir.getStepX();
        int lat = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
        if (lat != 0 && Double.isNaN(findLedge(p.level, p.getX() + rx * lat * 0.4D, p.getY(),
                p.getZ() + rz * lat * 0.4D, n, 2.6D))) {
            lat = 0;
        }
        if (lat != 0) climbPhase += 0.3F;

        Vector3d st = faceVelocity(p, climbDir, top - 0.1D);
        double vy = MathHelper.clamp((top - 2.1D - p.getY()) * 0.5D, -0.3D, 0.3D);
        p.setDeltaMovement(st.x + rx * lat * 0.06D, vy, st.z + rz * lat * 0.06D);
    }

    // ------------------------------------------------------------- wall climb

    private static void startClimb(Direction d) {
        climbDir = d;
        state = State.CLIMB;
        stateTicks = 0;
        climbPhase = 0.0F;
    }

    private static void doClimb(ClientPlayerEntity p, GameSettings o, boolean fwd, boolean jump,
                                boolean shift, boolean jumpEdge) {
        stateTicks++;
        p.fallDistance = 0.0F;
        Vector3d n = new Vector3d(climbDir.getStepX(), 0.0D, climbDir.getStepZ());

        if (!wallAhead(p, n, false) || stateTicks > 1200 || (!fwd && !jump && !shift)) {
            endState();
            cooldown = 6;
            return;
        }
        // S + Space: push off the wall backwards
        if (o.keyDown.isDown() && jumpEdge) {
            p.setDeltaMovement(-n.x * 0.42D, 0.5D, -n.z * 0.42D);
            endState();
            cooldown = 12;
            kick = 1.0F;
            return;
        }
        // reached the top: pull over the ledge
        if (jump) {
            double top = findLedge(p.level, p.getX(), p.getY(), p.getZ(), n, 2.3D);
            if (!Double.isNaN(top) && top - p.getY() >= 0.9D) {
                startPullUp(climbDir, top);
                return;
            }
        }

        double vy = jump ? 0.13D : (shift ? -0.12D : 0.0D);
        if (vy < 0.0D && p.isOnGround()) {
            endState();
            cooldown = 6;
            return;
        }

        double rx = -climbDir.getStepZ();
        double rz = climbDir.getStepX();
        int lat = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
        if (lat != 0 && !solidAt(p, n.x * 0.55D + rx * lat * 0.4D, n.z * 0.55D + rz * lat * 0.4D, 0.8D)) {
            lat = 0;
        }
        if (vy != 0.0D || lat != 0) climbPhase += 0.45F;

        Vector3d st = faceVelocity(p, climbDir, p.getY() + 0.8D);
        p.setDeltaMovement(st.x + rx * lat * 0.07D, vy, st.z + rz * lat * 0.07D);
    }

    /** Small velocity that keeps the player 0.35 blocks away from the wall face in front of them. */
    private static Vector3d faceVelocity(ClientPlayerEntity p, Direction d, double probeY) {
        BlockPos bp = new BlockPos(p.getX() + d.getStepX() * 0.7D, probeY, p.getZ() + d.getStepZ() * 0.7D);
        if (d.getAxis() == Direction.Axis.X) {
            double target = d.getStepX() > 0 ? bp.getX() - 0.35D : bp.getX() + 1.35D;
            return new Vector3d(MathHelper.clamp((target - p.getX()) * 0.5D, -0.15D, 0.15D), 0.0D, 0.0D);
        }
        double target = d.getStepZ() > 0 ? bp.getZ() - 0.35D : bp.getZ() + 1.35D;
        return new Vector3d(0.0D, 0.0D, MathHelper.clamp((target - p.getZ()) * 0.5D, -0.15D, 0.15D));
    }

    // ---------------------------------------------------------------- ledges

    private static Vector3d horizontalLook(ClientPlayerEntity p) {
        Vector3d look = p.getLookAngle();
        double l = Math.sqrt(look.x * look.x + look.z * look.z);
        if (l < 1.0E-4D) return null;
        return new Vector3d(look.x / l, 0.0D, look.z / l);
    }

    /**
     * Top Y of the first ledge ahead (a solid block with 2 free blocks above it) whose top is
     * between 0.6 and maxRise above py, or NaN. Tall walls are scanned upwards block by block.
     */
    private static double findLedge(World w, double px, double py, double pz, Vector3d dir, double maxRise) {
        double x = px + dir.x * 0.7D;
        double z = pz + dir.z * 0.7D;
        int baseY = MathHelper.floor(py + 0.001D);
        int maxK = (int) Math.ceil(maxRise) + 1;

        for (int k = 0; k <= maxK; k++) {
            BlockPos ledge = new BlockPos(x, baseY + k, z);
            BlockState bs = w.getBlockState(ledge);
            VoxelShape shape = bs.getCollisionShape(w, ledge);
            if (shape.isEmpty()) continue;

            BlockPos a1 = ledge.above();
            BlockPos a2 = ledge.above(2);
            if (!w.getBlockState(a1).getCollisionShape(w, a1).isEmpty()) continue;
            if (!w.getBlockState(a2).getCollisionShape(w, a2).isEmpty()) continue;

            double top = ledge.getY() + shape.max(Direction.Axis.Y);
            double rise = top - py;
            if (rise > 0.6D && rise <= maxRise) return top;
            if (rise > maxRise) return Double.NaN;
        }
        return Double.NaN;
    }

    /** There is solid wall under the ledge (so we are not grabbing a floating block). */
    private static boolean hasSupport(World w, double px, double pz, double top, Vector3d dir) {
        BlockPos ledge = new BlockPos(px + dir.x * 0.7D, top - 0.1D, pz + dir.z * 0.7D);
        BlockPos below = ledge.below();
        return !w.getBlockState(below).getCollisionShape(w, below).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static void endState() {
        if (state == State.SLIDE && slideLow) {
            sendSlide(false);
            slideLow = false;
        }
        state = State.NONE;
        stateTicks = 0;
    }

    private static void applyModifiers(ClientPlayerEntity p) {
        ModifiableAttributeInstance attr = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;

        // momentum bonus
        double bonus = Math.round(momentum * 40.0D) / 100.0D; // 0.00 .. 0.40
        boolean has = attr.getModifier(SPEED_ID) != null;
        if (bonus != appliedBonus || has != (bonus > 0)) {
            attr.removeModifier(SPEED_ID);
            if (bonus > 0) {
                attr.addTransientModifier(new AttributeModifier(SPEED_ID, "edgerunner_momentum", bonus,
                        AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
            appliedBonus = bonus;
        }

        // slow, careful walking on fences
        boolean hasBal = attr.getModifier(BALANCE_ID) != null;
        if (balancing != appliedBalance || hasBal != balancing) {
            attr.removeModifier(BALANCE_ID);
            if (balancing) {
                attr.addTransientModifier(new AttributeModifier(BALANCE_ID, "edgerunner_balance", -0.55D,
                        AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
            appliedBalance = balancing;
        }
    }

    private static void updateAnim() {
        float tiltT = state == State.WALLRUN ? -wallSide * 9.0F * TILT_DIR : 0.0F;
        float slideT = state == State.SLIDE ? 1.0F : 0.0F;
        float vaultT = state == State.VAULT ? (vaultRising ? 1.0F : -0.6F) : 0.0F;
        float wallT = state == State.WALLRUN ? wallSide : 0.0F;
        float balT = balancing ? 1.0F : 0.0F;
        float hangT = state == State.HANG ? 1.0F : 0.0F;
        float climbT = state == State.CLIMB ? 1.0F : 0.0F;

        tilt += (tiltT - tilt) * 0.3F;
        slideAmt += (slideT - slideAmt) * 0.35F;
        vaultAmt += (vaultT - vaultAmt) * 0.4F;
        wallAmt += (wallT - wallAmt) * 0.3F;
        balAmt += (balT - balAmt) * 0.25F;
        hangAmt += (hangT - hangAmt) * 0.35F;
        climbAmt += (climbT - climbAmt) * 0.35F;
        momAmt += ((float) momentum - momAmt) * 0.2F;
        fallAmt += (fallTarget - fallAmt) * 0.25F;
        kick *= 0.75F;
        landAmt *= 0.8F;
    }

    private static float lerp(float pt, float a, float b) {
        return a + (b - a) * pt;
    }
}
