package com.edgerunner;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.block.BlockState;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
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
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = EdgeRunner.MODID, value = Dist.CLIENT)
public class ParkourClient {

    private enum State { NONE, WALLRUN, SLIDE, VAULT }

    /** Key to toggle parkour mode (default G, changeable in Controls). */
    public static final KeyBinding TOGGLE_KEY =
            new KeyBinding("key.edgerunner.toggle", GLFW.GLFW_KEY_G, "key.categories.edgerunner");

    /** If the camera tilts TOWARD the wall instead of away, change 1.0F to -1.0F. */
    private static final float TILT_DIR = 1.0F;
    private static final UUID SPEED_ID = UUID.fromString("5b2d3f1e-7c41-4a0e-9a35-2f6f0d9b7c11");

    private static boolean enabled = true;

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
    private static int clientTicks;

    // wall run
    private static Vector3d wallNormal = Vector3d.ZERO;
    private static Vector3d runDir = Vector3d.ZERO;
    private static int wallSide;
    private static double runSpeed;

    // slide
    private static Vector3d slideDir = Vector3d.ZERO;
    private static double slideSpeed;

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
    private static float kick, prevKick;        // jump / wall-jump thrust
    private static float landAmt, prevLand;     // landing impact
    private static float fallAmt, prevFall;     // falling fast
    private static float fallTarget;

    private static boolean handPushed;

    // ------------------------------------------------------------------ toggle

