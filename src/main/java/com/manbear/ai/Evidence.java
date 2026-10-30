package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.block.FootprintBlock;
import com.manbear.entity.CorpseEntity;
import com.manbear.registry.ModBlocks;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What he leaves behind when he vanishes without you ever having noticed him: a trail of giant footprints, an armed
 * bear trap, a few dead animals, a pile of leaves, or a hidden pit. The type is rolled with the configured weights
 * (defaults 40 / 10 / 15 / 30 / 5); if the ground does not allow it, footprints are used instead.
 */
public final class Evidence {
    private enum Kind { FOOTPRINTS, TRAP, ANIMALS, LEAVES, PIT }

    private static final String[] ANIMALS = {
            "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit"
    };

    private Evidence() { }

    /**
     * @param at     where he was when he vanished
     * @param away   horizontal direction from you to him (footprints lead back along it when he never walked far)
     * @param trail  his recent positions, oldest first
     */
    public static void leave(ServerLevel level, ManbearBrain brain, Vec3 at, Vec3 away, List<Vec3> trail) {
        BlockPos ground = CoverFinder.groundNear(level, at.x, at.z, Mth.floor(at.y), 2);
        if (ground == null || !level.hasChunkAt(ground)) return;
        RandomSource rnd = level.random;

        int[] w = {
                ManbearConfig.EVIDENCE_FOOTPRINTS.get(), ManbearConfig.EVIDENCE_TRAP.get(), ManbearConfig.EVIDENCE_ANIMALS.get(),
                ManbearConfig.EVIDENCE_LEAVES.get(), ManbearConfig.EVIDENCE_PIT.get()
        };
        int total = 0;
        for (int x : w) total += x;
        if (total <= 0) return;
        int pick = rnd.nextInt(total);
        int k = 0;
        while (k < w.length - 1 && (pick -= w[k]) >= 0) k++;
        Kind kind = Kind.values()[k];

        if (!place(kind, level, brain, ground, at, away, trail, rnd) && kind != Kind.FOOTPRINTS) {
            place(Kind.FOOTPRINTS, level, brain, ground, at, away, trail, rnd);
        }
    }

    /** test command: place one specific kind of evidence */
    public static boolean leaveAs(String name, ServerLevel level, ManbearBrain brain, Vec3 at, Vec3 away, List<Vec3> trail) {
        Kind kind;
        try { kind = Kind.valueOf(name.toUpperCase()); } catch (IllegalArgumentException e) { return false; }
        BlockPos ground = CoverFinder.groundNear(level, at.x, at.z, Mth.floor(at.y), 2);
        return ground != null && level.hasChunkAt(ground) && place(kind, level, brain, ground, at, away, trail, level.random);
    }

    private static boolean place(Kind kind, ServerLevel level, ManbearBrain brain, BlockPos ground, Vec3 at, Vec3 away,
                                 List<Vec3> trail, RandomSource rnd) {
        return switch (kind) {
            case FOOTPRINTS -> footprints(level, at, away, trail, rnd);
            case TRAP -> trap(level, brain, ground);
            case ANIMALS -> deadAnimals(level, ground, rnd);
            case LEAVES -> leafPile(level, ground, rnd);
            case PIT -> pit(level, ground);
        };
    }

    // ------------------------------------------------------------------ footprints
    private static boolean footprints(ServerLevel level, Vec3 at, Vec3 away, List<Vec3> trail, RandomSource rnd) {
        List<Vec3> line = new ArrayList<>(trail);
        if (line.size() < 3) {   // he hardly moved: the prints come in from the direction he was watching you from
            Vec3 a = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : new Vec3(away.x, 0, away.z).normalize();
            Vec3 perp = new Vec3(-a.z, 0, a.x);
            line.clear();
            for (int i = 8; i >= 1; i--) {
                double wobble = Math.sin(i * 0.9) * 0.9;
                line.add(new Vec3(at.x + a.x * i + perp.x * wobble, at.y, at.z + a.z * i + perp.z * wobble));
            }
        }
        line.add(at);

        // resample the polyline every ~1.6 blocks (one stride)
        List<Vec3> strides = new ArrayList<>();
        double carry = 0;
        strides.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            Vec3 p0 = line.get(i - 1), p1 = line.get(i);
            double len = p0.distanceTo(p1);
            if (len < 1.0E-4) continue;
            double d = 1.6 - carry;
            while (d <= len) {
                strides.add(p0.lerp(p1, d / len));
                d += 1.6;
            }
            carry = len - (d - 1.6);
        }
        if (strides.size() > 14) strides = strides.subList(strides.size() - 14, strides.size());

