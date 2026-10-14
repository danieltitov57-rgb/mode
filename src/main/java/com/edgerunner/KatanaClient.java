package com.edgerunner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** First-person katana animations (light combo + heavy dash strike). */
@Mod.EventBusSubscriber(modid = EdgeRunner.MODID, value = Dist.CLIENT)
public class KatanaClient {

    // keyframes: {t, tx, ty, tz, rx, ry, rz, ease}  ease: 0 smooth, 1 fast-out, 2 linear
    private static final float[][] LIGHT1 = {
            {0.00F,  0.00F,  0.00F,  0.00F,   0F,   0F,   0F, 0},
            {0.28F,  0.10F,  0.10F,  0.14F,  45F, -40F, -30F, 0},
            {0.50F, -0.16F, -0.16F, -0.24F, -50F,  60F,  28F, 1},
            {0.68F, -0.20F, -0.20F, -0.22F, -58F,  70F,  32F, 2},
            {1.00F,  0.00F,  0.00F,  0.00F,   0F,   0F,   0F, 0}};
    private static final float[][] LIGHT2 = {
            {0.00F,  0.00F,  0.00F,  0.00F,   0F,   0F,   0F, 0},
            {0.26F, -0.16F, -0.04F,  0.08F,  12F,  55F,  22F, 0},
            {0.48F,  0.18F,  0.02F, -0.22F,  -8F, -62F, -28F, 1},
            {0.66F,  0.22F,  0.02F, -0.24F, -10F, -70F, -32F, 2},
            {1.00F,  0.00F,  0.00F,  0.00F,   0F,   0F,   0F, 0}};
    private static final float[][] LIGHT3 = {
            {0.00F,  0.00F,  0.00F,  0.00F,   0F,   0F,   0F, 0},
            {0.32F,  0.00F, -0.08F,  0.26F,  30F,   0F,   6F, 0},
            {0.50F,  0.02F,  0.04F, -0.62F, -28F,  -4F,   0F, 1},
            {0.70F,  0.02F,  0.04F, -0.64F, -30F,  -4F,   0F, 2},
            {1.00F,  0.00F,  0.00F,  0.00F,   0F,   0F,   0F, 0}};
    private static final float[][] HEAVY = {
            {0.00F,  0.00F,  0.00F,  0.00F,   0F,    0F,   0F, 0},
            {0.30F,  0.28F,  0.06F,  0.32F,  55F,  -85F, -45F, 0},
            {0.55F, -0.40F, -0.12F, -0.46F, -45F,  115F,  42F, 1},
            {0.75F, -0.46F, -0.14F, -0.46F, -52F,  125F,  46F, 2},
            {1.00F,  0.00F,  0.00F,  0.00F,   0F,    0F,   0F, 0}};

    private static final int[] DURATION = {0, 10, 10, 13, 20};

    private static int type;          // 0 none, 1..3 light combo, 4 heavy
    private static int ticks;
    private static float prog, prevProg;
    private static int combo;
    private static int comboWindow;
    private static int heavyCd;
    private static float draw, prevDraw;
    private static boolean wasHolding;

    private static int dashTicks;
    private static double dashSpeed;
    private static Vector3d dashDir = Vector3d.ZERO;
    private static float dashFov;

    // ------------------------------------------------------------------ input

    @SubscribeEvent
    public static void onClick(InputEvent.ClickInputEvent e) {
        ClientPlayerEntity p = Minecraft.getInstance().player;
        if (p == null || e.getHand() != Hand.MAIN_HAND) return;
        if (!(p.getMainHandItem().getItem() instanceof KatanaItem)) return;

        if (e.isAttack()) {
            startLight(p);
        } else if (e.isUseItem()) {
            e.setCanceled(true);
            startHeavy(p);
        }
    }

    private static void startLight(ClientPlayerEntity p) {
        if (type == 4) return;
        if (type != 0 && prog < 0.6F) return;
        type = 1 + combo;
        combo = (combo + 1) % 3;
        ticks = 0;
        prog = 0;
        prevProg = 0;
        comboWindow = 0;
    }

    private static void startHeavy(ClientPlayerEntity p) {
        if (heavyCd > 0) return;
        if (type == 4) return;
        if (type != 0 && prog < 0.5F) return;
        type = 4;
        ticks = 0;
        prog = 0;
        prevProg = 0;
        heavyCd = 26;
        combo = 0;
    }

    private static Vector3d horizontalLook(ClientPlayerEntity p) {
        Vector3d look = p.getLookAngle();
        double l = Math.sqrt(look.x * look.x + look.z * look.z);
        if (l < 1.0E-4D) return Vector3d.ZERO;
        return new Vector3d(look.x / l, 0.0D, look.z / l);
    }