    public static boolean isEnabled() {
        return enabled;
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
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !event.side.isClient()) return;
        if (!(event.player instanceof ClientPlayerEntity)) return;
        ClientPlayerEntity p = (ClientPlayerEntity) event.player;
        if (p != Minecraft.getInstance().player) return;
        tick(p);
    }

    @SubscribeEvent
    public static void onCamera(EntityViewRenderEvent.CameraSetup e) {
        handPushed = false; // new frame

        float pt = (float) e.getRenderPartialTicks();
        float roll = lerp(pt, prevTilt, tilt);
        float slide = lerp(pt, prevSlide, slideAmt);
        float vault = lerp(pt, prevVault, vaultAmt);
        float mom = lerp(pt, prevMom, momAmt);
        float land = lerp(pt, prevLand, landAmt);
        float kk = lerp(pt, prevKick, kick);
        float t = clientTicks + pt;

        roll += (float) Math.sin(t * 0.5F) * 0.5F * mom; // head sway while running fast
        e.setRoll(e.getRoll() + roll);
        e.setPitch(e.getPitch() + slide * 2.5F + vault * 5.0F + land * 4.0F - kk * 1.5F);
    }

    /**
     * RenderHandEvent fires once per hand and shares one matrix stack, so we
     * undo the previous hand's transform before applying the next one.
     */
    @SubscribeEvent
    public static void onHand(RenderHandEvent e) {
        MatrixStack ms = e.getMatrixStack();
        if (handPushed) {
            ms.popPose();
            handPushed = false;
        }
        ms.pushPose();
        boolean main = e.getHand() == Hand.MAIN_HAND;
        handPushed = main;

        ClientPlayerEntity pl = Minecraft.getInstance().player;
        boolean rightMain = pl == null || pl.getMainArm() == HandSide.RIGHT;
        boolean right = (main == rightMain);
        animateHand(ms, e.getPartialTicks(), right ? 1.0F : -1.0F);
    }

    private static void animateHand(MatrixStack ms, float pt, float s) {
        float slide = lerp(pt, prevSlide, slideAmt);
        float vault = lerp(pt, prevVault, vaultAmt);
        float mom = lerp(pt, prevMom, momAmt);
        float wa = lerp(pt, prevWall, wallAmt);
        float kk = lerp(pt, prevKick, kick);
        float land = lerp(pt, prevLand, landAmt);
        float fall = lerp(pt, prevFall, fallAmt);
        float t = clientTicks + pt;

        // running: arms pump in opposite phase
        float phase = t * 0.6F + (s > 0 ? 0.0F : (float) Math.PI);
        if (mom > 0.001F) {
            ms.translate(0.0D, Math.sin(phase) * 0.02F * mom - 0.02F * mom, Math.cos(phase) * 0.03F * mom);
            ms.mulPose(Vector3f.XP.rotationDegrees((float) Math.sin(phase) * 5.0F * mom));
        }

        // wall run: the hand next to the wall reaches out and scrapes it, the far hand tucks in
        float aw = Math.abs(wa);
        if (aw > 0.001F) {
            boolean near = Math.signum(wa) == s;
            if (near) {
                ms.translate(s * 0.16F * aw, 0.03F * aw + Math.sin(t * 1.8F) * 0.004F * aw, -0.04F * aw);
                ms.mulPose(Vector3f.ZP.rotationDegrees(-s * 22.0F * aw));
                ms.mulPose(Vector3f.YP.rotationDegrees(-s * 15.0F * aw));
            } else {
                ms.translate(-s * 0.05F * aw, -0.07F * aw, 0.0D);
                ms.mulPose(Vector3f.ZP.rotationDegrees(s * 8.0F * aw));
            }
        }

        // slide: hands pushed down and out, bracing, slight vibration
        if (slide > 0.001F) {
            ms.translate(s * 0.06F * slide, -0.12F * slide + Math.sin(t * 2.2F) * 0.003F * slide, 0.05F * slide);
            ms.mulPose(Vector3f.XP.rotationDegrees(18.0F * slide));
            ms.mulPose(Vector3f.ZP.rotationDegrees(-s * 10.0F * slide));
        }

        // vault: reach up and forward, then push off the ledge
        if (vault > 0.001F) {
            ms.mulPose(Vector3f.XP.rotationDegrees(-35.0F * vault));
            ms.translate(s * 0.03F * vault, 0.12F * vault, -0.12F * vault);
        } else if (vault < -0.001F) {
            float k = -vault;
            ms.mulPose(Vector3f.XP.rotationDegrees(15.0F * k));
            ms.translate(0.0D, -0.1F * k, 0.02F * k);
        }

        // jump / wall jump thrust
        if (kk > 0.001F) {
            ms.translate(0.0D, 0.06F * kk, -0.12F * kk);
            ms.mulPose(Vector3f.XP.rotationDegrees(-12.0F * kk));
        }

        // landing impact
        if (land > 0.001F) {
            ms.translate(0.0D, -0.18F * land, 0.04F * land);
            ms.mulPose(Vector3f.XP.rotationDegrees(12.0F * land));
        }

        // falling fast: hands lift and shake in the wind
        if (fall > 0.001F) {
            ms.translate(Math.sin(t * 2.5F + s) * 0.006F * fall, 0.04F * fall + Math.cos(t * 2.9F) * 0.006F * fall,
                    -0.03F * fall);
        }
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
    }

    private static void tick(ClientPlayerEntity p) {
        Minecraft mc = Minecraft.getInstance();
        capturePrev();

        if (p != lastPlayer) {
            lastPlayer = p;
            endState();
            momentum = 0;
            appliedBonus = -1;
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
        if (disabled) {
            endState();
            momentum = 0;
        } else {
            updateMomentum(p, fwd, hs, ground);

            // landing impact (uses velocity from the previous tick)
            if (ground && !wasGround && lastVy < -0.5D && state == State.NONE) {
                landAmt = Math.max(landAmt, (float) MathHelper.clamp((-lastVy - 0.4D) / 0.8D, 0.15D, 1.0D));
            }
            if (state == State.NONE && !ground) {
                fallTarget = (float) MathHelper.clamp((-m.y - 0.3D) / 0.7D, 0.0D, 1.0D);
            }
            if (state == State.NONE && ground && jumpEdge) {
                kick = Math.max(kick, 0.5F);
            }

            if (state == State.NONE) {
                Vector3d look = horizontalLook(p);
                if (look != null && fwd && jump && cooldown == 0) {
                    double top = findLedge(p, look);
                    if (!Double.isNaN(top)) startVault(look, top);
                }
                if (state == State.NONE && ground && shiftEdge && hs > 0.25D) {
                    startSlide(m, hs);
                }
                if (state == State.NONE && !ground && airTicks >= 2 && fwd && hs > 0.2D
                        && wallRunBudget > 0 && cooldown == 0) {
                    tryStartWallRun(m, hs);
                }
            }

            switch (state) {
                case WALLRUN: doWallRun(p, fwd, jumpEdge); break;
                case SLIDE:   doSlide(p, shift, jumpEdge, ground); break;
                case VAULT:   doVault(p); break;
                default: break;
            }
        }

        applySpeed(p, momentum);
        updateAnim();

        wasGround = ground;
        lastVy = m.y;
    }

    private static void updateMomentum(ClientPlayerEntity p, boolean fwd, double hs, boolean ground) {
        if (ground && fwd && hs > 0.2D) {
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

    private static void startSlide(Vector3d m, double hs) {
        slideDir = new Vector3d(m.x / hs, 0.0D, m.z / hs);
        slideSpeed = Math.min(0.62D, hs * 1.2D);
        state = State.SLIDE;
        stateTicks = 0;
    }

    private static void doSlide(ClientPlayerEntity p, boolean shift, boolean jumpEdge, boolean ground) {
        stateTicks++;
        slideSpeed *= 0.955D;
        Vector3d m = p.getDeltaMovement();

        if (jumpEdge) {
            // slide-jump keeps the speed
            p.setDeltaMovement(slideDir.x * slideSpeed * 1.1D, m.y, slideDir.z * slideSpeed * 1.1D);
            endState();
            kick = 0.7F;
            return;
        }
        if (!shift || slideSpeed < 0.13D || p.horizontalCollision || airTicks > 6 || stateTicks > 40) {
            endState();
            return;
        }
        p.setDeltaMovement(slideDir.x * slideSpeed, m.y, slideDir.z * slideSpeed);
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
        state = State.NONE;
        stateTicks = 0;
    }

    private static void applySpeed(ClientPlayerEntity p, double mom) {
        ModifiableAttributeInstance attr = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        double bonus = Math.round(mom * 40.0D) / 100.0D; // 0.00 .. 0.40
        boolean has = attr.getModifier(SPEED_ID) != null;
        if (bonus == appliedBonus && has == (bonus > 0)) return;
        attr.removeModifier(SPEED_ID);
        if (bonus > 0) {
            attr.addTransientModifier(new AttributeModifier(SPEED_ID, "edgerunner_momentum", bonus,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        appliedBonus = bonus;
    }

    private static void updateAnim() {
        float tiltT = state == State.WALLRUN ? -wallSide * 9.0F * TILT_DIR : 0.0F;
        float slideT = state == State.SLIDE ? 1.0F : 0.0F;
        float vaultT = state == State.VAULT ? (vaultRising ? 1.0F : -0.6F) : 0.0F;
        float wallT = state == State.WALLRUN ? wallSide : 0.0F;

        tilt += (tiltT - tilt) * 0.3F;
        slideAmt += (slideT - slideAmt) * 0.35F;
        vaultAmt += (vaultT - vaultAmt) * 0.4F;
        wallAmt += (wallT - wallAmt) * 0.3F;
        momAmt += ((float) momentum - momAmt) * 0.2F;
        fallAmt += (fallTarget - fallAmt) * 0.25F;
        kick *= 0.75F;
        landAmt *= 0.8F;
    }

    private static float lerp(float pt, float a, float b) {
        return a + (b - a) * pt;
    }
}
