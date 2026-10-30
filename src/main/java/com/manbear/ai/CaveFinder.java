package com.manbear.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "The nearest cave": a standable floor that is well below the surface and out of the sky's light.
 * Candidates are sampled on rings around a point (nearest rings first), so this is cheap enough to run once per event.
 */
public final class CaveFinder {
    private CaveFinder() { }

    /** how far below the surface a floor must be to count as "inside" a cave */
    private static final int MIN_DEPTH = 6;

    public static boolean isCaveFloor(ServerLevel level, BlockPos p) {
        if (!CoverFinder.standable(level, p, 3)) return false;
        if (level.getBrightness(LightLayer.SKY, p) > 2) return false;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return surface - p.getY() >= MIN_DEPTH;
    }

    /** cave floors around {@code from}, nearest first (at most {@code limit}) */
    public static List<BlockPos> find(ServerLevel level, BlockPos from, int radius, int limit, RandomSource rnd) {
        List<BlockPos> found = new ArrayList<>();
        for (int r = 8; r <= radius && found.size() < limit; r += 6) {
            int columns = Math.max(8, (int) (Math.PI * 2 * r / 6.0));
            double offset = rnd.nextDouble() * Math.PI * 2;
            for (int i = 0; i < columns && found.size() < limit * 2; i++) {
                double ang = offset + i * (Math.PI * 2 / columns);
                int x = Mth.floor(from.getX() + 0.5 + Math.cos(ang) * r);
                int z = Mth.floor(from.getZ() + 0.5 + Math.sin(ang) * r);
                if (!level.hasChunkAt(new BlockPos(x, from.getY(), z))) continue;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int top = Math.min(surface - MIN_DEPTH, from.getY() + 8);
                int bottom = Math.max(level.getMinBuildHeight() + 2, from.getY() - 45);
                for (int y = top; y >= bottom; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (isCaveFloor(level, p)) { found.add(p); break; }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        return found;
    }
}
