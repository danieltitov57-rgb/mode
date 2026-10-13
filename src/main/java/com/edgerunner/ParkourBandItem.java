package com.edgerunner;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/** Right click toggles parkour mode on / off. */
public class ParkourBandItem extends Item {

    public ParkourBandItem(Properties properties) {
        super(properties);
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ParkourClient::toggle);
        }
        return ActionResult.sidedSuccess(stack, world.isClientSide());
    }
}
