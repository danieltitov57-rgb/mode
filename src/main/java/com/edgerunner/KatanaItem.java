package com.edgerunner;

import net.minecraft.item.ItemTier;
import net.minecraft.item.SwordItem;

/**
 * Katana: LMB = light combo (vanilla attack + custom first-person animation),
 * RMB = heavy sweeping strike with a dash (handled by KatanaClient + KatanaServer).
 */
public class KatanaItem extends SwordItem {
    public KatanaItem(Properties properties) {
        super(ItemTier.IRON, 3, -2.0F, properties);
    }
}
