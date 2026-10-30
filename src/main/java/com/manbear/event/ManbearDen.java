package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.data.PlayerProfile;
import com.manbear.entity.CorpseEntity;
import com.manbear.entity.ManbearEntity;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/** Persistent one-per-world Manbear cave den, generated near a learned home area. */
public final class ManbearDen {
    private ManbearDen() { }

    private record Home(UUID owner, BlockPos base, PlayerProfile profile) { }

    public static void tick(MinecraftServer server, long now) {
        if (server == null || !ManbearConfig.DEN_ENABLED.get()) return;
        ManbearData data = ManbearData.get(server);

        if (data.denCooldownUntil() > 0L) {
            if (now < data.denCooldownUntil()) return;
            data.completeDenCooldown(); // fills the responsible player's rage to 100 once
        }

        if (data.hasDen()) {
            if (now % 100L == 0L) {
                // Older versions sampled the top of a tree canopy as the "surface", leaving the den floating
                // in the forest. Repair those saved dens once their region is loaded, then generate a proper one.
                if (repairMisplacedDen(server, data)) return;
                if (upgradeExistingDen(server, data)) return;
                inspectDen(server, now, data);
            }
            return;
        }
        if (now % 100L != 0L) return;

        Home home = findHome(server, data);
        if (home == null) return;
        if (!data.denUnlocked() && home.profile().basePresenceTicks < ManbearConfig.DEN_HOME_TIME_MINUTES.get() * 1200) return;

        ServerLevel level = server.overworld();
        long layoutSeed = level.random.nextLong() ^ level.getSeed() ^ now
                ^ java.util.UUID.randomUUID().getLeastSignificantBits()
                ^ home.owner().getMostSignificantBits();
        RandomSource layoutRandom = RandomSource.create(layoutSeed);
        BlockPos generated = tryGenerate(level, home.base(), layoutRandom);
        if (generated == null) return;
        int surfaceY = generated.getY();
        BlockPos center = new BlockPos(generated.getX(), surfaceY - 9, generated.getZ());
        // The saved entrance is the feet position at the mouth, not the floor block beneath it.
        BlockPos entrance = new BlockPos(generated.getX() + 19, surfaceY - 1, generated.getZ());
        build(level, center, surfaceY, layoutRandom);
        data.setDen(level.dimension().location().toString(), center, entrance, home.owner());
    }

