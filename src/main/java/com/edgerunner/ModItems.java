package com.edgerunner;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public class ModItems {
    /** Creative inventory tab "Edge Runner". */
    public static final ItemGroup TAB = new ItemGroup("edgerunner") {
        @Override
        public ItemStack makeIcon() {
            return new ItemStack(ModItems.KATANA.get());
        }
    };

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, EdgeRunner.MODID);

    public static final RegistryObject<Item> PARKOUR_BAND = ITEMS.register("parkour_band",
            () -> new ParkourBandItem(new Item.Properties().tab(TAB).stacksTo(1)));

    public static final RegistryObject<Item> KATANA = ITEMS.register("katana",
            () -> new KatanaItem(new Item.Properties().tab(TAB)));
}
