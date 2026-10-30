package com.manbear.data;

import com.manbear.ManbearConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.minecraft.nbt.StringTag;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What the Manbear has learned about one player. Every trait is a 0..1 moving average,
 * saved with the world, so he remembers you between sessions.
 */
public class PlayerProfile {
    /** how often you look at him when you could see him */
    public float vigilance = 0.5F;
    /** how often you run away when chased */
    public float panic = 0.5F;
    /** how often you fight back */
    public float aggression = 0.0F;
    /** how much you stand still / hole up */
    public float camping = 0.0F;
    public float sneakiness = 0.0F;
    public float sprintiness = 0.3F;
    /** His aggression towards this player, 0..100. Fed by tree cutting, killing harmless animals, hitting him, ... */
    public float rage = 0F;

    public int hits;
    public int trapsSprung;
    public int deaths;
    public int chases;
    /** Number of pursuits where the player successfully opened a large gap or broke line of sight. */
    public int escapedChases;
    /** Times escape behavior suggests the player is relying on enclosed shelter / a learned base. */
    public int shelterEscapes;
    /** Times escape behavior suggests repeated sprinting away. */
    public int runEscapes;
    /** Persistent strategy stage; every successful escape changes the Manbear's preferred response. */
    public int strategyStage;
    /** Combat style samples used for counter-tactics. */
    public int rangedHits;
    public int meleeHits;
    public int shieldUses;
    /** Lifetime count of client-reported loud-vocal events; the server never receives microphone audio. */
    public int totalScreams;
    /** Recent scream-like activity stacks; decays over in-game time and influences spawn frequency gently. */
    private int activeScreamSignals;
    private long lastScreamEventTime = Long.MIN_VALUE;
    private long lastScreamDecayTime = -1L;
    /** Revisable theories about how this particular player reacts; 0.5 means unknown. */
    public float investigatesCues = 0.5F;
    public float monitorsManbear = 0.5F;
    public float followsManbear = 0.5F;
    public int theorySamples;
    /** Short excerpts remembered for locally generated Manbear taunts; never sent to an external service. */
    private final java.util.ArrayList<String> speechFragments = new java.util.ArrayList<>();

    private final Map<Long, Integer> heat = new HashMap<>();
    private final Map<Long, Long> heatPos = new HashMap<>();   // cell -> last block position seen there
    /** Recent player-made changes the Manbear can actually observe later, not omniscient world knowledge. */
    public record WorldMemory(long pos, String dimension, boolean placed, long gameTime) { }
    private final java.util.ArrayList<WorldMemory> worldMemories = new java.util.ArrayList<>();
    /** Player-placed trap/redstone pieces the Manbear had a plausible chance to notice. */
    public record TrapMemory(long pos, String dimension, long gameTime) { }
    private final java.util.ArrayList<TrapMemory> trapMemories = new java.util.ArrayList<>();
    /** Compact episodic memories: not just what happened, but the result and the Manbear's best explanation. */
    public record EpisodeMemory(String kind, String outcome, String reason, long pos, String dimension, long gameTime) { }
    private final java.util.ArrayList<EpisodeMemory> episodes = new java.util.ArrayList<>();
    /** Long-running investigation objective that can survive a particular Manbear entity despawning. */
    public record CampaignMemory(String type, long pos, String dimension, int stage, long updatedAt) { }
    private CampaignMemory campaign;
    public enum LikelyGoal { UNKNOWN, BUILDING, GATHERING, EXPLORING, RETURNING_HOME, CAVE_EXPLORATION }
    private LikelyGoal likelyGoal = LikelyGoal.UNKNOWN;
    private float goalConfidence;
    private long lastGoalEvaluation = Long.MIN_VALUE;
    private double lastBaseDistance = Double.NaN;
    /** Confidence that a frequently visited 8x8 area is a useful route/meeting point; 0.5 means uncertain. */
    private final Map<Long, Float> routeBeliefs = new HashMap<>();
    private final Map<Long, Long> routeBeliefTimes = new HashMap<>();
    /** Cooldowns and last-occurrence times for multi-stage scripted encounters. */
    private final Map<String, Long> scriptedEventTimes = new HashMap<>();
    private int houseRehearsalStage;
    private long houseRehearsalFocus;
    private String houseRehearsalDimension = "";
    public record UnfinishedTask(String kind, long pos, String dimension, long updatedAt) { }
    private UnfinishedTask unfinishedTask;
    public BlockPos base = null;
    /** home raid: set while you are away, carried out when you come back within range */
    public boolean raidPending;
    public long raidCooldownUntil;
    public int awayTicks;
    /** a decaying counter of time spent close to the learned base; used to unlock the den */
    public int basePresenceTicks;

    // transient sampling state
    private Vec3 areaAnchor;
    private int areaTicks;
    private Vec3 lastPos;
    private Vec3 heading = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;

    public Vec3 heading() { return heading; }
    /** blocks per sample (10 ticks) */
    public Vec3 velocity() { return velocity; }

    public static float ema(float old, float sample, float alpha) {
        return old + (sample - old) * alpha;
    }


