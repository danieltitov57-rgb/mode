package com.edgerunner;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.FMLNetworkConstants;

@Mod(EdgeRunner.MODID)
public class EdgeRunner {
    public static final String MODID = "edgerunner";

    public EdgeRunner() {
        // Parkour logic is client-side: allow joining servers that do not have the mod.
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (a, b) -> true));

        ModItems.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
