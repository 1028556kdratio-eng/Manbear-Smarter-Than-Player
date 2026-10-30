package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.data.Pit;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The pit of the pit-lure event: a 3x3 hole under a roof of ordinary grass blocks.
 *
 * Nothing about it is a custom block. The nine surface blocks are left exactly as they were and the volume UNDER them
 * is dug out, so the trap looks identical to the ground around it. The server watches the block under every player's
 * feet; the moment it is one of the nine cover blocks it crunches and, a few ticks later, all nine break.
 */
public final class PitTraps {
    /** ticks between the crunch under your feet and the cover giving way */
    private static final int COLLAPSE_DELAY = 4;

    private PitTraps() { }

    @Nullable
    public static ServerLevel levelOf(MinecraftServer server, String dim) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dim)));
    }

    /** Y of the first free block above the ground in this column (the block you stand IN; the ground is Y-1) */
    private static int surface(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** can a pit go here? {@code top} is the middle cover block (the block you would stand ON) */
    public static boolean siteOk(ServerLevel level, BlockPos top, int depth) {
        for (int sx = -3; sx <= 3; sx += 6) {
            for (int sz = -3; sz <= 3; sz += 6) {
                if (!level.hasChunkAt(top.offset(sx, 0, sz))) return false;
            }
        }
        if (top.getY() - depth - 3 < level.getMinBuildHeight() + 2) return false;

        int grass = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = top.offset(dx, 0, dz);
                if (surface(level, c.getX(), c.getZ()) != top.getY() + 1) return false;   // the nine must be level
                BlockState st = level.getBlockState(c);
                if (!st.is(BlockTags.DIRT)) return false;
                if (st.is(Blocks.GRASS_BLOCK)) grass++;
                BlockState above = level.getBlockState(c.above());
                if (!above.getCollisionShape(level, c.above()).isEmpty() || !above.getFluidState().isEmpty()) return false;
            }
        }
        if (grass < 6 || !level.getBlockState(top).is(Blocks.GRASS_BLOCK)) return false;

        // the volume to dig: solid, ordinary, no tile entities, nothing unbreakable
        for (int dy = 1; dy <= depth; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = top.offset(dx, -dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir() || !st.getFluidState().isEmpty()) return false;
                    if (st.getDestroySpeed(level, p) < 0F) return false;
                    if (level.getBlockEntity(p) != null) return false;
                }
            }
        }
        // no water or lava next to the hole (it would flood), and a solid floor under it
        for (int dy = 0; dy <= depth + 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= 1 && dy <= depth) continue;
                    BlockPos p = top.offset(dx, -dy, dz);
                    if (!level.getBlockState(p).getFluidState().isEmpty()) return false;
                    if (dy == depth + 1 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && level.getBlockState(p).isAir()) return false;
                }
            }
        }
        return true;
    }

    /**
     * Looks for a site beyond the Manbear as seen from the player (so the player must run past the pit to follow him),
     * digs it and registers it. Returns null if the terrain has no room for one.
     */
    @Nullable
    public static Pit plan(ServerLevel level, ServerPlayer p, Vec3 bearPos) {
        double dx = bearPos.x - p.getX();
        double dz = bearPos.z - p.getZ();
        double dB = Math.sqrt(dx * dx + dz * dz);
        if (dB < 1.0E-3) return null;
        double ux = dx / dB, uz = dz / dB;
        double sx = -uz, sz = ux;
        int depth = ManbearConfig.PIT_DEPTH.get();
        double[] beyond = {16, 20, 24, 13, 28, 18, 22};
        int[] lateral = {0, 3, -3, 6, -6};
        for (double t : beyond) {
            for (int lat : lateral) {
                int x = Mth.floor(p.getX() + ux * (dB + t) + sx * lat);
                int z = Mth.floor(p.getZ() + uz * (dB + t) + sz * lat);
                if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
                int y = surface(level, x, z);
                if (Math.abs(y - p.getBlockY()) > 8) continue;
                BlockPos top = new BlockPos(x, y - 1, z);
                if (siteOk(level, top, depth)) return build(level, top, depth);
            }
        }
        return null;
    }

    /** digs the hole under the cover and registers the pit */
    public static Pit build(ServerLevel level, BlockPos top, int depth) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dy = 1; dy <= depth; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(top.offset(dx, -dy, dz), air, 3);
                }
            }
        }
        Pit pit = new Pit(level.dimension().location().toString(), top, depth);
        MinecraftServer server = level.getServer();
        for (Pit old : ManbearData.get(server).addPit(pit, ManbearConfig.PIT_MAX.get())) {
            fillIn(levelOf(server, old.dim), old);
        }
        return pit;
    }

    /** puts dirt back in an armed pit (the cover was never touched) */
    public static void fillIn(@Nullable ServerLevel level, Pit pit) {
        if (level == null || pit.sprung()) return;
        AABB cavity = new AABB(pit.top.getX() - 1, pit.top.getY() - pit.depth, pit.top.getZ() - 1,
                pit.top.getX() + 2, pit.top.getY(), pit.top.getZ() + 2);
        if (!level.getEntitiesOfClass(Player.class, cavity).isEmpty()) return;   // never bury somebody
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int dy = 1; dy <= pit.depth; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = pit.top.offset(dx, -dy, dz);
                    if (level.hasChunkAt(p) && level.getBlockState(p).isAir()) level.setBlock(p, dirt, 3);
                }
            }
        }
    }

    /** the lure failed: fill the hole and forget it */
    public static void removeAndFill(ServerLevel level, Pit pit) {
        if (pit.sprung()) return;
        if (ManbearConfig.PIT_FILL_UNUSED.get()) fillIn(level, pit);
        ManbearData.get(level.getServer()).removePit(pit);
    }

    /** somebody mined a cover block: the trap is found and disarmed (the hole stays) */
    public static void onBlockBroken(ServerLevel level, BlockPos pos) {
        ManbearData data = ManbearData.get(level.getServer());
        if (data.pits().isEmpty()) return;
        String dim = level.dimension().location().toString();
        for (Pit pit : new ArrayList<>(data.pits())) {
            if (pit.dim.equals(dim) && !pit.sprung() && pit.covers(pos)) data.removePit(pit);
        }
    }

    /** call every couple of ticks: watches for feet on the cover and breaks it */
    public static void tick(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        List<Pit> pits = data.pits();
        if (pits.isEmpty()) return;
        for (Pit pit : new ArrayList<>(pits)) {
            ServerLevel level = levelOf(server, pit.dim);
            if (level == null) continue;
            if (pit.sprung()) {
                if (now >= pit.collapseAt) collapse(level, pit);
                continue;
            }
            if (!level.hasChunkAt(pit.top)) continue;
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator() || p.isCreative() || !p.isAlive() || !p.onGround()) continue;
                if (pit.covers(p.getOnPos())) {
                    pit.sprungBy = p.getUUID();
                    pit.collapseAt = now + COLLAPSE_DELAY;
                    level.playSound(null, pit.top, ModSounds.PIT_CRUNCH.get(), SoundSource.BLOCKS, 2.5F, 0.8F);
                    break;
                }
            }
        }
    }

    private static void collapse(ServerLevel level, Pit pit) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = pit.top.offset(dx, 0, dz);
                if (!level.getBlockState(p).isAir()) level.destroyBlock(p, false);
            }
        }
        ManbearData.get(level.getServer()).removePit(pit);
    }
}
