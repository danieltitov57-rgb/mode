package com.edgerunner;

import java.util.List;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

/** Server side of the katana heavy attack: a wide frontal sweep. */
public class KatanaServer {

    public static void heavyAttack(ServerPlayerEntity player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof KatanaItem)) return;
        if (player.getCooldowns().isOnCooldown(stack.getItem())) return;
        player.getCooldowns().addCooldown(stack.getItem(), 28);

        Vector3d look = player.getLookAngle();
        double l = Math.sqrt(look.x * look.x + look.z * look.z);
        if (l < 1.0E-4D) return;
        double lx = look.x / l;
        double lz = look.z / l;

        World world = player.level;
        float damage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE) * 1.5F;
        AxisAlignedBB box = player.getBoundingBox().inflate(3.2D, 1.0D, 3.2D);
        List<LivingEntity> targets = world.getEntitiesOfClass(LivingEntity.class, box);

        int hits = 0;
        for (LivingEntity t : targets) {
            if (t == player || !t.isAlive() || player.isAlliedTo(t)) continue;
            double dx = t.getX() - player.getX();
            double dz = t.getZ() - player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 3.4D) continue;
            double dot = dist < 1.0E-4D ? 1.0D : (dx * lx + dz * lz) / dist;
            if (dist > 0.9D && dot < 0.1D) continue; // only the front half
            if (t.hurt(DamageSource.playerAttack(player), damage)) {
                t.knockback(1.1F, -lx, -lz);
                hits++;
            }
        }

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0F, 0.75F);
        if (hits > 0) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0F, 0.9F);
        }
        if (world instanceof ServerWorld) {
            ServerWorld sw = (ServerWorld) world;
            for (int i = -2; i <= 2; i++) {
                double a = i * 0.45D;
                double cos = Math.cos(a);
                double sin = Math.sin(a);
                double dx = lx * cos - lz * sin;
                double dz = lx * sin + lz * cos;
                sw.sendParticles(ParticleTypes.SWEEP_ATTACK,
                        player.getX() + dx * 1.8D, player.getY() + 1.0D, player.getZ() + dz * 1.8D,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        stack.hurtAndBreak(hits > 0 ? 2 : 1, player, p -> p.broadcastBreakEvent(EquipmentSlotType.MAINHAND));
    }
}
