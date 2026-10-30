package com.manbear.registry;

import com.manbear.ManbearMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ManbearMod.MODID);

    public static final RegistryObject<Item> BEAR_TRAP = ITEMS.register("bear_trap",
            () -> new BlockItem(ModBlocks.BEAR_TRAP.get(), new Item.Properties()));

    public static final RegistryObject<Item> MANBEAR_SPAWN_EGG = ITEMS.register("manbear_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.MANBEAR, 0x2B1D14, 0xC01010, new Item.Properties()));
}
