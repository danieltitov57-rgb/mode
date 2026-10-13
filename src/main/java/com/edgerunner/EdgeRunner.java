package com.edgerunner;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.network.FMLNetworkConstants;

@Mod(EdgeRunner.MODID)
public class EdgeRunner {
    public static final String MODID = "edgerunner";

    public EdgeRunner() {
        // Client-only mod: allow joining servers that do not have it.
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (a, b) -> true));
    }
}
