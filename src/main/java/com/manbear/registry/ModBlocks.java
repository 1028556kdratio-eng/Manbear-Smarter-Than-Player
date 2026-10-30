package com.manbear.registry;

import com.manbear.ManbearMod;
import com.manbear.block.BearTrapBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ManbearMod.MODID);

    /** the number of his rage, clawed into a log */
    public static final RegistryObject<Block> RAGE_SCRATCH = BLOCKS.register("rage_scratch",
            () -> new com.manbear.block.RageScratchBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(0.05F)
                    .sound(SoundType.WOOD)
                    .noCollission()
                    .noOcclusion()));

    public static final RegistryObject<Block> FOOTPRINT = BLOCKS.register("footprint",
            () -> new com.manbear.block.FootprintBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DIRT)
                    .strength(0.05F)
                    .sound(SoundType.GRAVEL)
                    .noCollission()
                    .noOcclusion()));

    public static final RegistryObject<Block> BEAR_TRAP = BLOCKS.register("bear_trap",
            () -> new BearTrapBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noCollission()
                    .noOcclusion()));
}
