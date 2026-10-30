package com.manbear.raid;

import com.manbear.ai.CoverFinder;
import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.block.FootprintBlock;
import com.manbear.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Something rummaged through your house": doors left open, a few chest/barrel items taken (when trophy mode is on),
 * windows and some wall blocks disturbed, and giant footprints inside. In legacy mode the chests can still be broken.
 * BEDS ARE NEVER TOUCHED (nor anything right next to one).
 */
public final class RaidHandler {
    private static final int RADIUS = 18;

    private RaidHandler() { }

    /** Compatibility overload for old callers. */
    public static String rummage(ServerLevel level, BlockPos center, RandomSource rnd) {
        return rummage(level, center, rnd, null);
    }

    /** @return a short summary, or null if there was no house to rummage through */
    public static String rummage(ServerLevel level, BlockPos center, RandomSource rnd, UUID owner) {
        List<BlockPos> doors = new ArrayList<>();
        List<BlockPos> containers = new ArrayList<>();
        List<BlockPos> glass = new ArrayList<>();
        List<BlockPos> beds = new ArrayList<>();
        List<BlockPos> walls = new ArrayList<>();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -4; dy <= 8; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir()) continue;
                    if (st.is(BlockTags.BEDS)) {
                        beds.add(pos);
                    } else if (st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        doors.add(pos);
                    } else if (st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST) || st.is(Blocks.BARREL)) {
                        containers.add(pos);
                    } else if (st.is(Tags.Blocks.GLASS) || st.is(Tags.Blocks.GLASS_PANES)) {
                        glass.add(pos);
                    } else if (isWallBlock(level, pos, st)) {
                        walls.add(pos);
                    }
                }
            }
        }
        if (doors.isEmpty() && containers.isEmpty()) return null;   // nothing that looks like a house

        // never go anywhere near a bed
        Set<BlockPos> safe = new HashSet<>();
        for (BlockPos b : beds) {
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) safe.add(b.offset(x, y, z));
        }

        int nDoors = 0;
        int nChests = 0;
        int nItemsTaken = 0;
        int nGlass = 0;
        int nBlocks = 0;
        int nPrints = 0;

        // --- doors: swing open (the one closest to the middle first)
        doors.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
        BlockPos mainDoor = doors.isEmpty() ? null : doors.get(0);
        for (BlockPos d : doors) {
            if (nDoors >= 2) break;
            BlockState st = level.getBlockState(d);
            if (st.getBlock() instanceof DoorBlock door && !st.getValue(DoorBlock.OPEN)) {
                door.setOpen(null, level, st, d, true);
                nDoors++;
            }
        }

        // --- containers: normally he takes a handful of items and leaves the chest intact.
        // The items are placed in his den trophy chest once that part of the world is loaded.
        Collections.shuffle(containers, new java.util.Random(rnd.nextLong()));
        int wantContainers = containers.isEmpty() ? 0 : Math.min(containers.size(), 1 + rnd.nextInt(2));
        int maxStacksTaken = 1 + rnd.nextInt(3);
        List<BlockPos> rummaged = new ArrayList<>();
        boolean trophyMode = ManbearConfig.DEN_TROPHIES_ENABLED.get() && owner != null && level.getServer() != null;
        for (BlockPos c : containers) {
            if (nChests >= wantContainers || (trophyMode && nItemsTaken >= maxStacksTaken)) break;
            if (safe.contains(c)) continue;
            if (trophyMode) {
                int taken = stealFromContainer(level, c, owner, rnd, maxStacksTaken - nItemsTaken);
                if (taken > 0) {
                    nItemsTaken += taken;
                    rummaged.add(c);
                    nChests++;
                    level.playSound(null, c, net.minecraft.sounds.SoundEvents.CHEST_OPEN,
                            net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 0.75F + rnd.nextFloat() * 0.25F);
                    level.playSound(null, c, net.minecraft.sounds.SoundEvents.CHEST_CLOSE,
                            net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 0.8F + rnd.nextFloat() * 0.25F);
                }
            } else {
                // Preserve the old destructive rummage behavior when trophy theft is disabled.
                spill(level, c, rnd);
                level.destroyBlock(c, false);
                rummaged.add(c);
                nChests++;
            }
        }

        // --- windows
        for (BlockPos g : glass) {
            if (nGlass >= 14) break;
            if (safe.contains(g) || rnd.nextFloat() > 0.55F) continue;
            level.destroyBlock(g, false);
            nGlass++;
        }

        // --- a few wall blocks knocked out
        Collections.shuffle(walls, new java.util.Random(rnd.nextLong()));
        int wantBlocks = 3 + rnd.nextInt(5);
        for (BlockPos w : walls) {
            if (nBlocks >= wantBlocks) break;
            if (safe.contains(w)) continue;
            level.destroyBlock(w, false);
            nBlocks++;
        }

        // --- giant footprints inside: from the door in, then around the broken chests
        BlockPos start = mainDoor == null ? null : insideNeighbor(level, mainDoor);
        if (start == null && !rummaged.isEmpty()) start = insideNeighbor(level, rummaged.get(0));
        if (start != null) nPrints += trail(level, start, 12, rnd, safe);
        for (BlockPos c : rummaged) {
            BlockPos in = insideNeighbor(level, c);
            if (in != null) nPrints += trail(level, in, 3, rnd, safe);
        }
        return "doors=" + nDoors + " chests=" + nChests + " itemsTaken=" + nItemsTaken + " windows=" + nGlass + " blocks=" + nBlocks + " footprints=" + nPrints;
    }

    private static boolean indoor(ServerLevel level, BlockPos p) {
        return CoverFinder.standable(level, p, 2) && !level.canSeeSky(p);
    }

    /** a solid, cheap, non-fancy block with indoor air right beside it (so the damage is visible from inside) */
    private static boolean isWallBlock(ServerLevel level, BlockPos pos, BlockState st) {
        if (st.hasBlockEntity() || st.is(BlockTags.BEDS) || st.is(BlockTags.DOORS) || !st.isSolidRender(level, pos)) return false;
        float h = st.getDestroySpeed(level, pos);
        if (h < 0 || h > 6.0F) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (indoor(level, pos.relative(d))) return true;
        }
        return false;
    }

    private static BlockPos insideNeighbor(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (indoor(level, n)) return n;
        }
        return null;
    }


    /** Removes a few units from nonempty slots without destroying the chest or spilling the rest. */
    private static int stealFromContainer(ServerLevel level, BlockPos pos, UUID owner, RandomSource random, int remainingStacks) {
        if (remainingStacks <= 0 || owner == null) return 0;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return 0;
        ManbearData data = ManbearData.get(level.getServer());
        int takenSlots = 0;
        int attempts = Math.max(1, container.getContainerSize());
        for (int n = 0; n < attempts && takenSlots < remainingStacks; n++) {
            int slot = random.nextInt(container.getContainerSize());
            ItemStack present = container.getItem(slot);
            if (present == null || present.isEmpty()) continue;
            int amount = Math.min(present.getCount(), 1 + random.nextInt(Math.min(4, present.getCount())));
            ItemStack stolen = present.split(amount);
            container.setItem(slot, present);
            container.setChanged();
            if (!stolen.isEmpty()) {
                data.recordStolenTrophy(owner, stolen);
                takenSlots++;
            }
        }
        return takenSlots;
    }

    /** dumps the contents on the floor; items never despawn, so they are still there when you get home */
    private static void spill(ServerLevel level, BlockPos pos, RandomSource rnd) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack st = container.removeItemNoUpdate(i);
            if (st.isEmpty()) continue;
            ItemEntity ie = new ItemEntity(level, pos.getX() + 0.5 + (rnd.nextDouble() - 0.5) * 0.6, pos.getY() + 0.6,
                    pos.getZ() + 0.5 + (rnd.nextDouble() - 0.5) * 0.6, st);
            ie.setDeltaMovement((rnd.nextDouble() - 0.5) * 0.35, 0.2 + rnd.nextDouble() * 0.15, (rnd.nextDouble() - 0.5) * 0.35);
            ie.setUnlimitedLifetime();
            level.addFreshEntity(ie);
        }
    }

    /** a wandering line of giant prints over indoor floor */
    private static int trail(ServerLevel level, BlockPos start, int steps, RandomSource rnd, Set<BlockPos> safe) {
        int placed = 0;
        BlockPos cur = start;
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(rnd);
        for (int i = 0; i < steps; i++) {
            if (!safe.contains(cur) && level.getBlockState(cur).isAir()) {
                BlockState print = ModBlocks.FOOTPRINT.get().defaultBlockState().setValue(FootprintBlock.FACING, dir);
                if (print.canSurvive(level, cur)) {
                    level.setBlock(cur, print, 3);
                    placed++;
                }
            }
            Direction next = rnd.nextFloat() < 0.7F ? dir : Direction.Plane.HORIZONTAL.getRandomDirection(rnd);
            BlockPos n = cur.relative(next);
            if (!indoor(level, n)) {
                boolean moved = false;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (indoor(level, cur.relative(d))) { next = d; n = cur.relative(d); moved = true; break; }
                }
                if (!moved) break;
            }
            dir = next;
            cur = n;
        }
        return placed;
    }
}
