package com.manbear.registry;

import com.manbear.ManbearMod;
import com.manbear.entity.CompanionBird;
import com.manbear.entity.CorpseEntity;
import com.manbear.entity.DoppelgangerEntity;
import com.manbear.entity.ManbearEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ManbearMod.MODID);

    public static final RegistryObject<EntityType<ManbearEntity>> MANBEAR = ENTITIES.register("manbear",
            () -> EntityType.Builder.of(ManbearEntity::new, MobCategory.MONSTER)
                    .sized(3.0F, 5.0F)
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build("manbear"));

    /** the bird that follows every player; looks and flies like a vanilla parrot */
    public static final RegistryObject<EntityType<CompanionBird>> BIRD = ENTITIES.register("companion_bird",
            () -> EntityType.Builder.of(CompanionBird::new, MobCategory.CREATURE)
                    .sized(0.5F, 0.9F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .build("companion_bird"));

    /** a dead animal he left behind (evidence) */
    public static final RegistryObject<EntityType<CorpseEntity>> CORPSE = ENTITIES.register("corpse",
            () -> EntityType.Builder.<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(1.0F, 0.6F)
                    .clientTrackingRange(8)
                    .updateInterval(40)
                    .fireImmune()
                    .noSummon()
                    .build("corpse"));

    /** "you": the hallucination that leads you to the woods */
    public static final RegistryObject<EntityType<DoppelgangerEntity>> DOPPELGANGER = ENTITIES.register("doppelganger",
            () -> EntityType.Builder.<DoppelgangerEntity>of(DoppelgangerEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .noSave()
                    .noSummon()
                    .build("doppelganger"));
}
