package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.ai.CoverFinder;
import com.manbear.ai.FleeManbearGoal;
import com.manbear.ai.PitTraps;
import com.manbear.entity.CompanionBird;
import com.manbear.command.ManbearCommand;
import com.manbear.data.ManbearData;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.Stance;
import com.manbear.raid.RaidHandler;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.living.BabyEntitySpawnEvent;
import net.minecraftforge.common.ToolActions;
import com.manbear.ai.PetKidnap;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import com.manbear.registry.ModBlocks;
import com.manbear.block.BearTrapBlock;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import com.manbear.data.PlayerProfile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.VanillaGameEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ServerEvents {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.overworld().getGameTime();
        AmbientHorror.tick(server, now);
        LightningEvent.tick(server, now);
        CompanionBirds.tick(server, now);
        RageScratch.tick(server, now);
        if (now % 2 == 0) PitTraps.tick(server, now);
        if (now % 10 == 0) sampleProfiles(server, now);
        if (now % 20 == 0) ManbearDen.tick(server, now);
        if (now % 20 == 0) raidTick(server, now);
        if (now > 0 && now % ManbearConfig.SPAWN_INTERVAL_TICKS.get() == 0) spawnRoll(server, now);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        ManbearCommand.register(e.getDispatcher());
    }

    /** Keep a small, local-world memory of public in-game wording for the Manbear's generated taunts. */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent e) {
        if (!ManbearConfig.CHAT_ECHO_ENABLED.get()) return;
        ServerPlayer player = e.getPlayer();
        if (player == null || player.getServer() == null) return;
        ManbearData data = ManbearData.get(player.getServer());
        data.profile(player.getUUID()).rememberSpeech(e.getMessage().getString());
        data.setDirty();
    }

    /** every animal learns to be afraid of him */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && e.getEntity() instanceof Animal a && !(a instanceof CompanionBird)) {
            a.goalSelector.addGoal(0, new FleeManbearGoal(a));
        }
    }

    /** vibrations (like the Warden hears) can wake a sleeping Manbear */
    @SubscribeEvent
    public void onGameEvent(VanillaGameEvent ev) {
        ManbearEntity b = ManbearEntity.current();
        if (b == null || !b.isAsleep() || ev.getLevel() != b.level() || ev.getCause() == b) return;
        double d = ev.getEventPosition().distanceTo(b.position());
        GameEvent ge = ev.getVanillaEvent();
        float amt = 0F;
        if (ge == GameEvent.STEP) amt = d < 16 ? 0.8F * (1F - (float) d / 16F) : 0F;
        else if (ge == GameEvent.HIT_GROUND) amt = d < 24 ? 2.0F * (1F - (float) d / 24F) : 0F;
        else if (ge == GameEvent.BLOCK_DESTROY || ge == GameEvent.BLOCK_PLACE) amt = d < 24 ? 3.0F * (1F - (float) d / 24F) : 0F;
        else if (ge == GameEvent.EXPLODE) amt = d < 64 ? 30F : 0F;
        else if (ge == GameEvent.PROJECTILE_LAND || ge == GameEvent.SPLASH) amt = d < 20 ? 1.5F * (1F - (float) d / 20F) : 0F;
        else if (ge == GameEvent.ENTITY_DAMAGE) amt = d < 24 ? 4.0F * (1F - (float) d / 24F) : 0F;
        // Crouch-walking is intentionally quiet enough to let a careful player escape the den.
        // Breaking/placing blocks and other loud events remain noisy even while crouching.
        if (ge == GameEvent.STEP && ev.getCause() instanceof Player stepping && stepping.isShiftKeyDown()) {
            amt *= 0.1F;
        }
        if (amt > 0F) b.hearNoise(amt);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        com.manbear.voice.VoiceBank.forget(e.getEntity().getUUID());   // shared phrases leave with the player
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent e) {
        LightningEvent.cleanup();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent e) {
        com.manbear.voice.VoiceBank.clear();
    }

    // ------------------------------------------------------------------ rage sources
    private static final java.util.Set<EntityType<?>> HARMLESS = java.util.Set.of(
            EntityType.RABBIT, EntityType.WOLF, EntityType.LLAMA, EntityType.TRADER_LLAMA, EntityType.VILLAGER,
            EntityType.WANDERING_TRADER, EntityType.IRON_GOLEM, EntityType.BAT, EntityType.FOX, EntityType.CAT,
            EntityType.OCELOT, EntityType.PARROT, EntityType.AXOLOTL, EntityType.FROG, EntityType.TURTLE);

    private static void rageUp(ServerPlayer p, double amount) {
        if (amount == 0 || p.getServer() == null || !ManbearConfig.RAGE_ENABLED.get()) return;
        ManbearData data = ManbearData.get(p.getServer());
        data.profile(p.getUUID()).addRage((float) amount);
        data.setDirty();
    }

    /** any log of any tree, vanilla or modded */
    private static boolean isTreeBlock(BlockState st) {
        if (st.is(BlockTags.LOGS)) return true;
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(st.getBlock());
        if (key == null) return false;
        String path = key.getPath();
        return path.endsWith("_log") || path.endsWith("_stem") || path.contains("_log_") || path.startsWith("log_")
                || path.endsWith("_hyphae") || path.endsWith("_wood") && !path.contains("plank");
    }

    /** The Manbear only remembers environmental edits when he was plausibly close enough to notice them. */
    private static boolean manbearCouldNotice(ServerLevel level, BlockPos pos) {
        AABB area = new AABB(pos).inflate(24.0D);
        return !level.getEntitiesOfClass(ManbearEntity.class, area,
                bear -> bear.isAlive() && !bear.leaving).isEmpty();
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent e) {
        if (e.getLevel() instanceof ServerLevel brokenIn) {
            PitTraps.onBlockBroken(brokenIn, e.getPos());   // you found his pit
            if (e.getPlayer() instanceof ServerPlayer breaker) ManbearDen.onBlockBroken(brokenIn, e.getPos(), breaker);
        }
        if (!(e.getPlayer() instanceof ServerPlayer p) || p.isCreative()) return;
        if (manbearCouldNotice(p.serverLevel(), e.getPos())) {
            ManbearData memoryData = ManbearData.get(p.getServer());
            memoryData.profile(p.getUUID()).recordWorldAction(e.getPos(), p.serverLevel().dimension().location().toString(), false, p.level().getGameTime());
            memoryData.setDirty();
        }
        BlockState st = e.getState();
        if (st.is(ModBlocks.BEAR_TRAP.get()) && st.getValue(BearTrapBlock.HUNTER_SET)) {
            rageUp(p, ManbearConfig.RAGE_TRAP_BREAK.get());       // you wrecked one of his traps
        } else if (isTreeBlock(st)) {
            rageUp(p, ManbearConfig.RAGE_LOG.get());              // you are cutting his forest down
        } else if (st.is(Blocks.GRASS_BLOCK)) {
            rageUp(p, ManbearConfig.RAGE_GRASS.get());            // tearing up the ground (grass BLOCKS only)
        }
    }

    private static boolean isSuspiciousTrapComponent(BlockState state) {
        return state.is(Blocks.TRIPWIRE) || state.is(Blocks.TRIPWIRE_HOOK)
                || state.getBlock() instanceof net.minecraft.world.level.block.PressurePlateBlock
                || state.is(Blocks.TNT) || state.is(Blocks.DISPENSER) || state.is(Blocks.DROPPER)
                || state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)
                || state.is(Blocks.OBSERVER) || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL);
    }

    /** campfires anger him; saplings and crops calm him */
    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel() instanceof ServerLevel placedIn) {
            UUID actor = e.getEntity() instanceof ServerPlayer placer ? placer.getUUID() : null;
            ManbearDen.onBlockPlaced(placedIn, e.getPos(), actor);
        }
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isCreative()) return;
        if (manbearCouldNotice(p.serverLevel(), e.getPos())) {
            ManbearData memoryData = ManbearData.get(p.getServer());
            PlayerProfile profile = memoryData.profile(p.getUUID());
            profile.recordWorldAction(e.getPos(), p.serverLevel().dimension().location().toString(), true, p.level().getGameTime());
            BlockState observedState = e.getPlacedBlock();
            if (ManbearConfig.TRAP_LEARNING_ENABLED.get() && isSuspiciousTrapComponent(observedState)) {
                profile.recordTrapObservation(e.getPos(), p.serverLevel().dimension().location().toString(), p.level().getGameTime());
            }
            memoryData.setDirty();
        }
        BlockState st = e.getPlacedBlock();
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(st.getBlock());
        String path = key == null ? "" : key.getPath();
        if (st.is(BlockTags.CAMPFIRES)) rageUp(p, ManbearConfig.RAGE_CAMPFIRE.get());
        else if (st.is(BlockTags.SAPLINGS) || path.endsWith("_sapling")) rageUp(p, -ManbearConfig.CALM_SAPLING.get());
        else if (st.is(BlockTags.CROPS)) rageUp(p, -ManbearConfig.CALM_CROP.get());
    }

    /** breeding animals calms him */
    @SubscribeEvent
    public void onBreed(BabyEntitySpawnEvent e) {
        if (e.getCausedByPlayer() instanceof ServerPlayer p) rageUp(p, -ManbearConfig.CALM_BREED.get());
    }

    /** tilling soil (building a farm) calms him */
    @SubscribeEvent
    public void onTill(BlockEvent.BlockToolModificationEvent e) {
        if (!e.isSimulated() && e.getToolAction() == ToolActions.HOE_TILL && e.getPlayer() instanceof ServerPlayer p) {
            rageUp(p, -ManbearConfig.CALM_TILL.get());
        }
    }

    /** right-click a captive pet to let it out of the trap */
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || e.getLevel().isClientSide()) return;
        if (!(e.getTarget() instanceof LivingEntity pet) || !pet.getTags().contains(PetKidnap.CAPTIVE) || pet.isPassenger()) return;
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel level)) return;
        PetKidnap.free(level, pet);
        p.displayClientMessage(Component.literal("You got your pet back!"), true);
        rageUp(p, ManbearConfig.RAGE_PET_BACK.get());     // he does not like that
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static final java.util.Map<java.util.UUID, Long> NAME_COOLDOWN = new java.util.HashMap<>();

    /** the player's own microphone heard "man bear" / "bear" */
    public static void nameHeard(ServerPlayer p) {
        long now = p.level().getGameTime();
        Long last = NAME_COOLDOWN.get(p.getUUID());
        if (last != null && now - last < 200) return;                  // at most once per 10 s
        NAME_COOLDOWN.put(p.getUUID(), now);
        rageUp(p, ManbearConfig.RAGE_SAY_NAME.get());
        ManbearEntity m = ManbearEntity.current();
        if (m != null && m.level() == p.level()) m.ai().saidName(p, p.level().random.nextFloat());
    }

    /**
     * The client detected one sustained loud-vocal burst locally. Only the event count arrives here;
     * raw microphone audio and transcripts are never sent in this packet.
     */
    public static void screamHeard(ServerPlayer player) {
        if (player == null || !ManbearConfig.SCREAM_ACTIVITY_ENABLED.get()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ManbearData data = ManbearData.get(server);
        long now = server.overworld().getGameTime();
        if (data.profile(player.getUUID()).recordScreamEvent(now)) data.setDirty();
    }

    /** killing something harmless that gives you nothing */
    @SubscribeEvent
    public void onDrops(LivingDropsEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p)) return;
        LivingEntity v = e.getEntity();
        if (!(v instanceof Mob) || v instanceof Enemy || v instanceof ManbearEntity || v.getTags().contains("manbear_lure")) return;
        if (HARMLESS.contains(v.getType()) || e.getDrops().isEmpty()) {
            rageUp(p, ManbearConfig.RAGE_ANIMAL.get());
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (e.getEntity().getTags().contains("manbear_lure") && e.getSource().getEntity() instanceof ServerPlayer killer) {
            rageUp(killer, ManbearConfig.RAGE_LURE_KILL.get());   // you killed his bait
        }
        if (e.getEntity() instanceof ServerPlayer p && e.getSource().getEntity() instanceof ManbearEntity m) {
            m.onKilledPlayer(p);
        }
    }

    // ------------------------------------------------------------------ profiles
    private void sampleProfiles(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        Entity bear = findActive(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            data.profile(p.getUUID()).sample(p, now, bear);
        }
        if (now % 600 == 0) data.setDirty();
    }

    public static ManbearEntity findActive(MinecraftServer server) {
        UUID id = ManbearData.get(server).activeId();
        if (id == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof ManbearEntity m) return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ home raids
    /**
     * Every minute far from the base can queue a raid. The raid is executed while the player is away
     * whenever the nearby base chunks are already loaded; no chunks are force-loaded. If the base stayed
     * unloaded, the pending raid falls back to the return trip so the player can still discover the aftermath.
     */
    private void raidTick(MinecraftServer server, long now) {
        if (!ManbearConfig.RAIDS_ENABLED.get()) return;
        ManbearData data = ManbearData.get(server);
        double away = ManbearConfig.RAID_AWAY_DISTANCE.get();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !p.isAlive() || p.serverLevel() != server.overworld()) continue;
            PlayerProfile pr = data.profile(p.getUUID());
            if (pr.base == null) continue;
            ServerLevel level = p.serverLevel();
            double dx = pr.base.getX() + 0.5 - p.getX();
            double dz = pr.base.getZ() + 0.5 - p.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > away) {
                pr.awayTicks += 20;
                if (pr.awayTicks >= 1200) {
                    pr.awayTicks -= 1200;
                    if (!pr.raidPending && now >= pr.raidCooldownUntil && level.random.nextInt(ManbearConfig.RAID_CHANCE_DENOMINATOR.get()) == 0) {
                        pr.raidPending = true;
                        data.setDirty();
                    }
                }
            } else if (d < 60) {
                pr.awayTicks = 0;
            }
            boolean baseChunksLoaded = level.hasChunksAt(pr.base.offset(-24, -8, -24), pr.base.offset(24, 8, 24));
            boolean canRaidUnobserved = d > away;
            boolean returnFallback = d < 100.0D;
            if (pr.raidPending && baseChunksLoaded && (canRaidUnobserved || returnFallback)) {
                String result = RaidHandler.rummage(level, pr.base, level.random, p.getUUID());
                pr.raidPending = false;
                pr.raidCooldownUntil = now + (result == null ? 6000 : 24000);
                data.setDirty();
            }
        }
    }

    // ------------------------------------------------------------------ spawning (1/25 per minute, only one ever)
    private void spawnRoll(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        if (data.denSpawnBlocked(now)) return;
        if (data.isActive(now)) return;
        if (server.overworld().getDifficulty() == Difficulty.PEACEFUL) return;
        RandomSource rnd = server.overworld().random;

        List<? extends String> dims = ManbearConfig.DIMENSIONS.get();
        List<ServerPlayer> candidates = new ArrayList<>();
        Map<UUID, Double> screamBonuses = new HashMap<>();
        double bestRage = 0;
        double bestScreamBonus = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            if (!dims.contains(p.level().dimension().location().toString())) continue;
            candidates.add(p);
            PlayerProfile profile = data.profile(p.getUUID());
            bestRage = Math.max(bestRage, profile.rageFactor());
            double screamBonus = 0.0D;
            if (ManbearConfig.SCREAM_ACTIVITY_ENABLED.get()) {
                int activeBeforeDecay = profile.activeScreamSignals();
                screamBonus = profile.screamSpawnBonus(now,
                        ManbearConfig.SCREAM_SPAWN_BONUS_PER_SIGNAL.get(),
                        ManbearConfig.SCREAM_SPAWN_BONUS_MAX.get(),
                        ManbearConfig.SCREAM_ACTIVITY_DECAY_TICKS.get());
                if (profile.activeScreamSignals() != activeBeforeDecay) data.setDirty();
            }
            screamBonuses.put(p.getUUID(), screamBonus);
            bestScreamBonus = Math.max(bestScreamBonus, screamBonus);
        }
        if (candidates.isEmpty()) return;
        if (data.onCooldown(now) && bestRage < 0.7) return;   // a furious Manbear does not rest

        // the angrier he is, the more often he turns up
        double chance = (1.0 / ManbearConfig.SPAWN_CHANCE_DENOMINATOR.get())
                * (1.0 + ManbearConfig.RAGE_SPAWN_BONUS.get() * bestRage)
                * (1.0 + bestScreamBonus);
        if (rnd.nextDouble() >= chance) return;

        // and he picks on whoever made him the angriest
        double total = 0;
        for (ServerPlayer p : candidates) {
            PlayerProfile profile = data.profile(p.getUUID());
            double screamBonus = screamBonuses.getOrDefault(p.getUUID(), 0.0D);
            total += (1.0 + 4.0 * profile.rageFactor()) * (1.0 + screamBonus);
        }
        double pick = rnd.nextDouble() * total;
        ServerPlayer chosen = candidates.get(candidates.size() - 1);
        for (ServerPlayer p : candidates) {
            PlayerProfile profile = data.profile(p.getUUID());
            double screamBonus = screamBonuses.getOrDefault(p.getUUID(), 0.0D);
            pick -= (1.0 + 4.0 * profile.rageFactor()) * (1.0 + screamBonus);
            if (pick <= 0) { chosen = p; break; }
        }
        String kind = null;
        if (!chosen.level().canSeeSky(chosen.blockPosition()) && chosen.getBlockY() < 50
                && rnd.nextFloat() < ManbearConfig.CAVE_EVENT_CHANCE.get()) {
            kind = "cave";
        }
        spawnNear(server, chosen, kind);
    }

    public static ManbearEntity spawnNear(MinecraftServer server, ServerPlayer p) {
        return spawnNear(server, p, null);
    }

    /** Spawns him out of sight. kind (optional, for commands): chaser | faker | trapper | sleeper | behind | lurer | path | fog | cave. */
    public static ManbearEntity spawnNear(MinecraftServer server, ServerPlayer p, String kind) {
        ServerLevel level = p.serverLevel();
        RandomSource rnd = level.random;
        ManbearEntity e = ModEntities.MANBEAR.get().create(level);
        if (e == null) return null;
        if (kind != null) e.applyKind(kind);
        else e.rollKinds(ManbearData.get(server).profile(p.getUUID()).rageFactor(), PetKidnap.hasPet(level, p));

        if (e.isBehind()) return spawnBehind(server, level, p, e, 11, 16) ? e : null;
        if (e.isPetnapper()) {   // sneaks in behind you, near your pet
            if (PetKidnap.hasPet(level, p) && spawnBehind(server, level, p, e, 8, 12)) return e;
            e.makeOrdinary();
        }
        if (e.isPitLurer()) {   // stands behind you, waiting to be noticed; he digs the pit when you do
            if (spawnBehind(server, level, p, e, 9, 14)) return e;
            e.makeOrdinary();
        }
        if (e.isHallucinator()) {   // waiting deep in the woods, where your double will lead you
            if (spawnWoods(server, level, p, e)) return e;
            e.makeOrdinary();
        }
        if (e.isKidnapper()) {   // far enough behind that he has to sneak up on you
            if (spawnBehind(server, level, p, e, 20, 28)) return e;
            e.makeOrdinary();
        }
        if (e.isLurker()) {   // right at your back
            if (spawnBehind(server, level, p, e, 5, 8)) return e;
            e.makeOrdinary();
        }
        if (e.isFogger() && spawnFog(server, level, p, e)) return e;
        if (e.isPathBlocker() && spawnPath(server, level, p, e)) return e;
        if (e.isCaver() && spawnCave(server, level, p, e)) return e;
        // the special spot was not possible (no hot spots yet, not in a cave, ...): ordinary stalker
        if (e.isFogger() || e.isPathBlocker() || e.isCaver()) e.makeOrdinary();

        int min = ManbearConfig.SPAWN_MIN_DIST.get();
        int max = Math.max(min + 1, ManbearConfig.SPAWN_MAX_DIST.get());
        if (e.isSleeper()) { min = 22; max = 40; }   // closer, so you can actually stumble on him
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;

            BlockPos g;
            if (level.canSeeSky(p.blockPosition())) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                g = new BlockPos(x, y, z);
                if (!CoverFinder.standable(level, g, e.isSleeper() ? 2 : 5)) continue;
            } else {
                g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), e.isSleeper() ? 2 : 5);
                if (g == null) continue;
            }
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (seenByAnyPlayer(level, v)) continue;
            if (!level.noCollision((e.isSleeper() ? Stance.SLEEP : Stance.STAND).dims.makeBoundingBox(v))) continue;
            float yaw = rnd.nextFloat() * 360F;
            if (e.isSleeper() && !flatForLying(level, g, yaw)) continue;
            place(server, level, e, v, yaw);
            return e;
        }
        return null;
    }

    /** a lying Manbear is about 5.4 blocks long: the whole strip under him must be flat, open ground (no clipping into slopes or trees) */
    private static boolean flatForLying(ServerLevel level, BlockPos g, float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        for (int t = -3; t <= 3; t++) {
            for (int side = -1; side <= 1; side++) {
                double x = g.getX() + 0.5 + fx * t + fz * side;
                double z = g.getZ() + 0.5 + fz * t - fx * side;
                BlockPos c = new BlockPos((int) Math.floor(x), g.getY(), (int) Math.floor(z));
                if (!level.hasChunkAt(c) || !CoverFinder.standable(level, c, 2)) return false;
            }
        }
        return true;
    }

    private static void place(MinecraftServer server, ServerLevel level, ManbearEntity e, Vec3 v, float yaw) {
        e.moveTo(v.x, v.y, v.z, yaw, 0F);
        level.addFreshEntity(e);
        ManbearData.get(server).claim(e.getUUID(), server.overworld().getGameTime());
    }

    private static float yawFacing(Vec3 from, double tx, double tz) {
        return (float) (Mth.atan2(tz - from.z, tx - from.x) * (180D / Math.PI)) - 90.0F;
    }

    /** hallucination: standing among real trees 38-58 blocks away, out of sight */
    private static boolean spawnWoods(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        RandomSource rnd = level.random;
        for (int i = 0; i < 70; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = 38 + rnd.nextDouble() * 20;
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
            BlockPos g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), 6);
            if (g == null || Math.abs(g.getY() - p.getBlockY()) > 10) continue;
            if (woodsDensity(level, g, rnd) < 7) continue;                          // real woods, not a lone tree
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (!e.fitsIgnoringLeaves(Stance.STAND.dims.makeBoundingBox(v))) continue;
            if (seenByAnyPlayer(level, v)) continue;
            place(server, level, e, v, yawFacing(v, p.getX(), p.getZ()));
            return true;
        }
        return false;
    }

    /** how many of 60 sampled blocks around here are log or leaf */
    private static int woodsDensity(ServerLevel level, BlockPos c, RandomSource rnd) {
        int n = 0;
        for (int i = 0; i < 60; i++) {
            BlockState st = level.getBlockState(c.offset(rnd.nextInt(15) - 7, rnd.nextInt(9) - 1, rnd.nextInt(15) - 7));
            if (st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES)) n++;
        }
        return n;
    }

    /** behind-you event: directly behind the player, out of their view */
    private static boolean spawnBehind(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e, double minD, double maxD) {
        RandomSource rnd = level.random;
        Vec3 look = p.getLookAngle();
        double base = Math.atan2(-look.z, -look.x);
        for (int i = 0; i < 40; i++) {
            double ang = base + (rnd.nextDouble() - 0.5) * 0.9;
            double d = minD + rnd.nextDouble() * (maxD - minD);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
            BlockPos g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), 5);
            if (g == null || Math.abs(g.getY() - p.getBlockY()) > 3) continue;
            Vec3 v = Vec3.atBottomCenterOf(g);
            Vec3 to = new Vec3(v.x - p.getX(), 0, v.z - p.getZ()).normalize();
            if (new Vec3(look.x, 0, look.z).normalize().dot(to) > -0.3) continue; // must not be in front of them
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
            if (seenByAnyPlayer(level, v)) continue;
            place(server, level, e, v, 0F);
            return true;
        }
        return false;
    }

    /**
     * Fog watcher: at the very edge of YOUR render distance (where the fog thickens), roughly where you are
     * looking, so he is just barely visible as a dark shape.
     */
    private static boolean spawnFog(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        if (!level.canSeeSky(p.blockPosition())) return false;
        RandomSource rnd = level.random;
        int serverChunks = server.getPlayerList().getViewDistance();
        int override = ManbearConfig.FOG_EDGE_CHUNKS.get();
        int chunks = override > 0 ? Math.min(override, serverChunks) : serverChunks;
        double edge = chunks * 16.0;
        Vec3 look = p.getLookAngle();
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < 60; i++) {
            double ang = base + (rnd.nextBoolean() ? 1 : -1) * (1.5 + rnd.nextDouble() * 0.5);   // outside your field of view
            double d = edge * (ManbearConfig.FOG_WATCH_FACTOR.get() + rnd.nextDouble() * 0.07);   // inside the thick fog, not at its very end
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos g = new BlockPos(x, y, z);
            if (!CoverFinder.standable(level, g, 5) || Math.abs(y - p.getBlockY()) > 30) continue;
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (!CoverFinder.lineClear(level, p.getEyePosition(), v.add(0, 4.0, 0), p)) continue;   // clear line, so you spot him when you turn
            if (seenByAnyPlayer(level, v)) continue;                                               // but never while he appears
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
            place(server, level, e, v, yawFacing(v, p.getX(), p.getZ()));
            return true;
        }
        return false;
    }

    /** Path blocker: standing at one of the places you visit most, 30-40 blocks away, not in your view. */
    private static boolean spawnPath(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        PlayerProfile prof = ManbearData.get(server).profile(p.getUUID());
        int min = ManbearConfig.PATH_MIN_DISTANCE.get();
        int max = Math.max(min + 1, ManbearConfig.PATH_MAX_DISTANCE.get());
        Vec3 look = p.getLookAngle();
        for (BlockPos h : prof.hotspots()) {
            double dx = h.getX() + 0.5 - p.getX();
            double dz = h.getZ() + 0.5 - p.getZ();
            double dd = Math.sqrt(dx * dx + dz * dz);
            if (dd < min || dd > max || !level.hasChunkAt(h)) continue;
            BlockPos g = CoverFinder.groundNear(level, h.getX() + 0.5, h.getZ() + 0.5, h.getY(), 5);
            if (g == null) continue;
            Vec3 v = Vec3.atBottomCenterOf(g);
            Vec3 to = v.subtract(p.getEyePosition()).normalize();
            if (seenByAnyPlayer(level, v)) continue; // never pops in where anyone can see
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
            place(server, level, e, v, yawFacing(v, p.getX(), p.getZ()));
            return true;
        }
        return false;
    }

    /** Cave event: sitting in the open middle of a cave, back to you, in a spatter of redstone "blood". */
    private static boolean spawnCave(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        if (level.canSeeSky(p.blockPosition())) return false;
        RandomSource rnd = level.random;
        for (int i = 0; i < 70; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = 18 + rnd.nextDouble() * 18;
            double x = p.getX() + Math.cos(ang) * d;
            double z = p.getZ() + Math.sin(ang) * d;
            BlockPos g = CoverFinder.groundNear(level, x, z, p.getBlockY(), 3);
            if (g == null || level.canSeeSky(g) || Math.abs(g.getY() - p.getBlockY()) > 10) continue;
            int open = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (CoverFinder.standable(level, g.offset(dx, 0, dz), 3)) open++;
                }
            }
            if (open < 16) continue;   // needs real open floor: the middle of a cave, not a tunnel
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (seenByAnyPlayer(level, v)) continue;
            if (!level.noCollision(Stance.SIT.dims.makeBoundingBox(v))) continue;
            placeBlood(level, g, rnd);
            // back turned to you
            place(server, level, e, v, yawFacing(v, p.getX(), p.getZ()) + 180F);
            return true;
        }
        return false;
    }

    private static void placeBlood(ServerLevel level, BlockPos center, RandomSource rnd) {
        int placed = 0;
        for (int i = 0; i < 40 && placed < 16; i++) {
            int dx = rnd.nextInt(9) - 4;
            int dz = rnd.nextInt(9) - 4;
            if (Math.abs(dx) > 2 && Math.abs(dz) > 2 && rnd.nextBoolean()) continue;   // thicker near him
            BlockPos pos = center.offset(dx, 0, dz);
            if (!level.getBlockState(pos).isAir()) continue;
            BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
            if (!wire.canSurvive(level, pos)) continue;
            level.setBlock(pos, wire, 3);
            placed++;
        }
    }

    /** true if any player could see a Manbear standing at v right now (inside their field of view with a clear line) */
    private static boolean seenByAnyPlayer(ServerLevel level, Vec3 v) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (p.distanceToSqr(v) > 256.0 * 256.0) continue;
            Vec3 eye = p.getEyePosition();
            Vec3 to = v.add(0, 2.5, 0).subtract(eye).normalize();
            if (p.getLookAngle().dot(to) < 0.1) continue;   // outside the field of view (with a margin)
            if (CoverFinder.lineClear(level, eye, v.add(0, 0.5, 0), p)
                    || CoverFinder.lineClear(level, eye, v.add(0, 2.5, 0), p)
                    || CoverFinder.lineClear(level, eye, v.add(0, 4.5, 0), p)) return true;
        }
        return false;
    }
}
