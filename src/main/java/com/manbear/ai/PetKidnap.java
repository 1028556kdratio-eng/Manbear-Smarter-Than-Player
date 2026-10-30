package com.manbear.ai;

import com.manbear.block.BearTrapBlock;
import com.manbear.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Everything about pets (anything tamed, vanilla or modded) being snatched and left caught in a bear trap. */
public final class PetKidnap {
    public static final String CAPTIVE = "manbear_captive";

    private PetKidnap() { }

    /** all of the player's pets (tamed mobs owned by them) within the radius that can be taken */
    public static List<LivingEntity> petsOf(ServerLevel level, ServerPlayer owner, double radius) {
        AABB box = owner.getBoundingBox().inflate(radius);
        return level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e instanceof OwnableEntity oe && owner.getUUID().equals(oe.getOwnerUUID())
                        && e.isAlive() && !e.isPassenger() && !e.getTags().contains(CAPTIVE));
    }

    public static boolean hasPet(ServerLevel level, ServerPlayer owner) {
        return !petsOf(level, owner, 40).isEmpty();
    }

    /** held fast: it cannot move, cannot be hurt, and a closed bear trap is clamped around it */
    public static void capture(LivingEntity pet) {
        pet.addTag(CAPTIVE);
        pet.setInvulnerable(true);
        if (pet instanceof Mob mob) mob.setNoAi(true);
    }

    /** leaves the (already captured) pet standing on the ground in a closed bear trap */
    public static void trap(ServerLevel level, LivingEntity pet) {
        BlockPos feet = pet.blockPosition();
        BlockState here = level.getBlockState(feet);
        BlockState trap = ModBlocks.BEAR_TRAP.get().defaultBlockState().setValue(BearTrapBlock.ARMED, false);
        if ((here.isAir() || here.canBeReplaced()) && trap.canSurvive(level, feet)) {
            level.setBlock(feet, trap, 3);
        }
        level.playSound(null, feet, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.4F, 0.5F);
    }

    /** the player found it and let it out */
    public static void free(ServerLevel level, LivingEntity pet) {
        pet.removeTag(CAPTIVE);
        pet.setInvulnerable(false);
        if (pet instanceof Mob mob) mob.setNoAi(false);
        BlockPos feet = pet.blockPosition();
        if (level.getBlockState(feet).is(ModBlocks.BEAR_TRAP.get())) level.destroyBlock(feet, false);
        level.playSound(null, feet, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 1.2F, 0.8F);
    }
}