    /** Accepts a rate-limited loud-vocal event notification from the player's client. */
    public boolean recordScreamEvent(long now) {
        if (lastScreamEventTime != Long.MIN_VALUE && now >= lastScreamEventTime && now - lastScreamEventTime < 100L) {
            return false;
        }
        totalScreams = Math.min(1_000_000, totalScreams + 1);
        activeScreamSignals = Math.min(10, activeScreamSignals + 1);
        lastScreamEventTime = now;
        if (lastScreamDecayTime < 0L || now < lastScreamDecayTime) lastScreamDecayTime = now;
        return true;
    }

    /** Recent loud-vocal activity decays one stack at a time; lifetime count remains for memory. */
    public float screamSpawnBonus(long now, double perSignal, double maximum, int decayTicks) {
        int interval = Math.max(1200, decayTicks);
        if (lastScreamDecayTime < 0L || now < lastScreamDecayTime) lastScreamDecayTime = now;
        if (now > lastScreamDecayTime) {
            long elapsed = now - lastScreamDecayTime;
            long steps = elapsed / interval;
            if (steps > 0L) {
                activeScreamSignals = Math.max(0, activeScreamSignals - (int) Math.min(Integer.MAX_VALUE, steps));
                lastScreamDecayTime += steps * interval;
            }
        }
        return (float) Math.min(Math.max(0.0D, maximum), activeScreamSignals * Math.max(0.0D, perSignal));
    }

    public int totalScreams() { return totalScreams; }
    public int activeScreamSignals() { return activeScreamSignals; }

    public void observeCueReaction(boolean reacted) {
        investigatesCues = Math.max(0.05F, Math.min(0.95F, ema(investigatesCues, reacted ? 1.0F : 0.0F, 0.16F)));
        theorySamples++;
    }

    public void observeWatching(boolean watched) {
        monitorsManbear = Math.max(0.05F, Math.min(0.95F, ema(monitorsManbear, watched ? 1.0F : 0.0F, 0.07F)));
        theorySamples++;
    }

    public void observeFollowing(boolean followed) {
        followsManbear = Math.max(0.05F, Math.min(0.95F, ema(followsManbear, followed ? 1.0F : 0.0F, 0.18F)));
        theorySamples++;
    }

    public String theorySummary() {
        if (theorySamples < 3) return "uncertain";
        if (investigatesCues > 0.66F && monitorsManbear > 0.62F) return "alert and investigative";
        if (followsManbear > 0.66F) return "likely to follow";
        if (investigatesCues < 0.34F && monitorsManbear < 0.38F) return "hard to lure";
        return "mixed";
    }

    public void addRage(float v) {
        if (!ManbearConfig.RAGE_ENABLED.get()) return;
        rage = Math.max(0F, Math.min(100F, rage + v));
    }

    public float rageFactor() { return ManbearConfig.RAGE_ENABLED.get() ? rage / 100F : 0F; }

    /** Remember a nearby world change that this player personally made. Only a tiny, expiring history is kept. */
    public void recordWorldAction(BlockPos pos, String dimension, boolean placed, long gameTime) {
        if (pos == null || dimension == null || dimension.isBlank()) return;
        long packed = pos.asLong();
        for (int i = worldMemories.size() - 1; i >= 0; i--) {
            WorldMemory old = worldMemories.get(i);
            if (old.pos() == packed && old.dimension().equals(dimension) && gameTime - old.gameTime() < 40) {
                worldMemories.set(i, new WorldMemory(packed, dimension, placed, gameTime));
                return;
            }
        }
        worldMemories.add(new WorldMemory(packed, dimension, placed, gameTime));
        while (worldMemories.size() > 12) worldMemories.remove(0);
    }

    public java.util.List<WorldMemory> recentWorldActions(long now, String dimension, long maxAge) {
        java.util.ArrayList<WorldMemory> out = new java.util.ArrayList<>();
        for (int i = worldMemories.size() - 1; i >= 0; i--) {
            WorldMemory memory = worldMemories.get(i);
            long age = now - memory.gameTime();
            if (age >= 0 && age <= maxAge && memory.dimension().equals(dimension)) out.add(memory);
        }
        return out;
    }

    private static long routeCellKey(BlockPos p) {
        return (((long) (p.getX() >> 3)) << 32) | ((p.getZ() >> 3) & 0xFFFFFFFFL);
    }


    public long scriptedEventAt(String key) { return scriptedEventTimes.getOrDefault(key, Long.MIN_VALUE); }

    public boolean scriptedEventReady(String key, long now, long cooldownTicks) {
        long last = scriptedEventAt(key);
        return last == Long.MIN_VALUE || now < last || now - last >= cooldownTicks;
    }

    public void markScriptedEvent(String key, long now) {
        if (key != null && !key.isBlank()) scriptedEventTimes.put(key, now);
    }

    public int houseRehearsalStage() { return houseRehearsalStage; }

    public BlockPos houseRehearsalFocus(String dimension) {
        if (houseRehearsalDimension.isBlank() || houseRehearsalDimension.equals(dimension))
            return houseRehearsalFocus == 0L ? null : BlockPos.of(houseRehearsalFocus);
        return null;
    }

