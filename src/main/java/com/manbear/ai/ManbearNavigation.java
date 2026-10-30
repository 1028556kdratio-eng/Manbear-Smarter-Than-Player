package com.manbear.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Plans routes for a slim, 3-high body instead of his real 1-wide-and-5-tall one. With the real size almost every
 * gap between trees, every door and every room was "too small", so he found no path and got stuck. His real body
 * adapts on the way (crouch / duck / crawl, tearing through leaves and blocks that are in the way).
 */
public class ManbearNavigation extends WallClimberNavigation {
    public ManbearNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxNodes) {
        this.nodeEvaluator = new WalkNodeEvaluator() {
            @Override
            public void prepare(PathNavigationRegion region, Mob mob) {
                super.prepare(region, mob);
                this.entityWidth = 1;
                this.entityHeight = 3;
                this.entityDepth = 1;
            }

            /** Leaves are literally open space for the Manbear, including during path planning. */
            @Override
            protected BlockPathTypes getBlockPathType(Mob mob, BlockPos pos) {
                if (mob.level().getBlockState(pos).is(BlockTags.LEAVES)) return BlockPathTypes.OPEN;
                return super.getBlockPathType(mob, pos);
            }

            @Override
            public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
                if (level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LEAVES)) return BlockPathTypes.OPEN;
                return super.getBlockPathType(level, x, y, z, mob);
            }
        };
        this.nodeEvaluator.setCanPassDoors(true);
        this.nodeEvaluator.setCanOpenDoors(true);
        return new PathFinder(this.nodeEvaluator, maxNodes);
    }
}
