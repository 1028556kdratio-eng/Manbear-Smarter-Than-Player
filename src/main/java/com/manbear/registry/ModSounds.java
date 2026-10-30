package com.manbear.registry;

import com.manbear.ManbearMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ManbearMod.MODID);

    public static final RegistryObject<SoundEvent> GROWL = reg("growl");
    public static final RegistryObject<SoundEvent> HUFF = reg("huff");
    public static final RegistryObject<SoundEvent> ROAR = reg("roar");
    public static final RegistryObject<SoundEvent> SNARL = reg("snarl");
    public static final RegistryObject<SoundEvent> HURT = reg("hurt");
    public static final RegistryObject<SoundEvent> DEATH = reg("death");
    public static final RegistryObject<SoundEvent> STEP = reg("step");
    public static final RegistryObject<SoundEvent> THUMP = reg("thump");
    public static final RegistryObject<SoundEvent> ROAR_DEEP = reg("roar_deep");
    public static final RegistryObject<SoundEvent> SNORE = reg("snore");
    public static final RegistryObject<SoundEvent> TWIG = reg("twig_snap");
    public static final RegistryObject<SoundEvent> TREE_CRACK = reg("tree_crack");
    public static final RegistryObject<SoundEvent> TREE_RUSTLE = reg("tree_rustle");
    public static final RegistryObject<SoundEvent> SCREAM = reg("scream");
    public static final RegistryObject<SoundEvent> ANIMAL_DEATH = reg("animal_death");
    public static final RegistryObject<SoundEvent> MIMIC_PIG = reg("mimic_pig");
    public static final RegistryObject<SoundEvent> MIMIC_COW = reg("mimic_cow");
    public static final RegistryObject<SoundEvent> MIMIC_SHEEP = reg("mimic_sheep");
    public static final RegistryObject<SoundEvent> BIRD_CHIRP = reg("bird_chirp");
    public static final RegistryObject<SoundEvent> CHASE_SCREAM = reg("chase_scream");
    public static final RegistryObject<SoundEvent> PIT_CRUNCH = reg("pit_crunch");

    private static RegistryObject<SoundEvent> reg(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ManbearMod.MODID, name)));
    }
}