    public void completeHouseRehearsal(BlockPos focus, String dimension, long now) {
        if (focus == null || dimension == null || dimension.isBlank()) return;
        if (!houseRehearsalDimension.equals(dimension)) houseRehearsalStage = 0;
        houseRehearsalFocus = focus.asLong();
        houseRehearsalDimension = dimension;
        houseRehearsalStage = (houseRehearsalStage + 1) % 3;
        markScriptedEvent("HOUSE_REHEARSAL", now);
    }

    public void recordUnfinishedTask(String kind, BlockPos pos, String dimension, long now) {
        if (kind == null || kind.isBlank() || pos == null || dimension == null || dimension.isBlank()) return;
        // An existing unfinished objective keeps its original type unless a newer, substantive plan was interrupted.
        unfinishedTask = new UnfinishedTask(kind, pos.asLong(), dimension, now);
    }

    public UnfinishedTask unfinishedTask(long now, String dimension) {
        if (unfinishedTask == null) return null;
        if (now < unfinishedTask.updatedAt() || now - unfinishedTask.updatedAt() > 7L * 24000L
                || (dimension != null && !dimension.isBlank() && !dimension.equals(unfinishedTask.dimension()))) {
            unfinishedTask = null;
            return null;
        }
        return unfinishedTask;
    }

    public void clearUnfinishedTask() { unfinishedTask = null; }

    public float routeBelief(BlockPos p) { return routeBelief(p, 0L); }

    /** Old beliefs decay toward uncertainty; time passing without confirmation should matter. */
    public float routeBelief(BlockPos p, long now) {
        long key = routeCellKey(p);
        float belief = routeBeliefs.getOrDefault(key, 0.5F);
        Long last = routeBeliefTimes.get(key);
        if (last == null) return (float) (0.5D + (belief - 0.5D) * 0.18D); // pre-update beliefs have unknown age
        if (now <= 0L || last <= 0L || now <= last) return belief;
        double ageDays = (now - last) / 24000.0D;
        double retained = Math.exp(-ageDays / 3.0D);
        return (float) (0.5D + (belief - 0.5D) * retained);
    }

    public void adjustRouteBelief(BlockPos p, float delta) { adjustRouteBelief(p, delta, 0L); }

    public void adjustRouteBelief(BlockPos p, float delta, long now) {
        long key = routeCellKey(p);
        float current = routeBelief(p, now);
        routeBeliefs.put(key, Math.max(0.05F, Math.min(0.95F, current + delta)));
        if (now > 0L) routeBeliefTimes.put(key, now);
        while (routeBeliefs.size() > 64) {
            Long keyToRemove = null;
            for (Long candidate : routeBeliefs.keySet()) { keyToRemove = candidate; break; }
            if (keyToRemove == null) break;
            routeBeliefs.remove(keyToRemove);
            routeBeliefTimes.remove(keyToRemove);
        }
    }

    public LikelyGoal likelyGoal() { return likelyGoal; }
    public float goalConfidence() { return goalConfidence; }

    /** Observation results refine a goal hypothesis; negative evidence lowers confidence but does not erase it immediately. */
    public void adjustGoalConfidence(boolean supported, long gameTime) {
        goalConfidence = Math.max(0.0F, Math.min(1.0F, goalConfidence + (supported ? 0.12F : -0.16F)));
        if (!supported && goalConfidence < 0.22F) likelyGoal = LikelyGoal.UNKNOWN;
        lastGoalEvaluation = gameTime;
    }

    /**
     * Infer a broad short-term goal from observable behavior. This deliberately uses fuzzy clues,
     * not inventory inspection or hidden player intent, so the Manbear can be wrong.
     */
    private void updateLikelyGoal(ServerPlayer player, long gameTime) {
        if (lastGoalEvaluation != Long.MIN_VALUE && gameTime - lastGoalEvaluation < 100L) return;
        lastGoalEvaluation = gameTime;
        LikelyGoal candidate = LikelyGoal.UNKNOWN;
        float confidence = 0.0F;
        java.util.List<WorldMemory> recent = recentWorldActions(gameTime, player.level().dimension().location().toString(), 2400L);
        int placed = 0;
        int removed = 0;
        for (WorldMemory memory : recent) {
            if (memory.placed()) placed++; else removed++;
        }
        double baseDistance = base == null ? Double.NaN : Math.sqrt(player.blockPosition().distSqr(base));
        boolean moving = velocity.lengthSqr() > 0.025D;
        boolean movingTowardBase = !Double.isNaN(baseDistance) && !Double.isNaN(lastBaseDistance)
                && baseDistance + 2.0D < lastBaseDistance;

        if (movingTowardBase && baseDistance < 180.0D) {
            candidate = LikelyGoal.RETURNING_HOME;
            confidence = 0.58F + (baseDistance < 45.0D ? 0.12F : 0.0F);
        } else if (player.getY() < 48.0D && moving) {
            candidate = LikelyGoal.CAVE_EXPLORATION;
            confidence = 0.52F + (removed > 1 ? 0.14F : 0.0F);
        } else if (placed >= 3 && base != null && baseDistance < 48.0D) {
            candidate = LikelyGoal.BUILDING;
            confidence = Math.min(0.88F, 0.56F + placed * 0.055F);
        } else if (removed >= 3) {
            candidate = LikelyGoal.GATHERING;
            confidence = Math.min(0.82F, 0.50F + removed * 0.05F);
        } else if (moving && (base == null || baseDistance > 72.0D)) {
            candidate = LikelyGoal.EXPLORING;
            confidence = Math.min(0.78F, 0.49F + (base == null ? 0.08F : Math.min(0.20F, (float) (baseDistance / 500.0D))));
        }

        if (candidate == LikelyGoal.UNKNOWN) {
            goalConfidence = Math.max(0.0F, goalConfidence - 0.045F);
            if (goalConfidence < 0.18F) likelyGoal = LikelyGoal.UNKNOWN;
        } else if (candidate == likelyGoal) {
            goalConfidence = ema(goalConfidence, confidence, 0.22F);
        } else if (confidence > goalConfidence + 0.08F || goalConfidence < 0.30F) {
            likelyGoal = candidate;
            goalConfidence = confidence * 0.72F;
        } else {
            goalConfidence = Math.max(0.0F, goalConfidence - 0.015F);
        }
        if (!Double.isNaN(baseDistance)) lastBaseDistance = baseDistance;
    }

