package com.manbear.ai;

import com.manbear.entity.ManbearEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** A lure animal (wolf, pig, sheep, cow): once marked "manbear_run" it sprints to the Manbear (or its bait spot). */
public class LureWolfGoal extends Goal {
    private final PathfinderMob mob;
    private int repath;

    public LureWolfGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.getTags().contains("manbear_run") && ManbearEntity.current() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        ManbearEntity b = ManbearEntity.current();
        if (b == null) return;
        if (mob instanceof Villager v) v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);   // villagers run on a Brain
        CompoundTag t = mob.getPersistentData();
        double x = b.getX();
        double y = b.getY();
        double z = b.getZ();
        if (t.contains("lure_x")) {   // trap variant: run to the bait spot instead
            x = t.getDouble("lure_x");
            y = t.getDouble("lure_y");
            z = t.getDouble("lure_z");
        }
        mob.getLookControl().setLookAt(x, y + 1.0, z, 30.0F, 30.0F);
        if (--repath > 0 && !mob.getNavigation().isDone()) return;
        repath = 6;
        mob.getNavigation().moveTo(x, y, z, 1.9D);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
