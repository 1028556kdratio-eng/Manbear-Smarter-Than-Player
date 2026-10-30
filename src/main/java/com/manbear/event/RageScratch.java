package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.block.RageScratchBlock;
import com.manbear.block.RageScratchBlockEntity;
import com.manbear.data.ManbearData;
import com.manbear.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every 5-15 minutes (per player) he claws a number into a tree trunk near you: how angry he is with YOU, 0 to 100.
 * It only goes on a real trunk, at a height he could reach, on a face that is not buried in the forest, and prefers the
 * side you are on. The marks weather away (RageScratchBlock).
 */
public final class RageScratch {
    private static final Map<UUID, Long> NEXT = new HashMap<>();

    private RageScratch() { }

    private static long interval(RandomSource rnd) {
        int lo = ManbearConfig.RAGE_SCRATCH_MIN_MINUTES.get();
        int hi = Math.max(lo, ManbearConfig.RAGE_SCRATCH_MAX_MINUTES.get());
        return (long) ((lo + rnd.nextDouble() * (hi - lo)) * 1200L);
    }

    /** every server tick; acts twice a second */
    public static void tick(MinecraftServer server, long now) {
        if (now % 40 != 17 || !ManbearConfig.RAGE_SCRATCH.get()) return;
        boolean danger = CompanionBirds.danger(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !p.isAlive() || p.level().dimension() != Level.OVERWORLD) continue;
            UUID id = p.getUUID();
            Long next = NEXT.get(id);
            if (next == null) { NEXT.put(id, now + interval(p.level().random)); continue; }
            if (now < next) continue;
            // the scratching itself is silent while he is out there (no sound without a Manbear behind it)
            boolean done = scratch(p.serverLevel(), p, !danger);
            NEXT.put(id, now + (done ? interval(p.level().random) : 600L));    // no suitable tree: try again in 30 s
        }
    }

    /** scratches the player's current rage onto a tree near them. @return false if there was no suitable trunk */
    public static boolean scratch(ServerLevel level, ServerPlayer p, boolean sound) {
        int value = Mth.clamp(Math.round(ManbearData.get(level.getServer()).profile(p.getUUID()).rage), 0, 100);
        RandomSource rnd = level.random;
        Vec3 look = p.getLookAngle();
        double lookAng = Math.atan2(look.z, look.x);

        for (int i = 0; i < 160; i++) {
            boolean wide = i >= 100;                       // the first tries stay in front of you, later ones go anywhere
            double ang = wide ? rnd.nextDouble() * Math.PI * 2 : lookAng + (rnd.nextDouble() - 0.5) * Math.toRadians(150);
            double d = 9 + rnd.nextDouble() * 17;
            int x = Mth.floor(p.getX() + Math.cos(ang) * d);
            int z = Mth.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;

            BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
            if (!level.getBlockState(top).is(BlockTags.LOGS)) continue;
            BlockPos foot = top;
            for (int g = 0; g < 48 && level.getBlockState(foot.below()).is(BlockTags.LOGS); g++) foot = foot.below();
            int trunk = top.getY() - foot.getY() + 1;
            if (trunk < 3) continue;                                          // a tree, not a stump

            int reach = Math.min(top.getY(), foot.getY() + 4);                // as high as his claws go
            BlockPos at = new BlockPos(x, foot.getY() + 1 + rnd.nextInt(Math.max(1, reach - foot.getY())), z);
            if (!level.getBlockState(at).is(BlockTags.LOGS)) continue;

            Vec3 toPlayer = new Vec3(p.getX() - at.getX(), 0, p.getZ() - at.getZ());
            Direction best = null;
            double bestDot = -2;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockState face = level.getBlockState(at.relative(dir));
                if (!(face.isAir() || face.canBeReplaced()) || !face.getFluidState().isEmpty()) continue;
                double dot = dir.getStepX() * toPlayer.x + dir.getStepZ() * toPlayer.z;
                if (dot > bestDot) { bestDot = dot; best = dir; }
            }
            if (best == null) continue;

            BlockPos mark = at.relative(best);
            level.setBlock(mark, ModBlocks.RAGE_SCRATCH.get().defaultBlockState().setValue(RageScratchBlock.FACING, best), 3);
            if (level.getBlockEntity(mark) instanceof RageScratchBlockEntity scratch) scratch.setValue(value);
            if (sound) level.playSound(null, mark, SoundEvents.AXE_SCRAPE, SoundSource.BLOCKS, 1.6F, 0.6F);
            return true;
        }
        return false;
    }
}
