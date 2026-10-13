package com.edgerunner;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.block.BlockState;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.PlayerRenderer;
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

    private enum State { NONE, WALLRUN, SLIDE, VAULT }

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

    // vault
    private static Vector3d vaultDir = Vector3d.ZERO;
    private static double vaultTop;
    private static boolean vaultRising;
    private static int vaultForwardTicks;

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
    private static float fallTarget;

    // first-person hand transform bookkeeping (so we can undo it between the two hand events)
    private static final List<double[]> applied = new ArrayList<>();

    // ------------------------------------------------------------ public info

    public static boolean isEnabled() { return enabled; }
    public static boolean isHelpVisible() { return helpVisible; }
    public static double getMomentum() { return momentum; }

    public static String getStateLabel() {
        switch (state) {
            case WALLRUN: return "БЕГ ПО СТЕНЕ";
            case SLIDE:   return "ПОДКАТ";
            case VAULT:   return "ВСКАКИВАНИЕ";
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
        float mom = lerp(pt, prevMom, momAmt);
        float land = lerp(pt, prevLand, landAmt);
        float kk = lerp(pt, prevKick, kick);
        float bal = lerp(pt, prevBal, balAmt);
        float t = clientTicks + pt;

        roll += (float) Math.sin(t * 0.5F) * 0.5F * mom;   // head sway while running fast
        roll += (float) Math.sin(t * 0.12F) * 1.5F * bal;  // balancing sway
        e.setRoll(e.getRoll() + roll);
        e.setPitch(e.getPitch() + slide * 2.5F + vault * 5.0F + land * 4.0F - kk * 1.5F);
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

        // empty off-hand: vanilla draws no arm, so draw one ourselves while parkouring
        if (!main && pl != null && armsVisible() && !pl.isInvisible()
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
        return enabled && (momAmt > 0.05F || slideAmt > 0.02F || Math.abs(wallAmt) > 0.02F
                || Math.abs(vaultAmt) > 0.02F || balAmt > 0.02F || kick > 0.05F
                || landAmt > 0.05F || fallAmt > 0.05F);
    }

    private static void animateHand(MatrixStack ms, float pt, float s) {
        float slide = lerp(pt, prevSlide, slideAmt);
        float vault = lerp(pt, prevVault, vaultAmt);
        float mom = lerp(pt, prevMom, momAmt);
        float wa = lerp(pt, prevWall, wallAmt);
        float kk = lerp(pt, prevKick, kick);
        float land = lerp(pt, prevLand, landAmt);
        float fall = lerp(pt, prevFall, fallAmt);
        float bal = lerp(pt, prevBal, balAmt);
        float t = clientTicks + pt;

        // running: arms pump in opposite phase
        if (mom > 0.001F) {
            float phase = t * 0.6F + (s > 0 ? 0.0F : (float) Math.PI);
            tr(ms, 0.0D, Math.sin(phase) * 0.04D * mom - 0.03D * mom, Math.cos(phase) * 0.06D * mom);
            rot(ms, 0, (float) Math.sin(phase) * 8.0F * mom);
        }

        // balancing on a fence: arms spread wide and wobble
        if (bal > 0.001F) {
            tr(ms, s * 0.30D * bal, 0.10D * bal, 0.0D);
            rot(ms, 2, s * 38.0F * bal + (float) Math.sin(t * 0.17F + s) * 4.0F * bal);
        }

        // wall run: the hand next to the wall reaches out and scrapes it, the far hand tucks in
        float aw = Math.abs(wa);
        if (aw > 0.001F) {
            if (Math.signum(wa) == s) {
                tr(ms, s * 0.22D * aw, 0.08D * aw + Math.sin(t * 1.8F) * 0.01D * aw, -0.05D * aw);
                rot(ms, 2, s * 30.0F * aw);
            } else {
                tr(ms, -s * 0.06D * aw, -0.10D * aw, 0.0D);
                rot(ms, 2, -s * 8.0F * aw);
            }
        }

        // slide: hands pushed down and out, bracing
        if (slide > 0.001F) {
            tr(ms, s * 0.10D * slide, -0.18D * slide + Math.sin(t * 2.2F) * 0.004D * slide, 0.10D * slide);
            rot(ms, 0, 25.0F * slide);
            rot(ms, 2, s * 14.0F * slide);
        }

        // vault: reach up and forward, then push off the ledge
        if (vault > 0.001F) {
            rot(ms, 0, -45.0F * vault);
            tr(ms, s * 0.04D * vault, 0.18D * vault, -0.20D * vault);
        } else if (vault < -0.001F) {
            float k = -vault;
            rot(ms, 0, 22.0F * k);
            tr(ms, 0.0D, -0.15D * k, 0.03D * k);
        }

        // jump / wall jump thrust
        if (kk > 0.001F) {
            tr(ms, 0.0D, 0.10D * kk, -0.20D * kk);
            rot(ms, 0, -18.0F * kk);
        }

        // landing impact
        if (land > 0.001F) {
            tr(ms, 0.0D, -0.30D * land, 0.06D * land);
            rot(ms, 0, 16.0F * land);
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
                Vector3d look = horizontalLook(p);
                if (look != null && fwd && jump && cooldown == 0) {
                    double top = findLedge(p, look);
                    if (!Double.isNaN(top)) startVault(look, top);
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

            switch (state) {
                case WALLRUN: doWallRun(p, fwd, jumpEdge); break;
                case SLIDE:   doSlide(p, jumpEdge); break;
                case VAULT:   doVault(p); break;
                default: break;
            }
        }

        applyModifiers(p);
        updateAnim();

        wasGround = ground;
        lastVy = m.y;
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

    // ------------------------------------------------------------------ vault

    private static void startVault(Vector3d dir, double top) {
        vaultDir = dir;
        vaultTop = top;
        vaultRising = true;
        vaultForwardTicks = 0;
        state = State.VAULT;
        stateTicks = 0;
        kick = Math.max(kick, 0.6F);
    }

    private static void doVault(ClientPlayerEntity p) {
        stateTicks++;
        p.fallDistance = 0.0F;
        if (stateTicks > 18) {
            endState();
            cooldown = 8;
            return;
        }
        if (vaultRising && p.getY() >= vaultTop) vaultRising = false;

        if (vaultRising) {
            double vy = Math.min(0.5D, vaultTop + 0.05D - p.getY());
            p.setDeltaMovement(vaultDir.x * 0.1D, vy, vaultDir.z * 0.1D);
        } else {
            vaultForwardTicks++;
            p.setDeltaMovement(vaultDir.x * 0.3D, 0.0D, vaultDir.z * 0.3D);
            if (vaultForwardTicks >= 3) {
                endState();
                cooldown = 8;
            }
        }
    }

    private static Vector3d horizontalLook(ClientPlayerEntity p) {
        Vector3d look = p.getLookAngle();
        double l = Math.sqrt(look.x * look.x + look.z * look.z);
        if (l < 1.0E-4D) return null;
        return new Vector3d(look.x / l, 0.0D, look.z / l);
    }

    /** Returns the Y of the top of a ledge that can be vaulted onto, or NaN. */
    private static double findLedge(ClientPlayerEntity p, Vector3d dir) {
        World w = p.level;
        double x = p.getX() + dir.x * 0.7D;
        double z = p.getZ() + dir.z * 0.7D;
        int baseY = MathHelper.floor(p.getY() + 0.001D);

        for (int k = 0; k <= 2; k++) {
            BlockPos ledge = new BlockPos(x, baseY + k, z);
            BlockState bs = w.getBlockState(ledge);
            VoxelShape shape = bs.getCollisionShape(w, ledge);
            if (shape.isEmpty()) continue;

            BlockPos a1 = ledge.above();
            BlockPos a2 = ledge.above(2);
            if (!w.getBlockState(a1).getCollisionShape(w, a1).isEmpty()) return Double.NaN;
            if (!w.getBlockState(a2).getCollisionShape(w, a2).isEmpty()) return Double.NaN;

            double top = ledge.getY() + shape.max(Direction.Axis.Y);
            double rise = top - p.getY();
            if (rise > 0.6D && rise <= 2.6D) return top;
            return Double.NaN;
        }
        return Double.NaN;
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

        tilt += (tiltT - tilt) * 0.3F;
        slideAmt += (slideT - slideAmt) * 0.35F;
        vaultAmt += (vaultT - vaultAmt) * 0.4F;
        wallAmt += (wallT - wallAmt) * 0.3F;
        balAmt += (balT - balAmt) * 0.25F;
        momAmt += ((float) momentum - momAmt) * 0.2F;
        fallAmt += (fallTarget - fallAmt) * 0.25F;
        kick *= 0.75F;
        landAmt *= 0.8F;
    }

    private static float lerp(float pt, float a, float b) {
        return a + (b - a) * pt;
    }
}
