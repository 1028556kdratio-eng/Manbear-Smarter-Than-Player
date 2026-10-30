package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.entity.ManbearEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Lets the Manbear tear through whatever stands between him and his target. */
public class BlockBreaker {
    private BlockPos pos;
    private int progress;
    private int needed;
    /** Extra adjacent wall blocks to clear when the Manbear has learned that shelter is the player's escape habit. */
    private int extraBlocks;

    public boolean active() { return pos != null; }

    public static boolean canGrief(ServerLevel level) {
        return ManbearConfig.BREAK_BLOCKS.get() && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    public static boolean isBreakable(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (st.isAir() || !st.getFluidState().isEmpty()) return false;
        if (st.getCollisionShape(level, p).isEmpty()) return false;
        if (st.is(BlockTags.WITHER_IMMUNE)) return false;
        float h = st.getDestroySpeed(level, p);
        return h >= 0 && h <= ManbearConfig.MAX_BREAK_HARDNESS.get();
    }

    @Nullable
    public BlockPos find(ServerLevel level, ManbearEntity m, Entity target) {
        Vec3 to = target.position().subtract(m.position());
        Direction h = Direction.getNearest(to.x, 0, to.z);
        BlockPos base = m.blockPosition();
        int hgt = Math.max(1, Mth.ceil(m.getBbHeight()));
        List<BlockPos> c = new ArrayList<>();
        for (int dy = 0; dy < hgt; dy++) c.add(base.relative(h).above(dy));
        if (to.y > 1.5) {
            c.add(base.above(hgt));
            c.add(base.relative(h).above(hgt));
        }
        if (to.y < -1.5) {
            c.add(base.below());
            c.add(base.relative(h).below());
        }
        for (int dy = 0; dy < hgt; dy++) c.add(base.relative(h, 2).above(dy));
        for (BlockPos p : c) {
            if (isBreakable(level, p)) return p;
        }
        return null;
    }

    /**
     * First breakable block on the straight line between him and the target (checked low and high),
     * so he can tunnel through walls / roofs to get at someone inside a house. Null if an
     * unbreakable block is in the way or nothing blocks the line.
     */
    @Nullable
    public BlockPos findAlongRay(ServerLevel level, ManbearEntity m, Entity target) {
        Vec3[][] rays = {
                {m.position().add(0, 0.5, 0), target.position().add(0, 0.9, 0)},
                {m.position().add(0, 1.6, 0), target.getEyePosition()},
                {m.getEyePosition(), target.getEyePosition()}
        };
        for (Vec3[] r : rays) {
            Vec3 d = r[1].subtract(r[0]);
            double len = d.length();
            if (len < 0.5) continue;
            Vec3 step = d.scale(0.4 / len);
            Vec3 p = r[0];
            int n = (int) (Math.min(len, 14.0) / 0.4);
            for (int i = 1; i <= n; i++) {
                p = p.add(step);
                BlockPos bp = BlockPos.containing(p);
                if (isBreakable(level, bp)) return bp;
                BlockState st = level.getBlockState(bp);
                if (!st.getCollisionShape(level, bp).isEmpty()) break; // unbreakable wall: try the next ray
            }
        }
        return null;
    }

    public static boolean inReach(ManbearEntity m, BlockPos p) {
        return m.getBoundingBox().distanceToSqr(Vec3.atCenterOf(p)) <= 2.6 * 2.6;
    }

    public void start(ServerLevel level, BlockPos p) {
        start(level, p, 0);
    }

    public void start(ServerLevel level, BlockPos p, int adjacentBonusBlocks) {
        this.extraBlocks = Mth.clamp(adjacentBonusBlocks, 0, 4);
        startBlock(level, p);
    }

    private void startBlock(ServerLevel level, BlockPos p) {
        this.pos = p;
        this.progress = 0;
        float hardness = level.getBlockState(p).getDestroySpeed(level, p);
        // Adaptive breaching should feel like a determined animal tearing at a wall, not a normal mining speed.
        this.needed = Mth.clamp((int) (hardness * (extraBlocks > 0 ? 6.0F : 9.0F)), 6, 140);
    }

    /** @return true while still breaking */
    public boolean tick(ServerLevel level, ManbearEntity m) {
        if (pos == null) return false;
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            cancel(level, m);
            return false;
        }
        progress++;
        level.destroyBlockProgress(m.getId(), pos, Math.min(9, progress * 10 / needed));
        if (progress % 4 == 0) {
            level.playSound(null, pos, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.2F, 0.7F);
        }
        if (progress % 12 == 1) m.triggerAttack(2);
        if (progress >= needed) {
            BlockPos broken = pos;
            level.destroyBlockProgress(m.getId(), broken, -1);
            level.destroyBlock(broken, ManbearConfig.DROP_BROKEN_BLOCKS.get(), m);
            pos = null;
            if (extraBlocks > 0) {
                // Select a touching block to widen the same breach instead of restarting from scratch next tick.
                BlockPos[] adjacent = {broken.above(), broken.below(), broken.north(), broken.south(), broken.east(), broken.west()};
                for (BlockPos next : adjacent) {
                    if (isBreakable(level, next)) {
                        extraBlocks--;
                        startBlock(level, next);
                        return true;
                    }
                }
                extraBlocks = 0;
            }
            return false;
        }
        return true;
    }

    public void cancel(ServerLevel level, ManbearEntity m) {
        if (pos != null) level.destroyBlockProgress(m.getId(), pos, -1);
        pos = null;
        progress = 0;
        extraBlocks = 0;
    }
}
