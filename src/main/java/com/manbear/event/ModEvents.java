package com.manbear.event;

import com.manbear.ManbearMod;
import com.manbear.entity.ManbearEntity;
import com.manbear.client.ManbearRenderer;
import com.manbear.registry.ModEntities;
import com.manbear.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraft.client.renderer.entity.ParrotRenderer;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public class ModEvents {
    @Mod.EventBusSubscriber(modid = ManbearMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class Common {
        @SubscribeEvent
        public static void attributes(EntityAttributeCreationEvent e) {
            e.put(ModEntities.MANBEAR.get(), ManbearEntity.createAttributes().build());
            e.put(ModEntities.BIRD.get(), Parrot.createAttributes().build());
            e.put(ModEntities.DOPPELGANGER.get(), net.minecraft.world.entity.Mob.createMobAttributes()
                    .add(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, 0.13D)
                    .add(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE, 64.0D)
                    .build());
        }

        @SubscribeEvent
        public static void tabs(BuildCreativeModeTabContentsEvent e) {
            if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) e.accept(ModItems.MANBEAR_SPAWN_EGG);
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(ModItems.BEAR_TRAP);
        }
    }

    @Mod.EventBusSubscriber(modid = ManbearMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(ModEntities.MANBEAR.get(), ManbearRenderer::new);
            e.registerEntityRenderer(ModEntities.BIRD.get(), ParrotRenderer::new);   // it is a parrot to look at
            e.registerEntityRenderer(ModEntities.CORPSE.get(), com.manbear.client.CorpseRenderer::new);
            e.registerBlockEntityRenderer(com.manbear.registry.ModBlockEntities.RAGE_SCRATCH.get(), com.manbear.client.RageScratchRenderer::new);
            e.registerEntityRenderer(ModEntities.DOPPELGANGER.get(), com.manbear.client.DoppelgangerRenderer::new);
        }

        @SubscribeEvent
        public static void particleProviders(RegisterParticleProvidersEvent e) {
            e.registerSpriteSet(com.manbear.registry.ModParticles.FOG.get(), com.manbear.particle.FogParticle.Provider::new);
        }

        @SubscribeEvent
        public static void overlays(RegisterGuiOverlaysEvent e) {
            e.registerAboveAll("blackout", com.manbear.client.BlackoutOverlay.OVERLAY);
        }
    }
}
