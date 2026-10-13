package com.edgerunner;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Pose;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server side of the slide: while a player slides, keep their pose "swimming" (0.6 high),
 * otherwise the server would reject movement into a 1-block-high gap.
 */
@Mod.EventBusSubscriber(modid = EdgeRunner.MODID)
public class ServerSlide {
    private static final Map<UUID, Integer> SLIDING = new HashMap<>();

    public static void set(ServerPlayerEntity player, boolean on) {
        if (on) {
            SLIDING.put(player.getUUID(), 50);
            player.setPose(Pose.SWIMMING);
        } else {
            SLIDING.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side.isClient()) return;
        if (!(event.player instanceof ServerPlayerEntity)) return;
        UUID id = event.player.getUUID();
        Integer left = SLIDING.get(id);
        if (left == null) return;
        if (left <= 0 || event.player.isSpectator()) {
            SLIDING.remove(id);
            return;
        }
        SLIDING.put(id, left - 1);
        event.player.setPose(Pose.SWIMMING);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SLIDING.remove(event.getPlayer().getUUID());
    }
}
