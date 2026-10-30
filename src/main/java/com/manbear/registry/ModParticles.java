package com.manbear.registry;

import com.manbear.ManbearMod;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Particle types owned by the Manbear mod. */
public final class ModParticles {
    public static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, ManbearMod.MODID);

    public static final RegistryObject<SimpleParticleType> FOG =
            PARTICLE_TYPES.register("fog", () -> new SimpleParticleType(false));

    private ModParticles() { }
}