    public void recordEpisode(String kind, String outcome, String reason, BlockPos pos, String dimension, long gameTime) {
        if (kind == null || kind.isBlank() || outcome == null || outcome.isBlank()) return;
        long packed = pos == null ? 0L : pos.asLong();
        String dim = dimension == null ? "" : dimension;
        episodes.add(new EpisodeMemory(kind, outcome, reason == null ? "" : reason, packed, dim, gameTime));
        while (episodes.size() > 24) episodes.remove(0);
    }

    public int countEpisodes(String kind, String outcome, long now, long maxAge) {
        int count = 0;
        for (int i = episodes.size() - 1; i >= 0; i--) {
            EpisodeMemory e = episodes.get(i);
            if (now >= e.gameTime() && now - e.gameTime() > maxAge) break;
            if ((kind == null || kind.equals(e.kind())) && (outcome == null || outcome.equals(e.outcome()))
                    && now >= e.gameTime() && now - e.gameTime() <= maxAge) count++;
        }
        return count;
    }

    public EpisodeMemory lastEpisode(String kind) {
        for (int i = episodes.size() - 1; i >= 0; i--) {
            EpisodeMemory e = episodes.get(i);
            if (kind == null || kind.equals(e.kind())) return e;
        }
        return null;
    }

    public CampaignMemory campaign(long now, String dimension) {
        if (campaign == null) return null;
        if (now < campaign.updatedAt() || now - campaign.updatedAt() > 7L * 24000L
                || (dimension != null && !dimension.isBlank() && !dimension.equals(campaign.dimension()))) {
            campaign = null;
            return null;
        }
        return campaign;
    }

    public void updateCampaign(String type, BlockPos pos, String dimension, long gameTime, boolean progress) {
        if (type == null || type.isBlank() || pos == null || dimension == null || dimension.isBlank()) return;
        int stage = 1;
        boolean sameObjective = campaign != null && campaign.dimension().equals(dimension)
                && BlockPos.of(campaign.pos()).distSqr(pos) <= 10L * 10L;
        boolean sameInvestigation = sameObjective && isInvestigationCampaign(campaign.type()) && isInvestigationCampaign(type);
        if (sameObjective && (campaign.type().equals(type) || sameInvestigation)) {
            // A campaign advances after either useful evidence or a failed attempt that changes the next plan.
            stage = Math.min(6, campaign.stage() + 1);
        }
        campaign = new CampaignMemory(type, pos.asLong(), dimension, stage, gameTime);
    }

    private static boolean isInvestigationCampaign(String type) {
        return "INVESTIGATE_CHANGE".equals(type) || "TEST_ROUTE".equals(type)
                || "INSPECT_TRAP".equals(type) || "OBSERVE_GOAL".equals(type) || "DECEPTION".equals(type);
    }

    public void clearCampaign() { campaign = null; }

    public void recordRangedHit() { rangedHits = Math.min(100000, rangedHits + 1); }
    public void recordMeleeHit() { meleeHits = Math.min(100000, meleeHits + 1); }
    public void recordShieldUse() { shieldUses = Math.min(100000, shieldUses + 1); }
    public boolean favorsRangedCombat() { return rangedHits >= 3 && rangedHits > meleeHits * 1.2; }
    public boolean favorsMeleeCombat() { return meleeHits >= 5 && meleeHits > rangedHits * 1.15; }
    public boolean oftenUsesShield() { return shieldUses >= 3; }

