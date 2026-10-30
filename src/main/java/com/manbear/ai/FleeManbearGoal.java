package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.entity.ManbearEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Added to every animal. When the Manbear is near they bolt in a panic.
 * Right after he appears the range is bigger and they scatter in random directions.
 */
public class FleeManbearGoal extends Goal {
    private final PathfinderMob mob;
    private ManbearEntity bear;
    private int scan;
    private int repath;

    public FleeManbearGoal(PathfinderMob mob) {
        this.mob = mob;
        this.scan = mob.getRandom().nextInt(10);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private ManbearEntity scare() {
        if (!ManbearConfig.ANIMALS_FLEE.get() || mob.getTags().contains("manbear_lure")) return null;
        ManbearEntity b = ManbearEntity.current();
        if (b == null || !b.isAlive() || b.level() != mob.level()) return null;
        double range = b.lifeTicks < 240 ? 64.0 : ManbearConfig.ANIMAL_FLEE_RANGE.get();
        return mob.distanceToSqr(b) < range * range ? b : null;
    }

    @Override
    public boolean canUse() {
        if (--scan > 0) return false;
        scan = 6;
        bear = scare();
        return bear != null;
    }

    @Override
    public boolean canContinueToUse() {
        bear = scare();
        return bear != null;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void tick() {
        if (--repath > 0 && !mob.getNavigation().isDone()) return;
        repath = 10 + mob.getRandom().nextInt(15);
        Vec3 pos;
        // mostly away from him, sometimes a random dash - they are just panicking
        if (mob.getRandom().nextFloat() < 0.35F) pos = DefaultRandomPos.getPos(mob, 14, 6);
        else pos = DefaultRandomPos.getPosAway(mob, 16, 7, bear.position());
        if (pos != null) {
            mob.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.5D + mob.getRandom().nextDouble() * 0.35D);
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
