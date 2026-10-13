package com.edgerunner;

import java.util.function.Supplier;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

public class SlidePacket {
    private final boolean sliding;

    public SlidePacket(boolean sliding) {
        this.sliding = sliding;
    }

    public static void encode(SlidePacket msg, PacketBuffer buf) {
        buf.writeBoolean(msg.sliding);
    }

    public static SlidePacket decode(PacketBuffer buf) {
        return new SlidePacket(buf.readBoolean());
    }

    public static void handle(SlidePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayerEntity player = c.getSender();
            if (player != null) {
                ServerSlide.set(player, msg.sliding);
            }
        });
        c.setPacketHandled(true);
    }
}