    public void recordTrapObservation(BlockPos pos, String dimension, long gameTime) {
        if (pos == null || dimension == null || dimension.isBlank()) return;
        long packed = pos.asLong();
        for (int i = trapMemories.size() - 1; i >= 0; i--) {
            TrapMemory old = trapMemories.get(i);
            if (old.pos() == packed && old.dimension().equals(dimension)) {
                trapMemories.set(i, new TrapMemory(packed, dimension, gameTime));
                return;
            }
        }
        trapMemories.add(new TrapMemory(packed, dimension, gameTime));
        while (trapMemories.size() > 24) trapMemories.remove(0);
    }

    public java.util.List<TrapMemory> recentTrapObservations(long now, String dimension, long maxAge) {
        java.util.ArrayList<TrapMemory> out = new java.util.ArrayList<>();
        for (int i = trapMemories.size() - 1; i >= 0; i--) {
            TrapMemory memory = trapMemories.get(i);
            long age = now - memory.gameTime();
            if (age >= 0 && age <= maxAge && memory.dimension().equals(dimension)) out.add(memory);
        }
        return out;
    }

    public void onHitManbear() {
        hits++;
        aggression = Math.min(1F, aggression + 0.2F);
        addRage(ManbearConfig.RAGE_HIT.get().floatValue());
    }

    /** Record a chase escape and evolve his strategy for this player. */
    public void recordChaseEscape(boolean sheltered, boolean ran, long gameTime) {
        recordChaseEscape(sheltered, ran, gameTime, base, "");
    }

    public void recordChaseEscape(boolean sheltered, boolean ran, long gameTime, BlockPos pos, String dimension) {
        escapedChases = Math.min(100000, escapedChases + 1);
        if (sheltered) shelterEscapes = Math.min(100000, shelterEscapes + 1);
        if (ran) runEscapes = Math.min(100000, runEscapes + 1);
        strategyStage = Math.min(6, escapedChases);
        panic = ema(panic, ran ? 1.0F : 0.55F, 0.18F);
        camping = ema(camping, sheltered ? 1.0F : 0.25F, 0.16F);
        recordEpisode("CHASE", "ESCAPED", sheltered ? "SHELTER" : (ran ? "DISTANCE" : "BROKEN_LINE_OF_SIGHT"),
                pos, dimension, gameTime);
    }

