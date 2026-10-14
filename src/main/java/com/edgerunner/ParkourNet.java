package com.edgerunner;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;

public class ParkourNet {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EdgeRunner.MODID, "main"),
            () -> PROTOCOL,
            v -> true,
            v -> true);

    public static void register() {
        CHANNEL.registerMessage(0, SlidePacket.class, SlidePacket::encode, SlidePacket::decode, SlidePacket::handle);
        CHANNEL.registerMessage(1, HeavyAttackPacket.class, HeavyAttackPacket::encode, HeavyAttackPacket::decode,
                HeavyAttackPacket::handle);
    }
}
