package com.edgerunner;

import java.util.function.Supplier;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

public class HeavyAttackPacket {
    public HeavyAttackPacket() {
    }

    public static void encode(HeavyAttackPacket msg, PacketBuffer buf) {
    }

    public static HeavyAttackPacket decode(PacketBuffer buf) {
        return new HeavyAttackPacket();
    }

    public static void handle(HeavyAttackPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayerEntity player = c.getSender();
            if (player != null) {
                KatanaServer.heavyAttack(player);
            }
        });
        c.setPacketHandled(true);
    }
}
