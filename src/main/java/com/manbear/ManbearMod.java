package com.manbear;

import com.manbear.event.ServerEvents;
import com.manbear.registry.ModBlockEntities;
import com.manbear.registry.ModBlocks;
import com.manbear.registry.ModEntities;
import com.manbear.registry.ModItems;
import com.manbear.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ManbearMod.MODID)
public class ManbearMod {
    public static final String MODID = "manbear";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ManbearMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModEntities.ENTITIES.register(modBus);
        ModSounds.SOUNDS.register(modBus);
        com.manbear.registry.ModParticles.PARTICLE_TYPES.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ManbearConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ManbearClientConfig.SPEC);
        com.manbear.net.ManbearNet.init();
        MinecraftForge.EVENT_BUS.register(new ServerEvents());
    }
}
