package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.registry.ModSounds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sounds with nothing behind them: a roar far off in the trees, a twig snapping close by, a tree coming down,
 * a scream, an animal dying. Nothing spawns - it just makes the woods feel wrong.
 */
public final class AmbientHorror {
    private record Pending(long at, ServerLevel level, SoundEvent ev, SoundSource src, double x, double y, double z, float vol, float pitch) { }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Map<UUID, Long> NEXT = new HashMap<>();
    /** is he spawned right now? (checked twice a second) */
    private static boolean suppressed;

    private AmbientHorror() { }

    /** call every server tick */
    public static void tick(MinecraftServer server, long now) {
        if (now % 10 == 0) suppressed = CompanionBirds.danger(server);
        if (suppressed) {
            // nothing with "nothing behind it" while the real thing is out there: drop what was queued and push every
            // player's next sound back so they do not all fire the moment he is gone
            PENDING.clear();
            if (now % 20 == 0) {
                long earliest = now + ManbearConfig.AMBIENT_MIN_SECONDS.get() * 20L;
                for (Map.Entry<UUID, Long> e : NEXT.entrySet()) if (e.getValue() < earliest) e.setValue(earliest);
            }
            return;
        }
        if (!PENDING.isEmpty()) {
            for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (now >= p.at()) {
                    p.level().playSound(null, p.x(), p.y(), p.z(), p.ev(), p.src(), p.vol(), p.pitch());
                    it.remove();
                }
            }
        }
        if (now % 20 != 0 || !ManbearConfig.AMBIENT_SOUNDS.get()) return;
        int min = ManbearConfig.AMBIENT_MIN_SECONDS.get() * 20;
        int max = Math.max(min + 20, ManbearConfig.AMBIENT_MAX_SECONDS.get() * 20);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            ServerLevel level = p.serverLevel();
            RandomSource rnd = level.random;
            Long next = NEXT.get(p.getUUID());
            if (next == null) {
                NEXT.put(p.getUUID(), now + min + rnd.nextInt(max - min));
                continue;
            }
            if (now < next) continue;
            double scale = level.isNight() ? 0.7 : 1.0;
            NEXT.put(p.getUUID(), now + (long) ((min + rnd.nextInt(max - min)) * scale));
            play(level, p, rnd, now);
        }
    }

    private static void later(long at, ServerLevel level, SoundEvent ev, SoundSource src, double x, double y, double z, float vol, float pitch) {
        PENDING.add(new Pending(at, level, ev, src, x, y, z, vol, pitch));
    }

    private static void play(ServerLevel level, ServerPlayer p, RandomSource rnd, long now) {
        boolean outside = level.canSeeSky(p.blockPosition());
        // weights: bear, twig, tree, scream, animal
        int[] w = outside ? new int[]{3, 3, 2, 2, 2} : new int[]{4, 2, 0, 0, 0};
        int total = 0;
        for (int v : w) total += v;
        int pick = rnd.nextInt(total);
        int kind = 0;
        while (kind < w.length - 1 && (pick -= w[kind]) >= 0) kind++;

        double ang = rnd.nextDouble() * Math.PI * 2;
        double dx = Math.cos(ang);
        double dz = Math.sin(ang);
        double y = p.getY();
        switch (kind) {
            case 0 -> { // a bear, very far off, that you can hear but never find
                double d = 55 + rnd.nextInt(36);
                double x = p.getX() + dx * d;
                double z = p.getZ() + dz * d;
                boolean roar = rnd.nextBoolean();
                SoundEvent ev = roar ? ModSounds.ROAR.get() : ModSounds.GROWL.get();
                float pitch = 0.6F + rnd.nextFloat() * 0.2F;
                level.playSound(null, x, y, z, ev, SoundSource.HOSTILE, 8.0F, pitch);
                if (rnd.nextFloat() < 0.4F) {   // answers itself a little closer
                    later(now + 40 + rnd.nextInt(60), level, ModSounds.GROWL.get(), SoundSource.HOSTILE,
                            x - dx * 12, y, z - dz * 12, 7.0F, pitch - 0.05F);
                }
            }
            case 1 -> { // twigs snapping, fairly close
                double d = 10 + rnd.nextInt(19);
                double x = p.getX() + dx * d;
                double z = p.getZ() + dz * d;
                int snaps = 1 + rnd.nextInt(3);
                long t = now;
                for (int i = 0; i < snaps; i++) {
                    float pitch = 0.8F + rnd.nextFloat() * 0.4F;
                    if (i == 0) level.playSound(null, x, y, z, ModSounds.TWIG.get(), SoundSource.AMBIENT, 1.8F, pitch);
                    else later(t, level, ModSounds.TWIG.get(), SoundSource.AMBIENT, x + dx * i, y, z + dz * i, 1.8F, pitch);
                    t += 3 + rnd.nextInt(12);
                }
            }
            case 2 -> { // a tree falls: cracks, rushing leaves, a heavy crash
                double d = 30 + rnd.nextInt(26);
                double x = p.getX() + dx * d;
                double z = p.getZ() + dz * d;
                for (int i = 0; i < 3; i++) {
                    later(now + i * 6L, level, ModSounds.TREE_CRACK.get(), SoundSource.AMBIENT, x, y, z, 3.5F, 0.5F + rnd.nextFloat() * 0.2F);
                }
                for (int i = 0; i < 4; i++) {
                    later(now + 16 + i * 7L, level, ModSounds.TREE_RUSTLE.get(), SoundSource.AMBIENT, x, y, z, 3.0F, 0.5F + rnd.nextFloat() * 0.2F);
                }
                later(now + 46, level, ModSounds.THUMP.get(), SoundSource.AMBIENT, x, y, z, 6.0F, 0.45F);
                later(now + 47, level, ModSounds.TREE_RUSTLE.get(), SoundSource.AMBIENT, x, y, z, 4.0F, 0.45F);
            }
            case 3 -> { // someone screaming, far away
                double d = 45 + rnd.nextInt(36);
                double x = p.getX() + dx * d;
                double z = p.getZ() + dz * d;
                level.playSound(null, x, y, z, ModSounds.SCREAM.get(), SoundSource.AMBIENT, 6.0F, 0.9F + rnd.nextFloat() * 0.2F);
                if (rnd.nextFloat() < 0.5F) {
                    later(now + 18 + rnd.nextInt(14), level, ModSounds.SCREAM.get(), SoundSource.AMBIENT, x, y, z, 5.0F, 1.0F + rnd.nextFloat() * 0.2F);
                }
            }
            default -> { // an animal cries out and dies
                double d = 25 + rnd.nextInt(31);
                double x = p.getX() + dx * d;
                double z = p.getZ() + dz * d;
                level.playSound(null, x, y, z, ModSounds.ANIMAL_DEATH.get(), SoundSource.NEUTRAL, 3.5F, 0.9F + rnd.nextFloat() * 0.2F);
                if (rnd.nextFloat() < 0.35F) {
                    later(now + 10 + rnd.nextInt(16), level, ModSounds.ANIMAL_DEATH.get(), SoundSource.NEUTRAL, x, y, z, 3.0F, 0.8F + rnd.nextFloat() * 0.2F);
                }
            }
        }
    }
}