        Set<BlockPos> used = new HashSet<>();
        int placed = 0;
        for (int i = 0; i < strides.size(); i++) {
            Vec3 p = strides.get(i);
            Vec3 next = i + 1 < strides.size() ? strides.get(i + 1) : at;
            Vec3 prev = i > 0 ? strides.get(i - 1) : p;
            Vec3 dir = new Vec3(next.x - prev.x, 0, next.z - prev.z);
            if (dir.lengthSqr() < 1.0E-4) dir = new Vec3(away.x * -1, 0, away.z * -1);
            Vec3 d = dir.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, -1) : dir.normalize();
            double side = (i % 2 == 0 ? 0.6 : -0.6);          // left foot, right foot
            double x = p.x + -d.z * side;
            double z = p.z + d.x * side;
            if (!level.hasChunkAt(BlockPos.containing(x, p.y, z))) continue;
            BlockPos g = CoverFinder.groundNear(level, x, z, Mth.floor(p.y), 2);
            if (g == null || !used.add(g)) continue;
            BlockState here = level.getBlockState(g);
            if (!(here.isAir() || here.canBeReplaced()) || !here.getFluidState().isEmpty()) continue;
            BlockState print = ModBlocks.FOOTPRINT.get().defaultBlockState()
                    .setValue(FootprintBlock.FACING, Direction.getNearest(d.x, 0, d.z))
                    .setValue(FootprintBlock.FADING, true);
            if (!print.canSurvive(level, g)) continue;
            level.setBlock(g, print, 3);
            placed++;
        }
        return placed > 0;
    }

    // ------------------------------------------------------------------ bear trap
    private static boolean trap(ServerLevel level, ManbearBrain brain, BlockPos ground) {
        if (brain.placeTrapAt(level, ground)) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos g = CoverFinder.groundNear(level, ground.getX() + 0.5 + d.getStepX(), ground.getZ() + 0.5 + d.getStepZ(), ground.getY(), 2);
            if (g != null && brain.placeTrapAt(level, g)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ dead animals
    private static boolean deadAnimals(ServerLevel level, BlockPos ground, RandomSource rnd) {
        int n = 2 + rnd.nextInt(3);
        int made = 0;
        for (int i = 0; i < n * 3 && made < n; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double r = made == 0 ? 0.0 : 1.4 + rnd.nextDouble() * 2.2;
            double x = ground.getX() + 0.5 + Math.cos(ang) * r;
            double z = ground.getZ() + 0.5 + Math.sin(ang) * r;
            if (!level.hasChunkAt(BlockPos.containing(x, ground.getY(), z))) continue;
            BlockPos g = CoverFinder.groundNear(level, x, z, ground.getY(), 2);
            if (g == null) continue;
            CorpseEntity c = ModEntities.CORPSE.get().create(level);
            if (c == null) continue;
            c.setKind(ANIMALS[rnd.nextInt(ANIMALS.length)]);
            c.moveTo(x, g.getY(), z, rnd.nextFloat() * 360F, 0F);
            level.addFreshEntity(c);
            made++;
        }
        return made > 0;
    }

    // ------------------------------------------------------------------ pile of leaves
    private static boolean leafPile(ServerLevel level, BlockPos ground, RandomSource rnd) {
        // the same leaves as the trees around here
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        for (int i = 0; i < 40; i++) {
            BlockPos p = ground.offset(rnd.nextInt(21) - 10, rnd.nextInt(9) - 2, rnd.nextInt(21) - 10);
            if (!level.hasChunkAt(p)) continue;
            BlockState st = level.getBlockState(p);
            if (st.is(BlockTags.LEAVES)) { leaves = st; break; }
        }
        if (leaves.hasProperty(LeavesBlock.PERSISTENT)) leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);   // never decays
        if (leaves.hasProperty(BlockStateProperties.WATERLOGGED)) leaves = leaves.setValue(BlockStateProperties.WATERLOGGED, false);

        int placed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean centre = dx == 0 && dz == 0;
                if (!centre && rnd.nextFloat() < 0.35F) continue;   // ragged edge
                BlockPos g = CoverFinder.groundNear(level, ground.getX() + 0.5 + dx, ground.getZ() + 0.5 + dz, ground.getY(), 2);
                if (g == null || Math.abs(g.getY() - ground.getY()) > 1) continue;
                if (setLeaf(level, g, leaves)) {
                    placed++;
                    if ((centre || rnd.nextFloat() < 0.25F)) setLeaf(level, g.above(), leaves);   // the heap rises in the middle
                }
            }
        }
        return placed > 0;
    }

    private static boolean setLeaf(ServerLevel level, BlockPos p, BlockState leaves) {
        BlockState here = level.getBlockState(p);
        if (!(here.isAir() || here.canBeReplaced()) || !here.getFluidState().isEmpty()) return false;
        level.setBlock(p, leaves, 3);
        return true;
    }

    // ------------------------------------------------------------------ pit fall
    private static boolean pit(ServerLevel level, BlockPos ground) {
        int depth = ManbearConfig.PIT_DEPTH.get();
        int[][] offsets = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
        for (int[] o : offsets) {
            BlockPos g = CoverFinder.groundNear(level, ground.getX() + 0.5 + o[0], ground.getZ() + 0.5 + o[1], ground.getY(), 2);
            if (g == null) continue;
            BlockPos top = g.below();
            if (PitTraps.siteOk(level, top, depth)) {
                PitTraps.build(level, top, depth);
                return true;
            }
        }
        return false;
    }
}
