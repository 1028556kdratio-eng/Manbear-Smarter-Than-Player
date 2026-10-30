package com.manbear.registry;

import com.manbear.ManbearMod;
import com.manbear.block.RageScratchBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ManbearMod.MODID);

    public static final RegistryObject<BlockEntityType<RageScratchBlockEntity>> RAGE_SCRATCH = BLOCK_ENTITIES.register("rage_scratch",
            () -> BlockEntityType.Builder.of(RageScratchBlockEntity::new, ModBlocks.RAGE_SCRATCH.get()).build(null));
}