    private static Home findHome(MinecraftServer server, ManbearData data) {
        // Once the den has existed, rebuild around its original owner's learned home after the lockout.
        if (data.denUnlocked() && data.denOwner() != null) {
            PlayerProfile profile = data.profile(data.denOwner());
            if (profile.base != null) return new Home(data.denOwner(), profile.base, profile);
        }
        Home best = null;
        int bestPresence = -1;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || player.isCreative() || !player.isAlive() || player.serverLevel() != server.overworld()) continue;
            PlayerProfile profile = data.profile(player.getUUID());
            if (profile.base == null) continue;
            if (profile.basePresenceTicks > bestPresence) {
                bestPresence = profile.basePresenceTicks;
                best = new Home(player.getUUID(), profile.base, profile);
            }
        }
        return best;
    }

    /** Returns the chosen base-relative X/Z at its surface Y, or null until a safe loaded site is available. */
    private static BlockPos tryGenerate(ServerLevel level, BlockPos base, RandomSource random) {
        // Keep the full den footprint inside the configured base-distance band:
        // the structure extends roughly 20 blocks beyond its centre in either direction.
        int min = ManbearConfig.DEN_MIN_BASE_DISTANCE.get() + 25;
        int max = ManbearConfig.DEN_MAX_BASE_DISTANCE.get() - 20;
        if (max < min) return null;
        for (int attempt = 0; attempt < 100; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = min + random.nextDouble() * (max - min);
            int x = base.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos column = new BlockPos(x, base.getY(), z);
            BlockPos minPos = new BlockPos(x - 20, Math.max(level.getMinBuildHeight() + 8, base.getY() - 30), z - 20);
            BlockPos maxPos = new BlockPos(x + 20, Math.min(level.getMaxBuildHeight() - 2, base.getY() + 20), z + 20);
            if (!level.getWorldBorder().isWithinBounds(column) || !level.hasChunksAt(minPos, maxPos)) continue;

            // MOTION_BLOCKING_NO_LEAVES still treats tree trunks as terrain. Scanning down from WORLD_SURFACE
            // and explicitly skipping leaves/logs finds the actual ground below forest canopies.
            int surface = findNaturalSurface(level, x, z);
            if (surface == Integer.MIN_VALUE || surface < level.getMinBuildHeight() + 30
                    || surface > level.getMaxBuildHeight() - 20) continue;
            BlockPos ground = new BlockPos(x, surface - 1, z);
            if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) continue;
            if (!level.getFluidState(ground).isEmpty()) continue;

            double baseDistance = Math.sqrt(base.distSqr(new BlockPos(x, base.getY(), z)));
            if (baseDistance < ManbearConfig.DEN_MIN_BASE_DISTANCE.get() + 20.0
                    || baseDistance > ManbearConfig.DEN_MAX_BASE_DISTANCE.get() - 20.0) continue;
            if (!dryCaveVolume(level, x, z, surface)) continue;
            if (!hasBuriedChamberRoof(level, x, z, surface)) continue;
            int entranceSurface = findNaturalSurface(level, x + 19, z);
            if (entranceSurface == Integer.MIN_VALUE || Math.abs(entranceSurface - surface) > 2) continue;
            return new BlockPos(x, surface, z);
        }
        return null;
    }

    /** Returns the first air block above natural ground, ignoring foliage and trunks; MIN_VALUE means no ground found. */
    private static int findNaturalSurface(ServerLevel level, int x, int z) {
        int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), level.getMaxBuildHeight() - 1);
        int minY = Math.max(level.getMinBuildHeight() + 20, top - 128);
        for (int y = top - 1; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                    || isDenDecoration(state) || !level.getFluidState(pos).isEmpty()) continue;
            if (!state.canOcclude() || !state.isFaceSturdy(level, pos, Direction.UP)) continue;
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    /** Blocks used as den decorations; ignore these when reconstructing the real terrain surface on legacy saves. */
    private static boolean isDenDecoration(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.BONE_BLOCK) || state.is(Blocks.COBWEB)
                || state.is(Blocks.CHAIN) || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.REDSTONE_ORE)
                || state.is(Blocks.DEEPSLATE_REDSTONE_ORE) || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.SKELETON_SKULL) || state.is(Blocks.WITHER_SKELETON_SKULL)
                || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE);
    }

    /** Require a solid underground roof above the main room, rejecting hillsides and open-air/canopy sites. */
    private static boolean hasBuriedChamberRoof(ServerLevel level, int cx, int cz, int surface) {
        int roofY = surface - 7;
        for (int dx = -6; dx <= 6; dx += 3) {
            for (int dz = -6; dz <= 6; dz += 3) {
                BlockPos roof = new BlockPos(cx + dx, roofY, cz + dz);
                var state = level.getBlockState(roof);
                if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                        || !level.getFluidState(roof).isEmpty()
                        || !state.isFaceSturdy(level, roof, Direction.DOWN)) return false;
            }
        }
        return true;
    }

    /** Detect and migrate dens saved by the old tree-canopy heightmap bug. */
    private static boolean repairMisplacedDen(MinecraftServer server, ManbearData data) {
        BlockPos center = data.denCenter();
        if (center == null) return false;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(data.denDimension())));
        if (level == null) return false;

        int savedSurface = center.getY() + 9;
        int actualSurface = findNaturalSurface(level, center.getX(), center.getZ());
        if (actualSurface == Integer.MIN_VALUE || Math.abs(actualSurface - savedSurface) <= 4) return false;

        BlockPos from = new BlockPos(center.getX() - 20, savedSurface - 14, center.getZ() - 20);
        BlockPos to = new BlockPos(center.getX() + 20, savedSurface + 6, center.getZ() + 20);
        if (!level.hasChunksAt(from, to)) return false; // wait until cleanup can be done without loading/generating chunks

        // Remove den decorations and permanent remains that were stranded above the real ground by older versions.
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int z = from.getZ(); z <= to.getZ(); z++) {
                int localSurface = findNaturalSurface(level, x, z);
                if (localSurface == Integer.MIN_VALUE) continue;
                for (int y = Math.max(from.getY(), localSurface); y <= to.getY(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var state = level.getBlockState(p);
                    if (isDenDecoration(state)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        net.minecraft.world.phys.AABB oldArea = new net.minecraft.world.phys.AABB(
                from.getX(), from.getY(), from.getZ(), to.getX() + 1.0, to.getY() + 1.0, to.getZ() + 1.0);
        for (CorpseEntity corpse : level.getEntitiesOfClass(CorpseEntity.class, oldArea)) {
            if (corpse.isPermanent()) {
                int groundSurface = findNaturalSurface(level, corpse.blockPosition().getX(), corpse.blockPosition().getZ());
                if (groundSurface != Integer.MIN_VALUE && corpse.getY() >= groundSurface) corpse.discard();
            }
        }
        data.relocateDenForRegeneration();
        return true;
    }

    private static boolean dryCaveVolume(ServerLevel level, int cx, int cz, int surface) {
        // The chamber and the descending entrance may not intersect water or lava.
        for (int x = cx - 8; x <= cx + 20; x++) {
            for (int z = cz - 8; z <= cz + 8; z++) {
                for (int y = surface - 14; y <= surface + 1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        for (int x = cx - 15; x <= cx + 8; x++) {
            for (int z = cz - 15; z <= cz + 8; z++) {
                for (int y = surface - 13; y <= surface - 5; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    private static void build(ServerLevel level, BlockPos center, int surface, RandomSource random) {
        int cx = center.getX(), cz = center.getZ();
        // Main chamber: an irregular rounded room with enough ceiling clearance for the Manbear.
        double[][] edgeNoise = new double[17][17];
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            // The changing boundary breaks the perfect ellipse and makes the room look cave-formed.
            double noise = (random.nextDouble() - 0.5) * 0.34;
            double ellipse = (dx * dx) / 58.0 + (dz * dz) / 58.0;
            edgeNoise[dx + 8][dz + 8] = ellipse - noise;
        }
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (edgeNoise[dx + 8][dz + 8] > 1.08) continue;
                for (int dy = -11; dy <= -6; dy++) {
                    // A few ceiling/floor irregularities avoid the appearance of a perfectly level dug room.
                    if (dy == -6 && random.nextFloat() < 0.14F) continue;
                    carve(level, new BlockPos(cx + dx, surface + dy, cz + dz));
                }
            }
        }

        buildMainRamp(level, cx, cz, surface, random, false);
        buildSideTunnels(level, cx, cz, surface, random, false);
        buildWindingFeedingTunnel(level, cx, cz, surface, random, false);
        buildRandomBranches(level, cx, cz, surface, random, false);
        decorateMainChamber(level, cx, cz, surface, random, true);
        decorateSurfaceEntrance(level, cx, cz, surface, random, true);
    }

    /** Add the v2 geometry and decoration to dens already saved by earlier versions. */
    private static boolean upgradeExistingDen(MinecraftServer server, ManbearData data) {
        if (!data.hasDen() || data.denBuildVersion() >= ManbearData.CURRENT_DEN_BUILD_VERSION) return false;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(data.denDimension())));
        BlockPos center = data.denCenter();
        if (level == null || center == null) return false;
        int surface = center.getY() + 9;
        BlockPos from = new BlockPos(center.getX() - 20, surface - 14, center.getZ() - 20);
        BlockPos to = new BlockPos(center.getX() + 20, surface + 6, center.getZ() + 20);
        if (!level.hasChunksAt(from, to)) return false;

        // Widen the escort route and add irregular branches while preserving non-natural player builds.
        if (data.denBuildVersion() < 3) removeLegacyEntranceArch(level, center.getX(), center.getZ(), surface);
        buildMainRamp(level, center.getX(), center.getZ(), surface, level.random, true);
        buildSideTunnels(level, center.getX(), center.getZ(), surface, level.random, true);
        buildWindingFeedingTunnel(level, center.getX(), center.getZ(), surface, level.random, true);
        buildRandomBranches(level, center.getX(), center.getZ(), surface, level.random, true);
        decorateMainChamber(level, center.getX(), center.getZ(), surface, level.random, false);
        decorateSurfaceEntrance(level, center.getX(), center.getZ(), surface, level.random, true);
        data.updateDenEntrance(new BlockPos(center.getX() + 19, surface - 1, center.getZ()));
        data.setDenBuildVersion(ManbearData.CURRENT_DEN_BUILD_VERSION);
        return true;
    }

    private static int rampFloor(int surface, int xOffset) {
        return surface - 12 + Math.round((xOffset - 5) * 10.0F / 14.0F);
    }

    /** Five-block-wide, five-block-high ramp so the full-sized Manbear can walk the player into the den. */
    private static void buildMainRamp(ServerLevel level, int cx, int cz, int surface, RandomSource random, boolean upgrade) {
        for (int i = 5; i <= 19; i++) {
            int floorY = rampFloor(surface, i);
            for (int side = -3; side <= 3; side++) {
                for (int up = 1; up <= 6; up++) {
                    BlockPos p = new BlockPos(cx + i, floorY + up, cz + side);
                    if (upgrade) carveForUpgrade(level, p); else carve(level, p);
                }
                BlockPos floor = new BlockPos(cx + i, floorY, cz + side);
                ensureTunnelFloor(level, floor, random, upgrade);
            }
        }
    }

    private static void buildSideTunnels(ServerLevel level, int cx, int cz, int surface, RandomSource random, boolean upgrade) {
        // A crooked north tunnel. Small width variations keep its walls from looking machine-cut.
        for (int z = 6; z >= -13; z--) {
            int width = (Math.abs(z) % 5 == 0) ? 2 : 1;
            for (int xOff = -2; xOff <= 2; xOff++) {
                if (Math.abs(xOff) > width) continue;
                int floorY = surface - 12;
                for (int up = 1; up <= 5; up++) {
                    BlockPos p = new BlockPos(cx + xOff, floorY + up, cz + z);
                    if (upgrade) carveForUpgrade(level, p); else carve(level, p);
                }
                ensureTunnelFloor(level, new BlockPos(cx + xOff, floorY, cz + z), random, upgrade);
            }
        }
        // West passage into a feeding room; it intersects the northern tunnel, making a small loop.
        for (int x = -6; x >= -13; x--) {
            int width = (Math.abs(x) % 4 == 0) ? 2 : 1;
            for (int zOff = -4; zOff <= 0; zOff++) {
                if (Math.abs(zOff + 2) > width) continue;
                int floorY = surface - 12;
                for (int up = 1; up <= 5; up++) {
                    BlockPos p = new BlockPos(cx + x, floorY + up, cz + zOff);
                    if (upgrade) carveForUpgrade(level, p); else carve(level, p);
                }
                ensureTunnelFloor(level, new BlockPos(cx + x, floorY, cz + zOff), random, upgrade);
            }
        }
        // Rounded feeding alcove at the western end.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx * dx + dz * dz > 6) continue;
            int floorY = surface - 12;
            for (int up = 1; up <= 5; up++) {
                BlockPos p = new BlockPos(cx - 14 + dx, floorY + up, cz - 3 + dz);
                if (upgrade) carveForUpgrade(level, p); else carve(level, p);
            }
            ensureTunnelFloor(level, new BlockPos(cx - 14 + dx, floorY, cz - 3 + dz), random, upgrade);
        }
    }

    /** An extra wandering branch ending in a wider side chamber. */
    private static void buildWindingFeedingTunnel(ServerLevel level, int cx, int cz, int surface, RandomSource random, boolean upgrade) {
        int[][] path = {{3,5},{4,6},{5,7},{6,8},{6,9},{7,10},{8,11},{8,12}};
        int floorY = surface - 12;
        for (int i = 0; i < path.length; i++) {
            int px = path[i][0], pz = path[i][1];
            int radius = (i == 2 || i == 5) ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius + 1) continue;
                    for (int up = 1; up <= 5; up++) {
                        BlockPos p = new BlockPos(cx + px + dx, floorY + up, cz + pz + dz);
                        if (upgrade) carveForUpgrade(level, p); else carve(level, p);
                    }
                    ensureTunnelFloor(level, new BlockPos(cx + px + dx, floorY, cz + pz + dz), random, upgrade);
                }
            }
        }
        // A broader pocket gives the branch a natural destination rather than ending as a straight corridor.
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx / 1.0 + dz * dz / 1.0 > 12.0) continue;
            for (int up = 1; up <= 5; up++) {
                BlockPos p = new BlockPos(cx + 8 + dx, floorY + up, cz + 13 + dz);
                if (upgrade) carveForUpgrade(level, p); else carve(level, p);
            }
            ensureTunnelFloor(level, new BlockPos(cx + 8 + dx, floorY, cz + 13 + dz), random, upgrade);
        }
    }

    /** Several individually seeded, crooked side passages and irregular pockets; each den gets a different plan. */
    private static void buildRandomBranches(ServerLevel level, int cx, int cz, int surface, RandomSource random, boolean upgrade) {
        int branches = 2 + random.nextInt(4);
        for (int b = 0; b < branches; b++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int x = (int) Math.round(Math.cos(angle) * (4 + random.nextInt(3)));
            int z = (int) Math.round(Math.sin(angle) * (4 + random.nextInt(3)));
            int dx = Math.abs(x) >= Math.abs(z) ? Integer.signum(x) : 0;
            int dz = dx == 0 ? Integer.signum(z) : 0;
            if (dx == 0 && dz == 0) dx = 1;
            int steps = 10 + random.nextInt(16);
            int turnAfter = 3 + random.nextInt(4);
            int width = random.nextBoolean() ? 1 : 2;
            for (int step = 0; step < steps; step++) {
                if (step > 0 && step % turnAfter == 0 && random.nextFloat() < 0.68F) {
                    int oldDx = dx;
                    dx = -dz * (random.nextBoolean() ? 1 : -1);
                    dz = oldDx * (random.nextBoolean() ? 1 : -1);
                    if (random.nextFloat() < 0.25F) width = width == 1 ? 2 : 1;
                }
                x += dx;
                z += dz;
                if (Math.abs(x) > 19 || Math.abs(z) > 19) break;
                int localWidth = (step % 6 == 0) ? Math.min(2, width + 1) : width;
                int height = 3 + random.nextInt(3);
                for (int ox = -localWidth; ox <= localWidth; ox++) {
                    for (int oz = -localWidth; oz <= localWidth; oz++) {
                        if (ox * ox + oz * oz > localWidth * localWidth + 1) continue;
                        for (int up = 1; up <= height; up++) {
                            BlockPos p = new BlockPos(cx + x + ox, surface - 12 + up, cz + z + oz);
                            if (upgrade) carveForUpgrade(level, p); else carve(level, p);
                        }
                        ensureTunnelFloor(level, new BlockPos(cx + x + ox, surface - 12, cz + z + oz), random, upgrade);
                    }
                }
            }
            // Blunt natural-looking pockets at the ends of a few routes.
            if (Math.abs(x) <= 18 && Math.abs(z) <= 18) {
                int rx = 2 + random.nextInt(3), rz = 2 + random.nextInt(3);
                for (int ox = -rx; ox <= rx; ox++) for (int oz = -rz; oz <= rz; oz++) {
                    double shape = ox * ox / (double)(rx * rx) + oz * oz / (double)(rz * rz);
                    if (shape > 1.15) continue;
                    for (int up = 1; up <= 4 + random.nextInt(2); up++) {
                        BlockPos p = new BlockPos(cx + x + ox, surface - 12 + up, cz + z + oz);
                        if (upgrade) carveForUpgrade(level, p); else carve(level, p);
                    }
                    ensureTunnelFloor(level, new BlockPos(cx + x + ox, surface - 12, cz + z + oz), random, upgrade);
                }
            }
        }
    }

    private static void ensureTunnelFloor(ServerLevel level, BlockPos floor, RandomSource random, boolean upgrade) {
        if (!level.hasChunkAt(floor) || !level.getFluidState(floor).isEmpty()) return;
        BlockState state = level.getBlockState(floor);
        if (state.isFaceSturdy(level, floor, Direction.UP)) return;
        if (upgrade && !(state.isAir() || state.canBeReplaced() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS))) return;
        BlockState replacement = switch (random.nextInt(5)) {
            case 0 -> Blocks.COBBLESTONE.defaultBlockState();
            case 1 -> Blocks.ANDESITE.defaultBlockState();
            case 2 -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case 3 -> Blocks.GRAVEL.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
        level.setBlock(floor, replacement, 2);
    }

    /** Only remove terrain while upgrading a saved den; player-placed blocks are left alone. */
    private static boolean carveForUpgrade(ServerLevel level, BlockPos p) {
        if (p.getY() <= level.getMinBuildHeight() + 4 || p.getY() >= level.getMaxBuildHeight() - 1
                || !level.hasChunkAt(p) || !level.getFluidState(p).isEmpty()) return false;
        BlockState state = level.getBlockState(p);
        if (state.isAir() || state.is(Blocks.BEDROCK)) return false;
        boolean natural = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND) || state.is(Blocks.CLAY)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MUD) || state.is(Blocks.COARSE_DIRT);
        if (natural && !isDenDecoration(state)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            return true;
        }
        return false;
    }

    private static void decorateMainChamber(ServerLevel level, int cx, int cz, int surface, RandomSource random, boolean createCorpses) {
        BlockPos floorCenter = new BlockPos(cx, surface - 12, cz);
        int[][] stones = {{-5,-4},{-4,3},{2,5},{5,1},{-2,5},{4,-4},{-5,2}};
        for (int[] o : stones) {
            BlockPos p = floorCenter.offset(o[0], 0, o[1]);
            if (level.getBlockState(p).isAir()) {
                if (random.nextBoolean()) level.setBlock(p, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
                else level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 2);
            }
        }
        for (BlockPos p : List.of(floorCenter.offset(-2, 1, -2), floorCenter.offset(-1, 1, -2), floorCenter.offset(-2, 1, -1))) {
            placeIfFree(level, p, Blocks.BONE_BLOCK.defaultBlockState());
        }
        // Skull piles, plus little cobwebbed corners.
        placeIfFree(level, floorCenter.offset(4, 1, 2), Blocks.SKELETON_SKULL.defaultBlockState());
        placeIfFree(level, floorCenter.offset(-4, 1, 2), Blocks.WITHER_SKELETON_SKULL.defaultBlockState());
        for (BlockPos p : List.of(
                new BlockPos(cx - 5, surface - 8, cz - 5), new BlockPos(cx + 5, surface - 8, cz - 5),
                new BlockPos(cx - 5, surface - 8, cz + 5), new BlockPos(cx + 5, surface - 8, cz + 5))) {
            placeIfFree(level, p, Blocks.COBWEB.defaultBlockState());
        }
        // Dim lanterns and rare redstone glows keep the den readable without lighting it like a base.
        for (int[] o : new int[][]{{-4,-4},{4,-4},{-4,4},{4,4}}) {
            BlockPos chain = new BlockPos(cx + o[0], surface - 6, cz + o[1]);
            BlockPos lantern = chain.below();
            placeIfFree(level, chain, Blocks.CHAIN.defaultBlockState());
            placeIfFree(level, lantern, Blocks.SOUL_LANTERN.defaultBlockState());
        }
        placeIfFree(level, floorCenter.offset(1, 1, 3), Blocks.REDSTONE_TORCH.defaultBlockState());
        placeIfFree(level, floorCenter.offset(-3, 1, -1), Blocks.REDSTONE_TORCH.defaultBlockState());
        decorateCaveWalls(level, cx, cz, surface, random);
        decorateDripstone(level, cx, cz, surface);

        if (createCorpses) spawnCorpses(level, cx, cz, surface - 11, random, 9, false);
    }

    private static void decorateCaveWalls(ServerLevel level, int cx, int cz, int surface, RandomSource random) {
        BlockPos[] oreSpots = {
                new BlockPos(cx + 9, surface - 9, cz), new BlockPos(cx - 9, surface - 9, cz + 4),
                new BlockPos(cx + 3, surface - 8, cz - 9), new BlockPos(cx - 5, surface - 8, cz + 8)
        };
        for (BlockPos p : oreSpots) {
            BlockState existing = level.getBlockState(p);
            if (existing.is(BlockTags.BASE_STONE_OVERWORLD)) {
                level.setBlock(p, random.nextBoolean() ? Blocks.REDSTONE_ORE.defaultBlockState()
                        : Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState(), 2);
            }
        }
    }

    private static void decorateDripstone(ServerLevel level, int cx, int cz, int surface) {
        int[][] stalactites = {{-3,0},{3,-2},{1,4},{-5,-2}};
        for (int[] o : stalactites) {
            BlockPos tip = new BlockPos(cx + o[0], surface - 6, cz + o[1]);
            BlockPos ceiling = tip.above();
            if (level.getBlockState(tip).isAir() && level.getBlockState(ceiling).isFaceSturdy(level, ceiling, Direction.DOWN)) {
                level.setBlock(tip, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                        .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
                        .setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP), 2);
            }
        }
        int[][] stalagmites = {{-1,1},{4,3},{-4,4}};
        for (int[] o : stalagmites) {
            BlockPos tip = new BlockPos(cx + o[0], surface - 11, cz + o[1]);
            if (level.getBlockState(tip).isAir() && level.getBlockState(tip.below()).isFaceSturdy(level, tip.below(), Direction.UP)) {
                level.setBlock(tip, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                        .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.UP)
                        .setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP), 2);
            }
        }
    }

    private static void decorateSurfaceEntrance(ServerLevel level, int cx, int cz, int surface, RandomSource random, boolean corpses) {
        // Keep the mouth easy to miss. Avoid a tall masonry arch; use low, uneven rubble and sparse remains nearby.
        int mouthX = cx + 19;
        int[][] rubble = {{17,-4},{18,-4},{20,4},{21,3},{16,3},{21,-2}};
        for (int[] off : rubble) {
            if (random.nextFloat() < 0.38F) continue;
            int localSurface = naturalSurfaceOr(level, cx + off[0], cz + off[1], surface);
            BlockPos p = new BlockPos(cx + off[0], localSurface - 1, cz + off[1]);
            BlockState stone = random.nextBoolean() ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
            placeEntranceMasonry(level, p, stone);
        }
        // Bone fragments lead toward a dark opening, but are broken up rather than arranged symmetrically.
        int[][] bones = {{13,-3},{16,4},{18,-4},{21,2}};
        for (int[] off : bones) {
            if (random.nextFloat() < 0.58F) {
                int localSurface = naturalSurfaceOr(level, cx + off[0], cz + off[1], surface);
                placeIfFree(level, new BlockPos(cx + off[0], localSurface, cz + off[1]), Blocks.BONE_BLOCK.defaultBlockState());
            }
        }
        if (random.nextFloat() < 0.42F) {
            int skullX = cx + 18 + random.nextInt(4);
            int skullZ = cz + (random.nextBoolean() ? 4 : -4);
            int localSurface = naturalSurfaceOr(level, skullX, skullZ, surface);
            placeIfFree(level, new BlockPos(skullX, localSurface, skullZ), Blocks.SKELETON_SKULL.defaultBlockState());
        }
        if (random.nextFloat() < 0.55F) {
            int webZ = cz + (random.nextBoolean() ? 3 : -3);
            int localSurface = naturalSurfaceOr(level, mouthX + 1, webZ, surface);
            placeIfFree(level, new BlockPos(mouthX + 1, localSurface + 1, webZ), Blocks.COBWEB.defaultBlockState());
        }
        if (corpses) spawnSurfaceCorpses(level, cx, cz, surface, random);
    }

    private static void removeLegacyEntranceArch(ServerLevel level, int cx, int cz, int surface) {
        int mouthX = cx + 19;
        for (int y = surface; y <= surface + 4; y++) {
            for (int z = -3; z <= 3; z++) {
                BlockPos pos = new BlockPos(mouthX, y, cz + z);
                if (!level.hasChunkAt(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.MOSSY_STONE_BRICKS)
                        || state.is(Blocks.STONE_BRICKS)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static void placeEntranceMasonry(ServerLevel level, BlockPos pos, BlockState replacement) {
        if (!level.hasChunkAt(pos) || !level.getFluidState(pos).isEmpty()) return;
        BlockState old = level.getBlockState(pos);
        if (old.isAir() || old.canBeReplaced() || old.is(BlockTags.LEAVES) || old.is(BlockTags.LOGS)
                || old.is(BlockTags.BASE_STONE_OVERWORLD) || old.is(BlockTags.DIRT) || old.is(Blocks.GRAVEL)) {
            level.setBlock(pos, replacement, 2);
        }
    }

    private static void placeIfFree(ServerLevel level, BlockPos p, BlockState state) {
        if (level.hasChunkAt(p) && level.getFluidState(p).isEmpty()
                && (level.getBlockState(p).isAir() || level.getBlockState(p).canBeReplaced())) {
            level.setBlock(p, state, 2);
        }
    }

    private static int naturalSurfaceOr(ServerLevel level, int x, int z, int fallback) {
        int local = findNaturalSurface(level, x, z);
        return local == Integer.MIN_VALUE ? fallback : local;
    }

    private static void spawnSurfaceCorpses(ServerLevel level, int cx, int cz, int surface, RandomSource random) {
        String[] kinds = {"minecraft:sheep", "minecraft:pig", "minecraft:cow", "minecraft:chicken"};
        int count = 1 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            int x = cx + 12 + random.nextInt(9);
            int z = cz + random.nextInt(11) - 5;
            int localSurface = naturalSurfaceOr(level, x, z, surface);
            spawnCorpseAtSurface(level, x, z, localSurface, random, kinds[random.nextInt(kinds.length)]);
        }
    }

    private static void spawnCorpses(ServerLevel level, int cx, int cz, int y, RandomSource random, int count, boolean surface) {
        String[] kinds = {"minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit"};
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = 1.5 + random.nextDouble() * 4.2;
            double x = cx + 0.5 + Math.cos(angle) * radius;
            double z = cz + 0.5 + Math.sin(angle) * radius;
            BlockPos feet = new BlockPos((int)Math.floor(x), y, (int)Math.floor(z));
            if (!level.getBlockState(feet).isAir()) feet = feet.above();
            CorpseEntity corpse = ModEntities.CORPSE.get().create(level);
            if (corpse == null) continue;
            corpse.setKind(kinds[random.nextInt(kinds.length)]);
            corpse.setPermanent(true);
            corpse.moveTo(x, feet.getY(), z, random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntity(corpse);
        }
    }

    private static void spawnCorpseAtSurface(ServerLevel level, int x, int z, int surface, RandomSource random, String kind) {
        BlockPos feet = new BlockPos(x, surface, z);
        if (!level.hasChunkAt(feet) || !level.getBlockState(feet).isAir()
                || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                || !level.getFluidState(feet).isEmpty()) return;
        CorpseEntity corpse = ModEntities.CORPSE.get().create(level);
        if (corpse == null) return;
        corpse.setKind(kind);
        corpse.setPermanent(true);
        corpse.moveTo(x + 0.5, surface, z + 0.5, random.nextFloat() * 360.0F, 0.0F);
        level.addFreshEntity(corpse);
    }

    private static void carve(ServerLevel level, BlockPos p) {
        if (p.getY() <= level.getMinBuildHeight() + 4 || p.getY() >= level.getMaxBuildHeight() - 1) return;
        if (!level.hasChunkAt(p)) return;
        if (!level.getFluidState(p).isEmpty()) return;
        if (level.getBlockState(p).is(Blocks.BEDROCK)) return;
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }

    public static boolean contains(ServerLevel level, BlockPos pos, ManbearData data) {
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) return false;
        BlockPos center = data.denCenter();
        if (center == null) return false;
        int cx = center.getX(), cz = center.getZ();
        int surface = center.getY() + 9;
        int dx = pos.getX() - cx, dz = pos.getZ() - cz;
        int ry = pos.getY() - surface;
        // Main room including floor/roof/walls.
        if (Math.abs(dx) <= 8 && Math.abs(dz) <= 8 && ry >= -13 && ry <= -5) return true;
        // Main entrance ramp, its full-width passage, and the reinforced visible mouth.
        if (dx >= 5 && dx <= 19 && Math.abs(dz) <= 2) {
            int floorY = -12 + Math.round((dx - 5) * 10.0F / 14.0F);
            if (ry >= floorY - 1 && ry <= floorY + 6) return true;
        }
        if (dx >= 16 && dx <= 20 && Math.abs(dz) <= 5 && ry >= -4 && ry <= 5) return true;
        // North and west branches, the western feeding room, and the newer winding southeast branch.
        if (dx >= -2 && dx <= 2 && dz >= -13 && dz <= 7 && ry >= -12 && ry <= -6) return true;
        if (dx >= -13 && dx <= -6 && dz >= -4 && dz <= 0 && ry >= -12 && ry <= -6) return true;
        if (dx >= -17 && dx <= -11 && dz >= -6 && dz <= 0 && ry >= -12 && ry <= -6) return true;
        if (dx >= 2 && dx <= 12 && dz >= 4 && dz <= 16 && ry >= -12 && ry <= -6) return true;
        // Player-driven natural side passages can be added gradually after a den has been explored.
        if (dx >= 4 && dx <= 10 && dz >= -17 && dz <= -7 && ry >= -12 && ry <= -6) return true;
        if (dx >= -8 && dx <= -2 && dz >= 7 && dz <= 17 && ry >= -12 && ry <= -6) return true;
        if (dx >= 5 && dx <= 17 && dz >= -8 && dz <= -2 && ry >= -12 && ry <= -6) return true;
        if (dx >= -8 && dx <= -2 && dz >= -17 && dz <= -7 && ry >= -12 && ry <= -6) return true;
        if (dx >= 11 && dx <= 17 && dz >= 4 && dz <= 10 && ry >= -12 && ry <= -6) return true;
        return false;
    }

    public static void onBlockBroken(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (player == null || player.getServer() == null) return;
        ManbearData data = ManbearData.get(player.getServer());
        if (contains(level, pos, data)) destroy(player.getServer(), level, player.getUUID(), "a block was broken inside the den");
    }

    public static void onBlockPlaced(ServerLevel level, BlockPos pos, UUID actor) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        ManbearData data = ManbearData.get(server);
        if (contains(level, pos, data)) destroy(server, level, actor, "a block was placed inside the den");
    }

    private static void inspectDen(MinecraftServer server, long now, ManbearData data) {
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(data.denDimension())));
        BlockPos center = data.denCenter();
        if (level == null || center == null) return;
        int surface = center.getY() + 9;
        BlockPos from = new BlockPos(center.getX() - 20, surface - 14, center.getZ() - 20);
        BlockPos to = new BlockPos(center.getX() + 20, surface + 6, center.getZ() + 20);
        if (!level.hasChunksAt(from, to)) return; // inspect when the whole den region is loaded
        if (ManbearConfig.DEN_TROPHIES_ENABLED.get()) {
            if (data.hasPendingStolenTrophies()) syncTrophyChest(level, center, data, surface);
            if (!data.denBorrowedRoomBuilt()) buildBorrowedRoomFromTrophies(level, center, data, surface);
        }

        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int y = from.getY(); y <= to.getY(); y++) {
                for (int z = from.getZ(); z <= to.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!contains(level, p, data)) continue;
                    var state = level.getBlockState(p);
                    if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || level.getFluidState(p).is(FluidTags.LAVA)) {
                        destroy(server, level, null, "fire reached the den");
                        return;
                    }
                }
            }
        }

        AABB area = new AABB(from.getX(), from.getY(), from.getZ(), to.getX() + 1.0, to.getY() + 1.0, to.getZ() + 1.0);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, area,
                mob -> !(mob instanceof ManbearEntity) && mob.isAlive() && contains(level, mob.blockPosition(), data));
        if (mobs.size() >= ManbearConfig.DEN_MOB_DESTROY_THRESHOLD.get()) {
            destroy(server, level, null, "the den was filled with mobs");
            return;
        }

        if (ManbearConfig.ADAPTIVE_DEN_REMODELING_ENABLED.get()) {
            List<ServerPlayer> visitors = level.getEntitiesOfClass(ServerPlayer.class, area,
                    player -> player.isAlive() && contains(level, player.blockPosition(), data));
            if (!visitors.isEmpty()) {
                data.noteDenVisit(now);
            } else if (data.canRemodelDen(now) && remodelNaturalSidePassage(level, center, surface, data.denRemodelCount())) {
                data.markDenRemodeled(now);
            }
        }
    }

    /** Adds one of several distinct natural-looking side passages after a player explores the den and then leaves. */
    private static boolean remodelNaturalSidePassage(ServerLevel level, BlockPos center, int surface, int remodelCount) {
        if (center == null) return false;
        boolean changed = false;
        switch (remodelCount % 5) {
            case 0 -> {
                // Curved north-east side tunnel.
                for (int z = -7; z >= -16; z--) {
                    int bend = (int) Math.round(Math.sin((z + 7) * 0.55D) * 1.2D);
                    int cx = 6 + bend;
                    for (int x = cx - 1; x <= cx + 1; x++) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                for (int x = 4; x <= 9; x++) for (int z = -17; z <= -14; z++) {
                    if ((x + z) % 4 != 0) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                placeNaturalCaveDetail(level, new BlockPos(center.getX() + 7, surface - 11, center.getZ() - 15), remodelCount);
            }
            case 1 -> {
                // South-west tunnel extending from a separate wall.
                for (int z = 7; z <= 16; z++) {
                    int bend = (int) Math.round(Math.sin((z - 7) * 0.48D) * 1.0D);
                    int cx = -5 + bend;
                    for (int x = cx - 1; x <= cx + 1; x++) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                for (int x = -8; x <= -3; x++) for (int z = 14; z <= 17; z++) {
                    if ((x + z) % 3 != 0) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                placeNaturalCaveDetail(level, new BlockPos(center.getX() - 5, surface - 11, center.getZ() + 15), remodelCount);
            }
            case 2 -> {
                // East-north passage, safely away from the surface entrance ramp.
                for (int x = 7; x <= 16; x++) {
                    int bend = (int) Math.round(Math.sin((x - 7) * 0.52D));
                    int cz = -5 + bend;
                    for (int z = cz - 1; z <= cz + 1; z++) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                for (int x = 14; x <= 17; x++) for (int z = -8; z <= -3; z++) {
                    if ((x + z) % 3 != 0) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                placeNaturalCaveDetail(level, new BlockPos(center.getX() + 15, surface - 11, center.getZ() - 5), remodelCount);
            }
            case 3 -> {
                // A parallel north-west passage; the irregular bend prevents it feeling like a straight dug tunnel.
                for (int z = -7; z >= -16; z--) {
                    int bend = (int) Math.round(Math.sin((z + 7) * 0.47D) * 1.1D);
                    int cx = -5 + bend;
                    for (int x = cx - 1; x <= cx + 1; x++) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                for (int x = -8; x <= -3; x++) for (int z = -17; z <= -14; z++) {
                    if ((x - z) % 4 != 0) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                placeNaturalCaveDetail(level, new BlockPos(center.getX() - 5, surface - 11, center.getZ() - 15), remodelCount);
            }
            default -> {
                // Extend the existing feeding alcove into a cramped side chamber, not into the entrance corridor.
                for (int x = 11; x <= 16; x++) {
                    int bend = (int) Math.round(Math.sin((x - 11) * 0.57D));
                    int cz = 7 + bend;
                    for (int z = cz - 1; z <= cz + 1; z++) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                for (int x = 14; x <= 17; x++) for (int z = 5; z <= 10; z++) {
                    if ((x + z) % 4 != 0) for (int y = surface - 11; y <= surface - 8; y++)
                        changed |= carveForUpgrade(level, new BlockPos(center.getX() + x, y, center.getZ() + z));
                }
                placeNaturalCaveDetail(level, new BlockPos(center.getX() + 15, surface - 11, center.getZ() + 7), remodelCount);
            }
        }
        return changed;
    }

    private static void placeNaturalCaveDetail(ServerLevel level, BlockPos p, int variant) {
        if (!level.hasChunkAt(p) || !level.getBlockState(p).isAir()) return;
        BlockPos floor = p.below();
        BlockState floorState = level.getBlockState(floor);
        if (!floorState.isFaceSturdy(level, floor, Direction.UP)
                || !(floorState.is(BlockTags.BASE_STONE_OVERWORLD) || floorState.is(BlockTags.DIRT)
                || floorState.is(Blocks.GRAVEL) || floorState.is(Blocks.CLAY) || floorState.is(Blocks.COARSE_DIRT))) return;
        if (variant % 3 == 0) {
            level.setBlock(floor, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
        } else if (variant % 3 == 1) {
            level.setBlock(floor, Blocks.GRAVEL.defaultBlockState(), 3);
        } else {
            level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
    }

    /**
     * Creates a tucked-away trophy chest in the feeding alcove and transfers pending stolen stacks into it.
     * The world ledger is only cleared for the amount actually inserted, making this safe across chunk reloads.
     */
    private static void syncTrophyChest(ServerLevel level, BlockPos center, ManbearData data, int surface) {
        List<ManbearData.StolenTrophy> pending = data.pendingStolenTrophies();
        if (pending.isEmpty()) return;
        Container chest = null;
        BlockPos chestPos = null;
        // The first candidates are in the den's tucked-away feeding alcove, not the entrance/main sightline.
        int[][] offsets = {{-14,-2,-3},{-14,-2,-4},{-14,-2,-2},{-13,-2,-3},{-15,-2,-3},
                {-13,-2,-4},{-13,-2,-2},{-12,-2,-3},{-15,-2,-4},{-15,-2,-2},
                {-12,-2,-4},{-12,-2,-2}};
        for (int[] off : offsets) {
            BlockPos p = center.offset(off[0], off[1], off[2]);
            if (!level.hasChunkAt(p)) continue;
            BlockState state = level.getBlockState(p);
            BlockEntity be = level.getBlockEntity(p);
            if (state.is(Blocks.CHEST) && be instanceof Container container) {
                chest = container;
                chestPos = p;
                break;
            }
        }
        if (chest == null) {
            for (int[] off : offsets) {
                BlockPos p = center.offset(off[0], off[1], off[2]);
                if (!level.hasChunkAt(p)) continue;
                BlockState state = level.getBlockState(p);
                BlockPos floor = p.below();
                if (!state.isAir() || !level.getFluidState(p).isEmpty()
                        || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                        || !level.getBlockState(p.above()).isAir()) continue;
                level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof Container container) {
                    chest = container;
                    chestPos = p;
                    break;
                }
            }
        }
        if (chest == null || chestPos == null) return; // try again when the area is next inspected

        for (ManbearData.StolenTrophy trophy : pending) {
            ItemStack remainder = insertStack(chest, trophy.stack());
            data.updateStolenTrophy(trophy.id(), remainder);
        }
        chest.setChanged();
        level.sendBlockUpdated(chestPos, level.getBlockState(chestPos), level.getBlockState(chestPos), 3);
    }

    /**
     * Once stolen items reach the den, create a small, crooked imitation of a room in the player's
     * home. The wall displays are copies; the actual stolen items remain recoverable in the trophy chest.
     * This makes the den's display a persistent clue without risking item loss if a frame is destroyed.
     */
    private static void buildBorrowedRoomFromTrophies(ServerLevel level, BlockPos center, ManbearData data, int surface) {
        int[][] offsets = {{-14,-2,-3},{-14,-2,-4},{-14,-2,-2},{-13,-2,-3},{-15,-2,-3},
                {-13,-2,-4},{-13,-2,-2},{-12,-2,-3},{-15,-2,-4},{-15,-2,-2},
                {-12,-2,-4},{-12,-2,-2}};
        Container chest = null;
        for (int[] off : offsets) {
            BlockPos p = center.offset(off[0], off[1], off[2]);
            if (!level.hasChunkAt(p) || !level.getBlockState(p).is(Blocks.CHEST)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof Container container) { chest = container; break; }
        }
        if (chest == null) return;
        List<ItemStack> samples = new java.util.ArrayList<>();
        for (int i = 0; i < chest.getContainerSize() && samples.size() < 2; i++) {
            ItemStack stack = chest.getItem(i);
            if (stack != null && !stack.isEmpty()) samples.add(stack.copyWithCount(1));
        }
        if (samples.isEmpty()) return; // don't build a symbolic room until there is something to display

        int floorY = surface - 12;
        int cx = center.getX() + 10;
        int cz = center.getZ() - 8;

        // A narrow, slightly crooked branch links the borrowed room to an existing side passage.
        for (int xOff = 2; xOff <= 7; xOff++) {
            int bend = (xOff >= 5) ? 1 : 0;
            for (int zOff = -1; zOff <= 1; zOff++) {
                for (int up = 1; up <= 4; up++) {
                    carveForUpgrade(level, center.offset(xOff, floorY - center.getY() + up, -8 + zOff + bend));
                }
                BlockPos floor = center.offset(xOff, floorY - center.getY(), -8 + bend);
                if (level.getBlockState(floor).isAir()) level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), 2);
            }
        }

        // Rounded chamber with a low ceiling and irregular mixed-material walls; preserve non-natural blocks.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double shape = dx * dx / 10.0D + dz * dz / 10.0D;
                if (shape > 1.15D) continue;
                for (int up = 1; up <= 4; up++) carveForUpgrade(level, new BlockPos(cx + dx, floorY + up, cz + dz));
                BlockPos floor = new BlockPos(cx + dx, floorY, cz + dz);
                BlockState floorState = level.getBlockState(floor);
                if (floorState.is(BlockTags.BASE_STONE_OVERWORLD) || floorState.is(BlockTags.DIRT)) {
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) level.setBlock(floor, Blocks.DARK_OAK_PLANKS.defaultBlockState(), 2);
                } else if (floorState.isAir()) {
                    level.setBlock(floor, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
                }
            }
        }

        // Back wall: frame backing blocks are only placed into air/natural terrain, never through player builds.
        for (int dx = -3; dx <= 3; dx++) {
            for (int up = 1; up <= 3; up++) {
                if (Math.abs(dx) == 3 && up == 3) continue; // rough, incomplete ceiling line
                BlockPos wall = new BlockPos(cx + dx, floorY + up, cz - 3);
                BlockState state = level.getBlockState(wall);
                if (isNaturalOrAir(state)) {
                    BlockState material = ((dx + up) & 3) == 0
                            ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState();
                    level.setBlock(wall, material, 2);
                }
            }
        }
        // Side walls are deliberately uneven and the west side remains open to the connecting passage.
        for (int side : new int[]{-3, 3}) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int up = 1; up <= 3; up++) {
                    if (side == -3 && Math.abs(dz) <= 1) continue;
                    if ((dz + up) % 4 == 0) continue;
                    BlockPos wall = new BlockPos(cx + side, floorY + up, cz + dz);
                    if (isNaturalOrAir(level.getBlockState(wall))) {
                        level.setBlock(wall, (up == 1 && (dz & 1) == 0 ? Blocks.MOSSY_COBBLESTONE : Blocks.DARK_OAK_PLANKS).defaultBlockState(), 2);
                    }
                }
            }
        }
        // A sparse front edge frames the entry without making the space look like a complete player-built room.
        for (int dx : new int[]{-2, 0, 2}) {
            for (int up = 1; up <= 2; up++) {
                BlockPos wall = new BlockPos(cx + dx, floorY + up, cz + 3);
                if (isNaturalOrAir(level.getBlockState(wall))) {
                    level.setBlock(wall, up == 1 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState(), 2);
                }
            }
        }

        boolean displayed = false;
        for (int i = 0; i < samples.size(); i++) {
            int dx = i == 0 ? -1 : 1;
            BlockPos framePos = new BlockPos(cx + dx, floorY + 2, cz - 2);
            if (!level.hasChunkAt(framePos)) continue;
            BlockPos supportPos = framePos.relative(Direction.NORTH);
            if (!level.getBlockState(supportPos).isFaceSturdy(level, supportPos, Direction.SOUTH)) continue;
            List<ItemFrame> existing = level.getEntitiesOfClass(ItemFrame.class,
                    new AABB(framePos.getX(), framePos.getY(), framePos.getZ(), framePos.getX() + 1.0D,
                            framePos.getY() + 1.0D, framePos.getZ() + 1.0D));
            if (!existing.isEmpty()) { displayed = true; continue; }
            ItemFrame frame = new ItemFrame(level, framePos, Direction.SOUTH);
            frame.setItem(samples.get(i), false);
            frame.setSilent(true);
            if (level.addFreshEntity(frame)) displayed = true;
        }
        BlockPos skull = new BlockPos(cx + 2, floorY + 1, cz + 1);
        if (level.getBlockState(skull).isAir() && level.getBlockState(skull.below()).isFaceSturdy(level, skull.below(), Direction.UP)) {
            level.setBlock(skull, Blocks.SKELETON_SKULL.defaultBlockState(), 2);
        }
        BlockPos web = new BlockPos(cx - 2, floorY + 3, cz + 2);
        if (level.getBlockState(web).isAir()) level.setBlock(web, Blocks.COBWEB.defaultBlockState(), 2);

        if (displayed) {
            data.setDenBorrowedRoomBuilt(true);
            data.setDirty();
        }
    }

    private static boolean isNaturalOrAir(BlockState state) {
        return state.isAir() || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                || state.is(BlockTags.LEAVES) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MUD) || state.is(Blocks.COARSE_DIRT);
    }

    private static ItemStack insertStack(Container container, ItemStack incoming) {
        if (incoming == null || incoming.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = incoming.copy();
        int max = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, remaining)) continue;
            int room = Math.min(existing.getMaxStackSize(), max) - existing.getCount();
            if (room <= 0) continue;
            int move = Math.min(room, remaining.getCount());
            existing.grow(move);
            remaining.shrink(move);
            container.setItem(i, existing);
        }
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, remaining)) continue;
            int move = Math.min(max, remaining.getCount());
            container.setItem(i, remaining.copyWithCount(move));
            remaining.shrink(move);
        }
        container.setChanged();
        return remaining;
    }

    private static void destroy(MinecraftServer server, ServerLevel level, UUID actor, String reason) {
        ManbearData data = ManbearData.get(server);
        if (!data.hasDen()) return;
        long cooldown = ManbearConfig.DEN_DESTROYED_COOLDOWN_MINUTES.get() * 1200L;
        data.destroyDen(server.overworld().getGameTime() + cooldown, actor);
        ManbearEntity active = ServerEvents.findActive(server);
        if (active != null && active.level() instanceof ServerLevel activeLevel) active.ai().beginLeaving(activeLevel);
        // No public announcement: discovery of the consequences is part of the encounter.
    }
}
