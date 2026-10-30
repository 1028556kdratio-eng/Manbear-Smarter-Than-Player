package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.ai.CoverFinder;
import com.manbear.data.ManbearData;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.Stance;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Night event: a bolt of lightning, the area around you lit up for about a second, and in that light
 * HE is standing 10-15 blocks in front of you. When the light goes out, so does he.
 */
public final class LightningEvent {
    private record Light(ServerLevel level, BlockPos pos, long until) { }

    private static final List<Light> LIGHTS = new ArrayList<>();
    private static final int FLASH_TICKS = 22;

    private LightningEvent() { }

    /** call every server tick */
    public static void tick(MinecraftServer server, long now) {
        for (Iterator<Light> it = LIGHTS.iterator(); it.hasNext(); ) {
            Light l = it.next();
            if (now >= l.until()) {
                if (l.level().getBlockState(l.pos()).is(Blocks.LIGHT)) l.level().setBlock(l.pos(), Blocks.AIR.defaultBlockState(), 3);
                it.remove();
            }
        }
        if (now % 1200 != 0 || now == 0 || !ManbearConfig.LIGHTNING_ENABLED.get()) return;
        ManbearData data = ManbearData.get(server);
        if (data.isActive(now) || data.onCooldown(now) || server.overworld().getDifficulty() == Difficulty.PEACEFUL) return;
        RandomSource rnd = server.overworld().random;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive() || p.serverLevel() != server.overworld()) continue;
            ServerLevel level = p.serverLevel();
            if (!level.isNight() || !level.canSeeSky(p.blockPosition())) continue;
            if (rnd.nextDouble() < ManbearConfig.LIGHTNING_CHANCE_PER_MINUTE.get() && strike(server, level, p)) return;
        }
    }

    /** server is shutting down: never leave one of the temporary light blocks behind */
    public static void cleanup() {
        for (Light l : LIGHTS) {
            if (l.level().getBlockState(l.pos()).is(Blocks.LIGHT)) l.level().setBlock(l.pos(), Blocks.AIR.defaultBlockState(), 3);
        }
        LIGHTS.clear();
    }

    /** @return false if no good spot was found in front of the player */
    public static boolean strike(MinecraftServer server, ServerLevel level, ServerPlayer p) {
        RandomSource rnd = level.random;
        Vec3 look = p.getLookAngle();
        double base = Math.atan2(look.z, look.x);
        Vec3 spot = null;
        double dist = 0;
        double ang = 0;
        for (int i = 0; i < 40 && spot == null; i++) {
            ang = base + (rnd.nextDouble() - 0.5) * 0.7;
            dist = 10 + rnd.nextDouble() * 5;
            double x = p.getX() + Math.cos(ang) * dist;
            double z = p.getZ() + Math.sin(ang) * dist;
            if (!level.hasChunkAt(new BlockPos((int) Math.floor(x), p.getBlockY(), (int) Math.floor(z)))) continue;
            BlockPos g = CoverFinder.groundNear(level, x, z, p.getBlockY(), 5);
            if (g == null) continue;
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
            if (!CoverFinder.lineClear(level, p.getEyePosition(), v.add(0, 3.0, 0), p)) continue;   // you must be able to see him
            spot = v;
        }
        if (spot == null) return false;

        long now = server.overworld().getGameTime();

        // the bolt itself: a little beyond him, so he is silhouetted against it
        double bx = p.getX() + Math.cos(ang) * (dist + 14);
        double bz = p.getZ() + Math.sin(ang) * (dist + 14);
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(bx, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(bx), (int) Math.floor(bz)), bz);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }

        // a second of daylight around you and around him
        lights(level, p.blockPosition(), 12, 12, rnd, now);
        lights(level, BlockPos.containing(spot), 5, 8, rnd, now);

        ManbearEntity e = ModEntities.MANBEAR.get().create(level);
        if (e == null) return true;
        e.applyKind("lightning");
        e.setLifespanTicks(26);
        float yaw = (float) (Mth.atan2(p.getZ() - spot.z, p.getX() - spot.x) * (180D / Math.PI)) - 90.0F;
        e.moveTo(spot.x, spot.y, spot.z, yaw, 0F);
        level.addFreshEntity(e);
        ManbearData.get(server).claim(e.getUUID(), now);
        return true;
    }

    private static void lights(ServerLevel level, BlockPos center, int radius, int count, RandomSource rnd, long now) {
        for (int i = 0; i < count * 3 && count > 0; i++) {
            BlockPos pos = center.offset(rnd.nextInt(radius * 2 + 1) - radius, 2 + rnd.nextInt(6), rnd.nextInt(radius * 2 + 1) - radius);
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()) continue;
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState(), 3);   // invisible light block, level 15
            LIGHTS.add(new Light(level, pos.immutable(), now + FLASH_TICKS));
            if (--count <= 0) break;
        }
    }
}
