package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.entity.CompanionBird;
import com.manbear.entity.ManbearEntity;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps every player supplied with their one bird.
 *  - first time: it appears next to you
 *  - while the Manbear is anywhere in the world the birds fly away (see CompanionBird) and none are replaced
 *  - after he is gone, each missing bird flies in from far away a few seconds later
 */
public final class CompanionBirds {
    /** when each player's bird may come back (game time) */
    private static final Map<UUID, Long> RETURN_AT = new HashMap<>();

    private CompanionBirds() { }

    /** true while he exists anywhere: spawned, even if he is not in view or in another dimension */
    public static boolean danger(MinecraftServer server) {
        ManbearEntity c = ManbearEntity.current();
        if (c != null && c.isAlive() && !c.isRemoved()) return true;
        return ServerEvents.findActive(server) != null;
    }

    /** every server tick; does its work once a second */
    public static void tick(MinecraftServer server, long now) {
        if (now % 20 != 5 || !ManbearConfig.BIRD_ENABLED.get()) return;
        boolean danger = danger(server);
        ManbearData data = ManbearData.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            UUID id = p.getUUID();
            if (danger) { RETURN_AT.remove(id); continue; }          // no new birds while he is out there
            if (find(p, data) != null) { RETURN_AT.remove(id); continue; }

            if (!data.hadBird(id)) {                                  // the very first one: simply there
                spawn(p, data, false);
                continue;
            }
            long at = RETURN_AT.computeIfAbsent(id, k -> now + ManbearConfig.BIRD_RETURN_DELAY_SECONDS.get() * 20L
                    + p.level().random.nextInt(100));
            if (now >= at) {
                if (spawn(p, data, true)) RETURN_AT.remove(id);       // it flies in from far away
            }
        }
    }

    private static CompanionBird find(ServerPlayer p, ManbearData data) {
        UUID bird = data.birdOf(p.getUUID());
        if (bird == null) return null;
        Entity e = p.serverLevel().getEntity(bird);
        return e instanceof CompanionBird b && b.isAlive() ? b : null;
    }

    /** @param far true = it arrives from about 50 blocks away and flies to you */
    public static boolean spawn(ServerPlayer p, ManbearData data, boolean far) {
        ServerLevel level = p.serverLevel();
        RandomSource rnd = level.random;
        for (int i = 0; i < 12; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double r = far ? 44 + rnd.nextDouble() * 8 : 3 + rnd.nextDouble() * 3;
            int x = Mth.floor(p.getX() + Math.cos(ang) * r);
            int z = Mth.floor(p.getZ() + Math.sin(ang) * r);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
            double y;
            if (far) {
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                y = Math.max(ground + 8, p.getY() + 6);
            } else {
                y = p.getY() + 2.5;
            }
            BlockPos at = BlockPos.containing(x + 0.5, y, z + 0.5);
            if (!level.getBlockState(at).isAir()) continue;
            CompanionBird bird = ModEntities.BIRD.get().create(level);
            if (bird == null) return false;
            bird.moveTo(x + 0.5, y, z + 0.5, rnd.nextFloat() * 360F, 0F);
            bird.setCompanionOwner(p.getUUID());
            bird.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);   // random plumage
            bird.setReturning(far);
            level.addFreshEntity(bird);
            data.setBird(p.getUUID(), bird.getUUID());
            return true;
        }
        return false;
    }
}