    private static void sound(ClientPlayerEntity p, net.minecraft.util.SoundEvent ev, float vol, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), ev, SoundCategory.PLAYERS, vol, pitch, false);
        }
    }

    // ------------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !event.side.isClient()) return;
        if (!(event.player instanceof ClientPlayerEntity)) return;
        ClientPlayerEntity p = (ClientPlayerEntity) event.player;
        if (p != Minecraft.getInstance().player) return;

        boolean holding = p.getMainHandItem().getItem() instanceof KatanaItem;
        prevProg = prog;
        prevDraw = draw;

        if (!holding) {
            type = 0;
            prog = 0;
            prevProg = 0;
            combo = 0;
            comboWindow = 0;
            dashTicks = 0;
        } else if (!wasHolding) {
            draw = 1.0F;
        }
        wasHolding = holding;
        draw *= 0.78F;
        if (heavyCd > 0) heavyCd--;

        if (type != 0) {
            ticks++;
            prog = Math.min(1.0F, ticks / (float) DURATION[type]);

            if (type == 4) {
                if (ticks == 5) {
                    dashDir = horizontalLook(p);
                    dashTicks = 6;
                    dashSpeed = 0.85D;
                    sound(p, SoundEvents.TRIDENT_RIPTIDE_1, 0.5F, 1.4F);
                }
                if (ticks == 7) {
                    sound(p, SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.7F);
                    ParkourNet.CHANNEL.sendToServer(new HeavyAttackPacket());
                }
            } else if (ticks == 4) {
                sound(p, SoundEvents.PLAYER_ATTACK_SWEEP, 0.7F, 1.1F + 0.1F * type);
                dashDir = horizontalLook(p);
                dashTicks = 2;
                dashSpeed = 0.22D;
            }

            if (ticks > DURATION[type]) {
                boolean heavy = type == 4;
                type = 0;
                prog = 0;
                prevProg = 0;
                comboWindow = heavy ? 0 : 14;
            }
        } else if (comboWindow > 0) {
            comboWindow--;
            if (comboWindow == 0) combo = 0;
        }

        if (dashTicks > 0 && !ParkourClient.isBusy() && !p.isPassenger() && !p.isInWater()) {
            Vector3d m = p.getDeltaMovement();
            p.setDeltaMovement(dashDir.x * dashSpeed, m.y, dashDir.z * dashSpeed);
            dashSpeed *= 0.8D;
            dashTicks--;
        } else if (dashTicks > 0) {
            dashTicks = 0;
        }
        dashFov += ((type == 4 && dashTicks > 0 ? 1.0F : 0.0F) - dashFov) * 0.3F;
    }

    @SubscribeEvent
    public static void onFov(FOVUpdateEvent e) {
        if (dashFov > 0.001F) {
            e.setNewfov(e.getNewfov() * (1.0F + 0.12F * dashFov));
        }
    }

    // ----------------------------------------------------------- render values

    private static float lerp(float pt, float a, float b) {
        return a + (b - a) * pt;
    }

    /** Pose offset {tx, ty, tz, rx, ry, rz} for the first-person katana. */
    public static float[] pose(float pt) {
        float[] out = new float[6];
        if (type == 0) return out;
        float p = Math.max(0.0F, Math.min(1.0F, lerp(pt, prevProg, prog)));
        float[][] kf = type == 1 ? LIGHT1 : (type == 2 ? LIGHT2 : (type == 3 ? LIGHT3 : HEAVY));
        for (int i = 0; i < kf.length - 1; i++) {
            float[] a = kf[i];
            float[] b = kf[i + 1];
            if (p >= a[0] && p <= b[0]) {
                float u = (p - a[0]) / (b[0] - a[0]);
                int ease = (int) b[7];
                if (ease == 0) u = u * u * (3.0F - 2.0F * u);
                else if (ease == 1) u = 1.0F - (1.0F - u) * (1.0F - u) * (1.0F - u);
                for (int j = 0; j < 6; j++) {
                    out[j] = a[j + 1] + (b[j + 1] - a[j + 1]) * u;
                }
                break;
            }
        }
        return out;
    }

    /** 1 right after the katana is selected, then fades to 0 (draw animation). */
    public static float draw(float pt) {
        return lerp(pt, prevDraw, draw);
    }

    public static float camRoll(float pt) {
        if (type == 0) return 0.0F;
        float p = lerp(pt, prevProg, prog);
        if (type == 4) {
            if (p < 0.35F) return lerp(p / 0.35F, 0.0F, -3.0F);
            if (p < 0.6F) return lerp((p - 0.35F) / 0.25F, -3.0F, 4.0F);
            return lerp(Math.min(1.0F, (p - 0.6F) / 0.4F), 4.0F, 0.0F);
        }
        float s = (float) Math.sin(p * Math.PI);
        return type == 1 ? -1.6F * s : (type == 2 ? 1.6F * s : 0.0F);
    }

    public static float camPitch(float pt) {
        if (type == 0) return 0.0F;
        float p = lerp(pt, prevProg, prog);
        float s = (float) Math.sin(p * Math.PI);
        return type == 4 ? 2.2F * s : (type == 3 ? 1.0F * s : 0.8F * s);
    }
}
