package com.manbear.ai;

import com.manbear.data.PlayerProfile;
import com.manbear.entity.ManbearEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** Finds trees/bushes/terrain to hide behind, peek spots beside them, and places for traps. */
public final class CoverFinder {
    private CoverFinder() { }

    /** lean: 1 = normal peek, 2 = deep lean for thick (2x2 / 3x3) trunks */
    public record Cover(BlockPos coverBlock, BlockPos hide, @Nullable BlockPos peek, int lean, boolean duck, boolean inside) { }

    public static boolean lineClear(Level level, Vec3 from, Vec3 to, Entity ctx) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx)).getType() == HitResult.Type.MISS;
    }

    public static boolean standable(Level level, BlockPos pos, int clearance) {
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        if (!level.getFluidState(pos).isEmpty()) return false;
        for (int i = 0; i < clearance; i++) {
            BlockPos p = pos.above(i);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return false;
        }
        return true;
    }

    /** nearest standable block around yHint at the given column, or null */
    @Nullable
    public static BlockPos groundNear(Level level, double x, double z, int yHint, int clearance) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        if (!level.hasChunkAt(new BlockPos(xi, yHint, zi))) return null;
        for (int dy = 4; dy >= -10; dy--) {
            BlockPos p = new BlockPos(xi, yHint + dy, zi);
            if (standable(level, p, clearance)) return p;
        }
        return null;
    }

    private static boolean logColumn(Level level, int x, int z) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return level.getBlockState(new BlockPos(x, ground - 1, z)).is(BlockTags.LOGS);
    }

    /** a solid 2-3 block tall obstacle (wall, rock, terrain step) that hides a ducking Manbear but not a standing one */
    @Nullable
    private static BlockPos lowStructure(Level level, int x, int z) {
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            if (!level.hasChunkAt(new BlockPos(x + o[0], 64, z + o[1]))) return null;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos topBlock = new BlockPos(x, top - 1, z);
        BlockState st = level.getBlockState(topBlock);
        if (st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || !st.isSolidRender(level, topBlock)) return null;
        int base = Math.min(
                Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 3, z), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x - 3, z)),
                Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z + 3), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z - 3)));
        int h = top - base;
        return (h >= 2 && h <= 3) ? topBlock : null;
    }

    private static Vec3 target0(Player p) { return p.getEyePosition(); }

    @Nullable
    private static BlockPos coverColumn(Level level, int x, int z) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos trunk = new BlockPos(x, ground - 1, z);
        if (level.getBlockState(trunk).is(BlockTags.LOGS)) return trunk;
        int withLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (withLeaves > ground && withLeaves - ground <= 3) {
            BlockPos leaf = new BlockPos(x, withLeaves - 1, z);
            if (level.getBlockState(leaf).is(BlockTags.LEAVES)) return leaf;
        }
        return null;
    }

    @Nullable
    public static Cover findCover(ServerLevel level, ManbearEntity m, Player p, int minDist, int maxDist) {
        return findCover(level, m, p, minDist, maxDist, null);
    }

    /** avoid: a previous hiding spot; new cover must be at least 6 blocks from it (so he moves tree to tree) */
    @Nullable
    public static Cover findCover(ServerLevel level, ManbearEntity m, Player p, int minDist, int maxDist, @Nullable BlockPos avoid) {
        RandomSource rnd = m.getRandom();
        Vec3 eye = p.getEyePosition();
        int px = p.getBlockX();
        int pz = p.getBlockZ();
        Cover best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -maxDist; dx <= maxDist; dx++) {
            for (int dz = -maxDist; dz <= maxDist; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 < minDist * minDist || d2 > maxDist * maxDist) continue;
                if (rnd.nextInt(3) != 0) continue;
                int x = px + dx;
                int z = pz + dz;
                if (m.distanceToSqr(x, m.getY(), z) > 80 * 80) continue;
                if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
                BlockPos cb = coverColumn(level, x, z);
                boolean lowCover = false;
                if (cb != null && level.getBlockState(cb).is(BlockTags.LEAVES)) {
                    // a bush on the ground: he climbs INSIDE it, ducks, and his eyes go dark
                    int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cb.getX(), cb.getZ());
                    BlockPos inside = new BlockPos(cb.getX(), gy, cb.getZ());
                    BlockPos under = inside.below();
                    if (level.getBlockState(inside).is(BlockTags.LEAVES) && !level.getBlockState(under).is(BlockTags.LEAVES)
                            && level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)
                            && (avoid == null || inside.distSqr(avoid) >= 36)) {
                        int cluster = 0;
                        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) {
                            if (level.getBlockState(inside.offset(ox, oy, oz)).is(BlockTags.LEAVES)) cluster++;
                        }
                        if (cluster >= 5) {
                            double idealB = (minDist + maxDist) / 2.0;
                            double scoreB = m.distanceToSqr(inside.getX(), inside.getY(), inside.getZ()) * 0.4
                                    + Math.pow(Math.sqrt(d2) - idealB, 2) + rnd.nextInt(20) - 70;   // bushes are preferred
                            if (scoreB < bestScore) {
                                bestScore = scoreB;
                                best = new Cover(cb, inside, null, 1, true, true);
                            }
                        }
                    }
                    continue;
                }
                if (cb == null) {
                    cb = lowStructure(level, x, z);
                    if (cb == null) continue;
                    lowCover = true;
                }

                Direction away = Direction.getNearest(cb.getX() - p.getX(), 0, cb.getZ() - p.getZ());
                Direction perp = away.getClockWise();
                boolean trunk = logColumn(level, cb.getX(), cb.getZ());
                BlockPos hideCol = cb.relative(away);
                int maxStep = 1;
                int lean = 1;
                if (trunk) {
                    // wide trunks: only use the back face, and hide behind the MIDDLE of the trunk
                    if (logColumn(level, cb.getX() + away.getStepX(), cb.getZ() + away.getStepZ())) continue;
                    int right = 0;
                    int left = 0;
                    while (right < 3 && logColumn(level, cb.getX() + perp.getStepX() * (right + 1), cb.getZ() + perp.getStepZ() * (right + 1))) right++;
                    while (left < 3 && logColumn(level, cb.getX() - perp.getStepX() * (left + 1), cb.getZ() - perp.getStepZ() * (left + 1))) left++;
                    int width = 1 + left + right;
                    hideCol = cb.relative(perp, (right - left) / 2).relative(away);
                    maxStep = width / 2 + 1;
                    if (width >= 2) lean = 2;
                }
                int hx = hideCol.getX();
                int hz = hideCol.getZ();
                int hy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hx, hz);
                BlockPos hide = new BlockPos(hx, hy, hz);
                if (!standable(level, hide, 3)) continue;
                if (avoid != null && hide.distSqr(avoid) < 36) continue;
                if (lineClear(level, eye, Vec3.atBottomCenterOf(hide).add(0, 1.3, 0), p)) continue;
                // behind a 2-3 block structure he must DUCK: standing, his head would show above it
                if (lowCover && !lineClear(level, eye, Vec3.atBottomCenterOf(hide).add(0, 3.3, 0), p)) continue;

                // the nearest spot past the trunk's edge where you can actually see him
                BlockPos peek = null;
                int peekStep = 0;
                search:
                for (int k = 1; k <= maxStep; k++) {
                    for (Direction side : new Direction[]{perp, perp.getOpposite()}) {
                        BlockPos cand = hide.relative(side, k);
                        BlockPos g = groundNear(level, cand.getX() + 0.5, cand.getZ() + 0.5, hide.getY(), 3);
                        if (g != null && Math.abs(g.getY() - hide.getY()) <= 1
                                && lineClear(level, eye, Vec3.atBottomCenterOf(g).add(0, 1.3, 0), p)) {
                            peek = g;
                            peekStep = k;
                            break search;
                        }
                    }
                }
                if (peekStep >= 2) lean = 2;
                double ideal = (minDist + maxDist) / 2.0;
                double score = m.distanceToSqr(hide.getX(), hide.getY(), hide.getZ()) * 0.4
                        + Math.pow(Math.sqrt(d2) - ideal, 2) + (peek == null ? 80 : 0) + rnd.nextInt(20);
                if (score < bestScore) {
                    bestScore = score;
                    best = new Cover(cb, hide, peek, lean, lowCover, false);
                }
            }
        }
        if (best != null) return best;

        // no trees / bushes: use any terrain that breaks the line of sight (hills, walls, cave corners)
        for (int i = 0; i < 80; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = minDist + rnd.nextDouble() * (maxDist - minDist);
            double x = p.getX() + Math.cos(ang) * d;
            double z = p.getZ() + Math.sin(ang) * d;
            BlockPos g = groundNear(level, x, z, p.getBlockY(), 3);
            if (g == null || m.distanceToSqr(g.getX(), g.getY(), g.getZ()) > 80 * 80) continue;
            if (avoid != null && g.distSqr(avoid) < 36) continue;
            if (lineClear(level, eye, Vec3.atBottomCenterOf(g).add(0, 1.3, 0), p)) continue;
            BlockPos peek = null;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = g.relative(dir);
                if (standable(level, n, 3) && lineClear(level, eye, Vec3.atBottomCenterOf(n).add(0, 1.3, 0), p)) { peek = n; break; }
            }
            boolean needDuck = lineClear(level, target0(p), Vec3.atBottomCenterOf(g).add(0, 3.3, 0), p);
            return new Cover(g, g, peek, 1, needDuck, false);
        }
        return null;
    }

    /** Somewhere ahead of where the player is walking (or near their base) to bury a trap. */
    @Nullable
    public static BlockPos findTrapSite(ServerLevel level, Player p, PlayerProfile prof, RandomSource rnd) {
        Vec3 dir = prof.heading();
        if (dir.lengthSqr() < 0.01) {
            Vec3 look = p.getLookAngle();
            dir = new Vec3(look.x, 0, look.z);
            if (dir.lengthSqr() < 0.01) dir = new Vec3(1, 0, 0);
            dir = dir.normalize();
        }
        Vec3 perp = new Vec3(-dir.z, 0, dir.x);
        for (int i = 0; i < 20; i++) {
            double x;
            double z;
            if (prof.base != null && rnd.nextFloat() < 0.35F && prof.base.distSqr(p.blockPosition()) < 60 * 60) {
                x = prof.base.getX() + rnd.nextInt(17) - 8;
                z = prof.base.getZ() + rnd.nextInt(17) - 8;
            } else {
                double dist = 13 + rnd.nextInt(10);
                double side = rnd.nextInt(7) - 3;
                x = p.getX() + dir.x * dist + perp.x * side;
                z = p.getZ() + dir.z * dist + perp.z * side;
            }
            BlockPos g = groundNear(level, x, z, p.getBlockY(), 1);
            if (g == null) continue;
            if (g.distSqr(p.blockPosition()) < 100) continue;
            BlockState st = level.getBlockState(g);
            if (st.isAir() || st.canBeReplaced()) return g;
        }
        return null;
    }
}