    /** Keep only a few brief, scrubbed fragments from in-game chat so the Manbear can remix them locally. */
    public void rememberSpeech(String raw) {
        if (raw == null) return;
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (raw.startsWith("/") || lower.contains("http://") || lower.contains("https://")
                || lower.contains("www.") || raw.contains("@") || raw.matches(".*\\d{7,}.*")) return;
        String cleaned = raw.replaceAll("[^A-Za-z' ]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 5 || cleaned.length() > 180) return;
        String[] tokens = cleaned.split(" ");
        java.util.ArrayList<String> useful = new java.util.ArrayList<>();
        java.util.Set<String> common = java.util.Set.of("the", "and", "but", "you", "your", "for", "are", "was", "were", "with", "that", "this", "have", "has", "had", "not", "what", "when", "where", "why", "how", "then", "than", "from", "into", "just", "like", "there", "here", "will", "would", "could", "should", "can", "cant", "dont", "does", "did", "its", "im", "ive", "they", "them", "their", "our", "out", "all", "who", "get", "got", "make", "made");
        for (String token : tokens) {
            String t = token.trim();
            if (t.length() < 3 || common.contains(t.toLowerCase(java.util.Locale.ROOT))) continue;
            useful.add(t);
            if (useful.size() == 4) break;
        }
        if (useful.size() < 2) return;
        String fragment = String.join(" ", useful);
        if (fragment.length() > 48) fragment = fragment.substring(0, 48).trim();
        for (String existing : speechFragments) if (existing.equalsIgnoreCase(fragment)) return;
        speechFragments.add(fragment);
        while (speechFragments.size() > 12) speechFragments.remove(0);
    }

    public String randomSpeechFragment(RandomSource random) {
        if (speechFragments.isEmpty()) return null;
        return speechFragments.get(random.nextInt(speechFragments.size()));
    }

    /** Called every 10 ticks for each survival player. */
    public void sample(ServerPlayer p, long gameTime, Entity manbear) {
        Vec3 pos = p.position();
        if (lastPos != null) {
            Vec3 d = pos.subtract(lastPos);
            velocity = new Vec3(d.x, 0, d.z);
            camping = ema(camping, d.lengthSqr() < 0.04 ? 1F : 0F, 0.004F);
            if (velocity.lengthSqr() > 0.01) heading = velocity.normalize();
        }
        lastPos = pos;
        updateLikelyGoal(p, gameTime);

        sprintiness = ema(sprintiness, p.isSprinting() ? 1F : 0F, 0.004F);
        sneakiness = ema(sneakiness, p.isShiftKeyDown() ? 1F : 0F, 0.004F);
        aggression = Math.max(0F, aggression - 0.0004F);

        if (manbear != null && manbear.level() == p.level() && manbear.distanceTo(p) < 64 && p.hasLineOfSight(manbear)) {
            Vec3 look = p.getViewVector(1F);
            Vec3 to = manbear.getEyePosition().subtract(p.getEyePosition()).normalize();
            vigilance = ema(vigilance, look.dot(to) > 0.8 ? 1F : 0F, 0.04F);
        }

        if (gameTime % 100 == 0) addHeat(p);
        if (base != null) {
            double bdx = pos.x - (base.getX() + 0.5);
            double bdz = pos.z - (base.getZ() + 0.5);
            if (bdx * bdx + bdz * bdz <= 32.0 * 32.0) basePresenceTicks = Math.min(3_000_000, basePresenceTicks + 10);
            else basePresenceTicks = Math.max(0, basePresenceTicks - 20);
        }

        // rage cools off, but staying in one place for ages feeds it
        if (ManbearConfig.RAGE_ENABLED.get()) {
            rage = Math.max(0F, rage - ManbearConfig.RAGE_DECAY_PER_MINUTE.get().floatValue() / 120F);
            if (areaAnchor == null || pos.distanceToSqr(areaAnchor) > 24 * 24) {
                areaAnchor = pos;
                areaTicks = 0;
            } else {
                areaTicks += 10;
                if (areaTicks > 6000) addRage(ManbearConfig.RAGE_CAMP_PER_MINUTE.get().floatValue() / 120F);
            }
        }
    }

    private void addHeat(ServerPlayer p) {
        long key = (((long) (p.getBlockX() >> 3)) << 32) | ((p.getBlockZ() >> 3) & 0xFFFFFFFFL);
        heat.merge(key, 1, Integer::sum);
        heatPos.put(key, p.blockPosition().asLong());
        if (heat.size() > 64) {
            long minKey = key;
            int min = Integer.MAX_VALUE;
            for (Map.Entry<Long, Integer> e : heat.entrySet()) {
                if (e.getValue() < min) { min = e.getValue(); minKey = e.getKey(); }
            }
            heat.remove(minKey);
            heatPos.remove(minKey);
        }
        int best = 0;
        long bestKey = key;
        for (Map.Entry<Long, Integer> e : heat.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); bestKey = e.getKey(); }
        }
        Long pos = heatPos.get(bestKey);
        if (best >= 6 && pos != null) base = BlockPos.of(pos);
    }

    /** Your most visited places, most visited first (needs roughly 20 s spent in a spot to count). */
    public java.util.List<BlockPos> hotspots() {
        java.util.List<Map.Entry<Long, Integer>> list = new java.util.ArrayList<>(heat.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (Map.Entry<Long, Integer> e : list) {
            Long pos = heatPos.get(e.getKey());
            if (pos != null && e.getValue() >= 4) out.add(BlockPos.of(pos));
        }
        return out;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putFloat("vig", vigilance); t.putFloat("pan", panic); t.putFloat("agg", aggression);
        t.putFloat("camp", camping); t.putFloat("sneak", sneakiness);
        t.putFloat("sprint", sprintiness);
        t.putFloat("rage", rage);
        t.putInt("hits", hits); t.putInt("traps", trapsSprung); t.putInt("deaths", deaths); t.putInt("chases", chases);
        t.putInt("escapedChases", escapedChases); t.putInt("shelterEscapes", shelterEscapes);
        t.putInt("runEscapes", runEscapes); t.putInt("strategyStage", strategyStage);
        t.putInt("rangedHits", rangedHits); t.putInt("meleeHits", meleeHits); t.putInt("shieldUses", shieldUses);
        t.putInt("totalScreams", totalScreams);
        t.putInt("activeScreamSignals", activeScreamSignals);
        t.putLong("lastScreamEventTime", lastScreamEventTime == Long.MIN_VALUE ? -1L : lastScreamEventTime);
        t.putLong("lastScreamDecayTime", lastScreamDecayTime);
        t.putFloat("investigatesCues", investigatesCues); t.putFloat("monitorsManbear", monitorsManbear);
        t.putFloat("followsManbear", followsManbear); t.putInt("theorySamples", theorySamples);
        t.putString("likelyGoal", likelyGoal.name()); t.putFloat("goalConfidence", goalConfidence);
        t.putLong("lastGoalEvaluation", lastGoalEvaluation);
        if (campaign != null) {
            CompoundTag c = new CompoundTag(); c.putString("type", campaign.type()); c.putLong("p", campaign.pos());
            c.putString("d", campaign.dimension()); c.putInt("stage", campaign.stage()); c.putLong("updated", campaign.updatedAt());
            t.put("campaign", c);
        }
        ListTag speech = new ListTag();
        for (String fragment : speechFragments) speech.add(StringTag.valueOf(fragment));
        t.put("speechFragments", speech);
        if (base != null) t.putLong("base", base.asLong());
        t.putBoolean("raid", raidPending); t.putLong("raidCd", raidCooldownUntil);
        t.putInt("basePresence", basePresenceTicks);
        ListTag list = new ListTag();
        for (Map.Entry<Long, Integer> e : heat.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("k", e.getKey()); c.putInt("v", e.getValue());
            Long hp = heatPos.get(e.getKey());
            if (hp != null) c.putLong("p", hp);
            list.add(c);
        }
        t.put("heat", list);
        ListTag actions = new ListTag();
        for (WorldMemory memory : worldMemories) {
            CompoundTag c = new CompoundTag();
            c.putLong("p", memory.pos()); c.putString("d", memory.dimension());
            c.putBoolean("placed", memory.placed()); c.putLong("t", memory.gameTime());
            actions.add(c);
        }
        t.put("worldActions", actions);
        ListTag traps = new ListTag();
        for (TrapMemory memory : trapMemories) {
            CompoundTag c = new CompoundTag();
            c.putLong("p", memory.pos()); c.putString("d", memory.dimension()); c.putLong("t", memory.gameTime());
            traps.add(c);
        }
        t.put("trapMemories", traps);
        ListTag episodic = new ListTag();
        for (EpisodeMemory episode : episodes) {
            CompoundTag c = new CompoundTag(); c.putString("kind", episode.kind()); c.putString("outcome", episode.outcome());
            c.putString("reason", episode.reason()); c.putLong("p", episode.pos()); c.putString("d", episode.dimension());
            c.putLong("t", episode.gameTime()); episodic.add(c);
        }
        t.put("episodes", episodic);
        ListTag routes = new ListTag();
        for (Map.Entry<Long, Float> e : routeBeliefs.entrySet()) {
            CompoundTag c = new CompoundTag(); c.putLong("k", e.getKey()); c.putFloat("v", e.getValue());
            c.putLong("t", routeBeliefTimes.getOrDefault(e.getKey(), 0L)); routes.add(c);
        }
        t.put("routeBeliefs", routes);
        ListTag scriptEvents = new ListTag();
        for (Map.Entry<String, Long> e : scriptedEventTimes.entrySet()) {
            CompoundTag c = new CompoundTag(); c.putString("name", e.getKey()); c.putLong("time", e.getValue());
            scriptEvents.add(c);
        }
        t.put("scriptedEventTimes", scriptEvents);
        t.putInt("houseRehearsalStage", houseRehearsalStage);
        t.putLong("houseRehearsalFocus", houseRehearsalFocus);
        t.putString("houseRehearsalDimension", houseRehearsalDimension == null ? "" : houseRehearsalDimension);
        if (unfinishedTask != null) {
            CompoundTag u = new CompoundTag(); u.putString("kind", unfinishedTask.kind()); u.putLong("p", unfinishedTask.pos());
            u.putString("d", unfinishedTask.dimension()); u.putLong("t", unfinishedTask.updatedAt());
            t.put("unfinishedTask", u);
        }
        return t;
    }

    public static PlayerProfile load(CompoundTag t) {
        PlayerProfile p = new PlayerProfile();
        p.vigilance = t.getFloat("vig"); p.panic = t.getFloat("pan"); p.aggression = t.getFloat("agg");
        p.camping = t.getFloat("camp"); p.sneakiness = t.getFloat("sneak");
        p.sprintiness = t.getFloat("sprint");
        p.rage = t.getFloat("rage");
        p.hits = t.getInt("hits"); p.trapsSprung = t.getInt("traps"); p.deaths = t.getInt("deaths"); p.chases = t.getInt("chases");
        p.escapedChases = t.getInt("escapedChases"); p.shelterEscapes = t.getInt("shelterEscapes");
        p.runEscapes = t.getInt("runEscapes"); p.strategyStage = Math.min(6, t.getInt("strategyStage"));
        p.rangedHits = Math.max(0, t.getInt("rangedHits")); p.meleeHits = Math.max(0, t.getInt("meleeHits"));
        p.shieldUses = Math.max(0, t.getInt("shieldUses"));
        p.totalScreams = Math.max(0, t.getInt("totalScreams"));
        p.activeScreamSignals = Math.max(0, Math.min(10, t.getInt("activeScreamSignals")));
        long savedScreamEvent = t.contains("lastScreamEventTime") ? t.getLong("lastScreamEventTime") : -1L;
        p.lastScreamEventTime = savedScreamEvent < 0L ? Long.MIN_VALUE : savedScreamEvent;
        p.lastScreamDecayTime = t.contains("lastScreamDecayTime") ? t.getLong("lastScreamDecayTime") : -1L;
        p.investigatesCues = Math.max(0.05F, Math.min(0.95F, t.contains("investigatesCues") ? t.getFloat("investigatesCues") : 0.5F));
        p.monitorsManbear = Math.max(0.05F, Math.min(0.95F, t.contains("monitorsManbear") ? t.getFloat("monitorsManbear") : 0.5F));
        p.followsManbear = Math.max(0.05F, Math.min(0.95F, t.contains("followsManbear") ? t.getFloat("followsManbear") : 0.5F));
        p.theorySamples = Math.max(0, t.getInt("theorySamples"));
        try { p.likelyGoal = LikelyGoal.valueOf(t.getString("likelyGoal")); } catch (Exception ignored) { p.likelyGoal = LikelyGoal.UNKNOWN; }
        p.goalConfidence = Math.max(0.0F, Math.min(1.0F, t.getFloat("goalConfidence")));
        p.lastGoalEvaluation = t.contains("lastGoalEvaluation") ? t.getLong("lastGoalEvaluation") : Long.MIN_VALUE;
        if (t.contains("campaign", Tag.TAG_COMPOUND)) {
            CompoundTag c = t.getCompound("campaign");
            String type = c.getString("type"), dimension = c.getString("d");
            if (!type.isBlank() && !dimension.isBlank()) p.campaign = new CampaignMemory(type, c.getLong("p"), dimension,
                    Math.max(1, Math.min(6, c.getInt("stage"))), c.getLong("updated"));
        }
        ListTag speech = t.getList("speechFragments", Tag.TAG_STRING);
        for (int i = 0; i < speech.size() && p.speechFragments.size() < 12; i++) {
            String fragment = speech.getString(i);
            if (!fragment.isBlank() && fragment.length() <= 48) p.speechFragments.add(fragment);
        }
        if (t.contains("base")) p.base = BlockPos.of(t.getLong("base"));
        p.raidPending = t.getBoolean("raid"); p.raidCooldownUntil = t.getLong("raidCd");
        p.basePresenceTicks = t.getInt("basePresence");
        ListTag list = t.getList("heat", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            p.heat.put(c.getLong("k"), c.getInt("v"));
            if (c.contains("p")) p.heatPos.put(c.getLong("k"), c.getLong("p"));
        }
        ListTag actions = t.getList("worldActions", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, actions.size() - 12); i < actions.size(); i++) {
            CompoundTag c = actions.getCompound(i);
            String dim = c.getString("d");
            if (!dim.isBlank()) p.worldMemories.add(new WorldMemory(c.getLong("p"), dim, c.getBoolean("placed"), c.getLong("t")));
        }
        ListTag trapList = t.getList("trapMemories", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, trapList.size() - 24); i < trapList.size(); i++) {
            CompoundTag c = trapList.getCompound(i);
            String dim = c.getString("d");
            if (!dim.isBlank()) p.trapMemories.add(new TrapMemory(c.getLong("p"), dim, c.getLong("t")));
        }
        ListTag episodic = t.getList("episodes", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, episodic.size() - 24); i < episodic.size(); i++) {
            CompoundTag c = episodic.getCompound(i);
            String kind = c.getString("kind"), outcome = c.getString("outcome");
            if (!kind.isBlank() && !outcome.isBlank()) p.episodes.add(new EpisodeMemory(kind, outcome, c.getString("reason"),
                    c.getLong("p"), c.getString("d"), c.getLong("t")));
        }
        ListTag routes = t.getList("routeBeliefs", Tag.TAG_COMPOUND);
        for (int i = 0; i < routes.size(); i++) {
            CompoundTag c = routes.getCompound(i);
            p.routeBeliefs.put(c.getLong("k"), Math.max(0.05F, Math.min(0.95F, c.getFloat("v"))));
            if (c.contains("t")) p.routeBeliefTimes.put(c.getLong("k"), c.getLong("t"));
        }
        ListTag scriptEvents = t.getList("scriptedEventTimes", Tag.TAG_COMPOUND);
        for (int i = 0; i < scriptEvents.size(); i++) {
            CompoundTag c = scriptEvents.getCompound(i); String name = c.getString("name");
            if (!name.isBlank()) p.scriptedEventTimes.put(name, c.getLong("time"));
        }
        p.houseRehearsalStage = Math.max(0, Math.min(2, t.getInt("houseRehearsalStage")));
        p.houseRehearsalFocus = t.getLong("houseRehearsalFocus");
        p.houseRehearsalDimension = t.getString("houseRehearsalDimension");
        if (t.contains("unfinishedTask", Tag.TAG_COMPOUND)) {
            CompoundTag u = t.getCompound("unfinishedTask");
            if (!u.getString("kind").isBlank() && !u.getString("d").isBlank())
                p.unfinishedTask = new UnfinishedTask(u.getString("kind"), u.getLong("p"), u.getString("d"), u.getLong("t"));
        }
        return p;
    }

    public String describe() {
        return String.format("RAGE=%.1f vigilance=%.2f panic=%.2f aggression=%.2f camping=%.2f sneaky=%.2f sprint=%.2f | hits=%d traps=%d chases=%d deaths=%d",
                rage, vigilance, panic, aggression, camping, sneakiness, sprintiness, hits, trapsSprung, chases, deaths)
                + String.format(" | escaped=%d shelter=%d run=%d strategy=%d rememberedFragments=%d loudVocalEvents=%d active=%d", escapedChases, shelterEscapes, runEscapes, strategyStage, speechFragments.size(), totalScreams, activeScreamSignals)
                + String.format(" | goal=%s confidence=%.2f episodes=%d campaign=%s theory=%s", likelyGoal, goalConfidence, episodes.size(), campaign == null ? "none" : campaign.type() + ":" + campaign.stage(), theorySummary())
                + String.format(" scriptedEvents=%d houseStage=%d unfinished=%s", scriptedEventTimes.size(), houseRehearsalStage, unfinishedTask == null ? "none" : unfinishedTask.kind());
    }
}
