package com.edgerunner;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, EdgeRunner.MODID);

    public static final RegistryObject<Item> PARKOUR_BAND = ITEMS.register("parkour_band",
            () -> new ParkourBandItem(new Item.Properties().tab(ItemGroup.TAB_MISC).stacksTo(1)));
}
