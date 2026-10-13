package com.edgerunner;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.block.BlockState;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = EdgeRunner.MODID, value = Dist.CLIENT)
public class ParkourClient {

    private enum State { NONE, WALLRUN, SLIDE, VAULT }

    /** If the camera tilts TOWARD the wall instead of away, change 1.0F to -1.0F. */
    private static final float TILT_DIR = 1.0F;
    private static final UUID SPEED_ID = UUID.fromString("5b2d3f1e-7c41-4a0e-9a35-2f6f0d9b7c11");

    // core state
    private static ClientPlayerEntity lastPlayer;
    private static State state = State.NONE;
    private static int stateTicks;
    private static int airTicks;
    private static int cooldown;
    private static int wallRunBudget;
    private static boolean wasJump, wasShift;
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
    private static float vaultAmt, prevVault;
    private static float momAmt, prevMom;

    // ------------------------------------------------------------------ events

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
        float pt = (float) e.getRenderPartialTicks();
        float roll = lerp(pt, prevTilt, tilt);
        float slide = lerp(pt, prevSlide, slideAmt);
        float vault = lerp(pt, prevVault, vaultAmt);
        float mom = lerp(pt, prevMom, momAmt);
        float t = clientTicks + pt;

        roll += (float) Math.sin(t * 0.5F) * 0.5F * mom; // head sway while running fast
        e.setRoll(e.getRoll() + roll);
        e.setPitch(e.getPitch() + slide * 2.5F + vault * 5.0F);
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent e) {
        float pt = e.getPartialTicks();
        float roll = lerp(pt, prevTilt, tilt);
        float slide = lerp(pt, prevSlide, slideAmt);
        float vault = lerp(pt, prevVault, vaultAmt);
        float mom = lerp(pt, prevMom, momAmt);
        float t = clientTicks + pt;
        MatrixStack ms = e.getMatrixStack();

        // running: hands slightly lowered and swaying
        ms.translate(Math.sin(t * 0.5F) * 0.006F * mom, -0.02F * mom, 0.0D);

        // wall run: hands drop and lean with the tilt
        float w = Math.abs(roll) / 9.0F;
        if (w > 0.001F) {
            ms.translate(-Math.signum(roll) * 0.04F * w, -0.06F * w, 0.0D);
            ms.mulPose(Vector3f.ZP.rotationDegrees(roll * 0.8F));
        }
        // slide: hands pushed down and tilted forward
        if (slide > 0.001F) {
            ms.translate(0.0D, -0.12F * slide, 0.05F * slide);
            ms.mulPose(Vector3f.XP.rotationDegrees(18.0F * slide));
        }
        // vault: hands reaching up and forward
        if (vault > 0.001F) {
            ms.mulPose(Vector3f.XP.rotationDegrees(-25.0F * vault));
            ms.translate(0.0D, 0.08F * vault, -0.1F * vault);
        }
    }

    // ------------------------------------------------------------------- logic

    private static void tick(ClientPlayerEntity p) {
        Minecraft mc = Minecraft.getInstance();
        if (p != lastPlayer) {
            lastPlayer = p;
            endState();
            momentum = 0;
            appliedBonus = -1;
            cooldown = 0;
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

        boolean disabled = p.isSpectator() || p.abilities.flying || p.isFallFlying() || p.isPassenger()
                || p.isInWater() || p.isInLava() || p.onClimbable() || p.isSwimming();

        if (disabled) {
            endState();
            momentum = 0;
        } else {
            updateMomentum(p, fwd, hs, ground);

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
        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity p = mc.player;
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
        prevTilt = tilt;
        prevSlide = slideAmt;
        prevVault = vaultAmt;
        prevMom = momAmt;

        float tiltT = state == State.WALLRUN ? -wallSide * 9.0F * TILT_DIR : 0.0F;
        float slideT = state == State.SLIDE ? 1.0F : 0.0F;
        float vaultT = state == State.VAULT ? 1.0F : 0.0F;

        tilt += (tiltT - tilt) * 0.3F;
        slideAmt += (slideT - slideAmt) * 0.35F;
        vaultAmt += (vaultT - vaultAmt) * 0.35F;
        momAmt += ((float) momentum - momAmt) * 0.2F;
    }

    private static float lerp(float pt, float a, float b) {
        return a + (b - a) * pt;
    }
}
