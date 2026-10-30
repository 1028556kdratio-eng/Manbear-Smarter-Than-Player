package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.data.PlayerProfile;
import com.manbear.data.Pit;
import com.manbear.entity.CompanionBird;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import com.manbear.entity.Stance;
import com.manbear.registry.ModBlocks;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/**
 * The decision maker. He is always in exactly one mode, but can switch at any moment:
 * stalk -> peek -> fake chase -> real chase and back, with weights shaped by what he has
 * learned about the player (see PlayerProfile).
 */
public class ManbearBrain {
    private enum Lunge { NONE, WINDUP, AIR, RECOVER }
    /** Long-running intentions. These are deliberately different from encounter modes. */
    private enum MindPlan { NONE, INVESTIGATE_CHANGE, INVESTIGATE_BAIT, TEST_ROUTE, INSPECT_TRAP, DECEPTION, OBSERVE_GOAL, OPPORTUNITY, RITUAL, DISTRACTION, HOUSE_REHEARSAL, CONTRADICTORY_TRACE, WATCH_TEST, WILDLIFE_INSTRUMENT, PRIVATE_PROJECT, FALSE_WEAKNESS, BAITED_REWARD, DIVIDED_ATTENTION, HIDDEN_OBJECTIVE, HIDDEN_ANSWER_TEST }

    private final ManbearEntity m;
    private final BlockBreaker breaker = new BlockBreaker();

    private ManbearMode mode = ManbearMode.STALK;
    private int modeTime;
    private int modeDuration = 600;

    private ServerPlayer target;
    private int targetLock;
    private int ticksAlive;
    private int noTargetTicks;
    private int lastChaseEnd;
    private double chaseStartDistance;

    // awareness
    private double dist;
    private boolean los;
    private boolean looking;
    private int lookedTicks;

    // learned traits (0..1)
    private PlayerProfile prof;
    private float vig = 0.5F, panic = 0.5F, aggr = 0F, camp = 0F, sprint = 0.3F;

    // movement
    private int pathCd;
    private boolean navFail;
    private int navFailCount;
    private Vec3 lastPos = Vec3.ZERO;
    private int stuckTicks;
    private int stanceLock;
    private int unreachableTicks;

    // stalk
    private int orbitDir = 1;
    private int orbitFlip = 200;
    private double stalkRadius = 18;
    private int freezeTicks;

    // hide / peek
    private CoverFinder.Cover cover;
    private boolean atHide;
    private int waitTicks;
    private int peekPhase;
    private int peekTimer;
    private int peeksLeft;

    // fake chase
    private int fakePhase;
    private double fakeStop;
    private int fakeFreeze;
    private int fakeHitsLanded;
    private int fakeHitsTarget = 2;

    // chase unpredictability: one possible silent pause, rush burst and teleport per encounter
    private boolean chasePauseAbilityArmed;
    private int chasePauseTicks;
    private double chasePauseStartDistance;
    private double chasePauseClosestDistance;
    private int rushBurstTicks;
    private int fakePauseExtensionTicks;
    private boolean chaseTeleportAbilityArmed;

    // persistent den-follow event
    private boolean returningToDen;
    private BlockPos returnDenEntrance;
    private double returnStartDistance;
    private int returnTicks;

    // chase
    private int chaseTicks;
    private int lungeCd;
    private int meleeCd;
    private int pendingMelee;
    private int hitsLanded;
    private Lunge lunge = Lunge.NONE;
    private int lungeTimer;
    private boolean lungeHit;
    private boolean ambushing;

    // traps
    private BlockPos trapSite;
    private int trapPhase;
    private int trapTimer;
    private int trapCooldown = 700;

    // ambience
    private int ambientCd = 200;
    private int coverFailCd;
    private int mimicCd = 300;
    private int chatEchoCd = 300;
    private String lastChatEchoLine = "";
    private int leavingTicks;
    private int peekSide;
    private boolean started;
    private float noise;
    private int roarsUsed;
    private int roarCd;
    private int stunTicks;
    private ServerPlayer stunTarget;
    private int breathCd = 100;
    private BlockPos lastHide;
    private int trickCd = 400;
    private final java.util.List<Pending> pending = new java.util.ArrayList<>();

    private record Pending(int at, SoundEvent ev, SoundSource src, double x, double y, double z, float vol, float pitch) { }
    private int snoreCd = 60;
    private int rushTicks;
    private LivingEntity carried;     // the kidnapped pet riding on his back
    private LivingEntity petTarget;
    private int petPhase;
    private int petTimer;
    private BlockPos dropSpot;
    private boolean petToDen;
    private int petDenWaypoint;
    private int petDenStuck;
    private int petDenRetries;
    private int petDenTotalStuck;
    private BlockPos petTravelWaypoint;
    private int manStunTicks;     // HE is stunned (axe hit)
    private int manStunCd;
    private int distractTicks;
    private int distractCd;
    private Entity distractAnimal;
    private float rageF;          // his rage towards the current target, 0..1
    private boolean ducking;
    private int voiceCd = 600;
    private int pathTrigger = 8;
    private double fogDist = -1;
    private boolean caveFleeing;
    private boolean ambushOnly;
    private boolean lethalGrab;     // the intimidation grab: no escape
    private int unstickStep;
    private int lurkSeen;
    // grab
    private ServerPlayer held;
    private float grabProgress;
    private int grabTicks;
    private int grabCd;
    private boolean pounce;
    private boolean lureStarted;
    private boolean lureDone;
    private int lurePhase;
    private int lureTimer;
    private int lureStare;
    private UUID animalId;
    private SoundEvent lureCall = SoundEvents.WOLF_WHINE;
    private boolean lureTrapVariant;
    private BlockPos baitSpot;
    private int lastChaseTick = -100000;

    // ================================================================= independent mind / long-term intention layer
    // This is separate from the chase state machine: plans survive interruptions and can resume later.
    private MindPlan mindPlan = MindPlan.NONE;
    private MindPlan mindResumePlan = MindPlan.NONE;
    private BlockPos mindGoal;
    private BlockPos mindLookAt;
    private BlockPos mindResumeGoal;
    private BlockPos mindResumeLookAt;
    private BlockPos mindLastInvestigated;
    private BlockPos mindRitualAnchor;
    private UUID mindPlayerId;
    private UUID mindAnimalId;
    private UUID mindBaitId;
    private String mindPlanDimension = "";
    private long mindLastInvestigatedAt = Long.MIN_VALUE;
    private long mindLastRitualAt = Long.MIN_VALUE;
    private int mindPlanAge;
    private int mindPlanWait;
    private int mindPlanCooldown;
    private int mindStuckTicks;
    private int mindRouteObservedTicks;
    private int mindWaypointRetries;
    private Vec3 mindLastPosition = Vec3.ZERO;
    private boolean mindInitialized;
    private boolean mindResumePending;
    private boolean mindRouteObserved;
    // Evidence-based perception: the stalking brain acts on last-known clues, not exact hidden-player coordinates.
    private Vec3 mindPerceivedPlayerPos;
    private long mindPerceivedAt = Long.MIN_VALUE;
    private long mindLastWorldEvidenceAt = Long.MIN_VALUE;
    private long mindLastWorldEvidencePos = Long.MIN_VALUE;
    private float mindPerceptionConfidence;
    private int mindDeceptionObservedTicks;
    private boolean mindDeceptionObserved;
    private int mindDecoyCooldown;
    private boolean mindScriptCuePlayed;
    private int mindWatchLostTicks;
    private int mindWatchMoves;
    // Manipulation director state. Every event is staged, interruptible, and records what the player actually chose.
    private int manipulationStage;
    private int manipulationTimer;
    private int manipulationChoiceScore;
    private int manipulationChoice;
    private boolean manipulationSawReaction;
    private BlockPos manipulationSecondaryFocus;
    private UUID manipulationBaitId;
    private String manipulationBaitItemId = "";
    // A deliberate release is a short, interruptible pause during a real chase, not a guaranteed escape.
    private int deliberateEscapeTicks;
    private double deliberateEscapeStartDistance;
    private int mindLastTraceAt = -10000;
    // Learned concealment: he waits for a gap in observation and walks to real cover rather than teleporting.
    private BlockPos concealmentGoal;
    private int concealmentPhase;
    private int concealmentWait;
    private int concealmentWatchTicks;
    private int concealmentCooldown;
    private long lastConcealmentAt = Long.MIN_VALUE;
    private String concealmentDimension = "";
    // Mechanisms are remembered only after he has come close enough to inspect them; only simple vanilla controls are used.
    private final List<Long> mindKnownObjectPositions = new ArrayList<>();
    private String mindKnownObjectsDimension = "";
    private BlockPos objectUseGoal;
    private int objectUsePhase;
    private int objectUseWait;
    private int objectUseCooldown;
    private String objectUseDimension = "";
    // Temporary injuries affect decisions and speed, but recover with time and rest.
    private int mindInjuryLevel;
    private int mindInjuryTicks;
    private long mindLastInjuryAt = Long.MIN_VALUE;
    private int opportunityCostCooldown;
    // The Manbear can stage a wildlife distraction without harming or taking control of tamed animals.
    // The Manbear develops needs and preferences instead of selecting behavior solely from event rolls.
    private float mindFatigue = 0.12F;
    private float mindHunger = 0.24F;
    private float mindTerritoryStress = 0.10F;
    private String mindPreferredItemId = "";
    private String mindLastBaitItemId = "";
    private long mindLastNeedTick = Long.MIN_VALUE;
    private long lastShieldObservationAt = Long.MIN_VALUE;
    private long lastEnvironmentReactionAt = Long.MIN_VALUE;
    private final List<Long> mindKnownTrapPositions = new ArrayList<>();
    private String mindKnownTrapDimension = "";
    // Failed approaches are remembered across chunk reloads, so he can try a genuinely different route later.
    private final List<Long> mindFailedApproaches = new ArrayList<>();
    // Per-entity temperament. These are saved, not re-rolled on chunk reload.
    private float mindCuriosity = 0.5F;
    private float mindPatience = 0.5F;
    private float mindTerritoriality = 0.5F;
    private float mindDistractibility = 0.5F;
    private float mindRitualism = 0.5F;
    private float mindCaution = 0.5F;
    // pit lure ("he waits behind you; you notice him; he runs off; you follow; you fall")
    private int pitPhase;                 // 0 behind you, 1 running off, 2 waiting beyond the pit
    private Pit pit;
    private BlockPos pitFar;
    private int pitTimer;
    private int pitWait;
    private boolean pitChase;             // you are in the hole: he attacks down into it from the edge
    // kidnapping ("he sneaks up on you, carries you to the nearest cave")
    private static final int KID_SNEAK = 0, KID_BACKOFF = 1, KID_CARRY = 2, KID_FADE = 3,
            KID_DEN_CARRY = 4, KID_DEN_GUARD = 5, KID_DEN_SLEEP = 6;
    private int kidPhase;
    private int kidSeen;
    private int kidAttempts;
    private int kidTimer;
    private int kidCount;                 // space-bar presses so far
    private int kidPresses = 50;          // presses needed (50-100 depending on rage)
    private int kidMinTicks;              // the escape window: he never gets there before you could have mashed free
    private int kidFadeTimer;
    private int kidStuck;
    private BlockPos kidCave;
    private BlockPos kidTravelWaypoint;
    private int kidRouteAttempts;
    private boolean kidnapToDen;
    private int denGuardWaitTicks;
    private int denCarryWaypoint;
    private int denCarryStuck;
    /** Persists the identity of the kidnapped player after the carry ends, so target reacquisition cannot drop the guard. */
    private UUID denCaptiveId;
    private double kidSpeed = 1.6;
    // continuous chase voice
    private int roarLoopCd;
    // hallucination
    private boolean hallSpawned;
    private java.util.UUID doppelId;
    private int hallTimer;
    private int hallGone;
    private boolean hallTreeVariant;
    // evidence: did you ever notice him, and where has he been
    private boolean noticed;
    private int noticeTicks;
    private boolean leaveFromWatch;
    private boolean noEvidence;
    private boolean destroyed;
    private final java.util.List<Vec3> trail = new java.util.ArrayList<>();
    private Vec3 lastTrail;
    private ManbearMode prevMode = ManbearMode.STALK;
    // fake death
    private int chaseHits;                // your hits in this chase
    private int fakeDeathAt = 7;          // ...WEAPON hits after which he plays dead (rolled 5-10 for every chase)
    private boolean hitWithWeapon;        // was the hit being processed made with a weapon?
    private int deadTicks;
    private int deadAway;

    public ManbearBrain(ManbearEntity m) { this.m = m; }

    private RandomSource rnd() { return m.getRandom(); }

    /** real chases only happen on "chaser" spawns, and never in the first few seconds */
    private boolean canChase() {
        // an angry Manbear will chase even if this spawn was not "a chaser", and needs less time to work up to it
        return (m.isChaser() || rageF >= 0.55F)
                && ticksAlive >= (int) (ManbearConfig.GRACE_SECONDS.get() * 20 * (1F - 0.7F * rageF));
    }

    // an angry Manbear is BOLDER: he works closer to you
    /**
     * His time is up, whatever he is doing (chase, grab, sleeping...). He only disappears while nobody can see him;
     * if someone can, he bolts for cover at full speed and is removed the instant he is out of sight.
     */
    private void expire(ServerLevel level) {
        if (!visibleToAnyone(level)) {
            m.discard();
            return;
        }
        if (!m.leaving) {
            caveFleeing = true;
            beginLeaving(level);
        }
    }

    private boolean visibleToAnyone(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive() || p.distanceToSqr(m) > 200.0 * 200.0) continue;
            Vec3 eye = p.getEyePosition();
            Vec3 to = m.getEyePosition().subtract(eye).normalize();
            if (p.getLookAngle().dot(to) < 0.1) continue;      // not looking his way
            if (CoverFinder.lineClear(level, eye, m.getEyePosition(), p)
                    || CoverFinder.lineClear(level, eye, m.position().add(0, 0.5, 0), p)) return true;
        }
        return false;
    }

    private int hideMin() { return (int) (ManbearConfig.STALK_MIN_DISTANCE.get() * (1F - 0.45F * rageF)); }
    private int hideMax() { return Math.max(hideMin() + 6, (int) ((ManbearConfig.STALK_MAX_DISTANCE.get() + 4) * (1F - 0.45F * rageF))); }

    /** being near him while he stalks, hides or peeks enrages him */
    private void closeRage() {
        if (prof == null || !(mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK)) return;
        float add = ManbearConfig.RAGE_CLOSE_PER_SECOND.get().floatValue();
        if (dist < 6) prof.addRage(add * 2F);
        else if (dist < 12) prof.addRage(add);
    }

    public ManbearMode mode() { return mode; }

    public String debugString() {
        return "chaser=" + m.isChaser() + " mode=" + mode + " t=" + modeTime + "/" + modeDuration + " dist=" + (int) dist
                + " looking=" + looking + " vig=" + String.format("%.2f", vig) + " panic=" + String.format("%.2f", panic)
                + " trapCd=" + trapCooldown + " mind=" + mindPlan + "/" + mindTemperament()
                + " belief=" + String.format("%.2f", mindPerceptionConfidence) + " failedRoutes=" + mindFailedApproaches.size()
                + " needs=" + String.format("F%.2f/H%.2f/T%.2f", mindFatigue, mindHunger, mindTerritoryStress)
                + " goal=" + (prof == null ? "unknown" : prof.likelyGoal()) + "/" + (prof == null ? "0" : String.format("%.2f", prof.goalConfidence()))
                + " episodes=" + (prof == null ? 0 : prof.countEpisodes(null, null, m.level().getGameTime(), 24000L))
                + " knownTraps=" + mindKnownTrapPositions.size() + " knownObjects=" + mindKnownObjectPositions.size()
                + " injury=" + mindInjuryLevel + "/" + mindInjuryTicks + " conceal=" + concealmentPhase
                + " preferredItem=" + (mindPreferredItemId.isBlank() ? "none" : mindPreferredItemId)
                + " target=" + (target == null ? "none" : target.getGameProfile().getName());
    }

    // ================================================================= main tick
    public void tick(ServerLevel level) {
        ticksAlive++;
        modeTime++;
        if (trapCooldown > 0) trapCooldown--;
        if (lungeCd > 0) lungeCd--;
        if (meleeCd > 0) meleeCd--;
        if (grabCd > 0) grabCd--;
        if (manStunCd > 0) manStunCd--;
        if (distractCd > 0) distractCd--;
        if (stanceLock > 0) stanceLock--;
        if (ambientCd > 0) ambientCd--;
        if (coverFailCd > 0) coverFailCd--;
        if (targetLock > 0) targetLock--;
        if (roarCd > 0) roarCd--;
        if (mindDecoyCooldown > 0) mindDecoyCooldown--;
        if (concealmentCooldown > 0) concealmentCooldown--;
        if (objectUseCooldown > 0) objectUseCooldown--;
        if (opportunityCostCooldown > 0) opportunityCostCooldown--;
        if (!started) {
            started = true;
            if (m.isSleeper()) setMode(level, ManbearMode.SLEEP);
            else if (m.isBehind()) setMode(level, ManbearMode.BEHIND);
            else if (m.isPathBlocker()) setMode(level, ManbearMode.PATH);
            else if (m.isFogger()) setMode(level, ManbearMode.FOG);
            else if (m.isCaver()) setMode(level, ManbearMode.CAVE);
            else if (m.isLurker()) setMode(level, ManbearMode.LURKER);
            else if (m.isPetnapper()) setMode(level, ManbearMode.PETNAP);
            else if (m.isFlash()) setMode(level, ManbearMode.FLASH);
            else if (m.isIntimidator()) setMode(level, ManbearMode.INTIMIDATE);
            else if (m.isPitLurer()) setMode(level, ManbearMode.PITLURE);
            else if (m.isKidnapper()) setMode(level, ManbearMode.KIDNAP);
            else if (m.isHallucinator()) setMode(level, ManbearMode.HALLUCINATE);
        }
        if (!mindInitialized) initializeMind(level);
        if (ticksAlive % 20 == 0) {
            tickMindNeeds(level);
            tickWoundRecovery(level);
            tickEnvironmentalReaction(level);
        }
        tickStun();

        if (mimicCd > 0) mimicCd--;
        // he never lingers: after his lifespan he fades away (after a chase in progress, at most 40 s later)
        if (mode == ManbearMode.FLASH && m.lifeTicks >= 24) { m.discard(); return; }
        boolean protectingDenCaptive = denCaptiveId != null && mode == ManbearMode.SLEEP && kidPhase == KID_DEN_SLEEP;
        boolean carryingPetToDen = mode == ManbearMode.PETNAP && petPhase >= 2 && petToDen && carried != null;
        if (m.lifeTicks > m.lifespan() && !(mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY)
                && !protectingDenCaptive && !carryingPetToDen) expire(level);   // do not expire mid-escort
        if (m.leaving) leavingTicks++;

        resolveTarget(level);
        restoreDenCaptiveToRoom(level);
        if (denCaptiveId != null && ((mode == ManbearMode.KIDNAP && kidPhase >= KID_DEN_GUARD)
                || (mode == ManbearMode.SLEEP && kidPhase == KID_DEN_SLEEP))) {
            ManbearData denData = ManbearData.get(level.getServer());
            if (denData.hasDen() && denData.denDimension().equals(level.dimension().location().toString())) {
                denData.setDenGuardian(m.getUUID()); // migrate guards saved by older versions into the persistent slot
            }
        }
        if (mode == ManbearMode.SLEEP) { tickSleep(level); return; }
        if (target == null) {
            if (tickIndependentMind(level, false)) { updateClimb(); return; }
            // Carrying and den-delivery are driven by the captive, not the normal chase target selector.
            // Do not let a long route with no resolved target discard the Manbear halfway to the den.
            if (mode == ManbearMode.KIDNAP && (held != null || kidPhase >= KID_DEN_GUARD)) {
                noTargetTicks = 0;
                tickKidnap(level);
                return;
            }
            if (mode == ManbearMode.PETNAP && petPhase >= 2 && carried != null) {
                noTargetTicks = 0;
                tickPetnap(level); // finish the pet escort even after the owner leaves tracking range
                return;
            }
            if (returningToDen) {
                m.setFaceTarget(null, 0F);
                tickReturnToDen(level);
                return;
            }
            m.setFaceTarget(null, 0F);
            m.getNavigation().stop();
            m.setClimbing(false);
            if (m.leaving || ++noTargetTicks > 1200) m.discard();
            return;
        }
        noTargetTicks = 0;
        if (ticksAlive % 20 == 1 || prof == null) refreshTraits(level);
        sense(level);
        if (ManbearConfig.PLAYER_THEORY_ENABLED.get() && ticksAlive % 40 == 0 && los && dist > 5.0D && dist < 42.0D) {
            PlayerProfile theory = ManbearData.get(level.getServer()).profile(target.getUUID());
            theory.observeWatching(looking);
            ManbearData.get(level.getServer()).setDirty();
        }
        if (ManbearConfig.COMBAT_ADAPTATION_ENABLED.get() && target.isBlocking()
                && dist < 9.0D && ticksAlive - lastShieldObservationAt >= 60) {
            ManbearData.get(level.getServer()).profile(target.getUUID()).recordShieldUse();
            ManbearData.get(level.getServer()).setDirty();
            lastShieldObservationAt = ticksAlive;
        }
        if (looking) {
            if (++noticeTicks >= 6) noticed = true;      // you really looked at him
            if (noticeTicks == 6 && isWatch(mode) && !returningToDen) {
                float returnChance = ManbearConfig.DEN_RETURN_CHANCE.get().floatValue();
                if (ManbearConfig.PLAYER_THEORY_ENABLED.get() && target != null) {
                    PlayerProfile theory = ManbearData.get(level.getServer()).profile(target.getUUID());
                    // If this player has followed before, the retreat becomes a more tempting lure.
                    returnChance *= 0.62F + 0.90F * theory.followsManbear;
                }
                if (rnd().nextFloat() < Mth.clamp(returnChance, 0.0F, 0.95F)) startReturnToDen(level);
            }
        } else noticeTicks = 0;
        if (ticksAlive % 10 == 0 && m.onGround()) {                                       // remember the way he walked (for footprints)
            Vec3 here = m.position();
            if (lastTrail == null || here.distanceToSqr(lastTrail) > 2.5) {
                trail.add(here);
                lastTrail = here;
                if (trail.size() > 14) trail.remove(0);
            }
        }
        if (ticksAlive % 20 == 0) closeRage();
        if (!lureStarted && m.isLurer() && mode == ManbearMode.STALK) {
            lureStarted = true;
            setMode(level, ManbearMode.LURE);
        }
        m.setFaceTarget(target, mode.isChaseLike() ? 38F : 16F);
        if (mode == ManbearMode.CAVE && !caveFleeing) m.setFaceTarget(null, 0F);   // sits with his back to you
        // running off (pit lure) or carrying you away: he looks where he is going, not at you
        if ((mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) || (mode == ManbearMode.PITLURE && pitPhase == 1)) m.setFaceTarget(null, 0F);
        if (mode == ManbearMode.PLAYDEAD) m.setFaceTarget(null, 0F);                     // lies where he fell
        updateStance(level);
        if (returningToDen) { tickReturnToDen(level); return; }
        if (mode == ManbearMode.PLAYDEAD) { tickPlayDead(level); return; }
        if ((mode == ManbearMode.FAKE_CHASE || mode == ManbearMode.CHASE) && chasePauseTicks <= 0) tryStartChasePause(level);
        if (chasePauseTicks > 0) { tickChasePause(level); return; }
        ambient();
        chaseVoice();
        mimicAnimal();
        breathe();
        tickTricks(level);
        voiceMimic();
        chatEcho();

        if (manStunTicks > 0) {   // axe stun: he can do nothing
            manStunTicks--;
            m.getNavigation().stop();
            m.setClimbing(false);
            lunge = Lunge.NONE;
            pounce = false;
            pendingMelee = 0;
            return;
        }
        if (tickIndependentMind(level, true)) { updateClimb(); return; }
        if (tickStrategicConcealment(level)) { updateClimb(); return; }
        if (tickLearnedObjectUse(level)) { updateClimb(); return; }
        switch (mode) {
            case STALK -> tickStalk(level);
            case HIDE -> tickHide(level);
            case PEEK -> tickPeek(level);
            case FAKE_CHASE -> tickFake(level);
            case CHASE, AMBUSH -> tickChase(level);
            case LAY_TRAP -> tickTrap(level);
            case RETREAT -> tickRetreat(level);
            case BEHIND -> tickBehind(level);
            case LURE -> tickLure(level);
            case PATH -> tickPath(level);
            case FOG -> tickFog(level);
            case CAVE -> tickCave(level);
            case GRAB -> tickGrab(level);
            case LURKER -> tickLurker(level);
            case PETNAP -> tickPetnap(level);
            case INTIMIDATE -> tickIntimidate(level);
            case PITLURE -> tickPitLure(level);
            case KIDNAP -> tickKidnap(level);
            case HALLUCINATE -> tickHallucinate(level);
            case FLASH -> { m.getNavigation().stop(); if (m.lifeTicks >= 24) m.discard(); }
            default -> { }
        }

        updateClimb();
        spontaneous(level);
        if (modeTime >= modeDuration) onModeExpired(level);
    }

    // ================================================================= independent mind / plans
    private boolean isMindCalmMode() {
        return mode == ManbearMode.STALK;
    }

    private String mindTemperament() {
        if (mindTerritoriality > 0.72F) return "territorial";
        if (mindPatience > 0.72F && mindCaution > 0.58F) return "patient";
        if (mindCuriosity > 0.72F) return "curious";
        if (mindDistractibility > 0.72F) return "easily-distracted";
        return "watchful";
    }

    private void initializeMind(ServerLevel level) {
        mindInitialized = true;
        RandomSource r = RandomSource.create(m.getUUID().getMostSignificantBits() ^ m.getUUID().getLeastSignificantBits());
        mindCuriosity = 0.22F + r.nextFloat() * 0.76F;
        mindPatience = 0.20F + r.nextFloat() * 0.78F;
        mindTerritoriality = 0.20F + r.nextFloat() * 0.78F;
        mindDistractibility = 0.12F + r.nextFloat() * 0.82F;
        mindRitualism = 0.10F + r.nextFloat() * 0.86F;
        mindCaution = 0.20F + r.nextFloat() * 0.78F;
        mindLastRitualAt = level.getGameTime();
        mindFatigue = 0.08F + r.nextFloat() * 0.24F;
        mindHunger = 0.12F + r.nextFloat() * 0.28F;
        mindTerritoryStress = 0.04F + r.nextFloat() * 0.18F;
        mindPlanCooldown = 500 + r.nextInt(900);
        int ox = (r.nextBoolean() ? 1 : -1) * (12 + r.nextInt(21));
        int oz = (r.nextBoolean() ? 1 : -1) * (12 + r.nextInt(21));
        BlockPos anchor = CoverFinder.groundNear(level, m.getX() + ox, m.getZ() + oz, m.getBlockY(), 3);
        mindRitualAnchor = anchor == null ? m.blockPosition().immutable() : anchor.immutable();
    }

    /** Slowly changing internal drives influence the choices the Manbear makes; they never expose exact player data. */
    private void tickMindNeeds(ServerLevel level) {
        if (!ManbearConfig.MIND_NEEDS_ENABLED.get()) return;
        long now = level.getGameTime();
        if (mindLastNeedTick == now) return;
        mindLastNeedTick = now;
        float fatigueGain = 0.00045F;
        if (mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE || mode == ManbearMode.AMBUSH
                || mode == ManbearMode.PETNAP || mode == ManbearMode.KIDNAP) fatigueGain = 0.0017F;
        else if (mode == ManbearMode.RETREAT || returningToDen || mode == ManbearMode.HIDE) fatigueGain = 0.0001F;
        mindFatigue = Mth.clamp(mindFatigue + fatigueGain, 0.0F, 1.0F);
        mindHunger = Mth.clamp(mindHunger + 0.00055F, 0.0F, 1.0F);

        ManbearData data = level.getServer() == null ? null : ManbearData.get(level.getServer());
        if (mode == ManbearMode.SLEEP) mindFatigue = Math.max(0.0F, mindFatigue - 0.018F);
        if (data != null && data.hasDen() && data.denDimension().equals(level.dimension().location().toString())) {
            double denDistance = m.blockPosition().distSqr(data.denCenter());
            if (denDistance < 18 * 18 && (mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK || mode == ManbearMode.SLEEP)) {
                mindFatigue = Math.max(0.0F, mindFatigue - 0.003F);
                mindTerritoryStress = Math.max(0.0F, mindTerritoryStress - 0.006F);
            } else if (denDistance < 36 * 36) {
                mindTerritoryStress = Math.max(0.0F, mindTerritoryStress - 0.001F);
            }
        } else if (data != null && data.denSpawnBlocked(now)) {
            // A recently destroyed den leaves him restless; the drive fades rather than forcing constant attacks.
            mindTerritoryStress = Mth.clamp(mindTerritoryStress + 0.0015F, 0.0F, 1.0F);
        }
        if (mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH || mode == ManbearMode.FAKE_CHASE) {
            mindTerritoryStress = Mth.clamp(mindTerritoryStress + 0.0008F, 0.0F, 1.0F);
        } else if (mindPlan == MindPlan.RITUAL || mode == ManbearMode.SLEEP) {
            mindTerritoryStress = Math.max(0.0F, mindTerritoryStress - 0.002F);
        }
    }

    /** Wounds recover independently from the optional hunger/fatigue needs system. */
    private void tickWoundRecovery(ServerLevel level) {
        if (!ManbearConfig.WOUND_MEMORY_ENABLED.get() || mindInjuryLevel <= 0) return;
        ManbearData data = level.getServer() == null ? null : ManbearData.get(level.getServer());
        int recovery = mode == ManbearMode.SLEEP ? 80 : 20;
        if (data != null && data.hasDen() && data.denDimension().equals(level.dimension().location().toString())
                && m.blockPosition().distSqr(data.denCenter()) < 18L * 18L
                && (mode == ManbearMode.SLEEP || mode == ManbearMode.STALK)) recovery += 40;
        mindInjuryTicks = Math.max(0, mindInjuryTicks - recovery);
        if (mindInjuryTicks == 0) {
            mindInjuryLevel = Math.max(0, mindInjuryLevel - 1);
            if (mindInjuryLevel > 0) mindInjuryTicks = 500;
            if (mindInjuryLevel == 0) mindLastInjuryAt = Long.MIN_VALUE;
        }
    }

    /** Small environmental tells: nearby leaves rustle when he moves through foliage, but no blocks are secretly changed. */
    private void tickEnvironmentalReaction(ServerLevel level) {
        if (!ManbearConfig.ENVIRONMENT_REACTIONS_ENABLED.get()) return;
        if (!(mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH)) return;
        if (level.getGameTime() - lastEnvironmentReactionAt < 60L || rnd().nextFloat() > 0.30F) return;
        lastEnvironmentReactionAt = level.getGameTime();
        BlockPos origin = m.blockPosition();
        for (int attempt = 0; attempt < 18; attempt++) {
            BlockPos leaf = origin.offset(rnd().nextInt(15) - 7, rnd().nextInt(7) - 2, rnd().nextInt(15) - 7);
            if (!level.hasChunkAt(leaf) || !level.getBlockState(leaf).is(BlockTags.LEAVES)) continue;
            level.sendParticles(ParticleTypes.ASH, leaf.getX() + 0.5D, leaf.getY() + 0.15D, leaf.getZ() + 0.5D,
                    2 + rnd().nextInt(3), 0.24D, 0.16D, 0.24D, 0.006D);
            if (rnd().nextFloat() < 0.45F) {
                level.playSound(null, leaf, SoundEvents.GRASS_STEP, SoundSource.AMBIENT, 0.25F, 0.62F + rnd().nextFloat() * 0.35F);
            }
            return;
        }
    }

    private PlayerProfile mindProfile(ServerLevel level) {
        if (mindPlayerId == null || level.getServer() == null) return null;
        return ManbearData.get(level.getServer()).profile(mindPlayerId);
    }

    private void suspendMindPlan() {
        if (mindPlan == MindPlan.NONE) return;
        if (mindPlan != MindPlan.DISTRACTION && mindGoal != null) {
            mindResumePlan = mindPlan;
            mindResumeGoal = mindGoal.immutable();
            mindResumeLookAt = mindLookAt == null ? mindGoal.immutable() : mindLookAt.immutable();
            mindResumePending = true;
            // Keep a meaningful interrupted intention in world-save memory too, so it can return
            // after this entity is gone or the chunk has been unloaded for a long time.
            if (mindPlanAge >= 35 && mindPlan != MindPlan.WATCH_TEST && !isManipulationMindPlan(mindPlan) && m.level() instanceof ServerLevel serverLevel) {
                PlayerProfile profile = mindProfile(serverLevel);
                BlockPos focus = mindLookAt == null ? mindGoal : mindLookAt;
                if (profile != null && focus != null && mindPlayerId != null) {
                    profile.recordUnfinishedTask(mindPlan.name(), focus, serverLevel.dimension().location().toString(), serverLevel.getGameTime());
                    ManbearData.get(serverLevel.getServer()).setDirty();
                }
            }
        }
        mindPlan = MindPlan.NONE;
        mindGoal = null;
        mindLookAt = null;
        mindAnimalId = null;
        mindPlanWait = 0;
        mindStuckTicks = 0;
        mindPlanCooldown = Math.max(mindPlanCooldown, 80);
    }

    /** Returns true while an intention owns movement. Called once per tick in the ordinary path and
     * in target-loss handling, so plans can finish a short investigation without magically tracking a distant player. */
    private boolean tickIndependentMind(ServerLevel level, boolean mayStart) {
        if (!isMindCalmMode() || returningToDen || m.leaving || denCaptiveId != null) {
            suspendMindPlan();
            return false;
        }
        if (target != null) {
            if (mindPlayerId != null && !mindPlayerId.equals(target.getUUID())) {
                // Don't apply one player's memories or unfinished intentions to another player's encounter.
                if (mindPlan != MindPlan.NONE) suspendMindPlan();
                mindResumePending = false;
                mindResumePlan = MindPlan.NONE;
                mindResumeGoal = null;
                mindResumeLookAt = null;
                // Perception and investigated clues belong to the previous player; never carry them over.
                mindPerceivedPlayerPos = null;
                mindPerceivedAt = Long.MIN_VALUE;
                mindLastWorldEvidenceAt = Long.MIN_VALUE;
                mindLastWorldEvidencePos = Long.MIN_VALUE;
                mindPerceptionConfidence = 0.0F;
                mindLastInvestigated = null;
                mindLastInvestigatedAt = Long.MIN_VALUE;
                mindBaitId = null;
                mindPlayerId = target.getUUID();
            } else if ((mindPlan == MindPlan.NONE && !mindResumePending) || mindPlayerId == null) {
                mindPlayerId = target.getUUID();
            }
        }
        if (target != null && mindPlan != MindPlan.NONE && mindPlan != MindPlan.DISTRACTION
                && mindPlan != MindPlan.WATCH_TEST && !isManipulationMindPlan(mindPlan) && playerPerceivedWithin(8.0D)) {
            suspendMindPlan();
            return false;
        }
        if (mayStart && mindPlanCooldown > 0) mindPlanCooldown--;

        if (mindPlan == MindPlan.NONE) {
            if (!mayStart || mindPlanCooldown > 0 || target == null) return false;
            if (mindResumePending && mindResumePlan != MindPlan.NONE && mindResumeGoal != null && rnd().nextFloat() < 0.72F) {
                mindPlan = mindResumePlan;
                mindGoal = mindResumeGoal.immutable();
                mindLookAt = mindResumeLookAt == null ? mindGoal : mindResumeLookAt.immutable();
                mindPlanDimension = level.dimension().location().toString();
                mindResumePending = false;
                mindResumePlan = MindPlan.NONE;
                mindResumeGoal = null;
                mindResumeLookAt = null;
                mindPlanAge = 0;
                mindPlanWait = 0;
                mindStuckTicks = 0;
                mindWaypointRetries = 0;
                mindLastPosition = m.position();
                mindPlanCooldown = 180 + rnd().nextInt(260);
            } else {
                mindResumePending = false;
                mindResumePlan = MindPlan.NONE;
                mindResumeGoal = null;
                mindResumeLookAt = null;
                if (!chooseMindPlan(level)) {
                    mindPlanCooldown = 100 + rnd().nextInt(180);
                    return false;
                }
            }
        }
        if (mindPlan == MindPlan.NONE || mindGoal == null) return false;
        if (!mindPlanDimension.isEmpty() && !mindPlanDimension.equals(level.dimension().location().toString())) {
            finishMindPlan(level, false);
            return false;
        }
        if (!level.hasChunkAt(mindGoal)) {
            // Never force-load chunks just to let the creature complete a plan.
            finishMindPlan(level, false);
            return false;
        }

        mindPlanAge++;
        if (mindPlan == MindPlan.DISTRACTION) {
            Entity animal = mindAnimalId == null ? null : level.getEntity(mindAnimalId);
            if (animal instanceof Animal a && a.isAlive() && a.distanceToSqr(m) < 20 * 20) {
                mindGoal = a.blockPosition().immutable();
                mindLookAt = mindGoal;
            }
        } else if (mindPlan != MindPlan.RITUAL && tryStartCuriosityDistraction(level)) {
            // The original intention is saved and will resume after the creature has inspected the distraction.
        }

        if ((mindPlan == MindPlan.TEST_ROUTE || mindPlan == MindPlan.OBSERVE_GOAL) && target != null && mindLookAt != null) {
            // Hypotheses are confirmed by observable evidence, never by reading the player's hidden coordinates alone.
            double evidenceRadius = mindPlan == MindPlan.OBSERVE_GOAL ? 22.0D : 18.0D;
            boolean seenNearRoute = los && target.position().distanceToSqr(Vec3.atCenterOf(mindLookAt)) < evidenceRadius * evidenceRadius;
            boolean heardNearRoute = hasRecentWorldEvidenceNear(level, mindLookAt,
                    mindPlan == MindPlan.OBSERVE_GOAL ? 10 : 7, mindPlan == MindPlan.OBSERVE_GOAL ? 100L : 40L);
            if (seenNearRoute || heardNearRoute) {
                mindRouteObservedTicks++;
                if (mindRouteObservedTicks >= (mindPlan == MindPlan.OBSERVE_GOAL ? 6 : 4)) mindRouteObserved = true;
            } else {
                mindRouteObservedTicks = Math.max(0, mindRouteObservedTicks - 1);
            }
        } else if ((mindPlan == MindPlan.DECEPTION || mindPlan == MindPlan.WILDLIFE_INSTRUMENT) && target != null && mindLookAt != null) {
            Vec3 toLure = Vec3.atCenterOf(mindLookAt).subtract(target.position());
            toLure = new Vec3(toLure.x, 0.0D, toLure.z);
            boolean reacting = false;
            // He can only judge the player's reaction when he has a clear line of sight.
            if (los && toLure.lengthSqr() > 0.01D) {
                Vec3 lureDir = toLure.normalize();
                Vec3 gaze = target.getLookAngle();
                Vec3 flatGaze = new Vec3(gaze.x, 0.0D, gaze.z);
                double gazeDot = flatGaze.lengthSqr() > 0.01D ? flatGaze.normalize().dot(lureDir) : -1.0D;
                Vec3 move = target.getDeltaMovement();
                double moveDot = new Vec3(move.x, 0.0D, move.z).dot(lureDir);
                reacting = gazeDot > 0.62D || moveDot > 0.035D;
            }
            if (reacting) mindDeceptionObservedTicks++;
            else mindDeceptionObservedTicks = Math.max(0, mindDeceptionObservedTicks - 1);
            if (mindDeceptionObservedTicks >= 10) mindDeceptionObserved = true;
            mindRouteObserved = mindDeceptionObserved;
        }

        double distanceGoal = m.position().distanceToSqr(Vec3.atBottomCenterOf(mindGoal));
        if (distanceGoal <= 3.4 * 3.4) {
            m.getNavigation().stop();
            m.setFaceTarget(null, 0F);
            BlockPos look = mindLookAt == null ? mindGoal : mindLookAt;
            m.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.35, look.getZ() + 0.5, 16F, 20F);
            if (mindPlan == MindPlan.WATCH_TEST) {
                if (target == null || dist < 5.5D || mindPlanAge > 700) {
                    finishMindPlan(level, false);
                    return false;
                }
                // He freezes while watched, then relocates only after the player has looked away long enough.
                if (looking) {
                    mindWatchLostTicks = 0;
                    m.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 18F, 18F);
                    return true;
                }
                if (++mindWatchLostTicks < 14) return true;
                if (mindWatchMoves >= 2) {
                    finishMindPlan(level, true);
                    return false;
                }
                CoverFinder.Cover cover = CoverFinder.findCover(level, m, target, 3, 9, mindGoal);
                BlockPos next = cover == null ? null : cover.hide();
                if (next == null || next.distSqr(m.blockPosition()) < 5L) {
                    double angle = rnd().nextDouble() * Math.PI * 2.0D;
                    next = CoverFinder.groundNear(level, target.getX() + Math.cos(angle) * 5.0D,
                            target.getZ() + Math.sin(angle) * 5.0D, target.getBlockY(), 3);
                }
                if (next == null || next.distSqr(m.blockPosition()) < 5L) {
                    finishMindPlan(level, true);
                    return false;
                }
                mindGoal = next.immutable();
                mindLookAt = next.immutable();
                mindWatchMoves++;
                mindWatchLostTicks = 0;
                mindPlanWait = 0;
                mindLastPosition = m.position();
                return true;
            }
            if (isManipulationMindPlan(mindPlan) && tickManipulationPlanAtGoal(level)) return true;
            if (mindPlan == MindPlan.WILDLIFE_INSTRUMENT && !mindScriptCuePlayed && mindLookAt != null) {
                playWildlifeDistraction(level);
                mindScriptCuePlayed = true;
            }
            if (mindPlan == MindPlan.HOUSE_REHEARSAL && !mindScriptCuePlayed && mindLookAt != null) {
                BlockPos cue = mindLookAt;
                level.playSound(null, cue, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 0.55F, 0.68F + rnd().nextFloat() * 0.18F);
                pending.add(new Pending(ticksAlive + 24 + rnd().nextInt(14), SoundEvents.WOODEN_DOOR_CLOSE,
                        SoundSource.BLOCKS, cue.getX() + 0.5D, cue.getY() + 0.5D, cue.getZ() + 0.5D, 0.48F, 0.72F));
                mindScriptCuePlayed = true;
            }
            if (mindPlan == MindPlan.RITUAL && mindPlanWait == 0) m.setStance(Stance.SIT);
            else if (mindPlan != MindPlan.RITUAL && m.getStance() == Stance.SIT) m.setStance(Stance.CROUCH);
            if (mindPlanWait == 0) mindPlanWait = mindWaitDuration(mindPlan);
            else mindPlanWait--;
            if (mindPlanWait <= 0) {
                MindPlan completedPlan = mindPlan;
                boolean baitWasConsumed = completedPlan == MindPlan.INVESTIGATE_BAIT && baitItemDisappeared(level);
                boolean success = switch (completedPlan) {
                    case TEST_ROUTE, OBSERVE_GOAL, DECEPTION, WILDLIFE_INSTRUMENT -> mindRouteObserved;
                    case OPPORTUNITY -> target != null && los && isPlayerCommittedOrVulnerable();
                    // If the item has vanished, he guesses the player may have come back for it.
                    // If it remains, he gradually learns that the clue may have meant nothing.
                    case INVESTIGATE_BAIT -> baitWasConsumed;
                    case WATCH_TEST, HOUSE_REHEARSAL, CONTRADICTORY_TRACE -> true;
                    default -> true;
                };
                boolean successfulOpportunity = completedPlan == MindPlan.OPPORTUNITY && success;
                if (completedPlan == MindPlan.DISTRACTION && ManbearConfig.MIND_NEEDS_ENABLED.get()
                        && mindAnimalId != null && !(level.getEntity(mindAnimalId) instanceof Animal alive && alive.isAlive())) {
                    mindHunger = Math.max(0.0F, mindHunger - 0.30F);
                }
                boolean successfulLure = completedPlan == MindPlan.DECEPTION && success;
                finishMindPlan(level, success);
                // He only exploits an opening if he can still observe it; the window can close before he arrives.
                if ((successfulLure || successfulOpportunity) && mode == ManbearMode.STALK && target != null && los && dist < 28.0D
                        && !looking && !returningToDen && denCaptiveId == null) {
                    setMode(level, rnd().nextFloat() < 0.52F ? ManbearMode.AMBUSH : ManbearMode.FAKE_CHASE);
                }
            }
            return true;
        }

        // A real plan can fail. He pauses, tries a nearby approach, and eventually gives up rather than teleporting.
        if (m.position().distanceToSqr(mindLastPosition) < 0.025D && (!m.getNavigation().isDone() || navFail)) mindStuckTicks++;
        else mindStuckTicks = 0;
        mindLastPosition = m.position();
        if (mindStuckTicks > 36) {
            m.getNavigation().stop();
            m.setFaceTarget(null, 0F);
            // He pauses to inspect the failed approach before deciding to try another route.
            m.getLookControl().setLookAt(mindGoal.getX() + 0.5, mindGoal.getY() + 0.5, mindGoal.getZ() + 0.5, 18F, 18F);
            if (mindWaypointRetries >= 3) {
                rememberFailedApproach(mindGoal);
                finishMindPlan(level, false);
                return true;
            }
            mindWaypointRetries++;
            mindStuckTicks = 0;
            int[][] offsets = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {3, 3}, {-3, -3}};
            int[] off = offsets[(mindWaypointRetries - 1) % offsets.length];
            BlockPos alternate = CoverFinder.groundNear(level, mindGoal.getX() + off[0], mindGoal.getZ() + off[1], mindGoal.getY(), 3);
            if (alternate != null) mindGoal = alternate.immutable();
            mindPlanWait = 12 + rnd().nextInt(18); // hesitation before the next attempt
            return true;
        }
        if (mindPlanWait > 0) {
            m.getNavigation().stop();
            m.setFaceTarget(null, 0F);
            mindPlanWait--;
            return true;
        }
        m.setFaceTarget(null, 0F);
        double walkSpeed = 0.48D + mindPatience * 0.24D;
        if (playerPerceivedWithin(18.0D)) walkSpeed *= (0.70D + 0.25D * mindCaution);
        navTo(level, mindGoal.getX() + 0.5, mindGoal.getY(), mindGoal.getZ() + 0.5, walkSpeed, 12);
        return true;
    }

    private boolean isManipulationMindPlan(MindPlan plan) {
        return plan == MindPlan.FALSE_WEAKNESS || plan == MindPlan.BAITED_REWARD
                || plan == MindPlan.DIVIDED_ATTENTION || plan == MindPlan.HIDDEN_OBJECTIVE
                || plan == MindPlan.HIDDEN_ANSWER_TEST;
    }

    /** Runs only after a manipulation plan reaches its observation point; movement remains ordinary navigation. */
    private boolean tickManipulationPlanAtGoal(ServerLevel level) {
        if (target == null) {
            finishMindPlan(level, false);
            return true;
        }
        switch (mindPlan) {
            case FALSE_WEAKNESS -> {
                if (manipulationStage == 0) {
                    manipulationStage = 1;
                    manipulationTimer = 180 + rnd().nextInt(100);
                    m.getNavigation().stop();
                    m.setStance(Stance.SIT);
                    m.setFaceTarget(null, 0.0F);
                    level.playSound(null, m.blockPosition(), SoundEvents.WOLF_WHINE, SoundSource.HOSTILE, 0.42F, 0.58F);
                    return true;
                }
                if (manipulationStage == 1) {
                    m.getNavigation().stop();
                    m.setStance(Stance.SIT);
                    if (los && looking && target.distanceToSqr(m) < 11.0D * 11.0D) manipulationSawReaction = true;
                    // He only breaks the act after the player has genuinely approached, not merely looked from afar.
                    if (los && target.distanceToSqr(m) < 7.0D * 7.0D) {
                        manipulationSawReaction = true;
                        CoverFinder.Cover picked = CoverFinder.findCover(level, m, target, 3, 12, mindGoal);
                        BlockPos escape = picked == null ? null : picked.hide();
                        if (escape == null || escape.distSqr(m.blockPosition()) < 9L) {
                            Vec3 away = m.position().subtract(target.position());
                            away = new Vec3(away.x, 0.0D, away.z);
                            if (away.lengthSqr() < 0.01D) away = new Vec3(1, 0, 0);
                            away = away.normalize().scale(9.0D);
                            escape = CoverFinder.groundNear(level, m.getX() + away.x, m.getZ() + away.z, m.getBlockY(), 3);
                        }
                        if (escape != null && !isRecentlyFailedApproach(escape, 3)) {
                            manipulationStage = 2;
                            manipulationTimer = 120;
                            mindGoal = escape.immutable();
                            mindPlanWait = 8 + rnd().nextInt(12);
                            m.setStance(Stance.CROUCH);
                            m.getNavigation().stop();
                            return true;
                        }
                    }
                    if (--manipulationTimer <= 0) {
                        finishMindPlan(level, manipulationSawReaction);
                    }
                    return true;
                }
                if (manipulationStage == 2) {
                    boolean followed = target.distanceToSqr(m) < 18.0D * 18.0D && (los || manipulationSawReaction);
                    boolean success = manipulationSawReaction || followed;
                    finishMindPlan(level, success);
                    if (followed && mode == ManbearMode.STALK && dist < 25.0D && rnd().nextFloat() < 0.20F) {
                        setMode(level, ManbearMode.FAKE_CHASE);
                    }
                    return true;
                }
            }
            case BAITED_REWARD -> {
                if (manipulationStage == 0) {
                    manipulationStage = 1;
                    manipulationTimer = 420;
                    m.getNavigation().stop();
                    m.setStance(Stance.CROUCH);
                    return true;
                }
                Entity bait = manipulationBaitId == null ? null : level.getEntity(manipulationBaitId);
                BlockPos focus = mindLookAt == null ? mindGoal : mindLookAt;
                if (focus != null) {
                    double distanceToBait = target.distanceToSqr(Vec3.atCenterOf(focus));
                    if (distanceToBait < 7.0D * 7.0D) manipulationSawReaction = true;
                    if (los && isLookingToward(target, focus, 0.58D)) manipulationSawReaction = true;
                }
                boolean itemGone = !(bait instanceof ItemEntity item && item.isAlive() && !item.getItem().isEmpty());
                if (itemGone) {
                    boolean recoveredOrInvestigated = manipulationSawReaction && focus != null
                            && target.distanceToSqr(Vec3.atCenterOf(focus)) < 13.0D * 13.0D;
                    finishMindPlan(level, recoveredOrInvestigated);
                    return true;
                }
                if (--manipulationTimer <= 0) {
                    finishMindPlan(level, manipulationSawReaction);
                }
                return true;
            }
            case DIVIDED_ATTENTION, HIDDEN_OBJECTIVE, HIDDEN_ANSWER_TEST -> {
                if (manipulationStage == 0) {
                    manipulationStage = 1;
                    manipulationTimer = mindPlan == MindPlan.HIDDEN_OBJECTIVE ? 220 : 180;
                    m.getNavigation().stop();
                    m.setStance(Stance.CROUCH);
                    return true;
                }
                updateManipulationChoice();
                if (manipulationChoiceScore >= 5) manipulationChoice = 1;
                else if (manipulationChoiceScore <= -5) manipulationChoice = 2;
                else manipulationChoice = 0;
                if (--manipulationTimer <= 0) {
                    MindPlan completed = mindPlan;
                    int chosen = manipulationChoice;
                    boolean success = chosen != 0;
                    boolean playerMovedTowardBearTrail = completed == MindPlan.HIDDEN_ANSWER_TEST && chosen == 1
                            && target.distanceToSqr(m) < 22.0D * 22.0D;
                    finishMindPlan(level, success);
                    if (playerMovedTowardBearTrail && mode == ManbearMode.STALK && dist < 24.0D && rnd().nextFloat() < 0.18F) {
                        setMode(level, ManbearMode.AMBUSH);
                    }
                }
                return true;
            }
            default -> { return false; }
        }
        return false;
    }

    private void updateManipulationChoice() {
        if (target == null || mindLookAt == null || manipulationSecondaryFocus == null) return;
        double a = manipulationCueScore(target, mindLookAt);
        double b = manipulationCueScore(target, manipulationSecondaryFocus);
        if (Math.max(a, b) < 0.45D) return;
        if (a > b + 0.12D) manipulationChoiceScore = Mth.clamp(manipulationChoiceScore + 1, -30, 30);
        else if (b > a + 0.12D) manipulationChoiceScore = Mth.clamp(manipulationChoiceScore - 1, -30, 30);
        else manipulationChoiceScore = (int) (manipulationChoiceScore * 0.92D);
        if (a > 0.85D || b > 0.85D) manipulationSawReaction = true;
    }

    private double manipulationCueScore(ServerPlayer player, BlockPos cue) {
        Vec3 toward = Vec3.atCenterOf(cue).subtract(player.position());
        toward = new Vec3(toward.x, 0.0D, toward.z);
        double distance = toward.length();
        if (distance < 0.001D) return 2.0D;
        Vec3 direction = toward.scale(1.0D / distance);
        Vec3 gaze = player.getLookAngle();
        Vec3 flatGaze = new Vec3(gaze.x, 0.0D, gaze.z);
        double gazeDot = flatGaze.lengthSqr() > 0.001D ? flatGaze.normalize().dot(direction) : -1.0D;
        Vec3 velocity = player.getDeltaMovement();
        Vec3 flatVelocity = new Vec3(velocity.x, 0.0D, velocity.z);
        double moveDot = flatVelocity.lengthSqr() > 0.0005D ? flatVelocity.normalize().dot(direction) : 0.0D;
        double proximity = distance < 7.0D ? 1.0D : distance < 12.0D ? 0.35D : 0.0D;
        return gazeDot * 0.72D + moveDot * 0.82D + proximity;
    }

    private boolean isLookingToward(ServerPlayer player, BlockPos cue, double threshold) {
        Vec3 toward = Vec3.atCenterOf(cue).subtract(player.getEyePosition());
        if (toward.lengthSqr() < 0.001D) return true;
        return player.getLookAngle().normalize().dot(toward.normalize()) > threshold;
    }

    private boolean hasRecentWorldEvidenceNear(ServerLevel level, BlockPos center, int radius, long maxAge) {
        PlayerProfile profile = mindProfile(level);
        if (profile == null || center == null) return false;
        long r2 = (long) radius * radius;
        for (PlayerProfile.WorldMemory memory : profile.recentWorldActions(level.getGameTime(),
                level.dimension().location().toString(), maxAge)) {
            if (BlockPos.of(memory.pos()).distSqr(center) <= r2) return true;
        }
        return false;
    }

    private boolean baitItemDisappeared(ServerLevel level) {
        if (mindBaitId == null) return false; // old saves or a lost ID should not create false certainty
        Entity bait = level.getEntity(mindBaitId);
        return !(bait instanceof ItemEntity item && item.isAlive() && !item.getItem().isEmpty());
    }

    private void queueMindDecoySound(BlockPos source) {
        if (source == null) return;
        double x = source.getX() + 0.5D, y = source.getY() + 0.5D, z = source.getZ() + 0.5D;
        float pitch = 0.88F + rnd().nextFloat() * 0.18F;
        // Sound comes from the decoy point rather than his real body position.
        pending.add(new Pending(ticksAlive + 12 + rnd().nextInt(10), SoundEvents.CHEST_OPEN,
                SoundSource.BLOCKS, x, y, z, 1.0F, pitch));
        pending.add(new Pending(ticksAlive + 52 + rnd().nextInt(12), SoundEvents.CHEST_CLOSE,
                SoundSource.BLOCKS, x, y, z, 0.9F, pitch));
    }

    private int mindWaitDuration(MindPlan plan) {
        return switch (plan) {
            case INVESTIGATE_CHANGE -> 45 + rnd().nextInt(100);
            case INVESTIGATE_BAIT -> 35 + rnd().nextInt(80);
            case TEST_ROUTE -> 90 + rnd().nextInt((int) (150 + 100 * mindPatience));
            case INSPECT_TRAP -> 55 + rnd().nextInt(95);
            case DECEPTION -> 55 + rnd().nextInt(90);
            case OBSERVE_GOAL -> 100 + rnd().nextInt(180) + (int) (mindPatience * 50F);
            case OPPORTUNITY -> 35 + rnd().nextInt(70);
            case RITUAL -> 100 + rnd().nextInt(180) + (ManbearConfig.MIND_NEEDS_ENABLED.get() ? (int) (mindFatigue * 100F) : 0);
            case DISTRACTION -> 45 + rnd().nextInt(75);
            case HOUSE_REHEARSAL -> 80 + rnd().nextInt(100);
            case CONTRADICTORY_TRACE -> 30 + rnd().nextInt(55);
            case WATCH_TEST -> 1;
            case WILDLIFE_INSTRUMENT -> 40 + rnd().nextInt(70);
            case PRIVATE_PROJECT -> 110 + rnd().nextInt(190) + (int) (mindRitualism * 60F);
            case FALSE_WEAKNESS -> 120 + rnd().nextInt(100);
            case BAITED_REWARD -> 220 + rnd().nextInt(180);
            case DIVIDED_ATTENTION -> 120 + rnd().nextInt(100);
            case HIDDEN_OBJECTIVE -> 150 + rnd().nextInt(150);
            case HIDDEN_ANSWER_TEST -> 140 + rnd().nextInt(140);
            default -> 1;
        };
    }

    private boolean tryStartScriptedPlan(ServerLevel level, PlayerProfile profile, String dim, long now) {
        if (target == null || profile == null || level.getServer() == null) return false;

        // Resume an interrupted objective across entity/chunk lifetimes before inventing a new one.
        PlayerProfile.UnfinishedTask unfinished = profile.unfinishedTask(now, dim);
        if (unfinished != null && profile.scriptedEventReady("UNFINISHED_TASK_REVISIT", now, 2600L)
                && rnd().nextFloat() < (0.18F + 0.20F * mindPatience)) {
            BlockPos focus = BlockPos.of(unfinished.pos());
            double fromBear = m.blockPosition().distSqr(focus);
            double fromPlayer = target.blockPosition().distSqr(focus);
            MindPlan resume = safeMindPlan(unfinished.kind());
            if (resume == MindPlan.NONE || resume == MindPlan.DISTRACTION || resume == MindPlan.WATCH_TEST
                    || resume == MindPlan.OPPORTUNITY) resume = MindPlan.INVESTIGATE_CHANGE;
            BlockPos standing = level.hasChunkAt(focus) ? mindStandNear(level, focus) : null;
            if (standing != null && fromBear > 8 * 8 && fromBear < 100 * 100 && fromPlayer > 12 * 12
                    && !isRecentlyFailedApproach(standing, 5)) {
                beginMindPlan(level, resume, standing, focus);
                profile.markScriptedEvent("UNFINISHED_TASK_REVISIT", now);
                ManbearData.get(level.getServer()).setDirty();
                return true;
            }
        }

        // Multi-visit house rehearsal: he tests one entrance, remembers which one it was, and
        // changes the next visit rather than repeating the exact same approach.
        if (level == level.getServer().overworld() && profile.base != null
                && profile.scriptedEventReady("HOUSE_REHEARSAL", now, 14000L)
                && target.blockPosition().distSqr(profile.base) < 88L * 88L
                && m.blockPosition().distSqr(profile.base) > 14L * 14L
                && m.blockPosition().distSqr(profile.base) < 96L * 96L
                && rnd().nextFloat() < (0.18F + 0.16F * mindPatience)) {
            BlockPos focus = findHouseRehearsalFocus(level, profile, dim);
            BlockPos standing = focus == null ? null : mindStandNear(level, focus);
            if (standing != null && level.hasChunkAt(focus) && !isRecentlyFailedApproach(standing, 4)
                    && target.blockPosition().distSqr(focus) > 10L * 10L) {
                beginMindPlan(level, MindPlan.HOUSE_REHEARSAL, standing, focus);
                profile.markScriptedEvent("HOUSE_REHEARSAL", now);
                ManbearData.get(level.getServer()).setDirty();
                return true;
            }
        }

        // Watch-testing begins only when the target is actually looking at him. He holds still,
        // then moves a few blocks only during a gap in observation.
        if (los && looking && dist >= 8.0D && dist <= 32.0D
                && profile.scriptedEventReady("WATCH_TEST", now, 24000L)
                && rnd().nextFloat() < ((0.10F + 0.15F * mindPatience) * (ManbearConfig.PLAYER_THEORY_ENABLED.get() ? (0.65F + 0.85F * profile.monitorsManbear) : 1.0F))) {
            beginMindPlan(level, MindPlan.WATCH_TEST, m.blockPosition(), m.blockPosition());
            profile.markScriptedEvent("WATCH_TEST", now);
            ManbearData.get(level.getServer()).setDirty();
            return true;
        }

        // Contradictory tracks are staged near a familiar area while the player is away from it.
        // The two branches lead in opposite directions; the Manbear can then observe which trail is followed.
        if (level == level.getServer().overworld() && profile.base != null
                && target.blockPosition().distSqr(profile.base) > 20L * 20L
                && target.blockPosition().distSqr(profile.base) < 120L * 120L
                && profile.scriptedEventReady("CONTRADICTORY_TRACE", now, 18000L)
                && m.blockPosition().distSqr(target.blockPosition()) > 18L * 18L
                && rnd().nextFloat() < (0.12F + 0.10F * mindCuriosity)) {
            double a = rnd().nextDouble() * Math.PI * 2.0D;
            double radius = 4.0D + rnd().nextDouble() * 6.0D;
            BlockPos focus = CoverFinder.groundNear(level, profile.base.getX() + Math.cos(a) * radius,
                    profile.base.getZ() + Math.sin(a) * radius, profile.base.getY(), 3);
            BlockPos standing = focus == null ? null : mindStandNear(level, focus);
            if (standing != null && level.hasChunkAt(focus) && m.blockPosition().distSqr(standing) > 8L * 8L
                    && m.blockPosition().distSqr(standing) < 72L * 72L) {
                beginMindPlan(level, MindPlan.CONTRADICTORY_TRACE, standing, focus);
                profile.markScriptedEvent("CONTRADICTORY_TRACE", now);
                ManbearData.get(level.getServer()).setDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * The manipulation director stages rare encounters with a visible surface objective and a quieter learning goal.
     * It never reads hidden inventory or forces a chunk load: bait uses a real item already in the world or a pending
     * stolen trophy owned by this player, and all reactions are inferred from sight, movement, or proximity.
     */
    private boolean tryStartManipulationPlan(ServerLevel level, PlayerProfile profile, String dim, long now) {
        if (target == null || profile == null || level.getServer() == null || mode != ManbearMode.STALK
                || dist < 10.0D || dist > 46.0D
                || !profile.scriptedEventReady("MANIPULATION_DIRECTOR", now, ManbearConfig.MANIPULATION_COOLDOWN_TICKS.get())) return false;

        // 1) False weakness: only staged when the player can plausibly witness the posture.
        if (los && looking && dist >= 12.0D && dist <= 28.0D
                && profile.scriptedEventReady("FALSE_WEAKNESS", now, 36_000L)
                && rnd().nextFloat() < ManbearConfig.FALSE_WEAKNESS_CHANCE.get()) {
            beginMindPlan(level, MindPlan.FALSE_WEAKNESS, m.blockPosition(), m.blockPosition());
            markManipulationStarted(profile, "FALSE_WEAKNESS", now);
            return true;
        }

        // 2) Baited reward: move a real dropped item, or stage a pending stolen item without making a duplicate.
        if (!looking && dist >= 12.0D && dist <= 38.0D
                && ManbearConfig.DEN_TROPHIES_ENABLED.get()
                && profile.scriptedEventReady("BAITED_REWARD", now, 48_000L)
                && rnd().nextFloat() < ManbearConfig.BAITED_REWARD_CHANCE.get()) {
            if (tryStageBaitedReward(level, profile, now)) return true;
        }

        // 3) Divided attention: two spatially distinct cues; he watches which one wins the player's attention.
        if (los && !looking && dist >= 15.0D && dist <= 34.0D
                && profile.scriptedEventReady("DIVIDED_ATTENTION", now, 32_000L)
                && rnd().nextFloat() < ManbearConfig.DIVIDED_ATTENTION_CHANCE.get()) {
            BlockPos[] pair = manipulationCuePair(level, target.blockPosition());
            if (pair != null) {
                beginMindPlan(level, MindPlan.DIVIDED_ATTENTION, m.blockPosition(), pair[0]);
                manipulationSecondaryFocus = pair[1].immutable();
                queueMindDecoySound(pair[0]);
                queueDoorCue(pair[1]);
                markManipulationStarted(profile, "DIVIDED_ATTENTION", now);
                return true;
            }
        }

        // 4) Hidden objective: a single apparent sound conceals the real purpose—learning which way the player exits.
        if (level == level.getServer().overworld() && los && !looking && profile.base != null
                && target.blockPosition().distSqr(profile.base) < 28L * 28L
                && target.blockPosition().distSqr(profile.base) > 12L * 12L
                && profile.scriptedEventReady("HIDDEN_OBJECTIVE", now, 42_000L)
                && rnd().nextFloat() < ManbearConfig.HIDDEN_OBJECTIVE_CHANCE.get()) {
            BlockPos[] pair = manipulationCuePair(level, profile.base);
            if (pair != null) {
                beginMindPlan(level, MindPlan.HIDDEN_OBJECTIVE, m.blockPosition(), pair[0]);
                manipulationSecondaryFocus = pair[1].immutable();
                queueMindDecoySound(pair[0]);
                markManipulationStarted(profile, "HIDDEN_OBJECTIVE", now);
                return true;
            }
        }

        // 5) Hidden-answer test: two plausible trails diverge; he watches which explanation the player trusts.
        if (dist >= 14.0D && dist <= 40.0D
                && profile.scriptedEventReady("HIDDEN_ANSWER_TEST", now, 48_000L)
                && rnd().nextFloat() < ManbearConfig.HIDDEN_ANSWER_TEST_CHANCE.get()) {
            Vec3 towardBear = m.position().subtract(target.position());
            towardBear = new Vec3(towardBear.x, 0.0D, towardBear.z);
            if (towardBear.lengthSqr() > 0.01D) {
                towardBear = towardBear.normalize();
                Vec3 origin = target.position();
                Vec3 nearBear = origin.add(towardBear.scale(2.0D));
                Vec3 awayBear = origin.subtract(towardBear.scale(2.0D));
                boolean a = Evidence.leaveAs("FOOTPRINTS", level, this, nearBear, towardBear, new ArrayList<>(trail));
                boolean b = Evidence.leaveAs("FOOTPRINTS", level, this, awayBear, towardBear.scale(-1.0D), new ArrayList<>(trail));
                if (a && b) {
                    BlockPos clueA = BlockPos.containing(origin.add(towardBear.scale(8.0D)));
                    BlockPos clueB = BlockPos.containing(origin.subtract(towardBear.scale(8.0D)));
                    beginMindPlan(level, MindPlan.HIDDEN_ANSWER_TEST, m.blockPosition(), clueA);
                    manipulationSecondaryFocus = clueB.immutable();
                    markManipulationStarted(profile, "HIDDEN_ANSWER_TEST", now);
                    return true;
                }
            }
        }
        return false;
    }

    private void markManipulationStarted(PlayerProfile profile, String event, long now) {
        profile.markScriptedEvent("MANIPULATION_DIRECTOR", now);
        profile.markScriptedEvent(event, now);
        ManbearData.get(m.level().getServer()).setDirty();
    }

    private BlockPos[] manipulationCuePair(ServerLevel level, BlockPos center) {
        if (target == null || center == null) return null;
        Vec3 awayFromBear = target.position().subtract(m.position());
        awayFromBear = new Vec3(awayFromBear.x, 0.0D, awayFromBear.z);
        if (awayFromBear.lengthSqr() < 0.01D) awayFromBear = new Vec3(1, 0, 0);
        awayFromBear = awayFromBear.normalize();
        Vec3 side = new Vec3(-awayFromBear.z, 0.0D, awayFromBear.x);
        for (int attempt = 0; attempt < 12; attempt++) {
            double spread = 7.0D + rnd().nextDouble() * 5.0D;
            double forward = rnd().nextDouble() * 5.0D;
            double jitter = rnd().nextDouble() * 2.0D - 1.0D;
            double ax = center.getX() + side.x * spread + awayFromBear.x * forward + side.x * jitter;
            double az = center.getZ() + side.z * spread + awayFromBear.z * forward + side.z * jitter;
            double bx = center.getX() - side.x * spread + awayFromBear.x * forward - side.x * jitter;
            double bz = center.getZ() - side.z * spread + awayFromBear.z * forward - side.z * jitter;
            BlockPos a = CoverFinder.groundNear(level, ax, az, center.getY(), 4);
            BlockPos b = CoverFinder.groundNear(level, bx, bz, center.getY(), 4);
            if (a == null || b == null || !level.hasChunkAt(a) || !level.hasChunkAt(b)) continue;
            if (a.distSqr(b) < 10L * 10L || a.distSqr(target.blockPosition()) > 22L * 22L
                    || b.distSqr(target.blockPosition()) > 22L * 22L) continue;
            if (isKnownTrap(level, a, 3) || isKnownTrap(level, b, 3)) continue;
            return new BlockPos[]{a.immutable(), b.immutable()};
        }
        return null;
    }

    private void queueDoorCue(BlockPos source) {
        if (source == null) return;
        double x = source.getX() + 0.5D, y = source.getY() + 0.5D, z = source.getZ() + 0.5D;
        float pitch = 0.78F + rnd().nextFloat() * 0.22F;
        pending.add(new Pending(ticksAlive + 8 + rnd().nextInt(14), SoundEvents.WOODEN_DOOR_OPEN,
                SoundSource.BLOCKS, x, y, z, 0.72F, pitch));
        pending.add(new Pending(ticksAlive + 42 + rnd().nextInt(18), SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, x, y, z, 0.64F, pitch));
    }

    private BlockPos findBaitGround(ServerLevel level) {
        if (target == null) return null;
        Vec3 preferred = target.position().subtract(m.position());
        preferred = new Vec3(preferred.x, 0.0D, preferred.z);
        if (preferred.lengthSqr() < 0.01D) preferred = new Vec3(1, 0, 0);
        preferred = preferred.normalize();
        for (int i = 0; i < 20; i++) {
            double side = (rnd().nextDouble() - 0.5D) * 10.0D;
            double ahead = 7.0D + rnd().nextDouble() * 7.0D;
            double x = target.getX() + preferred.x * ahead - preferred.z * side;
            double z = target.getZ() + preferred.z * ahead + preferred.x * side;
            BlockPos spot = CoverFinder.groundNear(level, x, z, target.getBlockY(), 4);
            if (spot == null || !level.hasChunkAt(spot) || !level.getBlockState(spot).isAir()
                    || !level.getBlockState(spot.above()).isAir()
                    || !level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), net.minecraft.core.Direction.UP)) continue;
            double playerDistance = spot.distToCenterSqr(target.getX(), target.getY(), target.getZ());
            double bearDistance = spot.distToCenterSqr(m.getX(), m.getY(), m.getZ());
            if (playerDistance < 5.0D * 5.0D || playerDistance > 16.0D * 16.0D || bearDistance < 4.0D * 4.0D) continue;
            return spot.immutable();
        }
        return null;
    }

    private boolean tryStageBaitedReward(ServerLevel level, PlayerProfile profile, long now) {
        BlockPos spot = findBaitGround(level);
        if (spot == null) return false;
        ItemEntity bait = null;

        // Reposition an existing dropped item when possible. It remains the same entity and is never duplicated.
        AABB search = target.getBoundingBox().inflate(18.0D, 6.0D, 18.0D);
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class, search,
                item -> item.isAlive() && !item.getItem().isEmpty()
                        && item.distanceToSqr(target) > 2.5D * 2.5D && item.distanceToSqr(m) > 3.0D * 3.0D);
        if (!dropped.isEmpty()) {
            if (!mindPreferredItemId.isBlank()) {
                for (ItemEntity candidate : dropped) {
                    if (candidate.getItem().getItem().getDescriptionId().equals(mindPreferredItemId)) { bait = candidate; break; }
                }
            }
            if (bait == null) bait = dropped.get(rnd().nextInt(dropped.size()));
            bait.setPos(spot.getX() + 0.5D, spot.getY() + 0.15D, spot.getZ() + 0.5D);
            bait.setDeltaMovement(Vec3.ZERO);
            bait.setPickUpDelay(10);
        } else {
            // If no world item exists, only use a pending trophy actually stolen from this player.
            ManbearData data = ManbearData.get(level.getServer());
            for (ManbearData.StolenTrophy trophy : data.pendingStolenTrophies()) {
                if (!target.getUUID().equals(trophy.owner()) || trophy.stack().isEmpty()) continue;
                ItemStack stack = trophy.stack();
                bait = new ItemEntity(level, spot.getX() + 0.5D, spot.getY() + 0.15D, spot.getZ() + 0.5D, stack);
                bait.setPickUpDelay(10);
                if (level.addFreshEntity(bait)) {
                    data.updateStolenTrophy(trophy.id(), ItemStack.EMPTY);
                    data.setDirty();
                    break;
                }
                bait = null;
            }
        }
        if (bait == null) return false;
        beginMindPlan(level, MindPlan.BAITED_REWARD, m.blockPosition(), spot);
        manipulationBaitId = bait.getUUID();
        manipulationBaitItemId = bait.getItem().getItem().getDescriptionId();
        markManipulationStarted(profile, "BAITED_REWARD", now);
        return true;
    }

    private BlockPos findHouseRehearsalFocus(ServerLevel level, PlayerProfile profile, String dimension) {
        BlockPos base = profile.base;
        if (base == null) return null;
        List<BlockPos> doors = new ArrayList<>();
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
            for (int dy = -3; dy <= 5; dy++) {
                BlockPos p = base.offset(dx, dy, dz);
                if (!level.hasChunkAt(p) || !level.getBlockState(p).is(BlockTags.DOORS)) continue;
                boolean duplicate = false;
                for (BlockPos seen : doors) if (seen.distSqr(p) <= 2L) { duplicate = true; break; }
                if (!duplicate) doors.add(p.immutable());
            }
        }
        BlockPos previous = profile.houseRehearsalFocus(dimension);
        if (!doors.isEmpty() && profile.houseRehearsalStage() == 0) {
            doors.sort(java.util.Comparator.comparingDouble(p -> p.distToCenterSqr(base.getX() + 0.5D, base.getY() + 0.5D, base.getZ() + 0.5D)));
            return doors.get(0);
        }
        if (!doors.isEmpty() && profile.houseRehearsalStage() > 0) {
            BlockPos chosen = null;
            double farthest = -1.0D;
            for (BlockPos door : doors) {
                double d = previous == null ? door.distSqr(base) : door.distSqr(previous);
                if (previous != null && d < 25L) continue;
                if (d > farthest) { farthest = d; chosen = door; }
            }
            if (chosen != null) return chosen;
        }
        int stage = profile.houseRehearsalStage();
        int[][] fallback = {{7, 0}, {-7, 0}, {0, 7}};
        int ox = fallback[stage][0], oz = fallback[stage][1];
        if (previous != null) {
            int vx = previous.getX() - base.getX(), vz = previous.getZ() - base.getZ();
            if (vx == 0 && vz == 0) { vx = 1; vz = 0; }
            if (stage == 1 || stage == 2) { ox = -Integer.signum(vx) * 7; oz = -Integer.signum(vz) * 7; }
            if (ox == 0 && oz == 0) oz = 7;
        }
        return CoverFinder.groundNear(level, base.getX() + ox, base.getZ() + oz, base.getY(), 3);
    }

    private boolean chooseMindPlan(ServerLevel level) {
        if (target == null || concealmentPhase > 0 || objectUsePhase > 0) return false;
        PlayerProfile profile = mindProfile(level);
        if (profile == null) return false;
        String dim = level.dimension().location().toString();
        long now = level.getGameTime();
        PlayerProfile.LikelyGoal inferredGoal = profile.likelyGoal();
        float goalConfidence = profile.goalConfidence();

        // Rare multi-stage encounters have priority over ordinary curiosity rolls, but still obey
        // player-specific cooldowns and only use locations/changes the Manbear could plausibly know.
        if (ManbearConfig.SCRIPTED_ENCOUNTERS_ENABLED.get() && tryStartScriptedPlan(level, profile, dim, now)) return true;
        if (ManbearConfig.MANIPULATION_DIRECTOR_ENABLED.get() && tryStartManipulationPlan(level, profile, dim, now)) return true;

        // He sometimes uses nearby wildlife as a moving distraction, without attacking or commandeering pets.
        if (ManbearConfig.CREATURE_INSTRUMENTS_ENABLED.get() && !looking && dist > 11.0D && dist < 38.0D
                && profile.scriptedEventReady("WILDLIFE_INSTRUMENT", now, 9000L)
                && rnd().nextFloat() < (0.025F + 0.035F * mindDistractibility)) {
            Animal animal = findInstrumentAnimal(level);
            if (animal != null) {
                BlockPos standing = mindStandNear(level, animal.blockPosition());
                if (standing != null && m.blockPosition().distSqr(standing) > 4L && target.blockPosition().distSqr(standing) > 7L * 7L) {
                    beginMindPlan(level, MindPlan.WILDLIFE_INSTRUMENT, standing, animal.blockPosition());
                    mindAnimalId = animal.getUUID();
                    mindScriptCuePlayed = false;
                    profile.markScriptedEvent("WILDLIFE_INSTRUMENT", now);
                    ManbearData.get(level.getServer()).setDirty();
                    return true;
                }
            }
        }

        // Quiet private projects give him an occasional objective that is not an immediate attempt to frighten the player.
        if (ManbearConfig.PRIVATE_PROJECTS_ENABLED.get() && !looking && dist > 24.0D
                && profile.scriptedEventReady("PRIVATE_PROJECT", now, 24000L)
                && rnd().nextFloat() < (0.018F + 0.045F * mindRitualism)) {
            BlockPos project = findPrivateProjectSite(level, profile);
            if (project != null) {
                mindRitualAnchor = project.immutable();
                beginMindPlan(level, MindPlan.PRIVATE_PROJECT, project, project);
                profile.markScriptedEvent("PRIVATE_PROJECT", now);
                ManbearData.get(level.getServer()).setDirty();
                return true;
            }
        }

        // Long-running investigations can survive a particular entity disappearing and another encounter beginning.
        PlayerProfile.CampaignMemory campaign = profile.campaign(now, dim);
        if (campaign != null && campaign.stage() < 4 && rnd().nextFloat() < (0.38F + 0.08F * mindPatience)) {
            BlockPos focus = BlockPos.of(campaign.pos());
            if (level.hasChunkAt(focus) && m.blockPosition().distSqr(focus) > 10 * 10
                    && m.blockPosition().distSqr(focus) < 108 * 108
                    && !(los && target.blockPosition().distSqr(focus) < 20 * 20)) {
                MindPlan followUp = switch (campaign.type()) {
                    case "INVESTIGATE_CHANGE" -> MindPlan.INVESTIGATE_CHANGE;
                    case "TEST_ROUTE" -> MindPlan.TEST_ROUTE;
                    case "INSPECT_TRAP" -> MindPlan.INSPECT_TRAP;
                    case "OBSERVE_GOAL" -> MindPlan.OBSERVE_GOAL;
                    case "DECEPTION" -> MindPlan.OBSERVE_GOAL;
                    default -> MindPlan.NONE;
                };
                BlockPos standing = followUp == MindPlan.NONE ? null
                        : (followUp == MindPlan.OBSERVE_GOAL ? mindObservationPoint(level, focus) : mindStandNear(level, focus));
                if (standing != null && !isRecentlyFailedApproach(focus, 4)) {
                    beginMindPlan(level, followUp, standing, focus);
                    return true;
                }
            }
        } else if (campaign != null && campaign.stage() >= 4) {
            profile.clearCampaign();
            ManbearData.get(level.getServer()).setDirty();
        }

        // Infer a probable objective from recent building/mining/movement evidence, then gather information about it.
        // These are soft beliefs: they can be wrong, and observations can later weaken them.
        if (goalConfidence >= 0.48F && rnd().nextFloat() < (0.10F + 0.22F * goalConfidence)) {
            BlockPos focus = inferredGoalFocus(level, profile, inferredGoal, now, dim);
            if (focus != null && level.hasChunkAt(focus) && m.blockPosition().distSqr(focus) > 14 * 14
                    && m.blockPosition().distSqr(focus) < 88 * 88
                    && !(los && target.blockPosition().distSqr(focus) < 22 * 22)
                    && !isRecentlyFailedApproach(focus, 5)) {
                BlockPos observation = mindObservationPoint(level, focus);
                if (observation != null) {
                    beginMindPlan(level, MindPlan.OBSERVE_GOAL, observation, focus);
                    return true;
                }
            }
        }

        // Opportunity recognition: notice when the player is committed to an action or in a difficult position.
        // The Manbear still needs line of sight and a reachable intercept; he cannot exploit hidden status magically.
        if (los && !looking && dist > 12.0D && dist < 38.0D && isPlayerCommittedOrVulnerable()
                && rnd().nextFloat() < (0.10F + 0.20F * mindPatience + 0.12F * mindCaution)) {
            Vec3 heading = profile.velocity();
            Vec3 flat = new Vec3(heading.x, 0.0D, heading.z);
            if (flat.lengthSqr() > 0.025D) flat = flat.normalize();
            else flat = new Vec3(target.getLookAngle().x, 0.0D, target.getLookAngle().z).normalize();
            Vec3 predicted = target.position().add(flat.scale(4.0D + rnd().nextDouble() * 6.0D));
            BlockPos focus = CoverFinder.groundNear(level, predicted.x, predicted.z, target.getBlockY(), 3);
            BlockPos standing = focus == null ? null : mindStandNear(level, focus);
            if (standing != null && !isRecentlyFailedApproach(standing, 4)) {
                beginMindPlan(level, MindPlan.OPPORTUNITY, standing, target.blockPosition());
                return true;
            }
        }

        // Curiosity: investigate recent player-caused changes, but only if they are no longer standing beside them.
        float changeInvestigationChance = 0.06F + 0.26F * mindCuriosity;
        if (inferredGoal == PlayerProfile.LikelyGoal.BUILDING) changeInvestigationChance += 0.16F * goalConfidence;
        if (inferredGoal == PlayerProfile.LikelyGoal.CAVE_EXPLORATION) changeInvestigationChance += 0.10F * goalConfidence;
        if (profile.countEpisodes("INVESTIGATE_CHANGE", "FAILURE", now, 24_000L) >= 2) changeInvestigationChance *= 0.55F;
        if (rnd().nextFloat() < changeInvestigationChance) {
            List<PlayerProfile.WorldMemory> actions = profile.recentWorldActions(now, dim, 12_000L);
            List<PlayerProfile.WorldMemory> candidates = new ArrayList<>();
            for (PlayerProfile.WorldMemory action : actions) {
                BlockPos focus = BlockPos.of(action.pos());
                double dMan = m.blockPosition().distSqr(focus);
                long actionAge = now - action.gameTime();
                // A fresh clue may mean the player is still nearby; an old clue is safe to investigate.
                if (dMan < 8 * 8 || dMan > 92 * 92 || actionAge < 60L) continue;
                if (los && target.blockPosition().distSqr(focus) < 20 * 20) continue;
                if (isRecentlyFailedApproach(focus, 7)) continue;
                if (mindLastInvestigated != null && mindLastInvestigated.equals(focus)
                        && action.gameTime() <= mindLastInvestigatedAt + 1200L) continue;
                candidates.add(action);
            }
            if (!candidates.isEmpty()) {
                PlayerProfile.WorldMemory action = candidates.get(rnd().nextInt(candidates.size()));
                BlockPos focus = BlockPos.of(action.pos());
                BlockPos standing = mindStandNear(level, focus);
                if (standing != null) {
                    beginMindPlan(level, MindPlan.INVESTIGATE_CHANGE, standing, focus);
                    mindLastInvestigated = focus.immutable();
                    mindLastInvestigatedAt = action.gameTime();
                    return true;
                }
            }
        }

        // A dropped item is an ambiguous cue. He may investigate bait the player deliberately left behind.
        float baitChance = 0.12F + 0.16F * mindCuriosity;
        if (inferredGoal == PlayerProfile.LikelyGoal.GATHERING) baitChance += 0.10F * goalConfidence;
        if (profile.countEpisodes("INVESTIGATE_BAIT", "FAILURE", now, 24_000L) >= 2) baitChance *= 0.55F;
        if (mindDecoyCooldown <= 0 && rnd().nextFloat() < baitChance) {
            List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, m.getBoundingBox().inflate(28.0D),
                    item -> item.isAlive() && item.tickCount < 2400 && !item.getItem().isEmpty());
            List<ItemEntity> baitCandidates = new ArrayList<>();
            for (ItemEntity item : items) {
                double dMan = m.distanceToSqr(item);
                if (dMan < 5 * 5 || dMan > 26 * 26) continue;
                Vec3 perceived = perceivedPlayerPosition();
                if (perceived != null && mindPerceptionConfidence > 0.5F
                        && perceived.distanceToSqr(item.position()) < 4.5D * 4.5D) continue; // no lure right under a confidently located player
                if (isRecentlyFailedApproach(item.blockPosition(), 6)) continue;
                baitCandidates.add(item);
            }
            if (!baitCandidates.isEmpty()) {
                ItemEntity bait = null;
                if (!mindPreferredItemId.isBlank() && rnd().nextFloat() < 0.68F) {
                    List<ItemEntity> familiar = new ArrayList<>();
                    for (ItemEntity candidate : baitCandidates) {
                        if (candidate.getItem().getItem().getDescriptionId().equals(mindPreferredItemId)) familiar.add(candidate);
                    }
                    if (!familiar.isEmpty()) bait = familiar.get(rnd().nextInt(familiar.size()));
                }
                if (bait == null) bait = baitCandidates.get(rnd().nextInt(baitCandidates.size()));
                BlockPos focus = bait.blockPosition().immutable();
                BlockPos standing = mindStandNear(level, focus);
                if (standing != null) {
                    // A plausible but not necessarily correct inference: the dropped item may mark a player route.
                    profile.adjustRouteBelief(focus, 0.10F, now);
                    beginMindPlan(level, MindPlan.INVESTIGATE_BAIT, standing, focus);
                    mindBaitId = bait.getUUID();
                    mindLastBaitItemId = bait.getItem().getItem().getDescriptionId();
                    mindDecoyCooldown = 900 + rnd().nextInt(1500);
                    return true;
                }
            }
        }

        // Deception: create a plausible sound cue at one location, then wait along a different approach.
        // This is only attempted when he has direct or very recent evidence of the player.
        float deceptionChance = 0.10F + 0.12F * mindPatience
                + (ManbearConfig.MIND_NEEDS_ENABLED.get() ? 0.035F * mindTerritoryStress : 0.0F);
        if (inferredGoal == PlayerProfile.LikelyGoal.RETURNING_HOME) deceptionChance += 0.08F * goalConfidence;
        if (ManbearConfig.PLAYER_THEORY_ENABLED.get()) {
            if (profile.investigatesCues > 0.62F) deceptionChance *= 1.0F + 0.45F * (profile.investigatesCues - 0.5F);
            else deceptionChance *= 0.70F + 0.50F * profile.investigatesCues;
        }
        if (profile.countEpisodes("DECEPTION", "FAILURE", now, 24_000L) >= 2) deceptionChance *= 0.45F;
        if (los && !looking && dist > 13.0D && dist < 40.0D && mindDecoyCooldown <= 0
                && rnd().nextFloat() < deceptionChance) {
            Vec3 forward = target.getLookAngle();
            Vec3 flat = new Vec3(forward.x, 0.0D, forward.z);
            if (flat.lengthSqr() > 0.01D) {
                flat = flat.normalize();
                double lureDistance = 8.0D + rnd().nextDouble() * 5.0D;
                Vec3 lureRaw = target.position().subtract(flat.scale(lureDistance))
                        .add((rnd().nextDouble() - 0.5D) * 5.0D, 0.0D, (rnd().nextDouble() - 0.5D) * 5.0D);
                Vec3 interceptRaw = target.position().add(flat.scale(4.0D + rnd().nextDouble() * 4.0D));
                BlockPos lure = CoverFinder.groundNear(level, lureRaw.x, lureRaw.z, target.getBlockY(), 3);
                BlockPos intercept = CoverFinder.groundNear(level, interceptRaw.x, interceptRaw.z, target.getBlockY(), 3);
                BlockPos standing = intercept == null ? null : mindStandNear(level, intercept);
                if (lure != null && standing != null && m.blockPosition().distSqr(standing) > 5 * 5
                        && m.blockPosition().distSqr(standing) < 44 * 44) {
                    beginMindPlan(level, MindPlan.DECEPTION, standing, lure);
                    mindDecoyCooldown = 2400 + rnd().nextInt(2400);
                    return true;
                }
            }
        }

        // Hypothesis: a well-used area might be a route the player relies on. He scouts it and updates confidence.
        List<BlockPos> hotspots = profile.hotspots();
        if (!hotspots.isEmpty() && rnd().nextFloat() < (0.16F + 0.34F * mindPatience)) {
            List<BlockPos> possible = new ArrayList<>();
            for (BlockPos point : hotspots) {
                double dMan = m.blockPosition().distSqr(point);
                Vec3 knownPlayer = perceivedPlayerPosition();
                double dPlayer = knownPlayer == null ? Double.MAX_VALUE
                        : point.distToCenterSqr(knownPlayer.x, knownPlayer.y, knownPlayer.z);
                if (dMan < 18 * 18 || dMan > 78 * 78 || los && dPlayer < 16 * 16) continue;
                if (isRecentlyFailedApproach(point, 8)) continue;
                if (Math.abs(profile.routeBelief(point, now) - 0.5F) > 0.30F) continue;
                possible.add(point);
            }
            if (!possible.isEmpty()) {
                BlockPos focus = possible.get(rnd().nextInt(possible.size()));
                BlockPos standing = mindStandNear(level, focus);
                if (standing != null) {
                    beginMindPlan(level, MindPlan.TEST_ROUTE, standing, focus);
                    return true;
                }
            }
        }

        // Learn a trap only after actually walking over to inspect a player-made component.
        // He remembers the inspected area and avoids it later, but does not gain perfect knowledge of every redstone build.
        if (ManbearConfig.TRAP_LEARNING_ENABLED.get() && rnd().nextFloat() < (0.16F + 0.22F * mindCaution)) {
            List<PlayerProfile.TrapMemory> traps = profile.recentTrapObservations(now, dim, 72_000L);
            List<PlayerProfile.TrapMemory> candidates = new ArrayList<>();
            for (PlayerProfile.TrapMemory memory : traps) {
                BlockPos focus = BlockPos.of(memory.pos());
                double dMan = m.blockPosition().distSqr(focus);
                if (dMan < 9 * 9 || dMan > 58 * 58) continue;
                if (target.blockPosition().distSqr(focus) < 9 * 9 || los && target.blockPosition().distSqr(focus) < 18 * 18) continue;
                if (isRecentlyFailedApproach(focus, 5) || isKnownTrap(level, focus, 4)) continue;
                candidates.add(memory);
            }
            if (!candidates.isEmpty()) {
                PlayerProfile.TrapMemory memory = candidates.get(rnd().nextInt(candidates.size()));
                BlockPos focus = BlockPos.of(memory.pos());
                BlockPos standing = mindStandNear(level, focus);
                if (standing != null) {
                    beginMindPlan(level, MindPlan.INSPECT_TRAP, standing, focus);
                    return true;
                }
            }
        }

        // A ritual is a low-frequency self-directed routine. It is not triggered by a player action.
        // Fatigue makes his personal rest habits more likely; territorial stress makes him favor den-adjacent spots.
        float ritualChance = 0.025F + 0.095F * mindRitualism;
        if (ManbearConfig.MIND_NEEDS_ENABLED.get()) ritualChance += mindFatigue * 0.075F + mindTerritoryStress * 0.035F;
        if (mindRitualAnchor != null && now - mindLastRitualAt > 2400L
                && rnd().nextFloat() < ritualChance) {
            BlockPos anchor = mindRitualAnchor;
            ManbearData data = ManbearData.get(level.getServer());
            if (mindTerritoriality > 0.68F && data.hasDen() && data.denDimension().equals(dim)
                    && m.blockPosition().distSqr(data.denCenter()) < 120 * 120) {
                BlockPos nearDen = mindStandNear(level, data.denCenter());
                if (nearDen != null) anchor = nearDen;
            }
            beginMindPlan(level, MindPlan.RITUAL, anchor, anchor);
            return true;
        }
        return false;
    }

    private BlockPos inferredGoalFocus(ServerLevel level, PlayerProfile profile, PlayerProfile.LikelyGoal goal, long now, String dim) {
        if (goal == PlayerProfile.LikelyGoal.BUILDING || goal == PlayerProfile.LikelyGoal.RETURNING_HOME) {
            return profile.base == null ? null : profile.base.immutable();
        }
        if (goal == PlayerProfile.LikelyGoal.CAVE_EXPLORATION) {
            for (PlayerProfile.WorldMemory action : profile.recentWorldActions(now, dim, 4800L)) {
                BlockPos p = BlockPos.of(action.pos());
                if (p.getY() < 55 && level.hasChunkAt(p)) return p;
            }
        }
        if (goal == PlayerProfile.LikelyGoal.GATHERING) {
            for (PlayerProfile.WorldMemory action : profile.recentWorldActions(now, dim, 4800L)) {
                if (!action.placed()) {
                    BlockPos p = BlockPos.of(action.pos());
                    if (level.hasChunkAt(p)) return p;
                }
            }
        }
        List<BlockPos> hotspots = profile.hotspots();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos p : hotspots) {
            if (!level.hasChunkAt(p)) continue;
            double d = m.blockPosition().distSqr(p);
            if (d < 16 * 16 || d > 88 * 88) continue;
            if (target != null && target.blockPosition().distSqr(p) < 18 * 18) continue;
            if (d < bestDistance) { bestDistance = d; best = p; }
        }
        return best;
    }

    private BlockPos mindObservationPoint(ServerLevel level, BlockPos focus) {
        int[][] offsets = {{9,0},{-9,0},{0,9},{0,-9},{8,5},{-8,5},{8,-5},{-8,-5},{12,3},{-12,3},{3,12},{3,-12}};
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int[] offset : offsets) {
            BlockPos candidate = CoverFinder.groundNear(level, focus.getX() + offset[0], focus.getZ() + offset[1], focus.getY(), 4);
            if (candidate == null || isKnownTrap(level, candidate, 3) || isRecentlyFailedApproach(candidate, 4)) continue;
            double distance = candidate.distToCenterSqr(focus.getX() + 0.5D, focus.getY() + 0.5D, focus.getZ() + 0.5D);
            if (distance < 6 * 6 || distance > 18 * 18) continue;
            double cost = mindApproachCost(candidate);
            if (cost >= 0.0D && cost < bestCost) { best = candidate; bestCost = cost; }
        }
        return best;
    }

    /** A rough physical cost model: reachable paths are preferred; failed/long routes count against a plan. */
    private double mindApproachCost(BlockPos candidate) {
        if (candidate == null || m.level() == null) return -1.0D;
        double geometric = Math.sqrt(m.blockPosition().distSqr(candidate));
        try {
            Path path = m.getNavigation().createPath(candidate, 1);
            double verticalPenalty = Math.abs(candidate.getY() - m.getBlockY()) * 1.4D;
            double learnedPenalty = isRecentlyFailedApproach(candidate, 5) ? 40.0D : 0.0D;
            if (path == null || !path.canReach()) {
                // Don't pretend the route is easy: score it as costly, but keep it as a last-resort experiment
                // so the Manbear can test an alternative and remember the result instead of freezing forever.
                return geometric + 34.0D + verticalPenalty + learnedPenalty;
            }
            return path.getNodeCount() + verticalPenalty + learnedPenalty;
        } catch (RuntimeException ignored) {
            // An unavailable path preview gives only a weak estimate; live navigation still gets the final say.
            return geometric + 16.0D;
        }
    }

    private boolean isPlayerCommittedOrVulnerable() {
        if (target == null) return false;
        double healthRatio = target.getHealth() / Math.max(1.0F, target.getMaxHealth());
        return target.isUsingItem() || target.isInWater() || target.isPassenger()
                || (healthRatio < 0.48D) || (target.isSprinting() && dist > 20.0D);
    }

    private BlockPos mindStandNear(ServerLevel level, BlockPos focus) {
        int[][] offsets = {{2,0},{-2,0},{0,2},{0,-2},{3,1},{-3,1},{1,3},{1,-3},{3,-2},{-2,-3},{4,0},{-4,0},{0,4},{0,-4}};
        BlockPos best = null;
        BlockPos fallback = null;
        double bestCost = Double.MAX_VALUE;
        double fallbackDistance = Double.MAX_VALUE;
        for (int[] offset : offsets) {
            BlockPos candidate = CoverFinder.groundNear(level, focus.getX() + offset[0], focus.getZ() + offset[1], focus.getY(), 3);
            if (candidate == null || isKnownTrap(level, candidate, 3)) continue;
            double distance = m.blockPosition().distSqr(candidate);
            if (distance < fallbackDistance) { fallback = candidate; fallbackDistance = distance; }
            if (isRecentlyFailedApproach(candidate, 5)) continue;
            double cost = mindApproachCost(candidate);
            if (cost >= 0.0D && cost < bestCost) { best = candidate; bestCost = cost; }
        }
        // A geometrically close point is not necessarily a usable goal; reachable paths are preferred,
        // while costly fallbacks remain possible experiments that can teach him about his own limitations.
        return best;
    }

    private boolean isKnownTrap(ServerLevel level, BlockPos pos, int radius) {
        if (!ManbearConfig.TRAP_LEARNING_ENABLED.get() || pos == null
                || !mindKnownTrapDimension.equals(level.dimension().location().toString())) return false;
        long r2 = (long) radius * radius;
        for (Long packed : mindKnownTrapPositions) if (BlockPos.of(packed).distSqr(pos) <= r2) return true;
        return false;
    }

    private void rememberKnownTrap(ServerLevel level, BlockPos pos) {
        if (pos == null) return;
        String dimension = level.dimension().location().toString();
        if (!mindKnownTrapDimension.equals(dimension)) {
            mindKnownTrapPositions.clear();
            mindKnownTrapDimension = dimension;
        }
        for (Long packed : mindKnownTrapPositions) if (BlockPos.of(packed).distSqr(pos) <= 4 * 4) return;
        mindKnownTrapPositions.add(pos.asLong());
        while (mindKnownTrapPositions.size() > 16) mindKnownTrapPositions.remove(0);
    }

    private boolean isRecentlyFailedApproach(BlockPos pos, int radius) {
        long limit = (long) radius * radius;
        for (Long packed : mindFailedApproaches) {
            if (BlockPos.of(packed).distSqr(pos) <= limit) return true;
        }
        return false;
    }

    private void rememberFailedApproach(BlockPos pos) {
        if (pos == null) return;
        for (Long packed : mindFailedApproaches) {
            if (BlockPos.of(packed).distSqr(pos) <= 4 * 4) return;
        }
        mindFailedApproaches.add(pos.asLong());
        while (mindFailedApproaches.size() > 12) mindFailedApproaches.remove(0);
    }

    private void beginMindPlan(ServerLevel level, MindPlan plan, BlockPos goal, BlockPos lookAt) {
        mindPlan = plan;
        mindGoal = goal.immutable();
        mindLookAt = (lookAt == null ? goal : lookAt).immutable();
        mindPlanDimension = level.dimension().location().toString();
        mindPlanAge = 0;
        mindPlanWait = 0;
        mindStuckTicks = 0;
        mindRouteObservedTicks = 0;
        mindWaypointRetries = 0;
        mindRouteObserved = false;
        mindDeceptionObservedTicks = 0;
        mindDeceptionObserved = false;
        mindScriptCuePlayed = false;
        mindWatchLostTicks = 0;
        mindWatchMoves = 0;
        mindAnimalId = null;
        manipulationStage = 0;
        manipulationTimer = 0;
        manipulationChoiceScore = 0;
        manipulationChoice = 0;
        manipulationSawReaction = false;
        manipulationSecondaryFocus = null;
        manipulationBaitId = null;
        manipulationBaitItemId = "";
        mindLastPosition = m.position();
        mindPlanCooldown = 1600 + rnd().nextInt(2500);
        if (plan == MindPlan.DECEPTION) queueMindDecoySound(mindLookAt);
        m.getNavigation().stop();
    }

    private boolean tryStartCuriosityDistraction(ServerLevel level) {
        float curiosityChance = 0.018F + 0.055F * mindDistractibility;
        if (ManbearConfig.MIND_NEEDS_ENABLED.get()) curiosityChance += mindHunger * 0.065F;
        if (mindPlanAge < 55 || playerPerceivedWithin(16.0D)
                || ticksAlive % 20 != 0 || rnd().nextFloat() > curiosityChance) return false;
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, m.getBoundingBox().inflate(12.0D),
                a -> a.isAlive() && !a.isPassenger() && !(a instanceof Wolf w && w.isTame()));
        if (animals.isEmpty()) return false;
        Animal animal = animals.get(rnd().nextInt(animals.size()));
        if (mindPlan != MindPlan.DISTRACTION && mindGoal != null) {
            mindResumePlan = mindPlan;
            mindResumeGoal = mindGoal.immutable();
            mindResumeLookAt = mindLookAt == null ? mindGoal : mindLookAt.immutable();
            mindResumePending = true;
        }
        beginMindPlan(level, MindPlan.DISTRACTION, animal.blockPosition(), animal.blockPosition());
        mindAnimalId = animal.getUUID();
        // Don't discard the saved intention when beginMindPlan initializes a diversion.
        return true;
    }

    /** Counterfactual reasoning: classify the most plausible reason a plan failed, then remember it. */
    private String counterfactualFailureReason(MindPlan plan) {
        if (plan == MindPlan.INVESTIGATE_BAIT) return "BAIT_DID_NOT_CONFIRM_PLAYER_ROUTE";
        if (plan == MindPlan.DECEPTION) return mindDeceptionObserved ? "REACTION_DID_NOT_CREATE_OPENING" : "PLAYER_IGNORED_LURE";
        if (plan == MindPlan.TEST_ROUTE || plan == MindPlan.OBSERVE_GOAL) return "HYPOTHESIS_NOT_CONFIRMED";
        if (plan == MindPlan.INSPECT_TRAP) return "TRAP_APPROACH_UNREACHABLE_OR_UNINFORMATIVE";
        if (plan == MindPlan.FALSE_WEAKNESS) return manipulationSawReaction ? "PLAYER_APPROACHED_BUT_ESCAPED" : "PLAYER_DID_NOT_APPROACH";
        if (plan == MindPlan.BAITED_REWARD) return manipulationSawReaction ? "PLAYER_NOT_RECOVERED_OR_CONFIRMED_BAIT" : "PLAYER_IGNORED_STOLEN_REWARD";
        if (plan == MindPlan.DIVIDED_ATTENTION) return "PLAYER_DID_NOT_SELECT_A_CUE";
        if (plan == MindPlan.HIDDEN_OBJECTIVE) return "ALTERNATE_ROUTE_NOT_OBSERVED";
        if (plan == MindPlan.HIDDEN_ANSWER_TEST) return "PLAYER_DID_NOT_COMMIT_TO_EITHER_TRAIL";
        if (mindWaypointRetries >= 3 || navFail) return "PATH_OR_CLEARANCE_LIMITATION";
        if (target != null && los && looking) return "PLAYER_NOT_DISTRACTED";
        return "EXPECTED_EVIDENCE_NOT_FOUND";
    }

    private void finishMindPlan(ServerLevel level, boolean success) {
        PlayerProfile profile = mindProfile(level);
        MindPlan completed = mindPlan;
        BlockPos completedFocus = mindLookAt == null ? mindGoal : mindLookAt;
        String dimension = level.dimension().location().toString();
        String reason = success ? "EVIDENCE_SUPPORTED_PLAN" : counterfactualFailureReason(completed);
        if (isManipulationMindPlan(completed)) {
            reason = switch (completed) {
                case FALSE_WEAKNESS -> manipulationSawReaction ? "PLAYER_APPROACHED_FALSE_WEAKNESS" : "PLAYER_IGNORED_FALSE_WEAKNESS";
                case BAITED_REWARD -> manipulationSawReaction ? "PLAYER_INVESTIGATED_STOLEN_REWARD" : "PLAYER_IGNORED_STOLEN_REWARD";
                case DIVIDED_ATTENTION -> manipulationChoice == 1 ? "CHOSE_FIRST_CUE" : manipulationChoice == 2 ? "CHOSE_SECOND_CUE" : "NO_CLEAR_CUE_CHOICE";
                case HIDDEN_OBJECTIVE -> manipulationChoice == 1 ? "FOLLOWED_OBVIOUS_CUE" : manipulationChoice == 2 ? "USED_ALTERNATE_ROUTE" : "HIDDEN_OBJECTIVE_UNRESOLVED";
                case HIDDEN_ANSWER_TEST -> manipulationChoice == 1 ? "FOLLOWED_TRAIL_TOWARD_MANBEAR" : manipulationChoice == 2 ? "FOLLOWED_OPPOSITE_TRAIL" : "NO_TRAIL_SELECTED";
                default -> reason;
            };
        }
        if (profile != null && completed != MindPlan.NONE) {
            profile.recordEpisode(completed.name(), success ? "SUCCESS" : "FAILURE", reason,
                    completedFocus, dimension, level.getGameTime());
            if (completed == MindPlan.INVESTIGATE_CHANGE || completed == MindPlan.TEST_ROUTE
                    || completed == MindPlan.INSPECT_TRAP || completed == MindPlan.OBSERVE_GOAL || completed == MindPlan.DECEPTION) {
                profile.updateCampaign(completed.name(), completedFocus, dimension, level.getGameTime(), success);
            }
            if (completed == MindPlan.OBSERVE_GOAL) profile.adjustGoalConfidence(success, level.getGameTime());
            if (ManbearConfig.PLAYER_THEORY_ENABLED.get() && (completed == MindPlan.DECEPTION || completed == MindPlan.WILDLIFE_INSTRUMENT
                    || completed == MindPlan.DIVIDED_ATTENTION || completed == MindPlan.HIDDEN_OBJECTIVE
                    || completed == MindPlan.HIDDEN_ANSWER_TEST || completed == MindPlan.BAITED_REWARD)) profile.observeCueReaction(success);
            if (success && completed == MindPlan.HOUSE_REHEARSAL && mindLookAt != null) {
                profile.completeHouseRehearsal(mindLookAt, dimension, level.getGameTime());
            }
            PlayerProfile.UnfinishedTask oldTask = profile.unfinishedTask(level.getGameTime(), dimension);
            if (success && oldTask != null && completed.name().equals(oldTask.kind())) profile.clearUnfinishedTask();
            if (completed == MindPlan.CONTRADICTORY_TRACE && success && mindLookAt != null) {
                if (leaveContradictoryEvidence(level, profile, mindLookAt)) {
                    profile.markScriptedEvent("CONTRADICTORY_TRACE", level.getGameTime());
                    profile.adjustRouteBelief(mindLookAt, 0.12F, level.getGameTime());
                }
            }
            if ((completed == MindPlan.DIVIDED_ATTENTION || completed == MindPlan.HIDDEN_OBJECTIVE
                    || completed == MindPlan.HIDDEN_ANSWER_TEST) && manipulationChoice != 0) {
                BlockPos selected = manipulationChoice == 1 ? mindLookAt : manipulationSecondaryFocus;
                if (selected != null) profile.adjustRouteBelief(selected, 0.10F, level.getGameTime());
            }
            ManbearData.get(level.getServer()).setDirty();
        }
        if (profile != null && mindLookAt != null) {
            if (mindPlan == MindPlan.TEST_ROUTE) {
                // Repeated tests revise a belief gradually; absence of evidence is not proof after one attempt.
                profile.adjustRouteBelief(mindLookAt, success ? 0.18F : -0.07F, level.getGameTime());
            } else if (mindPlan == MindPlan.INVESTIGATE_BAIT) {
                // A bait item can create a false route belief. If it is gone on arrival, he slowly corrects that guess.
                profile.adjustRouteBelief(mindLookAt, success ? 0.08F : -0.15F, level.getGameTime());
            } else if (mindPlan == MindPlan.DECEPTION) {
                // If the player reacted, he overestimates how useful this location is; later failed tests can correct him.
                profile.adjustRouteBelief(mindLookAt, success ? 0.13F : -0.14F, level.getGameTime());
            } else if (mindPlan == MindPlan.INSPECT_TRAP && success && mindLookAt != null) {
                rememberKnownTrap(level, mindLookAt);
                // His conclusion is probabilistic: nearby approaches become less attractive, not impossible forever.
                profile.adjustRouteBelief(mindLookAt, -0.09F, level.getGameTime());
            }
            if (mindPlan == MindPlan.INVESTIGATE_BAIT && success && !mindLastBaitItemId.isBlank()) {
                mindPreferredItemId = mindLastBaitItemId;
            }
            ManbearData.get(level.getServer()).setDirty();
        }
        if (!success && mindGoal != null && mindPlan != MindPlan.DISTRACTION) rememberFailedApproach(mindGoal);
        if ((mindPlan == MindPlan.INVESTIGATE_CHANGE || mindPlan == MindPlan.DECEPTION) && ticksAlive - mindLastTraceAt > 240
                && rnd().nextFloat() < (mindPlan == MindPlan.DECEPTION ? 0.6F : 0.42F)) {
            Vec3 knownPlayer = perceivedPlayerPosition();
            Vec3 away = knownPlayer == null ? new Vec3(1, 0, 0) : m.position().subtract(knownPlayer);
            Evidence.leaveAs("FOOTPRINTS", level, this, m.position(), away, new ArrayList<>(trail));
            mindLastTraceAt = ticksAlive;
        }
        if (mindPlan == MindPlan.RITUAL) mindLastRitualAt = level.getGameTime();
        if (completed == MindPlan.PRIVATE_PROJECT && success && completedFocus != null) {
            placePrivateProjectMark(level, completedFocus);
            if (profile != null) profile.markScriptedEvent("PRIVATE_PROJECT", level.getGameTime());
        }
        if (success && mindPlan == MindPlan.INVESTIGATE_CHANGE && mindLookAt != null
                && rnd().nextFloat() < 0.14F + 0.12F * mindCuriosity) {
            mindRitualAnchor = mindLookAt.immutable(); // a place he inspected can become a personal return point
        }
        if (mindPlan == MindPlan.DISTRACTION && mindResumePending && mindResumePlan != MindPlan.NONE && mindResumeGoal != null) {
            mindPlan = mindResumePlan;
            mindGoal = mindResumeGoal.immutable();
            mindLookAt = mindResumeLookAt == null ? mindGoal : mindResumeLookAt.immutable();
            mindPlanDimension = level.dimension().location().toString();
            mindResumePending = false;
            mindResumePlan = MindPlan.NONE;
            mindResumeGoal = null;
            mindResumeLookAt = null;
            mindPlanAge = 0;
            mindPlanWait = 0;
            mindStuckTicks = 0;
            mindWaypointRetries = 0;
            mindLastPosition = m.position();
            mindAnimalId = null;
            mindPlanCooldown = 220 + rnd().nextInt(280);
            m.getNavigation().stop();
            return;
        }
        if (mindPlan == MindPlan.INVESTIGATE_BAIT) {
            mindBaitId = null;
            mindLastBaitItemId = "";
        }
        manipulationStage = 0;
        manipulationTimer = 0;
        manipulationChoiceScore = 0;
        manipulationChoice = 0;
        manipulationSawReaction = false;
        manipulationSecondaryFocus = null;
        manipulationBaitId = null;
        manipulationBaitItemId = "";
        mindPlan = MindPlan.NONE;
        mindGoal = null;
        mindLookAt = null;
        mindAnimalId = null;
        mindPlanAge = 0;
        mindPlanWait = 0;
        mindStuckTicks = 0;
        mindPlanCooldown = 1800 + rnd().nextInt(3200);
        if (m.getStance() == Stance.SIT) m.setStance(Stance.CROUCH);
        m.getNavigation().stop();
    }

    /** Leave two divergent trails from the same nearby area so the player must decide which evidence to trust. */
    private boolean leaveContradictoryEvidence(ServerLevel level, PlayerProfile profile, BlockPos focus) {
        if (focus == null || profile == null || profile.base == null || !level.hasChunkAt(focus)) return false;
        Vec3 fromBase = new Vec3(focus.getX() - profile.base.getX(), 0.0D, focus.getZ() - profile.base.getZ());
        if (fromBase.lengthSqr() < 0.1D) fromBase = new Vec3(1.0D, 0.0D, 0.0D);
        fromBase = fromBase.normalize();
        Vec3 towardBase = fromBase.scale(-1.0D);
        Vec3 lateral = new Vec3(-towardBase.z, 0.0D, towardBase.x);
        Vec3 origin = new Vec3(focus.getX() + 0.5D, focus.getY(), focus.getZ() + 0.5D);
        boolean first = Evidence.leaveAs("FOOTPRINTS", level, this, origin, towardBase, List.of());
        Vec3 branch = origin.add(lateral.scale(2.6D));
        boolean second = Evidence.leaveAs("FOOTPRINTS", level, this, branch, fromBase, List.of());
        return first || second;
    }

    /** Save an unfinished plan/temperament with the entity so a chunk unload doesn't erase intent. */
    public void saveMindPersistence(CompoundTag tag) {
        tag.putBoolean("MindInitialized", mindInitialized);
        tag.putString("MindPlan", mindPlan.name());
        tag.putString("MindResumePlan", mindResumePlan.name());
        tag.putBoolean("MindResumePending", mindResumePending);
        tag.putInt("MindPlanAge", mindPlanAge);
        tag.putInt("MindPlanWait", mindPlanWait);
        tag.putInt("MindPlanCooldown", mindPlanCooldown);
        tag.putInt("MindPlanRetries", mindWaypointRetries);
        tag.putInt("MindRouteObserved", mindRouteObservedTicks);
        tag.putBoolean("MindRouteSeen", mindRouteObserved);
        tag.putLong("MindLastInvestigatedAt", mindLastInvestigatedAt);
        tag.putLong("MindLastRitualAt", mindLastRitualAt);
        tag.putFloat("MindCuriosity", mindCuriosity);
        tag.putFloat("MindPatience", mindPatience);
        tag.putFloat("MindTerritoriality", mindTerritoriality);
        tag.putFloat("MindDistractibility", mindDistractibility);
        tag.putFloat("MindRitualism", mindRitualism);
        tag.putFloat("MindCaution", mindCaution);
        tag.putString("MindDimension", mindPlanDimension == null ? "" : mindPlanDimension);
        if (mindPlayerId != null) tag.putUUID("MindPlayer", mindPlayerId);
        if (mindAnimalId != null) tag.putUUID("MindAnimal", mindAnimalId);
        if (mindBaitId != null) tag.putUUID("MindBait", mindBaitId);
        if (mindGoal != null) tag.putLong("MindGoal", mindGoal.asLong());
        if (mindLookAt != null) tag.putLong("MindLookAt", mindLookAt.asLong());
        if (mindResumeGoal != null) tag.putLong("MindResumeGoal", mindResumeGoal.asLong());
        if (mindResumeLookAt != null) tag.putLong("MindResumeLookAt", mindResumeLookAt.asLong());
        if (mindLastInvestigated != null) tag.putLong("MindLastInvestigated", mindLastInvestigated.asLong());
        if (mindRitualAnchor != null) tag.putLong("MindRitualAnchor", mindRitualAnchor.asLong());
        if (mindPerceivedPlayerPos != null) tag.putLong("MindPerceivedPlayerPos", BlockPos.containing(
                mindPerceivedPlayerPos.x, mindPerceivedPlayerPos.y, mindPerceivedPlayerPos.z).asLong());
        tag.putLong("MindPerceivedAt", mindPerceivedAt);
        tag.putLong("MindLastWorldEvidenceAt", mindLastWorldEvidenceAt);
        tag.putLong("MindLastWorldEvidencePos", mindLastWorldEvidencePos);
        tag.putFloat("MindPerceptionConfidence", mindPerceptionConfidence);
        tag.putInt("MindDeceptionObservedTicks", mindDeceptionObservedTicks);
        tag.putBoolean("MindDeceptionObserved", mindDeceptionObserved);
        tag.putInt("MindDecoyCooldown", mindDecoyCooldown);
        tag.putBoolean("MindScriptCuePlayed", mindScriptCuePlayed);
        tag.putInt("MindWatchLostTicks", mindWatchLostTicks);
        tag.putInt("MindWatchMoves", mindWatchMoves);
        tag.putInt("ManipulationStage", manipulationStage);
        tag.putInt("ManipulationTimer", manipulationTimer);
        tag.putInt("ManipulationChoiceScore", manipulationChoiceScore);
        tag.putInt("ManipulationChoice", manipulationChoice);
        tag.putBoolean("ManipulationSawReaction", manipulationSawReaction);
        if (manipulationSecondaryFocus != null) tag.putLong("ManipulationSecondaryFocus", manipulationSecondaryFocus.asLong());
        if (manipulationBaitId != null) tag.putUUID("ManipulationBaitId", manipulationBaitId);
        tag.putString("ManipulationBaitItemId", manipulationBaitItemId == null ? "" : manipulationBaitItemId);
        tag.putInt("DeliberateEscapeTicks", deliberateEscapeTicks);
        tag.putDouble("DeliberateEscapeStartDistance", deliberateEscapeStartDistance);
        tag.putInt("MindLastTraceAt", mindLastTraceAt);
        tag.putFloat("MindFatigue", mindFatigue);
        tag.putFloat("MindHunger", mindHunger);
        tag.putFloat("MindTerritoryStress", mindTerritoryStress);
        tag.putString("MindPreferredItemId", mindPreferredItemId == null ? "" : mindPreferredItemId);
        tag.putString("MindLastBaitItemId", mindLastBaitItemId == null ? "" : mindLastBaitItemId);
        tag.putLong("MindLastNeedTick", mindLastNeedTick);
        tag.putLong("LastShieldObservationAt", lastShieldObservationAt);
        tag.putLong("LastEnvironmentReactionAt", lastEnvironmentReactionAt);
        tag.putString("MindKnownTrapDimension", mindKnownTrapDimension == null ? "" : mindKnownTrapDimension);
        tag.putInt("MindInjuryLevel", mindInjuryLevel); tag.putInt("MindInjuryTicks", mindInjuryTicks); tag.putLong("MindLastInjuryAt", mindLastInjuryAt);
        tag.putInt("OpportunityCostCooldown", opportunityCostCooldown);
        tag.putInt("ConcealmentPhase", concealmentPhase); tag.putInt("ConcealmentWait", concealmentWait);
        tag.putInt("ConcealmentWatchTicks", concealmentWatchTicks); tag.putInt("ConcealmentCooldown", concealmentCooldown);
        tag.putLong("LastConcealmentAt", lastConcealmentAt); tag.putString("ConcealmentDimension", concealmentDimension == null ? "" : concealmentDimension);
        if (concealmentGoal != null) tag.putLong("ConcealmentGoal", concealmentGoal.asLong());
        tag.putInt("ObjectUsePhase", objectUsePhase); tag.putInt("ObjectUseWait", objectUseWait); tag.putInt("ObjectUseCooldown", objectUseCooldown);
        tag.putString("ObjectUseDimension", objectUseDimension == null ? "" : objectUseDimension);
        if (objectUseGoal != null) tag.putLong("ObjectUseGoal", objectUseGoal.asLong());
        ListTag knownObjects = new ListTag(); for (Long packed : mindKnownObjectPositions) knownObjects.add(LongTag.valueOf(packed));
        tag.put("MindKnownObjects", knownObjects); tag.putString("MindKnownObjectsDimension", mindKnownObjectsDimension == null ? "" : mindKnownObjectsDimension);
        ListTag knownTraps = new ListTag();
        for (Long packed : mindKnownTrapPositions) knownTraps.add(LongTag.valueOf(packed));
        tag.put("MindKnownTraps", knownTraps);
        ListTag failed = new ListTag();
        for (Long packed : mindFailedApproaches) failed.add(LongTag.valueOf(packed));
        tag.put("MindFailedApproaches", failed);
    }

    public void loadMindPersistence(CompoundTag tag) {
        mindInitialized = tag.getBoolean("MindInitialized");
        mindPlan = safeMindPlan(tag.getString("MindPlan"));
        mindResumePlan = safeMindPlan(tag.getString("MindResumePlan"));
        mindResumePending = tag.getBoolean("MindResumePending");
        mindPlanAge = Math.max(0, tag.getInt("MindPlanAge"));
        mindPlanWait = Math.max(0, tag.getInt("MindPlanWait"));
        mindPlanCooldown = Math.max(0, tag.getInt("MindPlanCooldown"));
        mindWaypointRetries = Mth.clamp(tag.getInt("MindPlanRetries"), 0, 3);
        mindRouteObservedTicks = Math.max(0, tag.getInt("MindRouteObserved"));
        mindRouteObserved = tag.getBoolean("MindRouteSeen");
        mindLastInvestigatedAt = tag.getLong("MindLastInvestigatedAt");
        mindLastRitualAt = tag.getLong("MindLastRitualAt");
        mindCuriosity = Mth.clamp(tag.getFloat("MindCuriosity"), 0.05F, 1F);
        mindPatience = Mth.clamp(tag.getFloat("MindPatience"), 0.05F, 1F);
        mindTerritoriality = Mth.clamp(tag.getFloat("MindTerritoriality"), 0.05F, 1F);
        mindDistractibility = Mth.clamp(tag.getFloat("MindDistractibility"), 0.05F, 1F);
        mindRitualism = Mth.clamp(tag.getFloat("MindRitualism"), 0.05F, 1F);
        mindCaution = Mth.clamp(tag.getFloat("MindCaution"), 0.05F, 1F);
        mindPlanDimension = tag.getString("MindDimension");
        if (tag.hasUUID("MindPlayer")) mindPlayerId = tag.getUUID("MindPlayer");
        if (tag.hasUUID("MindAnimal")) mindAnimalId = tag.getUUID("MindAnimal");
        if (tag.hasUUID("MindBait")) mindBaitId = tag.getUUID("MindBait");
        if (tag.contains("MindGoal")) mindGoal = BlockPos.of(tag.getLong("MindGoal"));
        if (tag.contains("MindLookAt")) mindLookAt = BlockPos.of(tag.getLong("MindLookAt"));
        if (tag.contains("MindResumeGoal")) mindResumeGoal = BlockPos.of(tag.getLong("MindResumeGoal"));
        if (tag.contains("MindResumeLookAt")) mindResumeLookAt = BlockPos.of(tag.getLong("MindResumeLookAt"));
        if (tag.contains("MindLastInvestigated")) mindLastInvestigated = BlockPos.of(tag.getLong("MindLastInvestigated"));
        if (tag.contains("MindRitualAnchor")) mindRitualAnchor = BlockPos.of(tag.getLong("MindRitualAnchor"));
        if (tag.contains("MindPerceivedPlayerPos")) mindPerceivedPlayerPos = Vec3.atCenterOf(BlockPos.of(tag.getLong("MindPerceivedPlayerPos")));
        mindPerceivedAt = tag.contains("MindPerceivedAt") ? tag.getLong("MindPerceivedAt") : Long.MIN_VALUE;
        mindLastWorldEvidenceAt = tag.contains("MindLastWorldEvidenceAt") ? tag.getLong("MindLastWorldEvidenceAt") : Long.MIN_VALUE;
        mindLastWorldEvidencePos = tag.contains("MindLastWorldEvidencePos") ? tag.getLong("MindLastWorldEvidencePos") : Long.MIN_VALUE;
        mindPerceptionConfidence = Mth.clamp(tag.getFloat("MindPerceptionConfidence"), 0.0F, 1.0F);
        mindDeceptionObservedTicks = Math.max(0, tag.getInt("MindDeceptionObservedTicks"));
        mindDeceptionObserved = tag.getBoolean("MindDeceptionObserved");
        mindDecoyCooldown = Math.max(0, tag.getInt("MindDecoyCooldown"));
        mindScriptCuePlayed = tag.getBoolean("MindScriptCuePlayed");
        mindWatchLostTicks = Math.max(0, tag.getInt("MindWatchLostTicks"));
        mindWatchMoves = Mth.clamp(tag.getInt("MindWatchMoves"), 0, 2);
        manipulationStage = Mth.clamp(tag.getInt("ManipulationStage"), 0, 2);
        manipulationTimer = Math.max(0, tag.getInt("ManipulationTimer"));
        manipulationChoiceScore = Mth.clamp(tag.getInt("ManipulationChoiceScore"), -30, 30);
        manipulationChoice = Mth.clamp(tag.getInt("ManipulationChoice"), 0, 2);
        manipulationSawReaction = tag.getBoolean("ManipulationSawReaction");
        manipulationSecondaryFocus = tag.contains("ManipulationSecondaryFocus") ? BlockPos.of(tag.getLong("ManipulationSecondaryFocus")) : null;
        manipulationBaitId = tag.hasUUID("ManipulationBaitId") ? tag.getUUID("ManipulationBaitId") : null;
        manipulationBaitItemId = tag.getString("ManipulationBaitItemId");
        deliberateEscapeTicks = Math.max(0, tag.getInt("DeliberateEscapeTicks"));
        deliberateEscapeStartDistance = Math.max(0.0D, tag.getDouble("DeliberateEscapeStartDistance"));
        mindLastTraceAt = tag.contains("MindLastTraceAt") ? tag.getInt("MindLastTraceAt") : -10000;
        mindFatigue = Mth.clamp(tag.getFloat("MindFatigue"), 0.0F, 1.0F);
        mindHunger = Mth.clamp(tag.getFloat("MindHunger"), 0.0F, 1.0F);
        mindTerritoryStress = Mth.clamp(tag.getFloat("MindTerritoryStress"), 0.0F, 1.0F);
        mindPreferredItemId = tag.getString("MindPreferredItemId");
        mindLastBaitItemId = tag.getString("MindLastBaitItemId");
        mindLastNeedTick = tag.contains("MindLastNeedTick") ? tag.getLong("MindLastNeedTick") : Long.MIN_VALUE;
        lastShieldObservationAt = tag.contains("LastShieldObservationAt") ? tag.getLong("LastShieldObservationAt") : Long.MIN_VALUE;
        lastEnvironmentReactionAt = tag.contains("LastEnvironmentReactionAt") ? tag.getLong("LastEnvironmentReactionAt") : Long.MIN_VALUE;
        mindKnownTrapDimension = tag.getString("MindKnownTrapDimension");
        mindInjuryLevel = Mth.clamp(tag.getInt("MindInjuryLevel"), 0, 2); mindInjuryTicks = Math.max(0, tag.getInt("MindInjuryTicks"));
        mindLastInjuryAt = tag.contains("MindLastInjuryAt") ? tag.getLong("MindLastInjuryAt") : Long.MIN_VALUE;
        opportunityCostCooldown = Math.max(0, tag.getInt("OpportunityCostCooldown"));
        concealmentPhase = Mth.clamp(tag.getInt("ConcealmentPhase"), 0, 2); concealmentWait = Math.max(0, tag.getInt("ConcealmentWait"));
        concealmentWatchTicks = Math.max(0, tag.getInt("ConcealmentWatchTicks")); concealmentCooldown = Math.max(0, tag.getInt("ConcealmentCooldown"));
        lastConcealmentAt = tag.contains("LastConcealmentAt") ? tag.getLong("LastConcealmentAt") : Long.MIN_VALUE;
        concealmentDimension = tag.getString("ConcealmentDimension");
        if (tag.contains("ConcealmentGoal")) concealmentGoal = BlockPos.of(tag.getLong("ConcealmentGoal"));
        else concealmentGoal = null;
        objectUsePhase = Mth.clamp(tag.getInt("ObjectUsePhase"), 0, 2); objectUseWait = Math.max(0, tag.getInt("ObjectUseWait"));
        objectUseCooldown = Math.max(0, tag.getInt("ObjectUseCooldown")); objectUseDimension = tag.getString("ObjectUseDimension");
        if (tag.contains("ObjectUseGoal")) objectUseGoal = BlockPos.of(tag.getLong("ObjectUseGoal")); else objectUseGoal = null;
        mindKnownObjectPositions.clear(); ListTag knownObjects = tag.getList("MindKnownObjects", Tag.TAG_LONG);
        mindKnownObjectsDimension = tag.getString("MindKnownObjectsDimension");
        for (int i = Math.max(0, knownObjects.size() - 24); i < knownObjects.size(); i++) mindKnownObjectPositions.add(((LongTag) knownObjects.get(i)).getAsLong());
        mindKnownTrapPositions.clear();
        ListTag knownTraps = tag.getList("MindKnownTraps", Tag.TAG_LONG);
        for (int i = Math.max(0, knownTraps.size() - 16); i < knownTraps.size(); i++) mindKnownTrapPositions.add(((LongTag) knownTraps.get(i)).getAsLong());
        mindFailedApproaches.clear();
        ListTag failed = tag.getList("MindFailedApproaches", Tag.TAG_LONG);
        for (int i = Math.max(0, failed.size() - 12); i < failed.size(); i++) mindFailedApproaches.add(((LongTag) failed.get(i)).getAsLong());
        mindLastPosition = m.position();
        if (mindPlan != MindPlan.NONE && mindGoal == null) mindPlan = MindPlan.NONE;
    }

    private MindPlan safeMindPlan(String name) {
        try { return MindPlan.valueOf(name); } catch (Exception ignored) { return MindPlan.NONE; }
    }

    // ================================================================= target / senses / traits
    private void resolveTarget(ServerLevel level) {
        boolean denCaptivity = denCaptiveId != null
                && ((mode == ManbearMode.KIDNAP && kidPhase >= KID_DEN_GUARD)
                || (mode == ManbearMode.SLEEP && kidPhase == KID_DEN_SLEEP));
        if (denCaptivity) {
            ServerPlayer captive = level.getServer().getPlayerList().getPlayer(denCaptiveId);
            if (captive != null && captive.isAlive() && captive.serverLevel() == level) {
                target = captive;
                targetLock = Math.max(targetLock, 20 * 60 * 60);
            } else {
                target = null;
            }
            return;
        }
        if (target != null && !validTarget(target)) target = null;
        if (target == null || (targetLock <= 0 && ticksAlive % 100 == 0)) {
            ServerPlayer best = null;
            double bd = Double.MAX_VALUE;
            for (ServerPlayer p : level.players()) {
                if (!validTarget(p)) continue;
                double d = p.distanceToSqr(m);
                if (p == target) d -= 24 * 24;
                if (d < bd) { bd = d; best = p; }
            }
            target = best;
        }
    }

    private boolean validTarget(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative() && p.level() == m.level() && m.distanceToSqr(p) < 110 * 110;
    }

    private void sense(ServerLevel level) {
        dist = m.distanceTo(target);
        los = m.hasLineOfSight(target);
        Vec3 look = target.getViewVector(1F);
        Vec3 to = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        looking = los && dist < 70 && look.dot(to) > 0.72;
        lookedTicks = looking ? lookedTicks + 1 : Math.max(0, lookedTicks - 2);
        updatePerception(level);
    }

    /** Update the information he has actually observed. Direct sight is reliable; sound and old clues are not. */
    private void updatePerception(ServerLevel level) {
        if (target == null) return;
        long now = level.getGameTime();
        if (los && dist < 70.0D) {
            mindPerceivedPlayerPos = target.position();
            mindPerceivedAt = now;
            mindPerceptionConfidence = 1.0F;
            return;
        }

        PlayerProfile profile = mindProfile(level);
        if (profile != null) {
            for (PlayerProfile.WorldMemory memory : profile.recentWorldActions(now,
                    level.dimension().location().toString(), 100L)) {
                // Memories are returned newest-first. Only accept a strictly newer clue, otherwise
                // several edits made in one tick would make his estimated position bounce between them.
                boolean newer = memory.gameTime() > mindLastWorldEvidenceAt;
                if (!newer) continue;
                BlockPos evidence = BlockPos.of(memory.pos());
                if (m.blockPosition().distSqr(evidence) > 38L * 38L) continue;
                mindLastWorldEvidenceAt = memory.gameTime();
                mindLastWorldEvidencePos = memory.pos();
                mindPerceivedPlayerPos = Vec3.atCenterOf(evidence);
                mindPerceivedAt = now;
                // A nearby block change is a clue, not proof that the player is still there.
                mindPerceptionConfidence = 0.68F;
                return;
            }
        }

        // Sprinting can be heard at short range. The remembered source is deliberately imprecise.
        if (target.isSprinting() && dist < 22.0D && now % 10L == 0L) {
            double angle = rnd().nextDouble() * Math.PI * 2.0D;
            double offset = 2.5D + rnd().nextDouble() * 4.5D;
            mindPerceivedPlayerPos = target.position().add(Math.cos(angle) * offset, 0.0D, Math.sin(angle) * offset);
            mindPerceivedAt = now;
            mindPerceptionConfidence = 0.48F;
            return;
        }

        if (mindPerceivedAt != Long.MIN_VALUE) {
            long age = Math.max(0L, now - mindPerceivedAt);
            mindPerceptionConfidence = Math.max(0.0F, 1.0F - age / 360.0F);
        } else {
            mindPerceptionConfidence = 0.0F;
        }
    }

    /** Proximity for independent plans is based on observed/estimated location, not hidden coordinates. */
    private boolean playerPerceivedWithin(double radius) {
        Vec3 perceived = perceivedPlayerPosition();
        return perceived != null && mindPerceptionConfidence > 0.45F
                && m.position().distanceToSqr(perceived) < radius * radius;
    }

    /** Null means he has no sufficiently recent clue to position the player. */
    private Vec3 perceivedPlayerPosition() {
        if (los && target != null && dist < 70.0D) return target.position();
        return mindPerceivedPlayerPos != null && mindPerceptionConfidence > 0.04F
                ? mindPerceivedPlayerPos : null;
    }

    private BlockPos perceivedPlayerBlock() {
        Vec3 pos = perceivedPlayerPosition();
        return pos == null ? null : BlockPos.containing(pos.x, pos.y, pos.z);
    }

    private void refreshTraits(ServerLevel level) {
        ManbearData data = ManbearData.get(level.getServer());
        prof = data.profile(target.getUUID());
        rageF = prof.rageFactor();
        if (ManbearConfig.ADAPTATION.get()) {
            vig = prof.vigilance; panic = prof.panic; aggr = prof.aggression;
            camp = prof.camping; sprint = prof.sprintiness;
        } else {
            vig = 0.5F; panic = 0.5F; aggr = 0F; camp = 0F; sprint = 0.3F;
        }
        if (ticksAlive % 600 == 1) data.setDirty();
    }

    // ================================================================= mode switching
    private void setMode(ServerLevel level, ManbearMode nm) {
        if (nm == mode && modeTime < 5) return;
        exitMode(level);
        prevMode = mode;
        mode = nm;
        if (!isWatch(nm) && nm != ManbearMode.RETREAT) noticed = true;     // anything but stalking/hiding/peeking: you know about him
        if (nm != ManbearMode.CHASE && nm != ManbearMode.AMBUSH && nm != ManbearMode.GRAB) pitChase = false;
        modeTime = 0;
        m.setSyncedMode(nm);
        navFail = false;
        navFailCount = 0;
        unreachableTicks = 0;
        stuckTicks = 0;
        pathCd = 0;
        m.getNavigation().stop();
        enterMode(level);
    }

    private void exitMode(ServerLevel level) {
        breaker.cancel(level, m);
        m.setPeekSide(0);
        if (ducking) { ducking = false; m.setDucking(false); }
        lunge = Lunge.NONE;
        pounce = false;
        if (mode == ManbearMode.GRAB) { restoreXpBar(); held = null; lethalGrab = false; }
        if (mode == ManbearMode.PETNAP) dropPet(level);
        if (mode == ManbearMode.KIDNAP) { restoreXpBar(); held = null; }
        if (mode == ManbearMode.PITLURE && pit != null) {
            if (!pit.sprung()) PitTraps.removeAndFill(level, pit);   // the lure failed: put the ground back
            pit = null;
        }
        if (mode == ManbearMode.LURE) {
            if (lurePhase >= 2) releaseLureAnimal(level);
            else discardLureAnimal(level);
        }
        if (mode.isChaseLike()) ambushing = false;
    }

    private void enterMode(ServerLevel level) {
        RandomSource r = rnd();
        switch (mode) {
            case STALK -> {
                modeDuration = (int) ((160 + r.nextInt(240)) * (1F - 0.5F * rageF));   // angrier = more restless
                int lo = (int) (ManbearConfig.STALK_MIN_DISTANCE.get() * (1F - 0.45F * rageF));
                int hi = Math.max(lo + 1, (int) (ManbearConfig.STALK_MAX_DISTANCE.get() * (1F - 0.45F * rageF)));
                stalkRadius = Mth.clamp(lo + r.nextInt(hi - lo) - 4 * (1 - vig) + 4 * vig - 3 * camp, lo - 4, hi + 6);
                orbitDir = r.nextBoolean() ? 1 : -1;
                orbitFlip = 120 + r.nextInt(240);
                freezeTicks = 0;
            }
            case HIDE -> {
                modeDuration = 700;
                cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax(), lastHide);
                if (cover == null) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                lastHide = cover.hide();
                atHide = false;
                waitTicks = 80 + r.nextInt(140);
            }
            case PEEK -> {
                modeDuration = 900;
                if (cover == null || cover.peek() == null || cover.duck()) cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax());
                if (cover == null || cover.peek() == null || cover.duck()) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                peekPhase = 0;
                peeksLeft = 1 + r.nextInt(3);
            }
            case FAKE_CHASE -> {
                modeDuration = 420;
                chaseStartDistance = dist;
                fakePhase = 0;
                fakeStop = 3.5;
                fakeHitsLanded = 0;
                fakePauseExtensionTicks = 0;
                int lo = ManbearConfig.FAKE_CHASE_HITS_MIN.get();
                int hi = Math.max(lo, ManbearConfig.FAKE_CHASE_HITS_MAX.get());
                fakeHitsTarget = lo + r.nextInt(hi - lo + 1);
                pendingMelee = 0;
                meleeCd = 0;
                rushBurstTicks = 0;
                chasePauseTicks = 0;
                chasePauseAbilityArmed = r.nextFloat() < adaptiveStareChance();
                chaseTeleportAbilityArmed = r.nextDouble() < adaptiveTeleportArmChance();
                if (r.nextFloat() < 0.55F) m.playBear(ModSounds.ROAR.get(), 3.5F, 0.85F + r.nextFloat() * 0.2F);
            }
            case CHASE, AMBUSH -> {
                float panicScale = 0.8F + 0.6F * panic;
                modeDuration = (int) ((400 + r.nextInt(700)) * panicScale);
                if (mode == ManbearMode.AMBUSH) modeDuration = 700;
                chaseTicks = 0;
                chaseStartDistance = dist;
                if (ManbearConfig.ADAPTATION.get() && prof != null) {
                    // Every escape changes the next encounter: he gives longer pursuits and prioritizes route ambushes.
                    modeDuration += Math.min(360, prof.strategyStage * 60);
                }
                if (prevMode != ManbearMode.CHASE && prevMode != ManbearMode.AMBUSH && prevMode != ManbearMode.GRAB) {
                    // a new chase: a new number of hits he will take before he plays dead
                    int lo = ManbearConfig.FAKE_DEATH_MIN_HITS.get();
                    int hi = Math.max(lo, ManbearConfig.FAKE_DEATH_MAX_HITS.get());
                    chaseHits = 0;
                    fakeDeathAt = lo + r.nextInt(hi - lo + 1);
                }
                if (ticksAlive - lastChaseTick > 600) roarsUsed = 0;   // a new chase only after 30 s of calm
                roarCd = Math.max(roarCd, 100);
                hitsLanded = 0;
                lunge = Lunge.NONE;
                pendingMelee = 0;
                int patientDelay = ManbearConfig.ADAPTATION.get() && prof != null ? prof.strategyStage * 8 : 0;
                lungeCd = ambushing ? 0 : 30 + r.nextInt(30) + patientDelay;
                freezeTicks = 0;
                rushBurstTicks = 0;
                chasePauseTicks = 0;
                chasePauseAbilityArmed = mode == ManbearMode.CHASE && r.nextFloat() < adaptiveStareChance();
                chaseTeleportAbilityArmed = mode == ManbearMode.CHASE && r.nextDouble() < adaptiveTeleportArmChance();
                if (prof != null) prof.chases++;
                // unpredictable start: sometimes he roars first, sometimes he just stares for a breath, sometimes silence
                float roll = r.nextFloat();
                if (ambushing || roll < 0.45F) m.playBear(ModSounds.ROAR.get(), 4.5F, 0.8F + r.nextFloat() * 0.2F);
                if (!ambushing && roll > 0.7F) freezeTicks = 10 + r.nextInt(14);
            }
            case LAY_TRAP -> {
                modeDuration = 900;
                trapSite = null;
                if (trapCooldown <= 0 && ManbearConfig.MAX_TRAPS.get() > 0) {
                    trapSite = CoverFinder.findTrapSite(level, target, prof, r);
                }
                if (trapSite == null) { trapCooldown = 300; setMode(level, ManbearMode.STALK); return; }
                trapPhase = 0;
            }
            case LURE -> {
                modeDuration = 1800;
                lurePhase = 0;
                lureTimer = 0;
                lureStare = 0;
                lureTrapVariant = r.nextFloat() < ManbearConfig.LURE_TRAP_CHANCE.get().floatValue();
                cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax());
                if (cover == null) { lureDone = true; setMode(level, ManbearMode.STALK); return; }
            }
            case PATH -> {
                modeDuration = 1_000_000;
                pathTrigger = 4 + r.nextInt(9);   // he lunges when you get this close (4-12 blocks)
            }
            case FOG -> {
                modeDuration = 1_000_000;
                fogDist = -1;
            }
            case LURKER -> {
                modeDuration = 1_000_000;
                lurkSeen = 0;
            }
            case PETNAP -> {
                modeDuration = 1_000_000;
                lurkSeen = 0;
                petPhase = 0;
                petTimer = 0;
                petTarget = null;
                dropSpot = null;
                petToDen = false;
                petDenWaypoint = 0;
                petDenStuck = 0;
                petDenRetries = 0;
                petDenTotalStuck = 0;
                petTravelWaypoint = null;
            }
            case FLASH -> modeDuration = 1_000_000;
            case INTIMIDATE -> modeDuration = 1_000_000;
            case PITLURE -> {
                modeDuration = 1_000_000;
                pitPhase = 0;
                pit = null;
                pitTimer = 0;
                pitWait = 0;
            }
            case KIDNAP -> {
                modeDuration = 1_000_000;
                kidPhase = KID_SNEAK;
                kidSeen = 0;
                kidTimer = 0;
                kidStuck = 0;
                kidAttempts++;
                denCaptiveId = null;
                denCarryWaypoint = 0;
                denCarryStuck = 0;
                // A generated den is a valid kidnapping destination even when there is no natural cave nearby.
                ManbearData denData = ManbearData.get(level.getServer());
                boolean hasDenDestination = denData.hasDen()
                        && denData.denDimension().equals(level.dimension().location().toString());
                if (!hasDenDestination && CaveFinder.find(level, m.blockPosition(), ManbearConfig.KIDNAP_SEARCH_RADIUS.get(), 1, r).isEmpty()) {
                    kidAttempts = 99;
                    setMode(level, ManbearMode.STALK);
                    return;
                }
            }
            case GRAB -> {
                modeDuration = 20 * 12;
                m.getNavigation().stop();
                m.setClimbing(false);
            }
            case CAVE -> {
                modeDuration = 1_000_000;
                caveFleeing = false;
                m.setStance(Stance.SIT);
            }
            case BEHIND -> modeDuration = 1_000_000;
            case SLEEP -> {
                modeDuration = 1_000_000;
                m.setStance(Stance.SLEEP);
                m.setFaceTarget(null, 0F);
            }
            case HALLUCINATE -> {
                modeDuration = 1_000_000;
                hallSpawned = false;
                hallTimer = 0;
                hallGone = 0;
                hallTreeVariant = false;
                doppelId = null;
            }
            case PLAYDEAD -> {
                modeDuration = 1_000_000;
                deadTicks = 0;
                deadAway = 0;
                m.setStance(Stance.SLEEP);          // lies down exactly like a sleeper
                m.setClimbing(false);
            }
            case RETREAT -> modeDuration = m.leaving ? 1_000_000 : 100 + r.nextInt(120);
        }
    }

    private void onModeExpired(ServerLevel level) {
        if (m.leaving) { modeTime = 0; return; }
        switch (mode) {
            case CHASE, AMBUSH -> endChase(level);
            case FAKE_CHASE -> vanishFast(level);
            case GRAB -> endGrab(level, false);
            case RETREAT -> setMode(level, rnd().nextFloat() < 0.55F ? ManbearMode.HIDE : ManbearMode.STALK);
            default -> setMode(level, pickNextMode(false));
        }
    }

    private ManbearMode pickNextMode(boolean spontaneous) {
        boolean canChase = canChase();
        double tension = Mth.clamp(0.8 + (ticksAlive - lastChaseEnd) / 2400.0, 0.8, 2.0);
        double[] w = new double[ManbearMode.values().length];
        w[ManbearMode.STALK.ordinal()] = spontaneous ? 0 : 1.0;
        w[ManbearMode.HIDE.ordinal()] = coverFailCd > 0 ? 0 : 2.8 + 2.2 * vig;
        w[ManbearMode.PEEK.ordinal()] = coverFailCd > 0 ? 0 : 1.6 + 1.6 * vig;
        w[ManbearMode.FAKE_CHASE.ordinal()] = ((m.isFaker() || rageF > 0.4F) && ticksAlive > 200) ? 0.7 + 1.8 * vig * (1 - aggr) + 0.8 * panic : 0;
        w[ManbearMode.CHASE.ordinal()] = canChase ? (0.5 + 2.2 * panic + 1.2 * (1 - vig) + 1.2 * camp + 0.8 * sprint) * tension * (1.0 + 3.0 * rageF) : 0;
        w[ManbearMode.LAY_TRAP.ordinal()] = ((m.isTrapper() || rageF > 0.5F) && trapCooldown <= 0 && ManbearConfig.MAX_TRAPS.get() > 0) ? 0.6 + 2.0 * aggr + 1.0 * sprint + 1.0 * camp : 0;
        w[ManbearMode.KIDNAP.ordinal()] = (m.isKidnapper() && kidAttempts < 2 && ticksAlive > 200 && !m.leaving) ? 1.2 + 2.0 * rageF : 0;
        w[ManbearMode.AMBUSH.ordinal()] = 0;
        w[ManbearMode.RETREAT.ordinal()] = 0;
        w[ManbearMode.SLEEP.ordinal()] = 0;
        w[mode.ordinal()] = 0;
        double total = 0;
        for (double d : w) total += d;
        if (total <= 0) return ManbearMode.STALK;
        double pick = rnd().nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            pick -= w[i];
            if (pick <= 0) return ManbearMode.byId(i);
        }
        return ManbearMode.STALK;
    }

    /** once a second: random, unpredictable switches */
    private void spontaneous(ServerLevel level) {
        if (m.leaving || ticksAlive % 20 != 0) return;
        boolean calm = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK || mode == ManbearMode.LAY_TRAP;
        if (!calm) return;
        boolean canChase = canChase();

        // you wandered too close while he was lurking: pounce
        if (dist < 8.5 + 6.0 * rageF && modeTime > 60 && canChase && rnd().nextFloat() < 0.22F + 0.5F * rageF) {
            setMode(level, ManbearMode.CHASE);
            return;
        }
        // stared at for too long: duck out of sight
        if (mode == ManbearMode.STALK && lookedTicks > 40 && vig > 0.25F && rageF < 0.6F && coverFailCd <= 0) {
            setMode(level, ManbearMode.HIDE);
            return;
        }
        // out in the open he gets nervous quickly: duck behind the next tree
        if (mode == ManbearMode.STALK && coverFailCd <= 0 && rnd().nextFloat() < 0.10F) {
            setMode(level, ManbearMode.HIDE);
            return;
        }
        float p = (0.04F + 0.05F * (1F - vig)) * (1F + 2F * rageF);   // angry = erratic: switches behaviour far more often
        if (rnd().nextFloat() < p) setMode(level, pickNextMode(true));
    }

    // ================================================================= events
    /**
     * Every hit on him comes through here. Only WEAPON hits count toward the fake death: a sword, axe or trident in the
     * hand (or any item whose attack damage bonus is 3 or more, modded weapons included), or an arrow / thrown trident.
     * Fists, tools, snowballs and eggs do not.
     */
    public void onDamaged(ServerLevel level, DamageSource source, float amount) {
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        hitWithWeapon = direct instanceof net.minecraft.world.entity.projectile.AbstractArrow
                || (attacker instanceof ServerPlayer p && direct == attacker && isWeapon(p.getMainHandItem()));
        if (ManbearConfig.WOUND_MEMORY_ENABLED.get() && hitWithWeapon && attacker instanceof ServerPlayer injuredBy) {
            int added = amount >= 7.0F ? 2 : 1;
            mindInjuryLevel = Math.min(2, mindInjuryLevel + added);
            mindInjuryTicks = Math.max(mindInjuryTicks, amount >= 7.0F ? 1800 : 1000);
            mindLastInjuryAt = level.getGameTime();
            mindFatigue = Mth.clamp(mindFatigue + 0.12F, 0.0F, 1.0F);
            level.sendParticles(ParticleTypes.CRIT, m.getX(), m.getY() + 1.3D, m.getZ(), 5 + added * 3, 0.3D, 0.35D, 0.3D, 0.04D);
            PlayerProfile injuryProfile = ManbearData.get(level.getServer()).profile(injuredBy.getUUID());
            injuryProfile.recordEpisode("WOUND", "WEAPON_HIT", "CHASE_OR_APPROACH_BECAME_COSTLY", m.blockPosition(), level.dimension().location().toString(), level.getGameTime());
            ManbearData.get(level.getServer()).setDirty();
        }
        if (ManbearConfig.COMBAT_ADAPTATION_ENABLED.get() && attacker instanceof ServerPlayer combatPlayer) {
            PlayerProfile combatProfile = ManbearData.get(level.getServer()).profile(combatPlayer.getUUID());
            if (direct instanceof net.minecraft.world.entity.projectile.AbstractArrow) combatProfile.recordRangedHit();
            else if (direct == combatPlayer) combatProfile.recordMeleeHit();
            ManbearData.get(level.getServer()).setDirty();
        }
        if (direct instanceof net.minecraft.world.entity.projectile.Projectile && attacker instanceof ServerPlayer thrower) {
            onProjectile(level, thrower);       // arrows, snowballs, eggs, tridents...
        } else {
            onHurt(level, attacker);
        }
        hitWithWeapon = false;
    }

    /** Decide that fatigue, a wound, or a more valuable territorial objective outweighs this pursuit. */
    private boolean tryOpportunityCostDecision(ServerLevel level) {
        if (!ManbearConfig.OPPORTUNITY_COST_ENABLED.get() || target == null || ambushOnly || chaseTicks < 100
                || ticksAlive % 20 != 0 || opportunityCostCooldown > 0 || dist < 14.0D
                || breaker.active() || lunge != Lunge.NONE || held != null || denCaptiveId != null) return false;
        float pressure = 0.0F;
        if (ManbearConfig.MIND_NEEDS_ENABLED.get()) pressure += Math.max(0.0F, mindFatigue - 0.42F) * 0.95F;
        pressure += mindInjuryLevel * 0.20F;
        if (mindTerritoryStress > 0.58F) pressure += 0.10F;
        ManbearData data = ManbearData.get(level.getServer());
        boolean denWorthReturning = data.hasDen() && data.denDimension().equals(level.dimension().location().toString())
                && data.denEntrance() != null && level.hasChunkAt(data.denEntrance())
                && (m.blockPosition().distSqr(data.denCenter()) < 110L * 110L || mindInjuryLevel > 0);
        if (denWorthReturning) pressure += 0.18F * mindTerritoriality;
        if (pressure < 0.25F || rnd().nextFloat() > Mth.clamp(0.025F + pressure * 0.16F + 0.04F * mindPatience, 0.0F, 0.30F)) return false;
        PlayerProfile profile = data.profile(target.getUUID());
        profile.recordEpisode("OPPORTUNITY_COST", "CHASE_ABANDONED", denWorthReturning ? "DEN_OR_RECOVERY_OUTWEIGHED_PURSUIT" : "FATIGUE_OR_WOUND_OUTWEIGHED_PURSUIT",
                target.blockPosition(), level.dimension().location().toString(), level.getGameTime());
        profile.markScriptedEvent("OPPORTUNITY_COST", level.getGameTime());
        data.setDirty();
        opportunityCostCooldown = 1200 + rnd().nextInt(1800);
        deliberateEscapeTicks = 0;
        if (denWorthReturning && startReturnToDen(level)) return true;
        m.getNavigation().stop();
        setMode(level, ManbearMode.RETREAT);
        return true;
    }

    private Animal findInstrumentAnimal(ServerLevel level) {
        if (target == null) return null;
        Animal best = null;
        double bestScore = Double.MAX_VALUE;
        AABB search = target.getBoundingBox().inflate(10.0D, 4.0D, 10.0D);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, search, a -> a.isAlive()
                && !(a instanceof CompanionBird) && !a.getTags().contains("manbear_lure")
                && !a.getTags().contains(PetKidnap.CAPTIVE)
                && !(a instanceof OwnableEntity owned && owned.getOwnerUUID() != null))) {
            double playerDistance = animal.distanceToSqr(target);
            double bearDistance = animal.distanceToSqr(m);
            if (playerDistance < 3.0D * 3.0D || playerDistance > 11.0D * 11.0D || bearDistance > 45.0D * 45.0D) continue;
            double score = playerDistance + bearDistance * 0.15D;
            if (score < bestScore) { bestScore = score; best = animal; }
        }
        return best;
    }

    private void playWildlifeDistraction(ServerLevel level) {
        if (mindLookAt == null) return;
        Entity entity = mindAnimalId == null ? null : level.getEntity(mindAnimalId);
        BlockPos cue = entity instanceof Animal animal && animal.isAlive() ? animal.blockPosition() : mindLookAt;
        SoundEvent sound = entity instanceof Sheep ? SoundEvents.SHEEP_AMBIENT
                : entity instanceof Pig ? SoundEvents.PIG_AMBIENT
                : entity instanceof Cow ? SoundEvents.COW_AMBIENT : SoundEvents.CHICKEN_AMBIENT;
        float pitch = 0.82F + rnd().nextFloat() * 0.34F;
        level.playSound(null, cue.getX() + 0.5D, cue.getY() + 0.5D, cue.getZ() + 0.5D,
                sound, SoundSource.AMBIENT, 0.7F, pitch);
        if (entity instanceof Animal animal && animal.isAlive() && !animal.isPassenger()) {
            Vec3 away = animal.position().subtract(m.position());
            away = new Vec3(away.x, 0.0D, away.z);
            if (away.lengthSqr() > 0.01D) {
                away = away.normalize().scale(3.0D + rnd().nextDouble() * 3.0D);
                BlockPos fleeingPoint = CoverFinder.groundNear(level, animal.getX() + away.x, animal.getZ() + away.z, animal.getBlockY(), 3);
                if (fleeingPoint != null && fleeingPoint.distSqr(animal.blockPosition()) > 4L) {
                    // A brief navigation goal makes the creature's normal movement carry it away; no damage or ownership change.
                    animal.getNavigation().moveTo(fleeingPoint.getX() + 0.5D, fleeingPoint.getY(), fleeingPoint.getZ() + 0.5D, 1.1D);
                }
            }
        }
        // The Manbear watches whether the player turns toward the sound; he doesn't harm or command the animal.
        m.getLookControl().setLookAt(cue.getX() + 0.5D, cue.getY() + 0.5D, cue.getZ() + 0.5D, 15F, 15F);
    }

    private BlockPos findPrivateProjectSite(ServerLevel level, PlayerProfile profile) {
        if (target == null) return null;
        for (int attempt = 0; attempt < 18; attempt++) {
            double angle = rnd().nextDouble() * Math.PI * 2.0D;
            double radius = 15.0D + rnd().nextDouble() * 19.0D;
            BlockPos candidate = CoverFinder.groundNear(level, m.getX() + Math.cos(angle) * radius,
                    m.getZ() + Math.sin(angle) * radius, m.getBlockY(), 4);
            if (candidate == null || !level.hasChunkAt(candidate) || !level.getBlockState(candidate).isAir()
                    || !level.getBlockState(candidate.above()).isAir() || candidate.distSqr(m.blockPosition()) < 12L * 12L
                    || candidate.distSqr(target.blockPosition()) < 24L * 24L) continue;
            BlockPos floor = candidate.below();
            BlockState ground = level.getBlockState(floor);
            if (!ground.isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)
                    || !(ground.is(BlockTags.BASE_STONE_OVERWORLD) || ground.is(BlockTags.DIRT) || ground.is(Blocks.GRAVEL)
                    || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MOSSY_COBBLESTONE))) continue;
            if (profile.base != null && candidate.distSqr(profile.base) < 26L * 26L) continue;
            ManbearData data = ManbearData.get(level.getServer());
            if (data.hasDen() && data.denDimension().equals(level.dimension().location().toString())
                    && candidate.distSqr(data.denCenter()) < 24L * 24L) continue;
            return candidate.immutable();
        }
        return null;
    }

    private void placePrivateProjectMark(ServerLevel level, BlockPos feet) {
        if (!ManbearConfig.PRIVATE_PROJECTS_ENABLED.get() || feet == null || !level.hasChunkAt(feet)) return;
        BlockPos base = feet.below();
        BlockState ground = level.getBlockState(base);
        if (!ground.isFaceSturdy(level, base, net.minecraft.core.Direction.UP) || ground.is(Blocks.BEDROCK)
                || !(ground.is(BlockTags.BASE_STONE_OVERWORLD) || ground.is(BlockTags.DIRT) || ground.is(Blocks.GRAVEL)
                || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MOSSY_COBBLESTONE))) return;
        if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) return;
        PlayerProfile profile = mindProfile(level);
        if (profile != null && profile.base != null && profile.base.distSqr(feet) < 24L * 24L) return;
        // Small, quiet cairn: a trace of a private activity, never a block placed inside a player's structure.
        level.setBlock(feet, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
        if (rnd().nextFloat() < 0.42F && level.getBlockState(feet.above()).isAir()) {
            level.setBlock(feet.above(), Blocks.BONE_BLOCK.defaultBlockState(), 3);
        }
        level.sendParticles(ParticleTypes.ASH, feet.getX() + 0.5D, feet.getY() + 0.6D, feet.getZ() + 0.5D, 3, 0.15D, 0.12D, 0.15D, 0.004D);
    }

    /** Move to natural cover only after the player has watched him; movement begins during a gap in observation. */
    private boolean tickStrategicConcealment(ServerLevel level) {
        if (!ManbearConfig.STRATEGIC_CONCEALMENT_ENABLED.get()) { concealmentPhase = 0; concealmentGoal = null; return false; }
        if (concealmentPhase > 0) {
            if (target == null || !(mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK)
                    || dist < 6.0D || concealmentGoal == null || !level.hasChunkAt(concealmentGoal)
                    || (!concealmentDimension.isEmpty() && !concealmentDimension.equals(level.dimension().location().toString()))) {
                concealmentPhase = 0; concealmentGoal = null; concealmentDimension = ""; concealmentCooldown = Math.max(concealmentCooldown, 160); m.getNavigation().stop(); return false;
            }
            if (los && looking) {
                concealmentWatchTicks++;
                m.getNavigation().stop();
                m.getMoveControl().setWantedPosition(m.getX(), m.getY(), m.getZ(), 0.0D);
                m.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 18F, 18F);
                if (concealmentWatchTicks > 180) { concealmentPhase = 0; concealmentGoal = null; concealmentDimension = ""; concealmentCooldown = 260; }
                return true;
            }
            concealmentWatchTicks = 0;
            if (concealmentPhase == 1) {
                navTo(level, concealmentGoal.getX() + 0.5D, concealmentGoal.getY(), concealmentGoal.getZ() + 0.5D,
                        Math.max(0.78D, refSpeedMod() * 0.9D), 4);
                if (m.position().distanceToSqr(Vec3.atBottomCenterOf(concealmentGoal)) < 2.8D * 2.8D) {
                    m.getNavigation().stop(); concealmentPhase = 2; concealmentWait = 24 + rnd().nextInt(45);
                }
                return true;
            }
            m.getNavigation().stop();
            if (--concealmentWait <= 0) {
                concealmentPhase = 0; concealmentGoal = null; concealmentDimension = ""; concealmentCooldown = 700 + rnd().nextInt(900);
            }
            return true;
        }
        if (concealmentCooldown > 0 || target == null || !(mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK)
                || !los || !looking || dist < 8.0D || dist > 36.0D || noticeTicks < 8
                || (lastConcealmentAt != Long.MIN_VALUE && level.getGameTime() - lastConcealmentAt < 200L)) return false;
        PlayerProfile profile = ManbearConfig.PLAYER_THEORY_ENABLED.get() ? mindProfile(level) : null;
        float observedHabit = profile == null ? 0.5F : profile.monitorsManbear;
        float chance = 0.012F + 0.07F * observedHabit + 0.025F * mindCaution;
        if (rnd().nextFloat() >= chance) return false;
        CoverFinder.Cover picked = CoverFinder.findCover(level, m, target, 4, 13, lastHide);
        if (picked == null || picked.hide().distSqr(m.blockPosition()) < 12L) return false;
        concealmentGoal = picked.hide().immutable();
        concealmentPhase = 1;
        concealmentWatchTicks = 0;
        lastConcealmentAt = level.getGameTime();
        concealmentDimension = level.dimension().location().toString();
        return true;
    }

    private boolean isSimpleMechanism(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) return !state.is(Blocks.IRON_DOOR);
        if (state.getBlock() instanceof TrapDoorBlock) return !state.is(Blocks.IRON_TRAPDOOR);
        return state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof LeverBlock;
    }

    private void rememberMechanism(BlockPos pos) {
        if (pos == null) return;
        for (Long packed : mindKnownObjectPositions) if (BlockPos.of(packed).distSqr(pos) <= 2L) return;
        mindKnownObjectPositions.add(pos.asLong());
        while (mindKnownObjectPositions.size() > 24) mindKnownObjectPositions.remove(0);
    }

    /** Learn and later manipulate only accessible, previously inspected doors, trapdoors, gates, and levers. */
    private boolean tickLearnedObjectUse(ServerLevel level) {
        if (!ManbearConfig.LEARNED_OBJECT_USE_ENABLED.get()) { objectUsePhase = 0; objectUseGoal = null; return false; }
        if (objectUsePhase > 0) {
            if (target == null || mode != ManbearMode.STALK || (dist < 12.0D) || (los && looking)
                    || objectUseGoal == null || !level.hasChunkAt(objectUseGoal)
                    || (!objectUseDimension.isEmpty() && !objectUseDimension.equals(level.dimension().location().toString()))) {
                objectUsePhase = 0; objectUseGoal = null; objectUseDimension = ""; objectUseCooldown = 180; m.getNavigation().stop(); return false;
            }
            if (objectUsePhase == 1) {
                navTo(level, objectUseGoal.getX() + 0.5D, objectUseGoal.getY(), objectUseGoal.getZ() + 0.5D, 0.72D, 4);
                if (m.position().distanceToSqr(Vec3.atBottomCenterOf(objectUseGoal)) < 2.4D * 2.4D) {
                    m.getNavigation().stop(); objectUsePhase = 2; objectUseWait = 20 + rnd().nextInt(28);
                }
                return true;
            }
            m.getNavigation().stop();
            if (--objectUseWait <= 0) {
                BlockState state = level.getBlockState(objectUseGoal);
                if (isSimpleMechanism(state) && target != null && dist > 12.0D && !looking) {
                    BlockState changed = state;
                    boolean interacted = false;
                    if (state.getBlock() instanceof DoorBlock door) {
                        door.setOpen(null, level, state, objectUseGoal, !state.getValue(DoorBlock.OPEN));
                        interacted = true;
                    } else if (state.getBlock() instanceof TrapDoorBlock) {
                        changed = state.cycle(TrapDoorBlock.OPEN);
                        interacted = changed != state;
                    } else if (state.getBlock() instanceof FenceGateBlock) {
                        changed = state.cycle(FenceGateBlock.OPEN);
                        interacted = changed != state;
                    } else if (state.getBlock() instanceof LeverBlock) {
                        changed = state.cycle(LeverBlock.POWERED);
                        interacted = changed != state;
                    }
                    if (changed != state) {
                        level.setBlock(objectUseGoal, changed, 3);
                        level.updateNeighborsAt(objectUseGoal, changed.getBlock());
                    }
                    if (interacted) {
                        level.playSound(null, objectUseGoal, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.35F, 0.65F + rnd().nextFloat() * 0.25F);
                        PlayerProfile profile = mindProfile(level);
                        if (profile != null) {
                            profile.recordEpisode("DEFENSE_TOOL", "USED", hasNearbyRedstoneCircuit(level, objectUseGoal)
                                    ? "OPERATED_KNOWN_CIRCUIT" : "MANIPULATED_KNOWN_MECHANISM",
                                    objectUseGoal, level.dimension().location().toString(), level.getGameTime());
                            profile.markScriptedEvent("DEFENSE_TOOL_USE", level.getGameTime());
                            ManbearData.get(level.getServer()).setDirty();
                        }
                    }
                }
                objectUsePhase = 0; objectUseGoal = null; objectUseDimension = ""; objectUseCooldown = 1200 + rnd().nextInt(1800);
            }
            return true;
        }
        if (objectUseCooldown > 0 || target == null || mode != ManbearMode.STALK || dist < 15.0D || dist > 42.0D || looking || ticksAlive % 20 != 0) return false;
        String currentDimension = level.dimension().location().toString();
        if (!mindKnownObjectsDimension.equals(currentDimension)) {
            mindKnownObjectPositions.clear();
            mindKnownObjectsDimension = currentDimension;
        }
        BlockPos origin = m.blockPosition();
        // A block is learned only by passing close enough to inspect it while the player is not watching.
        for (int dx = -5; dx <= 5; dx++) for (int dy = -2; dy <= 3; dy++) for (int dz = -5; dz <= 5; dz++) {
            BlockPos p = origin.offset(dx, dy, dz);
            if (level.hasChunkAt(p) && isSimpleMechanism(level.getBlockState(p)) && origin.distSqr(p) <= 6L * 6L) rememberMechanism(p);
        }
        if (mindKnownObjectPositions.isEmpty() || rnd().nextFloat() > 0.008F + 0.006F * mindCuriosity) return false;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (Long packed : mindKnownObjectPositions) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.hasChunkAt(pos) || !isSimpleMechanism(level.getBlockState(pos))
                    || pos.distToCenterSqr(target.getX(), target.getY(), target.getZ()) < 14.0D * 14.0D) continue;
            double manbearDistance = pos.distToCenterSqr(m.getX(), m.getY(), m.getZ());
            if (manbearDistance <= 4.0D * 4.0D || manbearDistance >= 18.0D * 18.0D) continue;
            double playerDistance = pos.distToCenterSqr(target.getX(), target.getY(), target.getZ());
            double score = Math.sqrt(manbearDistance) + Math.max(0.0D, Math.sqrt(playerDistance) - 18.0D) * 0.12D;
            // A lever attached to a discovered circuit is more strategically interesting than an isolated switch.
            if (level.getBlockState(pos).getBlock() instanceof LeverBlock && hasNearbyRedstoneCircuit(level, pos)) score -= 5.5D;
            // Mechanisms near the player's known base are more likely to be part of a defensive route.
            PlayerProfile profile = mindProfile(level);
            if (profile != null && profile.base != null && pos.distSqr(profile.base) < 24L * 24L) score -= 2.0D;
            if (isRecentlyFailedApproach(pos, 3)) score += 8.0D;
            if (score < bestScore) { best = pos; bestScore = score; }
        }
        if (best == null) return false;
        objectUseGoal = best.immutable(); objectUsePhase = 1; objectUseDimension = level.dimension().location().toString(); return true;
    }

    private boolean hasNearbyRedstoneCircuit(ServerLevel level, BlockPos center) {
        for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos p = center.offset(dx, dy, dz);
            if (!level.hasChunkAt(p)) continue;
            BlockState state = level.getBlockState(p);
            if (state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.REPEATER) || state.is(Blocks.COMPARATOR)
                    || state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)
                    || state.is(Blocks.DISPENSER) || state.is(Blocks.DROPPER)) return true;
        }
        return false;
    }

    private static boolean isWeapon(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return false;
        net.minecraft.world.item.Item item = stack.getItem();
        if (item instanceof net.minecraft.world.item.SwordItem || item instanceof AxeItem || item instanceof net.minecraft.world.item.TridentItem) return true;
        double bonus = 0;       // modded weapons: a real attack damage bonus (swords 3-7, axes 6-9; pickaxes 1, shovels 1.5, hoes 0)
        for (net.minecraft.world.entity.ai.attributes.AttributeModifier mod
                : stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (mod.getOperation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION) bonus += mod.getAmount();
        }
        return bonus >= 3.0;
    }

    public void onHurt(ServerLevel level, Entity attacker) {
        if (!(attacker instanceof ServerPlayer sp)) return;
        ManbearData data = ManbearData.get(level.getServer());
        data.profile(sp.getUUID()).onHitManbear();
        data.setDirty();
        target = sp;
        targetLock = 400;
        noticed = true;
        if (mode == ManbearMode.SLEEP) { wake(level); return; }
        if (ManbearConfig.FAKE_DEATH.get() && hitWithWeapon && mode.isChaseLike() && !ambushOnly && !m.leaving && ++chaseHits >= fakeDeathAt) {
            startPlayDead(level, sp);       // you hurt him enough: he drops like a stone
            return;
        }
        if (manStunCd == 0 && !m.leaving && (mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH || (mode == ManbearMode.GRAB && !lethalGrab)
                || (mode == ManbearMode.KIDNAP && kidPhase == KID_CARRY))
                && sp.getMainHandItem().getItem() instanceof AxeItem axe && axe.getTier().getLevel() >= 2) {
            stunHim(level, sp);       // iron, diamond or netherite axe
            return;
        }
        if (mode == ManbearMode.GRAB) return;   // too busy holding you
        if (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) return;
        if (mode == ManbearMode.CAVE) { caveFleeing = true; beginLeaving(level); return; }
        if (mode == ManbearMode.FOG) { beginLeaving(level); return; }
        if (mode == ManbearMode.PATH) { pounceChase(level, false, 8.0F); return; }
        if (m.leaving) return;
        if (m.getHealth() < m.getMaxHealth() * 0.3F) { beginLeaving(level); return; }
        if (!mode.isChaseLike()) {
            float r = rnd().nextFloat();
            if (r < 0.35F) setMode(level, ManbearMode.CHASE);
            else if (r < 0.85F) setMode(level, ManbearMode.RETREAT);
            else if (trapCooldown <= 0) setMode(level, ManbearMode.LAY_TRAP);
        }
    }

    public void beginLeaving(ServerLevel level) {
        ManbearData.get(level.getServer()).clearDenGuardian(m.getUUID());
        denCaptiveId = null;
        returningToDen = false;
        returnDenEntrance = null;
        chasePauseTicks = 0;
        rushBurstTicks = 0;
        if (!m.leaving) leaveFromWatch = isWatch(mode);
        m.leaving = true;
        setMode(level, ManbearMode.RETREAT);
        modeDuration = 1_000_000;
    }

    /** a trap he laid has just been sprung */
    public void ambush(ServerPlayer p, BlockPos pos) {
        if (m.leaving || !(m.level() instanceof ServerLevel level)) return;
        if (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) return;
        target = p;
        targetLock = 800;
        ambushing = true;
        ambushOnly = true;      // a trap ambush is a single lunge, not a chase
        setMode(level, ManbearMode.AMBUSH);
        refreshTraits(level);
    }

    public void forceMode(ServerLevel level, ManbearMode mm) {
        ambushing = false;
        modeTime = 100;
        setMode(level, mm);
    }

    private double adaptiveStareChance() {
        double chance = ManbearConfig.CHASE_STARE_ABILITY_CHANCE.get();
        if (ManbearConfig.ADAPTATION.get() && prof != null) {
            // After a successful escape he becomes more patient and more likely to use the silent waiting tactic.
            chance += Math.min(0.30, prof.strategyStage * 0.05);
        }
        return Mth.clamp(chance, 0.0, 0.9);
    }

    private double adaptiveTeleportArmChance() {
        double chance = ManbearConfig.CHASE_TELEPORT_ABILITY_CHANCE.get();
        if (ManbearConfig.ADAPTATION.get() && prof != null) {
            chance += Math.min(0.40, prof.strategyStage * 0.065);
            chance += Math.min(0.22, prof.runEscapes * 0.035);
            chance += Math.min(0.22, prof.shelterEscapes * 0.035);
        }
        return Mth.clamp(chance, 0.0, 0.95);
    }

    /** Number of adjacent blocks he can tear out in one wall breach after learning that shelter is your habit. */
    private int adaptiveBreakBonus() {
        if (!ManbearConfig.ADAPTATION.get() || prof == null) return 0;
        return Mth.clamp(prof.shelterEscapes / 2, 0, 4);
    }

    // ================================================================= movement helpers
    private boolean canFit(ServerLevel level, Stance s) {
        return m.fitsIgnoringLeaves(s.dims.makeBoundingBox(m.position()));   // leaves do not count: he walks through them
    }

    /** a point about 1.5 blocks along the way he is going (next path node, else toward you) */
    private Vec3 aheadPoint() {
        Vec3 dir = null;
        Path p = m.getNavigation().getPath();
        if (p != null && !p.isDone()) {
            dir = Vec3.atBottomCenterOf(p.getNextNodePos()).subtract(m.position());
        } else if (target != null) {
            dir = target.position().subtract(m.position());
        }
        if (dir == null) return null;
        dir = new Vec3(dir.x, 0, dir.z);
        if (dir.lengthSqr() < 1.0E-4) return null;
        return m.position().add(dir.normalize().scale(1.5));
    }

    /** deep enough water that he swims instead of wading */
    private boolean swimming() {
        return m.isInWater() && m.getFluidHeight(FluidTags.WATER) > 1.2D;
    }

    /** any real water (rivers are often only 1 block deep) */
    private boolean inWater() {
        return m.isInWater() && m.getFluidHeight(FluidTags.WATER) > 0.4D;
    }

    /** not lava / not a long drop of lava straight ahead */
    private boolean safeToCharge(ServerLevel level) {
        Vec3 d = target.position().subtract(m.position());
        Vec3 h = new Vec3(d.x, 0, d.z);
        if (h.lengthSqr() < 1.0E-4) return false;
        h = h.normalize();
        for (int i = 1; i <= 3; i++) {
            BlockPos ahead = BlockPos.containing(m.getX() + h.x * i, m.getY(), m.getZ() + h.z * i);
            if (level.getFluidState(ahead).is(FluidTags.LAVA) || level.getFluidState(ahead.below()).is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    private void updateStance(ServerLevel level) {
        if (ticksAlive % 3 != 0) return;
        Stance cur = m.getStance();
        if (mindPlan == MindPlan.RITUAL && mindGoal != null
                && m.position().distanceToSqr(Vec3.atBottomCenterOf(mindGoal)) < 3.4D * 3.4D) {
            if (cur != Stance.SIT) m.setStance(Stance.SIT);
            return;
        }
        if (mode == ManbearMode.PLAYDEAD) {
            if (cur != Stance.SLEEP) m.setStance(Stance.SLEEP);
            return;
        }
        if (swimming()) {
            if (cur != Stance.SWIM) m.setStance(Stance.SWIM);
            return;
        }
        if (mode == ManbearMode.CAVE && !caveFleeing) {
            if (cur != Stance.SIT) m.setStance(Stance.SIT);
            return;
        }
        if (ducking && mode == ManbearMode.HIDE) {
            if (cur != Stance.DUCK) m.setStance(Stance.DUCK);
            return;
        }
        Stance want;
        if (mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE || mode == ManbearMode.BEHIND
                || mode == ManbearMode.PATH || mode == ManbearMode.FOG || mode == ManbearMode.FLASH
                || (mode == ManbearMode.PETNAP && petPhase >= 1) || mode == ManbearMode.INTIMIDATE || (mode == ManbearMode.RETREAT && m.leaving)
                || (mode == ManbearMode.LURE && lurePhase >= 3) || mode == ManbearMode.PITLURE || mode == ManbearMode.HALLUCINATE
                || (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY)) want = Stance.STAND;
        else want = dist < 60 ? Stance.CROUCH : Stance.STAND;
        if (stanceLock > 0 && want.ordinal() < cur.ordinal() && cur != Stance.SWIM) want = cur;
        // shrink to whatever fits here AND a step ahead: low doorways, tunnels, branches
        // Crawl before ducking: if a tunnel is too short for a standing/crouched body, he drops to the crawl animation.
        Stance[] ladder = {Stance.STAND, Stance.CROUCH, Stance.CRAWL, Stance.DUCK};
        int idx = 0;
        while (idx < ladder.length - 1 && ladder[idx] != want) idx++;
        Vec3 ahead = aheadPoint();
        while (idx < ladder.length - 1 && (!canFit(level, ladder[idx])
                || (ahead != null && !m.fitsIgnoringLeaves(ladder[idx].dims.makeBoundingBox(ahead))))) idx++;
        want = ladder[idx];
        if (want != cur) m.setStance(want);
    }

    /** Paths to the goal; if the current body is too big for the route, crouches or crawls to fit. */
    private boolean moveToSmart(ServerLevel level, double x, double y, double z, double speed) {
        PathNavigation nav = m.getNavigation();
        Path best = nav.createPath(x, y, z, 1);
        if (best != null && best.canReach()) {
            nav.moveTo(best, speed);
            return true;
        }
        Stance cur = m.getStance();
        for (Stance s : Stance.values()) {
            if (s.ordinal() >= Stance.SLEEP.ordinal() || s.ordinal() <= cur.ordinal() || !canFit(level, s)) continue;
            m.setStance(s);
            Path p = nav.createPath(x, y, z, 1);
            if (p != null && p.canReach()) {
                nav.moveTo(p, speed);
                stanceLock = 60;
                return true;
            }
        }
        m.setStance(cur);
        if (best != null) nav.moveTo(best, speed);
        return false;
    }

    /** In deep water ground pathing is useless, so he just swims straight at the goal. */
    private void swimToward(double x, double y, double z, double speed) {
        Vec3 to = new Vec3(x - m.getX(), y - m.getY(), z - m.getZ());
        double len = to.length();
        if (len < 0.4) return;
        Vec3 dir = to.scale(1.0 / len);
        if (!swimming()) {   // wading in a shallow river: push along the bottom, do not float up
            dir = new Vec3(dir.x, Mth.clamp(dir.y, -0.2, 0.35), dir.z);
            dir = dir.normalize();
        }
        double perTick = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) * speed * 2.1 * ManbearConfig.SWIM_SPEED_FACTOR.get();
        Vec3 v = m.getDeltaMovement();
        m.impulse(v.scale(0.4).add(dir.scale(perTick * 0.6)));
    }

    private void navTo(ServerLevel level, double x, double y, double z, double speed, int every) {
        if (inWater()) {
            m.getNavigation().stop();
            navFail = false;
            swimToward(x, y, z, speed);
            return;
        }
        if (pathCd > 0 && !m.getNavigation().isDone()) {
            pathCd--;
            m.getNavigation().setSpeedModifier(speed);
            return;
        }
        pathCd = every;
        BlockPos detour = learnedTrapDetour(level, x, y, z);
        if (detour != null) {
            x = detour.getX() + 0.5D;
            y = detour.getY();
            z = detour.getZ() + 0.5D;
        }
        navFail = !moveToSmart(level, x, y, z, speed);
        navFailCount = navFail ? navFailCount + 1 : 0;
    }

    /** If a memorized trap sits across his current travel line, he tries to walk around it. */
    private BlockPos learnedTrapDetour(ServerLevel level, double goalX, double goalY, double goalZ) {
        if (!ManbearConfig.TRAP_LEARNING_ENABLED.get() || mindKnownTrapPositions.isEmpty()
                || !mindKnownTrapDimension.equals(level.dimension().location().toString())) return null;
        Vec3 start = m.position();
        Vec3 goal = new Vec3(goalX, goalY, goalZ);
        Vec3 segment = goal.subtract(start);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 36.0D) return null;
        for (Long packed : mindKnownTrapPositions) {
            BlockPos trap = BlockPos.of(packed);
            Vec3 trapCenter = Vec3.atCenterOf(trap);
            double t = Mth.clamp(trapCenter.subtract(start).dot(segment) / lengthSqr, 0.0D, 1.0D);
            if (t < 0.08D || t > 0.96D) continue;
            Vec3 closest = start.add(segment.scale(t));
            if (closest.distanceToSqr(trapCenter) > 3.2D * 3.2D
                    || start.distanceToSqr(trapCenter) < 3.0D * 3.0D) continue;
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            int[][] offsets = {{6,0},{-6,0},{0,6},{0,-6},{5,4},{-5,4},{5,-4},{-5,-4},{4,5},{-4,5},{4,-5},{-4,-5}};
            for (int[] offset : offsets) {
                BlockPos candidate = CoverFinder.groundNear(level, trap.getX() + offset[0] + 0.5D,
                        trap.getZ() + offset[1] + 0.5D, trap.getY(), 3);
                if (candidate == null || isKnownTrap(level, candidate, 3)) continue;
                Vec3 candidateCenter = Vec3.atBottomCenterOf(candidate);
                if (segmentDistanceSqr(start, candidateCenter, trapCenter) < 2.5D * 2.5D) continue;
                if (segmentDistanceSqr(candidateCenter, goal, trapCenter) < 3.4D * 3.4D) continue;
                double score = start.distanceToSqr(candidateCenter) + candidateCenter.distanceToSqr(goal) * 0.75D;
                if (score < bestScore) { bestScore = score; best = candidate; }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static double segmentDistanceSqr(Vec3 start, Vec3 end, Vec3 point) {
        Vec3 segment = end.subtract(start);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 1.0E-6D) return point.distanceToSqr(start);
        double t = Mth.clamp(point.subtract(start).dot(segment) / lengthSqr, 0.0D, 1.0D);
        return point.distanceToSqr(start.add(segment.scale(t)));
    }

    private boolean reached(BlockPos p, double sqr) {
        return m.position().distanceToSqr(Vec3.atBottomCenterOf(p)) < sqr;
    }

    /** the player's current top speed as a speed modifier for us (1.0 = walk, 1.3 = sprint) */
    private double refSpeedMod() {
        double base = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        double ps = target == null ? base : target.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (target != null && !target.isSprinting()) ps *= 1.3D;
        double modifier = Mth.clamp(ps / base, 1.0D, 1.9D);
        if (ManbearConfig.MIND_NEEDS_ENABLED.get()) {
            modifier *= 1.0D - 0.14D * mindFatigue + 0.10D * mindTerritoryStress;
        }
        if (ManbearConfig.WOUND_MEMORY_ENABLED.get()) modifier *= 1.0D - 0.11D * mindInjuryLevel;
        return Mth.clamp(modifier, 0.70D, 2.0D);
    }

    /** Modifier corresponding to the player's sprint pace, regardless of whether they are sprinting right now. */
    private double sprintSpeedMod() {
        double base = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        double playerSprintSpeed = target.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1.3D;
        double modifier = playerSprintSpeed / base;
        if (ManbearConfig.WOUND_MEMORY_ENABLED.get()) modifier *= 1.0D - 0.10D * mindInjuryLevel;
        return Mth.clamp(modifier, 0.75D, 3.0D);
    }

    private void updateClimb() {
        boolean chasing = mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE;
        boolean want = m.horizontalCollision
                && ((chasing && (target.getY() > m.getY() + 0.8 || stuckTicks > 8)) || target.getY() > m.getY() + 2.5);
        m.setClimbing(want && lunge == Lunge.NONE);
    }

    // ================================================================= STALK
    private void tickStalk(ServerLevel level) {
        if (--orbitFlip <= 0) {
            orbitDir = -orbitDir;
            orbitFlip = 120 + rnd().nextInt(240);
        }
        // freeze like a statue while you stare at him
        if (looking && dist < 60 && rageF < 0.6F) freezeTicks = Math.max(freezeTicks, 10);   // a furious one no longer freezes when watched
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        Vec3 p = perceivedPlayerPosition();
        if (p == null) {
            // He has lost the trail. He waits or shifts to cover instead of tracking the player's real coordinates through walls.
            m.getNavigation().stop();
            if (modeTime > 80 && rnd().nextFloat() < 0.035F) setMode(level, ManbearMode.HIDE);
            return;
        }
        Vec3 me = m.position();
        Vec3 r = new Vec3(me.x - p.x, 0, me.z - p.z);
        double rl = r.length();
        if (rl < 0.01) { r = new Vec3(1, 0, 0); rl = 1; }
        Vec3 u = r.scale(1.0 / rl);
        Vec3 t = new Vec3(-u.z, 0, u.x).scale(orbitDir);
        double radial = Mth.clamp(stalkRadius - rl, -6, 6);
        Vec3 goal = p.add(u.scale(rl + radial)).add(t.scale(4.0));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, Mth.floor(p.y), 3);
        if (g == null) {
            orbitDir = -orbitDir;
            if (++navFailCount > 3 && coverFailCd <= 0) setMode(level, ManbearMode.HIDE);
            return;
        }
        boolean crouched = m.getStance() != Stance.STAND;
        double speed = crouched ? 0.62 : 0.85;
        if (!looking && rl > stalkRadius + 3) speed = 0.95; // closes in while you look away
        navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, speed, 15);
    }

    // ================================================================= HIDE
    private void tickHide(ServerLevel level) {
        if (cover == null) { setMode(level, ManbearMode.STALK); return; }
        if (!atHide) {
            if (reached(cover.hide(), 1.6)) {
                atHide = true;
                m.getNavigation().stop();
                ducking = cover.duck();            // low wall or bush: squat down (and his eyes go dark)
                m.setDucking(ducking);
            } else {
                Vec3 h = Vec3.atBottomCenterOf(cover.hide());
                navTo(level, h.x, h.y, h.z, looking ? 1.15 : 0.9, 10);
                // inside a bush: walk straight in, he pushes through leaves
                if (cover.inside() && (navFail || m.distanceToSqr(h.x, h.y, h.z) < 36)) {
                    m.getMoveControl().setWantedPosition(h.x, h.y, h.z, 0.9);
                    navFailCount = 0;
                }
                if (navFailCount > 4) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
                return;
            }
        }
        m.getNavigation().stop();
        if (--waitTicks <= 0) {
            float pick = rnd().nextFloat();
            if (cover.peek() != null && !cover.duck() && pick < 0.55F) setMode(level, ManbearMode.PEEK);
            else if (pick < 0.88F) setMode(level, ManbearMode.HIDE);   // hop to a different tree
            else setMode(level, ManbearMode.STALK);
        }
    }

    // ================================================================= PEEK
    /** +1 = his left, -1 = his right, depending on which side the open view is */
    private int openSide() {
        BlockPos peek = cover.peek();
        if (peek == null) return rnd().nextBoolean() ? 1 : -1;
        double dx = peek.getX() - cover.hide().getX();
        double dz = peek.getZ() - cover.hide().getZ();
        float yaw = m.getYRot() * ((float) Math.PI / 180F);
        double left = dx * Mth.cos(yaw) + dz * Mth.sin(yaw);
        return left >= 0 ? 1 : -1;
    }

    private void tickPeek(ServerLevel level) {
        if (cover == null || cover.peek() == null || cover.duck()) { setMode(level, ManbearMode.STALK); return; }
        Vec3 h = Vec3.atBottomCenterOf(cover.hide());
        switch (peekPhase) {
            case 0 -> { // get behind the tree
                if (reached(cover.hide(), 1.6)) { peekPhase = 1; m.getNavigation().stop(); peekTimer = 20 + rnd().nextInt(30); }
                else navTo(level, h.x, h.y, h.z, 0.95, 10);
            }
            case 1 -> { // stand hidden for a beat
                m.getNavigation().stop();
                if (--peekTimer <= 0) {
                    peekSide = openSide() * cover.lean();
                    m.setPeekSide(peekSide);   // peek_left / peek_right
                    peekPhase = 2;
                    // he holds the lean for a good while, then simply moves on (leaving PEEK clears the pose)
                    peekTimer = Math.max(120, (int) (200 + rnd().nextInt(160) - 100 * vig));
                }
            }
            default -> { // leaning out, staring, motionless
                m.getNavigation().stop();
                m.setPeekSide(peekSide);
                if (--peekTimer <= 0) {
                    setMode(level, rnd().nextFloat() < 0.78F ? ManbearMode.HIDE : ManbearMode.STALK);
                }
            }
        }
        if (navFailCount > 5) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
    }

    // ================================================================= FAKE CHASE
    /** Fake chases now land a small number of real hits before he turns and leaves. */
    private void tickFake(ServerLevel level) {
        if (fakePhase == 1) {
            m.getNavigation().stop();
            if (--fakeFreeze <= 0) vanishFast(level);
            return;
        }

        if (pendingMelee > 0 && --pendingMelee == 0) {
            if (m.distanceTo(target) < 5.3 && m.doHurtTarget(target)) {
                fakeHitsLanded++;
                if (fakeHitsLanded >= fakeHitsTarget) {
                    fakePhase = 1;
                    fakeFreeze = 24 + rnd().nextInt(25);
                    m.getNavigation().stop();
                    return;
                }
            }
        }

        if (tryChaseTeleport(level)) return;
        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }

        if (dist < 4.0 && meleeCd == 0 && pendingMelee == 0) {
            m.triggerAttack(rnd().nextInt(3));
            meleeCd = 22;
            pendingMelee = 5;
            m.getNavigation().stop();
            return;
        }

        double speed = refSpeedMod() * ManbearConfig.FAKE_SPEED_MULT.get();
        if (rushBurstTicks > 0) {
            rushBurstTicks--;
            speed = sprintSpeedMod() * ManbearConfig.CHASE_BURST_SPEED_MULT.get();
        }
        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 4);
        tunnel(level, speed);
        // If the player completely outruns the encounter, he gives up rather than stretching a fake chase forever.
        if (modeTime > 400 + fakePauseExtensionTicks && fakeHitsLanded == 0) vanishFast(level);
    }

    /** Arm the sudden silent pause only once per chase, on a second boundary for readable timing. */
    private void tryStartChasePause(ServerLevel level) {
        if (!chasePauseAbilityArmed || ticksAlive % 20 != 0 || modeTime < 30 || dist < 8.0 || dist > 48.0
                || !m.onGround() || breaker.active() || lunge != Lunge.NONE || pendingMelee > 0) return;
        if (mode == ManbearMode.FAKE_CHASE && fakePhase != 0) return;
        if (rnd().nextFloat() >= 0.12F) return;
        int min = ManbearConfig.CHASE_STARE_MIN_SECONDS.get();
        int max = Math.max(min, ManbearConfig.CHASE_STARE_MAX_SECONDS.get());
        chasePauseTicks = (min + rnd().nextInt(max - min + 1)) * 20;
        chasePauseStartDistance = dist;
        chasePauseClosestDistance = dist;
        // A pause is a separate beat, not time taken away from the chase itself.
        modeDuration += chasePauseTicks;
        if (mode == ManbearMode.FAKE_CHASE) fakePauseExtensionTicks += chasePauseTicks;
        chasePauseAbilityArmed = false;
        m.getNavigation().stop();
        m.setDeltaMovement(Vec3.ZERO);
    }

    /** No footsteps, growls, mimicry or movement during this stare. If he sees you close the gap, he rushes. */
    private void tickChasePause(ServerLevel level) {
        m.getNavigation().stop();
        m.setClimbing(false);
        m.setDeltaMovement(Vec3.ZERO);
        if (target != null) {
            m.setFaceTarget(target, 45F);
            chasePauseClosestDistance = Math.min(chasePauseClosestDistance, m.distanceTo(target));
        }
        chasePauseTicks--;
        if (chasePauseTicks <= 0) {
            // Remember whether the player ever stepped closer during the stare, even if they backed away again.
            if (target != null && chasePauseClosestDistance < chasePauseStartDistance - 0.5) {
                rushBurstTicks = ManbearConfig.CHASE_BURST_SECONDS.get() * 20;
            }
        }
    }

    /** A single teleport during some chases places him ahead of a sprinting player, often between them and home. */
    private boolean tryChaseTeleport(ServerLevel level) {
        if (!chaseTeleportAbilityArmed || target == null || dist < 10.0 || dist > 48.0 || ticksAlive % 20 != 0) return false;
        if (!target.isSprinting() && (prof == null || prof.velocity().x * prof.velocity().x + prof.velocity().z * prof.velocity().z < 0.035)) return false;
        float ambushRollChance = 0.12F;
        if (ManbearConfig.ADAPTATION.get() && prof != null) {
            ambushRollChance = Mth.clamp(ambushRollChance + prof.strategyStage * 0.045F
                    + Math.min(0.18F, prof.runEscapes * 0.025F), 0.12F, 0.48F);
        }
        if (rnd().nextFloat() >= ambushRollChance) return false;

        Vec3 motion = prof != null ? prof.velocity() : target.getDeltaMovement();
        Vec3 dir = new Vec3(motion.x, 0, motion.z);
        if (dir.lengthSqr() < 1.0E-4) {
            Vec3 look = target.getLookAngle();
            dir = new Vec3(look.x, 0, look.z);
        }
        if (dir.lengthSqr() < 1.0E-4) return false;
        dir = dir.normalize();

        // If his learned base lies along the player's movement, ambush the route back to safety.
        if (prof != null && prof.base != null) {
            Vec3 toBase = new Vec3(prof.base.getX() + 0.5 - target.getX(), 0, prof.base.getZ() + 0.5 - target.getZ());
            if (toBase.lengthSqr() > 100.0 && toBase.lengthSqr() < 200.0 * 200.0) {
                Vec3 baseDir = toBase.normalize();
                if (baseDir.dot(dir) > 0.50) dir = baseDir;
            }
        }
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        for (int attempt = 0; attempt < 8; attempt++) {
            double ahead = 8.0 + rnd().nextInt(8);
            double sidestep = rnd().nextInt(7) - 3;
            double x = target.getX() + dir.x * ahead + side.x * sidestep;
            double z = target.getZ() + dir.z * ahead + side.z * sidestep;
            BlockPos ground = CoverFinder.groundNear(level, x, z, target.getBlockY(), 6);
            if (ground == null || !level.getFluidState(ground).isEmpty()) continue;
            double d2 = target.distanceToSqr(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            if (d2 < 64.0 || d2 > 34.0 * 34.0) continue;
            AABB box = m.getDimensions(m.getPose()).makeBoundingBox(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            if (!m.fitsIgnoringLeaves(box)) continue;
            m.getNavigation().stop();
            m.setDeltaMovement(Vec3.ZERO);
            m.setPos(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            m.setFaceTarget(target, 45F);
            chaseTeleportAbilityArmed = false;
            return true;
        }
        return false;
    }

    private boolean startReturnToDen(ServerLevel level) {
        if (m.leaving || target == null) return false;
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) return false;
        BlockPos entrance = data.denEntrance();
        if (entrance == null || !level.hasChunkAt(entrance)) return false;
        returningToDen = true;
        returnDenEntrance = entrance.immutable();
        returnStartDistance = dist;
        returnTicks = 0;
        setMode(level, ManbearMode.RETREAT);
        return true;
    }

    /** He walks into his den. A player who closes the gap noisily reveals that they are following. */
    private void tickReturnToDen(ServerLevel level) {
        if (!returningToDen || returnDenEntrance == null) return;
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) {
            returningToDen = false;
            returnDenEntrance = null;
            if (!m.leaving) setMode(level, ManbearMode.STALK);
            return;
        }
        returnTicks++;
        m.setClimbing(false);
        m.setFaceTarget(null, 0F);
        m.getNavigation().setSpeedModifier(1.0);

        if (target != null) {
            double currentDist = m.distanceTo(target);
            boolean closedGap = currentDist < returnStartDistance - 2.5;
            boolean runningAfter = target.isSprinting() && currentDist < 34.0;
            boolean tooClose = currentDist < 10.0;
            boolean noticedFollowing = target.isShiftKeyDown() && looking && currentDist < 18.0;
            if (tooClose || runningAfter || (closedGap && !target.isShiftKeyDown() && currentDist < 30.0) || noticedFollowing) {
                if (ManbearConfig.PLAYER_THEORY_ENABLED.get()) {
                    data.profile(target.getUUID()).observeFollowing(true);
                    data.setDirty();
                }
                returningToDen = false;
                returnDenEntrance = null;
                targetLock = 800;
                setMode(level, ManbearMode.CHASE);
                return;
            }
        }

        Vec3 entrance = Vec3.atBottomCenterOf(returnDenEntrance);
        if (m.position().distanceToSqr(entrance) < 9.0) {
            // Arriving without the player following is evidence that this player may not take the bait.
            if (target != null && ManbearConfig.PLAYER_THEORY_ENABLED.get() && returnTicks >= 40 && target.distanceToSqr(entrance) > 28.0D * 28.0D) {
                data.profile(target.getUUID()).observeFollowing(false);
                data.setDirty();
            }
            returningToDen = false;
            m.discard();
            return;
        }
        navTo(level, returnDenEntrance.getX() + 0.5, returnDenEntrance.getY(), returnDenEntrance.getZ() + 0.5, 1.05, 8);
        if (navFail || returnTicks % 80 == 0) m.getMoveControl().setWantedPosition(
                returnDenEntrance.getX() + 0.5, returnDenEntrance.getY(), returnDenEntrance.getZ() + 0.5, 1.05);
        if (returnTicks > 2400) {
            returningToDen = false;
            returnDenEntrance = null;
            setMode(level, ManbearMode.STALK);
        }
    }

    /** he bolts away from you and despawns the moment he is out of sight */
    private void vanishFast(ServerLevel level) {
        caveFleeing = true;
        beginLeaving(level);
    }

    /** wedged on something: try, in turn, a jump, a step left, a step right, a fresh path, backing off and running at it again */
    private void unstick(ServerLevel level, double speed) {
        unstickStep++;
        Vec3 to = target.position().subtract(m.position());
        Vec3 h = new Vec3(to.x, 0, to.z);
        h = h.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : h.normalize();
        Vec3 side = new Vec3(-h.z, 0, h.x);
        switch (unstickStep % 5) {
            case 0 -> m.getJumpControl().jump();
            case 1 -> m.getMoveControl().setWantedPosition(m.getX() + side.x * 3, m.getY(), m.getZ() + side.z * 3, speed);
            case 2 -> m.getMoveControl().setWantedPosition(m.getX() - side.x * 3, m.getY(), m.getZ() - side.z * 3, speed);
            case 3 -> { m.getNavigation().stop(); pathCd = 0; }
            default -> m.getMoveControl().setWantedPosition(m.getX() - h.x * 2, m.getY(), m.getZ() - h.z * 2, speed);
        }
        stuckTicks = 5;   // look again very soon
    }

    /** stuck or no path: tear through whatever is in the way (or wade the river) straight toward you */
    private void tunnel(ServerLevel level, double speed) {
        Vec3 cur = m.position();
        if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
        lastPos = cur;
        if ((navFail || stuckTicks > 12) && dist > 2.4) {
            BlockPos b = BlockBreaker.canGrief(level) ? breaker.findAlongRay(level, m, target) : null;
            if (b != null) {
                if (BlockBreaker.inReach(m, b)) {
                    breaker.start(level, b, adaptiveBreakBonus());
                    m.getNavigation().stop();
                    stuckTicks = 0;
                } else {
                    m.getMoveControl().setWantedPosition(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, speed);
                }
            } else if (stuckTicks > 10) {
                unstick(level, speed);
            } else if (safeToCharge(level)) {
                m.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
            }
        }
    }

    // ================================================================= CHASE
    private void tickChase(ServerLevel level) {
        chaseTicks++;
        lastChaseTick = ticksAlive;
        if (ambushOnly && chaseTicks > 500) { vanish(level); return; }   // never got him
        if (ticksAlive % 20 == 0) samplePanic();
        if (tryOpportunityCostDecision(level)) return;

        // Deliberate release: he temporarily stops pursuing to see which route the player chooses.
        // If the player turns back or closes the gap, the experiment is abandoned and the chase resumes.
        if (deliberateEscapeTicks > 0) {
            if (target == null || dist < deliberateEscapeStartDistance - 2.5D) {
                deliberateEscapeTicks = 0;
                return; // continue this tick's ordinary chase logic
            }
            m.getNavigation().stop();
            m.getMoveControl().setWantedPosition(m.getX(), m.getY(), m.getZ(), 0.0D);
            m.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 18F, 18F);
            if (--deliberateEscapeTicks <= 0) {
                PlayerProfile profile = prof != null && target != null ? ManbearData.get(level.getServer()).profile(target.getUUID()) : null;
                if (profile != null) {
                    profile.recordEpisode("DELIBERATE_ESCAPE", "RELEASED", "HE_PAUSED_TO_OBSERVE_THE_ESCAPE_ROUTE",
                            target.blockPosition(), level.dimension().location().toString(), level.getGameTime());
                    profile.adjustRouteBelief(target.blockPosition(), 0.08F, level.getGameTime());
                    ManbearData.get(level.getServer()).setDirty();
                }
                lastChaseEnd = ticksAlive;
                setMode(level, ManbearMode.STALK);
            }
            return;
        }
        if (!ambushOnly && chaseTicks > 100 && chaseTicks % 40 == 0 && target != null && prof != null
                && (target.isSprinting() || prof.sprintiness > 0.48F) && dist >= 9.0D && dist <= 34.0D
                && los && !breaker.active() && lunge == Lunge.NONE
                && ManbearConfig.SCRIPTED_ENCOUNTERS_ENABLED.get()
                && prof.scriptedEventReady("DELIBERATE_ESCAPE", level.getGameTime(), 48000L)
                && rnd().nextFloat() < (0.12F + 0.13F * mindPatience + 0.08F * mindCaution)) {
            deliberateEscapeTicks = 38 + rnd().nextInt(20);
            deliberateEscapeStartDistance = dist;
            prof.markScriptedEvent("DELIBERATE_ESCAPE", level.getGameTime());
            ManbearData.get(level.getServer()).setDirty();
            m.getNavigation().stop();
            m.setFaceTarget(null, 0F);
            return;
        }

        if (pendingMelee > 0 && --pendingMelee == 0) {
            if ((m.distanceTo(target) < 5.3 || (pitChase && reachDist() < 4.8)) && m.doHurtTarget(target)) {
                hitsLanded++;
                if (ambushOnly) { vanish(level); return; }
            }
        }
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        // an animal near his path distracts him for a couple of seconds while he kills it
        if (distractTicks > 0) { tickDistract(level); return; }
        if (distractCd == 0 && mode == ManbearMode.CHASE && lunge == Lunge.NONE && dist > 5 && chaseTicks % 10 == 0) {
            Animal near = nearbyAnimal(level);
            if (near != null) { startDistract(level, near); return; }
        }
        // stunning roar: only when you have opened up a gap, at most N times per chase; he keeps running meanwhile
        if (!ambushOnly && lunge == Lunge.NONE && roarCd == 0 && roarsUsed < ManbearConfig.ROARS_PER_CHASE.get() && chaseTicks > 80
                && los && dist >= ManbearConfig.ROAR_MIN_DISTANCE.get() && dist <= ManbearConfig.ROAR_MAX_DISTANCE.get()) {
            startRoar(level);
        }
        if (lunge != Lunge.NONE) { tickLunge(level); return; }

        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }
        if (tryChaseTeleport(level)) return;

        double speed = refSpeedMod() * ManbearConfig.CHASE_SPEED_MULT.get();   // as fast as a fake chase
        if (ManbearConfig.ADAPTATION.get() && prof != null) {
            // If repeatedly outrun, he steadily closes the gap faster; the increase has a hard cap.
            speed *= 1.0 + Math.min(0.25, prof.runEscapes * 0.035) + Math.min(0.12, prof.strategyStage * 0.02);
        }
        if (mode == ManbearMode.AMBUSH && chaseTicks < 100) speed *= 1.2;
        if (m.getRoarTicks() > 0) speed *= 1.12;   // catching up while you are frozen
        if (rushTicks > 0) {                         // the "behind you" jumpscare charge
            rushTicks--;
            if (dist > 3.5) speed = refSpeedMod() * ManbearConfig.BEHIND_RUSH_MULTIPLIER.get();
            else rushTicks = 0;
        }
        if (rushBurstTicks > 0) {
            rushBurstTicks--;
            speed = sprintSpeedMod() * ManbearConfig.CHASE_BURST_SPEED_MULT.get();
        }

        // melee
        if (reachDist() < 4.0 && meleeCd == 0 && pendingMelee == 0) {
            if (canGrab()) { startGrab(level); return; }
            m.triggerAttack(rnd().nextInt(3));
            meleeCd = 24;
            pendingMelee = 5;
            m.getNavigation().stop();
            return;
        }
        // lunge
        if (lungeCd == 0 && dist >= 4.4 && dist <= 9.5 && los && m.onGround() && !targetInPit()) {
            lunge = Lunge.WINDUP;
            lungeTimer = 7;
            lungeHit = false;
            m.getNavigation().stop();
            m.triggerAttack(rnd().nextInt(3));
            m.playBear(ModSounds.SNARL.get(), 3.5F, 0.75F + rnd().nextFloat() * 0.2F);
            return;
        }

        if (tryCounterCombatStyle(level, speed)) return;
        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 6);

        // Path to you is blocked (you are in a house / bunker) or he is stuck: tunnel straight toward you.
        Vec3 cur = m.position();
        if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
        lastPos = cur;
        if ((navFail || stuckTicks > 12) && dist > 2.4 && !(targetInPit() && reachDist() < 5.0)) {   // at the edge of the pit he reaches down, he does not dig
            BlockPos b = BlockBreaker.canGrief(level) ? breaker.findAlongRay(level, m, target) : null;
            if (b != null) {
                if (BlockBreaker.inReach(m, b)) {
                    breaker.start(level, b, adaptiveBreakBonus());
                    m.getNavigation().stop();
                    stuckTicks = 0;
                } else {
                    // walk right up to the wall first (overrides the partial path this tick)
                    m.getMoveControl().setWantedPosition(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, speed);
                }
                unreachableTicks = Math.max(0, unreachableTicks - 3);
            } else if (stuckTicks > 10) {
                unreachableTicks += 1;
                unstick(level, speed);
            } else if (safeToCharge(level)) {
                // nothing to break: a river or a gap. Go straight at you - he wades and swims across.
                m.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
            }
        }
        if (!navFail) unreachableTicks = Math.max(0, unreachableTicks - 2);
        if (unreachableTicks > 160) {
            setMode(level, trapCooldown <= 0 ? ManbearMode.LAY_TRAP : ManbearMode.STALK);
            return;
        }

        if (hitsLanded >= (aggr > 0.5F ? 2 : 4) || dist > 85) endChase(level);
    }

    /** Counter the player's repeatedly observed fighting style without giving the Manbear perfect knowledge. */
    private boolean tryCounterCombatStyle(ServerLevel level, double speed) {
        if (!ManbearConfig.COMBAT_ADAPTATION_ENABLED.get() || prof == null || target == null
                || chaseTicks < 30 || !los) return false;
        boolean ranged = prof.favorsRangedCombat() && dist >= 10.0D && dist <= 30.0D;
        boolean shield = prof.oftenUsesShield() && target.isBlocking() && dist >= 3.0D && dist <= 9.0D;
        if (!ranged && !shield) return false;
        int window = shield ? 44 : 72;
        int phase = shield ? 7 : 9;
        if (chaseTicks % window >= phase) return false;
        Vec3 away = m.position().subtract(target.position());
        Vec3 flat = new Vec3(away.x, 0.0D, away.z);
        if (flat.lengthSqr() < 0.01D) flat = new Vec3(1, 0, 0);
        flat = flat.normalize();
        Vec3 lateral = new Vec3(-flat.z, 0.0D, flat.x);
        double side = ((chaseTicks / window) % 2 == 0) ? 1.0D : -1.0D;
        double radius = shield ? 3.4D : 5.0D + rnd().nextDouble() * 2.0D;
        BlockPos flank = CoverFinder.groundNear(level,
                target.getX() + lateral.x * radius * side,
                target.getZ() + lateral.z * radius * side,
                target.getBlockY(), 3);
        if (flank == null || isKnownTrap(level, flank, 3) || m.blockPosition().distSqr(flank) < 2.0D) return false;
        navTo(level, flank.getX() + 0.5D, flank.getY(), flank.getZ() + 0.5D, speed * (ranged ? 0.98D : 1.06D), 4);
        return true;
    }

    private void tickLunge(ServerLevel level) {
        lungeTimer--;
        switch (lunge) {
            case WINDUP -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    Vec3 aim = target.position();
                    if (pounce && prof != null) aim = aim.add(prof.velocity().scale(0.9)); // lead a moving target
                    Vec3 d = aim.subtract(m.position());
                    Vec3 h = new Vec3(d.x, 0, d.z);
                    double hl = h.length();
                    h = hl < 1.0E-4 ? new Vec3(0, 0, 1) : h.scale(1.0 / hl);
                    if (pounce) {
                        // ballistic leap: reaches the target in about 9 ticks (under half a second)
                        double v = Mth.clamp(hl / 6.2, 1.0, 3.2);
                        m.impulse(new Vec3(h.x * v, 0.42, h.z * v));
                        lungeTimer = 24;
                    } else {
                        m.impulse(new Vec3(h.x * 0.95, 0.38, h.z * 0.95));
                        lungeTimer = 16;
                    }
                    lunge = Lunge.AIR;
                }
            }
            case AIR -> {
                if (!lungeHit && m.getBoundingBox().inflate(pounce ? 1.3 : 0.8, pounce ? 0.8 : 0.4, pounce ? 1.3 : 0.8).intersects(target.getBoundingBox())) {
                    lungeHit = true;
                    if (m.doHurtTarget(target)) {
                        hitsLanded++;
                        if (ambushOnly) { vanish(level); return; }   // lunged, hurt you, gone
                        if (canGrab()) { startGrab(level); return; }  // caught you mid-leap: grab
                    }
                }
                if (lungeTimer <= 0 || (lungeTimer < (pounce ? 18 : 11) && m.onGround())) {
                    lunge = Lunge.RECOVER;
                    lungeTimer = pounce ? 6 : 14;
                }
            }
            case RECOVER -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    lunge = Lunge.NONE;
                    pounce = false;
                    lungeCd = ManbearConfig.LUNGE_COOLDOWN_TICKS.get() + rnd().nextInt(30) - (aggr > 0.5F ? 20 : 0);
                    if (lungeHit && (aggr > 0.5F || rnd().nextFloat() < 0.2F)) endChase(level);
                }
            }
            default -> { }
        }
    }

    private void samplePanic() {
        if (prof == null) return;
        Vec3 away = target.position().subtract(m.position());
        away = new Vec3(away.x, 0, away.z);
        if (away.lengthSqr() < 0.01) return;
        away = away.normalize();
        boolean fleeing = prof.velocity().dot(away) > 0.5;
        if (fleeing) prof.panic = PlayerProfile.ema(prof.panic, 1F, 0.08F);
        else if (dist < 10) prof.panic = PlayerProfile.ema(prof.panic, 0F, 0.08F);
    }

    private void endChase(ServerLevel level) {
        lastChaseEnd = ticksAlive;
        if (ManbearConfig.ADAPTATION.get() && !ambushOnly && target != null && target.isAlive() && prof != null) {
            ServerPlayer player = target;
            boolean escaped = dist > 20.0 || (dist > 13.0 && !los && chaseTicks > 100);
            if (escaped) {
                boolean sheltered = !level.canSeeSky(player.blockPosition());
                if (prof.base != null && player.blockPosition().distSqr(prof.base) < 48L * 48L) sheltered = true;
                boolean ran = player.isSprinting() || prof.sprintiness > 0.45F;
                prof.recordChaseEscape(sheltered, ran, level.getGameTime(), player.blockPosition(), level.dimension().location().toString());
                ManbearData.get(level.getServer()).setDirty();
            }
        }
        float r = rnd().nextFloat();
        setMode(level, r < 0.6F ? ManbearMode.RETREAT : r < 0.85F ? ManbearMode.HIDE : ManbearMode.STALK);
    }

    // ================================================================= LAY TRAP
    private void tickTrap(ServerLevel level) {
        if (trapSite == null) { setMode(level, ManbearMode.STALK); return; }
        if (trapPhase == 0) {
            Vec3 s = Vec3.atBottomCenterOf(trapSite);
            navTo(level, s.x, s.y, s.z, 0.8, 15);
            if (dist < 10 && los) { // caught red-handed
                boolean canChase = canChase();
                setMode(level, canChase && rnd().nextFloat() < 0.5F ? ManbearMode.CHASE : ManbearMode.RETREAT);
                return;
            }
            if (reached(trapSite, 5.0)) {
                trapPhase = 1;
                trapTimer = 35;
                m.getNavigation().stop();
                m.triggerAttack(2);
            }
        } else {
            m.getNavigation().stop();
            if (--trapTimer <= 0) {
                placeTrap(level);
                trapCooldown = 900 + rnd().nextInt(1800);
                setMode(level, rnd().nextFloat() < 0.6F ? ManbearMode.HIDE : ManbearMode.STALK);
            }
        }
    }

    private void placeTrap(ServerLevel level) {
        placeTrapAt(level, trapSite);
    }

    boolean placeTrapAt(ServerLevel level, BlockPos site) {
        BlockState st = level.getBlockState(site);
        if (!(st.isAir() || st.canBeReplaced())) return false;
        BlockState trap = ModBlocks.BEAR_TRAP.get().defaultBlockState()
                .setValue(com.manbear.block.BearTrapBlock.HUNTER_SET, true);
        if (!trap.canSurvive(level, site)) return false;
        level.setBlock(site, trap, 3);
        ManbearData data = ManbearData.get(level.getServer());
        String dim = level.dimension().location().toString();
        for (ManbearData.Trap old : data.addTrap(new ManbearData.Trap(dim, site.asLong()), Math.max(ManbearConfig.MAX_TRAPS.get(), 4))) {
            ServerLevel l = level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(old.dim())));
            BlockPos p = BlockPos.of(old.pos());
            if (l != null && l.hasChunkAt(p) && l.getBlockState(p).is(ModBlocks.BEAR_TRAP.get())) l.removeBlock(p, false);
        }
        return true;
    }

    // ================================================================= RETREAT
    private void tickRetreat(ServerLevel level) {
        Vec3 me = m.position();
        Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
        Vec3 goal = me.add(away.scale(18));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, m.getBlockY(), 3);
        if (caveFleeing || g == null) {
            BlockPos alt = fleeSpot(level);
            if (alt != null) g = alt;
        }
        double fleeSpeed = caveFleeing ? Math.max(2.2, refSpeedMod() * 2.4) : (m.leaving ? 1.1 : 0.9);
        if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, fleeSpeed, caveFleeing ? 8 : 15);
        else m.getNavigation().stop();
        if (caveFleeing) m.setFaceTarget(null, 0F);   // runs forward, away from you

        if (m.leaving) {
            // only ever vanishes while nobody can see him (failsafe: far away for a full minute)
            if (!visibleToAnyone(level) || (leavingTicks > 1200 && dist > 40)) m.discard();
            return;
        }
        if ((!los && dist > 30) || dist > 46) {
            setMode(level, rnd().nextFloat() < 0.55F && coverFailCd <= 0 ? ManbearMode.HIDE : ManbearMode.STALK);
        }
    }

    // ================================================================= ANIMAL LURE
    private PathfinderMob lureAnimal(ServerLevel level) {
        if (animalId == null) return null;
        return level.getEntity(animalId) instanceof PathfinderMob a && a.isAlive() ? a : null;
    }

    /** nobody saw it yet: just remove it */
    private void discardLureAnimal(ServerLevel level) {
        PathfinderMob a = lureAnimal(level);
        if (a != null) a.discard();
        animalId = null;
    }

    /** it was seen: it simply becomes an ordinary animal that is terrified of him */
    private void releaseLureAnimal(ServerLevel level) {
        PathfinderMob a = lureAnimal(level);
        if (a != null) {
            a.removeTag("manbear_run");
            a.removeTag("manbear_lure");
            a.getPersistentData().remove("lure_x");
            a.getPersistentData().remove("lure_y");
            a.getPersistentData().remove("lure_z");
        }
        animalId = null;
    }

    private void abortLure(ServerLevel level) {
        lureDone = true;
        setMode(level, ManbearMode.STALK);   // exitMode removes / releases the animal
    }

    /** puts a random animal between you and him, off to the side where you are likely to notice it */
    private boolean spawnLureAnimal(ServerLevel level) {
        Vec3 toBear = new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ());
        double len = toBear.length();
        if (len < 12) return false;
        toBear = toBear.scale(1.0 / len);
        Vec3 side = new Vec3(-toBear.z, 0, toBear.x);
        EntityType<?>[] types = {EntityType.WOLF, EntityType.PIG, EntityType.SHEEP, EntityType.COW, EntityType.VILLAGER};
        for (int i = 0; i < 20; i++) {
            double fwd = 14 + rnd().nextInt(8);
            double lat = rnd().nextInt(13) - 6;
            Vec3 p = target.position().add(toBear.scale(fwd)).add(side.scale(lat));
            BlockPos g = CoverFinder.groundNear(level, p.x, p.z, target.getBlockY(), 2);
            if (g == null || g.distSqr(m.blockPosition()) < 10 * 10) continue;
            Entity e = types[rnd().nextInt(types.length)].create(level);
            if (!(e instanceof PathfinderMob a)) return false;
            a.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, rnd().nextFloat() * 360F, 0F);
            a.addTag("manbear_lure");
            a.goalSelector.addGoal(0, new LureWolfGoal(a));
            if (a instanceof Wolf) lureCall = SoundEvents.WOLF_WHINE;
            else if (a instanceof Pig) lureCall = SoundEvents.PIG_AMBIENT;
            else if (a instanceof Sheep) lureCall = SoundEvents.SHEEP_AMBIENT;
            else if (a instanceof Cow) lureCall = SoundEvents.COW_AMBIENT;
            else if (a instanceof Villager) lureCall = SoundEvents.VILLAGER_AMBIENT;
            level.addFreshEntity(a);
            animalId = a.getUUID();
            return true;
        }
        return false;
    }

    /** trap variant: a bait spot a few blocks in front of his hiding place, ringed with traps on your side */
    private boolean setupBait(ServerLevel level) {
        Vec3 hide = Vec3.atBottomCenterOf(cover.hide());
        Vec3 toPlayer = new Vec3(target.getX() - hide.x, 0, target.getZ() - hide.z);
        double len = toPlayer.length();
        if (len < 14) return false;
        toPlayer = toPlayer.scale(1.0 / len);
        Vec3 side = new Vec3(-toPlayer.z, 0, toPlayer.x);
        BlockPos bait = CoverFinder.groundNear(level, hide.x + toPlayer.x * 7, hide.z + toPlayer.z * 7, cover.hide().getY(), 2);
        if (bait == null) return false;
        int placed = 0;
        for (int k = -2; k <= 2; k += 2) {
            double tx = bait.getX() + 0.5 + toPlayer.x * 3 + side.x * k;
            double tz = bait.getZ() + 0.5 + toPlayer.z * 3 + side.z * k;
            BlockPos g = CoverFinder.groundNear(level, tx, tz, bait.getY(), 1);
            if (g != null && placeTrapAt(level, g)) placed++;
        }
        if (placed == 0) return false;
        baitSpot = bait;
        return true;
    }

    private boolean playerSeesAnimal(PathfinderMob a) {
        double d = target.distanceTo(a);
        if (d > 48 || d < 6 || !target.hasLineOfSight(a)) return false;
        Vec3 look = target.getViewVector(1F);
        Vec3 to = a.getEyePosition().subtract(target.getEyePosition()).normalize();
        return look.dot(to) > 0.65;
    }

    /**
     * Animal shows up -> you see it -> it bolts.
     *  - 70%: it runs to a bait spot ringed with his bear traps and waits there, calling you in.
     *  - 30%: it runs straight to him, he lets out a long roar and a chase (or fake chase) starts.
     */
    private void tickLure(ServerLevel level) {
        if (lurePhase > 0) m.getNavigation().stop();   // phase 0 is walking to the hiding spot
        switch (lurePhase) {
            case 0 -> { // slip into hiding first
                Vec3 h = Vec3.atBottomCenterOf(cover.hide());
                if (reached(cover.hide(), 1.6) || modeTime > 500) {
                    m.getNavigation().stop();
                    if (!spawnLureAnimal(level)) { abortLure(level); return; }
                    if (lureTrapVariant && !setupBait(level)) lureTrapVariant = false;   // no room for traps: go to him instead
                    lurePhase = 1;
                    lureTimer = 0;
                } else {
                    navTo(level, h.x, h.y, h.z, 1.0, 10);
                }
            }
            case 1 -> { // wait for you to notice the animal
                PathfinderMob a = lureAnimal(level);
                if (a == null) { abortLure(level); return; }
                lureTimer++;
                if (lureTimer % 100 == 40 && lureStare == 0) {
                    level.playSound(null, a.getX(), a.getY(), a.getZ(), lureCall, SoundSource.NEUTRAL, 2.5F, 0.9F);
                }
                if (lureStare == 0 && playerSeesAnimal(a)) lureStare = 28;
                if (lureStare > 0) {   // it stares at you for a moment...
                    a.getNavigation().stop();
                    a.getLookControl().setLookAt(target, 30.0F, 30.0F);
                    if (--lureStare == 0) {   // ...then bolts
                        if (lureTrapVariant && baitSpot != null) {
                            a.getPersistentData().putDouble("lure_x", baitSpot.getX() + 0.5);
                            a.getPersistentData().putDouble("lure_y", baitSpot.getY());
                            a.getPersistentData().putDouble("lure_z", baitSpot.getZ() + 0.5);
                        }
                        a.addTag("manbear_run");
                        lurePhase = 2;
                        lureTimer = 0;
                    }
                } else if (lureTimer > 1100) {
                    abortLure(level);
                }
            }
            case 2 -> { // it runs
                PathfinderMob a = lureAnimal(level);
                lureTimer++;
                if (a == null) { abortLure(level); return; }
                if (lureTrapVariant && baitSpot != null) {
                    if (a.distanceToSqr(baitSpot.getX() + 0.5, baitSpot.getY(), baitSpot.getZ() + 0.5) < 9 || lureTimer > 500) {
                        a.removeTag("manbear_run");        // it stops at the bait spot and calls to you
                        lurePhase = 4;
                        lureTimer = 0;
                    }
                } else if (a.distanceTo(m) < 4.5 || lureTimer > 500) {
                    a.removeTag("manbear_run");
                    a.removeTag("manbear_lure");   // from now on it is just an animal scared of him
                    lurePhase = 3;
                    lureTimer = 0;
                    m.setRoarTicks(80);
                    m.playBear(ModSounds.ROAR.get(), 9.0F, 0.65F);
                    m.playBear(ModSounds.ROAR_DEEP.get(), 9.0F, 0.6F);
                }
            }
            case 4 -> { // bait: it waits among the traps; if you step on one, the usual ambush starts
                PathfinderMob a = lureAnimal(level);
                lureTimer++;
                if (a == null) { lureDone = true; setMode(level, ManbearMode.HIDE); return; }
                a.getNavigation().stop();
                a.getLookControl().setLookAt(target, 30.0F, 30.0F);
                if (lureTimer % 90 == 30) level.playSound(null, a.getX(), a.getY(), a.getZ(), lureCall, SoundSource.NEUTRAL, 2.0F, 0.9F);
                if (lureTimer > 900) { lureDone = true; setMode(level, ManbearMode.HIDE); }
            }
            default -> { // phase 3: the long roar, then it starts
                lureTimer++;
                if (lureTimer == 30) m.playBear(ModSounds.ROAR_DEEP.get(), 9.0F, 0.55F);
                if (lureTimer == 55) m.playBear(ModSounds.ROAR.get(), 9.0F, 0.6F);
                if (lureTimer >= 80) {
                    lureDone = true;
                    if (m.isChaser()) {
                        ambushing = true;
                        setMode(level, ManbearMode.CHASE);
                    } else {
                        setMode(level, ManbearMode.FAKE_CHASE);
                    }
                }
            }
        }
    }

    // ================================================================= BEHIND YOU
    /** He stands behind you, silent and still, until you turn around - then charges and roars. */
    private void tickBehind(ServerLevel level) {
        Vec3 look = target.getLookAngle();
        Vec3 lookH = new Vec3(look.x, 0, look.z);
        lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        boolean seen = los && dist < 45 && look.dot(toEye) > 0.5;
        if (seen && modeTime > 30) { jumpscare(level); return; }

        // follow you at a distance so he stays at your back; when you stand still he stands still
        if (dist > 17 || dist < 5.5) {
            Vec3 goal = target.position().subtract(lookH.scale(11));
            BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 5);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, dist > 17 ? Math.max(0.9, refSpeedMod() * 1.05) : 0.7, 8);
        } else {
            m.getNavigation().stop();
        }
    }

    private void jumpscare(ServerLevel level) {
        pounceChase(level, true, 10.0F);
    }

    /** a very fast leap at the player that turns into a chase (behind-you jumpscare, path blocker, ...) */
    private void pounceChase(ServerLevel level, boolean rush, float roarVol) {
        m.setChaser(true);
        m.extendLife(2400);
        ambushing = true;
        rushTicks = rush ? 50 : 0;
        m.setRoarTicks(40);
        m.playBear(ModSounds.ROAR.get(), roarVol, 0.65F);
        m.playBear(ModSounds.ROAR_DEEP.get(), roarVol, 0.6F);
        if (!ManbearConfig.SOUNDS_FROM_BODY.get()) {      // the old 'right in the player's ears' copy (off by default: it came from nowhere)
            level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR_DEEP.get(), SoundSource.HOSTILE, 4.0F, 0.8F);
            level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR.get(), SoundSource.HOSTILE, 4.0F, 0.7F);
        }
        setMode(level, ManbearMode.CHASE);
        refreshTraits(level);
        // leap at the player right now (setMode cleared the lunge state, so set it afterwards)
        pounce = true;
        lunge = Lunge.WINDUP;
        lungeTimer = 4;
        lungeHit = false;
        m.getNavigation().stop();
        m.triggerLeap();
    }

    private void vanish(ServerLevel level) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 2.0, m.getZ(), 30, 0.6, 1.5, 0.6, 0.02);
        m.discard();
    }

    // ================================================================= GRAB
    private boolean canGrab() {
        return mode == ManbearMode.CHASE && !ambushOnly && grabCd == 0 && target != null && target.isAlive()
                && !target.isPassenger() && rnd().nextFloat() < ManbearConfig.GRAB_CHANCE.get().floatValue();
    }

    private void startGrab(ServerLevel level) {
        held = target;
        grabProgress = 0F;
        grabTicks = 0;
        m.playBear(ModSounds.SNARL.get(), 5.0F, 0.6F);
        setMode(level, ManbearMode.GRAB);
    }

    /** one space-bar press from the grabbed player */
    public void struggle(ServerPlayer p) {
        if (mode == ManbearMode.GRAB && p == held && !lethalGrab) grabProgress += ManbearConfig.STRUGGLE_PER_PRESS.get().floatValue();
        else if (mode == ManbearMode.KIDNAP && kidPhase == KID_CARRY && p == held) {
            kidCount++;                                              // exactly N presses, no drain
            grabProgress = Math.min(1F, kidCount / (float) Math.max(1, kidPresses));
        }
    }

    /** He holds you in front of his chest, hurts you every second, and you mash space (shown on the exp bar) to break free. */
    private void tickGrab(ServerLevel level) {
        m.getNavigation().stop();
        if (held == null || !held.isAlive() || held.isDeadOrDying() || held.level() != m.level() || m.distanceToSqr(held) > 30 * 30) {
            endGrab(level, false);
            return;
        }
        grabTicks++;
        float yaw = m.getYRot() * ((float) Math.PI / 180F);
        Vec3 fwd = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 hold = m.position().add(fwd.scale(1.6)).add(0, 1.2, 0);
        if (!level.noCollision(held, held.getBoundingBox().move(hold.subtract(held.position())))) {
            hold = m.position().add(fwd.scale(0.9)).add(0, 0.2, 0);   // not enough room to lift you here
        }
        if (ticksAlive % 2 == 0) {
            Vec3 d = m.getEyePosition().subtract(hold.add(0, 1.6, 0));
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            float ly = (float) (Mth.atan2(d.z, d.x) * (180D / Math.PI)) - 90.0F;
            float lp = (float) -(Mth.atan2(d.y, horiz) * (180D / Math.PI));
            held.setYRot(ly);
            held.setXRot(lp);
            held.connection.teleport(hold.x, hold.y, hold.z, ly, lp);
        }
        held.setDeltaMovement(Vec3.ZERO);
        held.fallDistance = 0F;

        grabProgress = Math.max(0F, grabProgress - 0.004F);   // the grip tightens if you stop
        if (lethalGrab) grabProgress = 0F;                    // intimidation: nothing you do fills the bar
        if (grabTicks % 20 == 0 && !lethalGrab) {
            held.hurt(level.damageSources().mobAttack(m), ManbearConfig.GRAB_DAMAGE.get().floatValue());
            m.triggerAttack(2);
        }
        if (ticksAlive % 2 == 0) {
            held.connection.send(new ClientboundSetExperiencePacket(Mth.clamp(grabProgress, 0F, 1F), 0, 0));   // the exp bar is the escape bar
        }
        if (lethalGrab) {
            if (ticksAlive % 10 == 0) held.displayClientMessage(Component.literal("..."), true);
            if (grabTicks >= 70) killHeld(level);
            return;
        }
        if (ticksAlive % 10 == 0) held.displayClientMessage(Component.literal("MASH SPACE!"), true);
        if (grabProgress >= 1F) endGrab(level, true);
    }

    /** intimidation: he reached you. There is no escape. */
    private void startLethalGrab(ServerLevel level) {
        held = target;
        grabProgress = 0F;
        grabTicks = 0;
        lethalGrab = true;
        m.playBear(ModSounds.HUFF.get(), 1.2F, 0.7F);   // still no roar: just a breath
        setMode(level, ManbearMode.GRAB);
    }

    private void killHeld(ServerLevel level) {
        ServerPlayer p = held;
        restoreXpBar();
        held = null;
        lethalGrab = false;
        if (p != null && p.isAlive()) {
            if (ManbearConfig.INTIMIDATE_LETHAL.get()) {
                // bypasses invulnerability and totems
                p.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
            } else {
                float dmg = Math.max(0F, p.getHealth() - 2F);
                if (dmg > 0F) p.hurt(level.damageSources().mobAttack(m), dmg);
            }
        }
        vanish(level);
    }

    /** He walks slowly (a player's walking pace) straight at you and does not care whether you look at him. No footsteps, only soft breathing. */
    private void tickIntimidate(ServerLevel level) {
        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }
        navTo(level, target.getX(), target.getY(), target.getZ(), 1.0, 10);
        tunnel(level, 1.0);
        if (dist < 3.0 && target.isAlive() && !target.isPassenger()) startLethalGrab(level);
    }

    private void restoreXpBar() {
        if (held != null && held.connection != null) {
            held.connection.send(new ClientboundSetExperiencePacket(held.experienceProgress, held.totalExperience, held.experienceLevel));
        }
    }

    private void endGrab(ServerLevel level, boolean escaped) {
        restoreXpBar();
        ServerPlayer p = held;
        ServerPlayer p0 = held;
        held = null;
        grabCd = escaped ? 300 : 200;
        if (escaped && p0 != null && level.getServer() != null) {
            ManbearData.get(level.getServer()).profile(p0.getUUID()).addRage(ManbearConfig.RAGE_ESCAPE.get().floatValue());
        }
        if (p != null && p.isAlive()) {
            Vec3 away = p.position().subtract(m.position());
            away = new Vec3(away.x, 0, away.z);
            away = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
            p.setDeltaMovement(away.x * 0.9, 0.45, away.z * 0.9);   // flung clear
            p.hurtMarked = true;
            p.fallDistance = 0F;
        }
        ambushing = false;
        setMode(level, ManbearMode.CHASE);
        if (escaped) freezeTicks = 30;     // he is dazed for a moment when you break free
    }

    /** called when he is removed: never leave a player's exp bar stuck on the escape bar */
    public void cleanup() {
        if (doppelId != null && m.level() instanceof ServerLevel dl) discardDoppel(dl);
        if (mode == ManbearMode.KIDNAP && kidPhase == KID_FADE && held != null && m.level() instanceof ServerLevel sl) {
            kidnapTeleport(sl, false); // he was removed mid-fade: preserve the old safe fallback without a guard entity
        }
        restoreXpBar();
        held = null;
        if (carried != null && m.level() instanceof ServerLevel sl) dropPet(sl);   // never lose a pet when he despawns
    }

    /** Persist the den-capture set piece across chunk unload/reload. Other encounter AI remains intentionally transient. */
    public void saveDenPersistence(CompoundTag tag) {
        boolean carryingIntoDen = mode == ManbearMode.KIDNAP && kidPhase == KID_DEN_CARRY && held != null;
        boolean guardingDen = denCaptiveId != null
                && ((mode == ManbearMode.KIDNAP && kidPhase >= KID_DEN_GUARD)
                || (mode == ManbearMode.SLEEP && kidPhase == KID_DEN_SLEEP));
        if (!carryingIntoDen && !guardingDen) return;
        UUID captive = carryingIntoDen ? held.getUUID() : denCaptiveId;
        if (captive == null) return;
        tag.putBoolean("ManbearDenCaptivity", true);
        tag.putUUID("ManbearDenCaptive", captive);
        tag.putBoolean("ManbearDenSleeping", mode == ManbearMode.SLEEP && kidPhase == KID_DEN_SLEEP);
        tag.putBoolean("ManbearDenHeadingToSleep", mode == ManbearMode.KIDNAP && kidPhase == KID_DEN_SLEEP);
        tag.putBoolean("ManbearDenCarryInterrupted", carryingIntoDen || restoreDenCaptiveToRoomPending);
        tag.putInt("ManbearDenGuardWait", denGuardWaitTicks);
        tag.putInt("ManbearDenKidTimer", kidTimer);
    }

    /** Restore the persistent den guard state after a chunk has been loaded again. */
    public void loadDenPersistence(CompoundTag tag) {
        if (!tag.getBoolean("ManbearDenCaptivity") || !tag.hasUUID("ManbearDenCaptive")) return;
        denCaptiveId = tag.getUUID("ManbearDenCaptive");
        kidnapToDen = true;
        denGuardWaitTicks = Math.max(1200, tag.getInt("ManbearDenGuardWait"));
        kidTimer = Math.max(0, tag.getInt("ManbearDenKidTimer"));
        boolean sleeping = tag.getBoolean("ManbearDenSleeping");
        boolean headingToSleep = tag.getBoolean("ManbearDenHeadingToSleep");
        kidPhase = (sleeping || headingToSleep) ? KID_DEN_SLEEP : KID_DEN_GUARD;
        mode = sleeping ? ManbearMode.SLEEP : ManbearMode.KIDNAP;
        modeDuration = 1_000_000;
        modeTime = 0;
        started = true;
        target = null;
        targetLock = 20 * 60 * 60;
        m.setSyncedMode(mode);
        m.setPersistenceRequired();
        if (sleeping) m.setStance(Stance.SLEEP);
        // A save may happen mid-carry; place the captive inside on the first server tick after both entities load.
        restoreDenCaptiveToRoomPending = tag.getBoolean("ManbearDenCarryInterrupted");
    }

    private boolean restoreDenCaptiveToRoomPending;

    private void restoreDenCaptiveToRoom(ServerLevel level) {
        if (!restoreDenCaptiveToRoomPending) return;
        if (denCaptiveId == null) { restoreDenCaptiveToRoomPending = false; return; }
        ManbearData data = ManbearData.get(level.getServer());
        ServerPlayer captive = level.getServer().getPlayerList().getPlayer(denCaptiveId);
        // Keep the restore pending while the player is offline; on rejoin, place them safely inside the den.
        if (captive == null) return;
        if (!data.hasDen() || data.denCenter() == null
                || !data.denDimension().equals(level.dimension().location().toString())) {
            restoreDenCaptiveToRoomPending = false;
            return;
        }
        if (!captive.isAlive()) return;
        restoreDenCaptiveToRoomPending = false;
        BlockPos feet = data.denCenter().below(2);
        level.getChunkAt(feet);
        captive.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, captive.getYRot(), 0F);
        captive.setDeltaMovement(Vec3.ZERO);
        captive.fallDistance = 0F;
        captive.setHealth(Math.min(captive.getHealth(), 1.0F));
        target = captive;
        targetLock = 20 * 60 * 60;
        kidPhase = KID_DEN_GUARD;
        kidTimer = 0;
        denGuardWaitTicks = Math.max(1200, denGuardWaitTicks);
        m.setPersistenceRequired();
    }

    // ================================================================= STUN / DISTRACTION
    private void stunHim(ServerLevel level, ServerPlayer by) {
        if (mode == ManbearMode.GRAB) endGrab(level, true);   // the hit knocks his grip loose
        else if (mode == ManbearMode.KIDNAP && kidPhase == KID_CARRY) endKidnap(level, true);
        manStunTicks = ManbearConfig.STUN_TICKS.get();
        manStunCd = manStunTicks + 100;
        m.getNavigation().stop();
        m.setRoarTicks(Math.min(manStunTicks, 30));
        level.sendParticles(ParticleTypes.CRIT, m.getX(), m.getY() + 2.0, m.getZ(), 25, 0.6, 1.2, 0.6, 0.2);
        level.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.SHIELD_BREAK, SoundSource.HOSTILE, 1.5F, 0.7F);
        ManbearData.get(level.getServer()).profile(by.getUUID()).addRage(ManbearConfig.RAGE_STUN.get().floatValue());
    }

    private Animal nearbyAnimal(ServerLevel level) {
        Animal best = null;
        double bd = Double.MAX_VALUE;
        for (Animal a : level.getEntitiesOfClass(Animal.class, m.getBoundingBox().inflate(7, 3, 7),
                x -> x.isAlive() && !(x instanceof CompanionBird) && !x.getTags().contains("manbear_lure") && !x.getTags().contains(PetKidnap.CAPTIVE)
                        && !(x instanceof OwnableEntity oe && oe.getOwnerUUID() != null))) {
            double d = a.distanceToSqr(m);
            if (d < bd) { bd = d; best = a; }
        }
        return best;
    }

    private void startDistract(ServerLevel level, Animal a) {
        distractAnimal = a;
        distractTicks = ManbearConfig.DISTRACT_TICKS.get();
        distractCd = distractTicks + 160;
        m.getNavigation().stop();
        m.playBear(ModSounds.SNARL.get(), 3.0F, 0.8F);
        if (prof != null && target != null) prof.addRage(ManbearConfig.RAGE_STUN.get().floatValue());   // you used an animal to slow him down
    }

    /** he runs to the animal, kills it, and goes straight back to the chase */
    private void tickDistract(ServerLevel level) {
        Entity a = distractAnimal;
        if (a == null || !a.isAlive()) { distractTicks = 0; distractAnimal = null; return; }
        distractTicks--;
        m.setFaceTarget(a, 40F);
        if (m.distanceTo(a) > 3.2) {
            navTo(level, a.getX(), a.getY(), a.getZ(), refSpeedMod() * 2.5, 4);
        } else {
            m.getNavigation().stop();
            if (distractTicks % 14 == 0) m.triggerAttack(rnd().nextInt(3));
            if (a instanceof LivingEntity le && distractTicks <= ManbearConfig.DISTRACT_TICKS.get() - 10) {
                le.hurt(level.damageSources().mobAttack(m), 1000F);
            }
        }
        if (distractTicks <= 0) distractAnimal = null;
    }

    // ================================================================= PET KIDNAPPING
    private LivingEntity findPet(ServerLevel level) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : PetKidnap.petsOf(level, target, 40)) {
            double d = e.distanceToSqr(m);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    /** sneaks up behind you; once your back has been turned long enough he dashes in, grabs your pet and carries it off to a trap */
    private void tickPetnap(ServerLevel level) {
        switch (petPhase) {
            case 0 -> {
                Vec3 look = target.getLookAngle();
                Vec3 lookH = new Vec3(look.x, 0, look.z);
                lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
                Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
                boolean seen = los && dist < 45 && look.dot(toEye) > 0.55;
                lurkSeen = seen ? lurkSeen + 1 : 0;
                petTarget = findPet(level);
                if ((lurkSeen >= 12 && modeTime > 40) || petTarget == null) {   // spotted, or nothing left to take
                    caveFleeing = true;
                    beginLeaving(level);
                    return;
                }
                petTimer = lurkSeen == 0 ? petTimer + 1 : 0;      // how long your back has been turned
                if (modeTime > 60 && petTimer >= 40 && m.distanceTo(petTarget) < 30) {
                    petPhase = 1;
                    petTimer = 0;
                    return;
                }
                // meanwhile stay at your back, like the lurker
                Vec3 toMe = new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ());
                double h = toMe.length();
                Vec3 toMeN = h < 1.0E-4 ? lookH.scale(-1) : toMe.scale(1.0 / h);
                if (dist > 11 || dist < 4 || lookH.dot(toMeN) > -0.5) {
                    Vec3 goal = target.position().subtract(lookH.scale(7.5));
                    BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 3);
                    if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, Math.max(1.2, refSpeedMod() * 1.6), 4);
                } else {
                    m.getNavigation().stop();
                }
            }
            case 1 -> {   // the dash
                if (petTarget == null || !petTarget.isAlive() || petTarget.isPassenger()) { petTarget = findPet(level); }
                if (petTarget == null || ++petTimer > 300) { caveFleeing = true; beginLeaving(level); return; }
                navTo(level, petTarget.getX(), petTarget.getY(), petTarget.getZ(), refSpeedMod() * 2.5, 4);
                if (m.distanceTo(petTarget) < 3.6) snatchPet(level, petTarget);
            }
            case 2 -> {   // carrying it away
                if (carried == null || !carried.isAlive()) { caveFleeing = true; beginLeaving(level); return; }
                petTimer++;
                if (petToDen) {
                    tickPetCarryToDen(level);
                    if (petTimer > 2400 && carried != null) { // safety fallback: never lose the pet if a custom cave blocks the route
                        petToDen = false;
                        dropSpot = chooseDropSpot(level);
                        petDenRetries = 0;
                    }
                    return;
                }
                if (dropSpot == null) dropSpot = chooseDropSpot(level);
                navTo(level, dropSpot.getX() + 0.5, dropSpot.getY(), dropSpot.getZ() + 0.5, refSpeedMod() * 2.2, 6);
                if (reached(dropSpot, 9.0) || petTimer > 500) {
                    dropPet(level);
                    petPhase = 3;
                }
            }
            default -> { caveFleeing = true; beginLeaving(level); }
        }
    }

    private void snatchPet(ServerLevel level, LivingEntity pet) {
        PetKidnap.capture(pet);
        pet.startRiding(m, true);
        carried = pet;
        m.triggerAttack(1);
        m.playBear(ModSounds.SNARL.get(), 3.5F, 0.8F);
        level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.WOLF_WHINE, SoundSource.NEUTRAL, 1.6F, 1.0F);
        ManbearData data = ManbearData.get(level.getServer());
        petToDen = ManbearConfig.DEN_PET_ABDUCTION_ENABLED.get() && data.hasDen()
                && data.denCenter() != null && data.denEntrance() != null
                && data.denDimension().equals(level.dimension().location().toString())
                // Do not force-load a den just to abduct a pet. If it is not currently loaded,
                // keep the established nearby-captive behavior instead.
                && level.hasChunkAt(data.denEntrance()) && level.hasChunkAt(data.denCenter())
                && rnd().nextFloat() < ManbearConfig.PET_TO_DEN_CHANCE.get().floatValue();
        dropSpot = petToDen ? null : chooseDropSpot(level);
        petDenWaypoint = 0;
        petDenStuck = 0;
        petDenRetries = 0;
        petDenTotalStuck = 0;
        petTravelWaypoint = null;
        if (petToDen) {
            m.setPersistenceRequired();
            m.extendLife(20 * 60 * 12);
        }
        petPhase = 2;
        petTimer = 0;
    }

    /** Walks the pet to the den through reachable exterior waypoints and the generated entrance ramp. */
    private void tickPetCarryToDen(ServerLevel level) {
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || data.denCenter() == null || data.denEntrance() == null
                || !data.denDimension().equals(level.dimension().location().toString())) {
            petToDen = false;
            dropSpot = chooseDropSpot(level);
            return;
        }
        BlockPos entrance = data.denEntrance();
        if (m.position().distanceToSqr(Vec3.atBottomCenterOf(entrance)) > 18.0D * 18.0D) {
            if (petTravelWaypoint == null || reached(petTravelWaypoint, 7.0) || petDenStuck > 28) {
                kidRouteAttempts = petDenRetries++;
                petTravelWaypoint = findReachableKidnapWaypoint(level, entrance);
                petDenStuck = 0;
            }
            BlockPos waypoint = petTravelWaypoint;
            if (waypoint == null) {
                Vec3 delta = Vec3.atBottomCenterOf(entrance).subtract(m.position());
                double len = Math.max(1.0, Math.sqrt(delta.x * delta.x + delta.z * delta.z));
                BlockPos near = CoverFinder.groundNear(level, m.getX() + delta.x / len * 10.0,
                        m.getZ() + delta.z / len * 10.0, m.getBlockY(), 8);
                if (near != null) waypoint = near;
            }
            if (waypoint != null) navTo(level, waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, 1.25, 8);
            if (petLastPositionClose()) petDenStuck++; else petDenStuck = 0;
            if (petDenStuck > 36) { m.getNavigation().stop(); unstick(level, 1.25); }
            if (petDenRetries > 14 && petDenTotalStuck > 180) {
                // Graceful fallback rather than risking the pet or holding an active entity forever.
                petToDen = false;
                dropSpot = chooseDropSpot(level);
            }
            return;
        }

        List<BlockPos> points = denCarryWaypoints(data);
        while (petDenWaypoint < points.size() && reached(points.get(petDenWaypoint), 3.5)) {
            petDenWaypoint++;
            petDenStuck = 0;
            m.getNavigation().stop();
            pathCd = 0;
        }
        if (petDenWaypoint >= points.size()) {
            dropPetInDen(level, data);
            petPhase = 3;
            petToDen = false;
            return;
        }
        BlockPos waypoint = points.get(petDenWaypoint);
        navTo(level, waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, 1.15, 4);
        if (petLastPositionClose()) petDenStuck++; else petDenStuck = 0;
        if (petDenStuck > 32) {
            clearNaturalDenLane(level, waypoint);
            m.getNavigation().stop();
            pathCd = 0;
            m.getJumpControl().jump();
            m.getMoveControl().setWantedPosition(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, 1.15);
            petDenStuck = 0;
        }
    }

    private Vec3 petLastPosition = Vec3.ZERO;
    private boolean petLastPositionClose() {
        boolean close = m.position().distanceToSqr(petLastPosition) < 0.0016D;
        if (close) petDenTotalStuck++; else petDenTotalStuck = Math.max(0, petDenTotalStuck - 1);
        petLastPosition = m.position();
        return close;
    }

    private void dropPetInDen(ServerLevel level, ManbearData data) {
        if (carried == null || !carried.isAlive() || data.denCenter() == null) return;
        LivingEntity pet = carried;
        carried = null;
        pet.stopRiding();
        BlockPos feet = data.denCenter().below(2).offset(2, 0, 2);
        if (!level.hasChunkAt(feet) || !level.getBlockState(feet).isAir()
                || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) {
            feet = data.denCenter().below(2);
        }
        pet.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, pet.getYRot(), 0F);
        pet.fallDistance = 0F;
        if (pet instanceof Mob mob) mob.setPersistenceRequired();
        pet.getPersistentData().putBoolean("ManbearDenPet", true);
        pet.getPersistentData().putLong("ManbearDenPetSince", level.getGameTime());
        PetKidnap.capture(pet);
        level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), SoundEvents.WOLF_WHINE, SoundSource.NEUTRAL, 1.0F, 0.65F);
    }

    /** somewhere 20-50 blocks from you, preferably out of your line of sight */
    private BlockPos chooseDropSpot(ServerLevel level) {
        BlockPos any = null;
        double originX = target != null ? target.getX() : m.getX();
        double originZ = target != null ? target.getZ() : m.getZ();
        int originY = target != null ? target.getBlockY() : m.getBlockY();
        for (int i = 0; i < 40; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = 20 + rnd().nextInt(31);
            BlockPos g = CoverFinder.groundNear(level, originX + Math.cos(ang) * d, originZ + Math.sin(ang) * d, originY, 2);
            if (g == null) continue;
            if (any == null) any = g;
            if (target == null || !CoverFinder.lineClear(level, target.getEyePosition(), Vec3.atBottomCenterOf(g).add(0, 1, 0), target)) return g;
        }
        return any != null ? any : m.blockPosition();
    }

    /** lets the pet down where he stands and clamps a closed bear trap around it */
    private void dropPet(ServerLevel level) {
        if (carried != null) {
            LivingEntity pet = carried;
            carried = null;
            if (pet.isAlive()) {
                pet.stopRiding();
                BlockPos at = m.blockPosition();
                pet.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, pet.getYRot(), 0F);
                pet.fallDistance = 0F;
                PetKidnap.capture(pet);
                PetKidnap.trap(level, pet);
            }
        }
    }

    /** he was told his name: a chase, a fake chase or he gives up (separate slices of one roll) */
    public void saidName(ServerPlayer p, float roll) {
        if (m.leaving || !(m.level() instanceof ServerLevel level)) return;
        if (mode == ManbearMode.FLASH || mode == ManbearMode.PETNAP) return;
        if (mode == ManbearMode.KIDNAP && kidPhase >= KID_CARRY) return;
        float c = ManbearConfig.SAY_CHASE_CHANCE.get().floatValue();
        float f = ManbearConfig.SAY_FAKE_CHANCE.get().floatValue();
        float d = ManbearConfig.SAY_DESPAWN_CHANCE.get().floatValue();
        if (roll < c) {
            target = p;
            targetLock = 600;
            ambushing = false;
            m.setChaser(true);
            setMode(level, ManbearMode.CHASE);
        } else if (roll < c + f) {
            target = p;
            targetLock = 600;
            setMode(level, ManbearMode.FAKE_CHASE);
        } else if (roll < c + f + d) {
            caveFleeing = true;
            beginLeaving(level);
        }
    }

    // ================================================================= LURKER
    /** Right behind you. If you turn, he scrambles to stay at your back; if you actually spot him he bolts and despawns out of sight. */
    private void tickLurker(ServerLevel level) {
        Vec3 look = target.getLookAngle();
        Vec3 lookH = new Vec3(look.x, 0, look.z);
        lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        boolean seen = los && dist < 45 && look.dot(toEye) > 0.55;
        lurkSeen = seen ? lurkSeen + 1 : 0;
        if (lurkSeen >= 12 && modeTime > 40) {   // you looked right at him: gone
            caveFleeing = true;
            beginLeaving(level);
            return;
        }
        Vec3 toMe = new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ());
        double h = toMe.length();
        Vec3 toMeN = h < 1.0E-4 ? lookH.scale(-1) : toMe.scale(1.0 / h);
        double facing = lookH.dot(toMeN);              // -1 = exactly behind you
        double want = 6.5;
        if (dist > 9.5 || dist < 3.5 || facing > -0.6) {
            Vec3 goal = target.position().subtract(lookH.scale(want));
            BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 3);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, Math.max(1.2, refSpeedMod() * 1.6), 4);
        } else {
            m.getNavigation().stop();
        }
    }

    // ================================================================= PATH BLOCKER / FOG WATCHER / CAVE
    /** He waits where you go most often. Come within a random 4-12 blocks and he lunges and chases. */
    private void tickPath(ServerLevel level) {
        m.getNavigation().stop();
        if (dist <= pathTrigger && (los || dist < 7)) pounceChase(level, false, 8.0F);
    }

    /** Stands at the very edge of your view in the fog and only watches. He keeps that distance and slips away if you rush him. */
    private void tickFog(ServerLevel level) {
        if (fogDist < 0) fogDist = dist;
        if (dist < fogDist * 0.45) { beginLeaving(level); return; }
        if (dist < fogDist * 0.85) {
            Vec3 me = m.position();
            Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
            away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
            BlockPos g = CoverFinder.groundNear(level, me.x + away.x * 14, me.z + away.z * 14, m.getBlockY(), 5);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0.9, 15);
            else m.getNavigation().stop();
        } else {
            m.getNavigation().stop();
        }
    }

    /** Sits in the blood with his back to you. If you come close he bolts, and vanishes once out of sight. */
    private void tickCave(ServerLevel level) {
        m.getNavigation().stop();
        if (dist < ManbearConfig.CAVE_FLEE_DISTANCE.get() && (los || dist < 6)) {
            caveFleeing = true;
            m.playBear(ModSounds.GROWL.get(), 3.0F, 0.8F);   // startled growl
            beginLeaving(level);
            return;
        }
        if (m.lifeTicks > m.lifespan() && ticksAlive % 20 == 0 && (!los || dist > 40)) m.discard();
    }

    private BlockPos fleeSpot(ServerLevel level) {
        BlockPos best = null;
        double bestScore = -1;
        for (int i = 0; i < 20; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = 10 + rnd().nextInt(14);
            BlockPos g = CoverFinder.groundNear(level, m.getX() + Math.cos(ang) * d, m.getZ() + Math.sin(ang) * d, m.getBlockY(), 3);
            if (g == null) continue;
            double score = g.distSqr(target.blockPosition());
            if (CoverFinder.lineClear(level, target.getEyePosition(), Vec3.atBottomCenterOf(g).add(0, 2, 0), target)) score *= 0.5;
            if (score > bestScore) { bestScore = score; best = g; }
        }
        return best;
    }

    // ================================================================= STUN ROAR
    private void startRoar(ServerLevel level) {
        roarsUsed++;
        roarCd = 160;
        stunTicks = ManbearConfig.ROAR_STUN_TICKS.get();
        stunTarget = target;
        m.setRoarTicks(Math.max(30, stunTicks));
        m.playBear(ModSounds.ROAR.get(), 6.0F, 0.7F);
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.8F);
        if (!ManbearConfig.SOUNDS_FROM_BODY.get()) level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR_DEEP.get(), SoundSource.HOSTILE, 1.8F, 0.9F);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, stunTicks + 6, 6, false, false, false));
        target.addEffect(new MobEffectInstance(MobEffects.JUMP, stunTicks + 6, 128, false, false, false));
    }

    /** freezes the player and forces their camera onto him */
    private void tickStun() {
        if (stunTicks <= 0) return;
        if (stunTarget == null || !stunTarget.isAlive() || stunTarget.hasDisconnected()) { stunTicks = 0; return; }
        stunTicks--;
        if (stunTicks % 2 == 0) {
            Vec3 d = m.getEyePosition().subtract(stunTarget.getEyePosition());
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Mth.atan2(d.z, d.x) * (180D / Math.PI)) - 90.0F;
            float pitch = (float) -(Mth.atan2(d.y, horiz) * (180D / Math.PI));
            stunTarget.setYRot(yaw);
            stunTarget.setXRot(pitch);
            stunTarget.connection.teleport(stunTarget.getX(), stunTarget.getY(), stunTarget.getZ(), yaw, pitch);
        }
    }

    // ================================================================= SLEEP
    public void addNoise(float amount) {
        if (mode == ManbearMode.SLEEP) noise += amount;
    }

    private void tickSleep(ServerLevel level) {
        m.getNavigation().stop();
        m.setFaceTarget(null, 0F);
        m.setClimbing(false);
        noise = Math.max(0F, noise - 0.025F);   // calms down 0.5 per second

        if (snoreCd-- <= 0) {
            snoreCd = 80 + rnd().nextInt(100);
            m.playBear(ModSounds.SNORE.get(), 2.2F, 0.9F + rnd().nextFloat() * 0.2F);
        }
        double nearest = Double.MAX_VALUE;
        ServerPlayer nearestPlayer = null;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            double d = p.distanceToSqr(m);
            if (d < nearest) { nearest = d; nearestPlayer = p; }
        }
        // walking right up to him (not sneaking) is noisy; sneaking past is fine
        if (nearestPlayer != null && nearest < 4.0 * 4.0 && !nearestPlayer.isShiftKeyDown()) noise += 0.04F;

        if (noise >= ManbearConfig.SLEEP_NOISE_THRESHOLD.get()) { wake(level); return; }

        // sleepers fade away eventually, but only when nobody is watching
        boolean denGuardian = denCaptiveId != null && kidPhase >= KID_DEN_GUARD;
        boolean escortingCaptive = mode == ManbearMode.KIDNAP && held != null;
        if (!denGuardian && !escortingCaptive && !returningToDen && m.lifeTicks > m.lifespan() && ticksAlive % 20 == 0
                && (nearestPlayer == null || nearest > 40 * 40 || !m.hasLineOfSight(nearestPlayer))) {
            m.discard();
        }
    }

    /** woken: roars and immediately chases whoever is closest */
    private void wake(ServerLevel level) {
        noise = 0;
        boolean wasDenGuardian = denCaptiveId != null && kidPhase == KID_DEN_SLEEP;
        m.setChaser(true);          // allowed to chase this time
        m.extendLife(2400);
        ambushing = true;           // roar + instant lunge readiness
        resolveTarget(level);
        if (target == null) { 
            if (denCaptiveId != null) {
                // If the captive is offline, stay in the den instead of deleting the guardian.
                m.getNavigation().stop();
                return;
            }
            m.discard();
            return;
        }
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.75F);
        setMode(level, ManbearMode.CHASE);
        if (wasDenGuardian) {
            ManbearData.get(level.getServer()).clearDenGuardian(m.getUUID());
            denCaptiveId = null;
        }
        refreshTraits(level);
    }

    /**
     * While he stalks he sometimes makes the sounds of someone else in your base: a chest or door opening
     * (and closing), a torch fizzling out, a low grumble. They come from random spots around you.
     */
    private void tickTricks(ServerLevel level) {
        for (java.util.Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (ticksAlive >= p.at()) {
                level.playSound(null, p.x(), p.y(), p.z(), p.ev(), p.src(), p.vol(), p.pitch());
                it.remove();
            }
        }
        if (trickCd-- > 0 || !ManbearConfig.AMBIENT_TRICKS.get()) return;
        int min = ManbearConfig.TRICK_MIN_INTERVAL_TICKS.get();
        int max = Math.max(min + 1, ManbearConfig.TRICK_MAX_INTERVAL_TICKS.get());
        trickCd = (int) ((min + rnd().nextInt(max - min)) * (1F - 0.5F * rageF));
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK;
        if (!stealthy) return;

        // He places the sound around his best current clue. If he lost sight, it is based on a stale/uncertain position.
        Vec3 believed = perceivedPlayerPosition();
        if (believed == null) return;
        Vec3 look = los ? target.getLookAngle() : Vec3.ZERO;
        double base = los && rnd().nextFloat() < 0.5F ? Math.atan2(-look.z, -look.x) : rnd().nextDouble() * Math.PI * 2;
        double ang = base + (rnd().nextDouble() - 0.5) * 1.2;
        double d = 7 + rnd().nextDouble() * 11;
        double x = believed.x + Math.cos(ang) * d;
        double z = believed.z + Math.sin(ang) * d;
        double y = believed.y;
        float gain = 1F;
        if (ManbearConfig.SOUNDS_FROM_BODY.get()) {        // it is HIM making them: from where he stands (louder, he is usually far off)
            x = m.getX();
            y = m.getY() + 1.0;
            z = m.getZ();
            gain = 2.2F;
        }
        float pitch = 0.9F + rnd().nextFloat() * 0.2F;
        int pick = rnd().nextInt(10);
        if (pick <= 1) {                       // chest creaks open, then shuts
            level.playSound(null, x, y, z, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1.3F * gain, pitch);
            pending.add(new Pending(ticksAlive + 30 + rnd().nextInt(30), SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, x, y, z, 1.3F * gain, pitch));
        } else if (pick <= 3) {                // a door
            boolean iron = rnd().nextFloat() < 0.25F;
            level.playSound(null, x, y, z, iron ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.4F * gain, pitch);
            pending.add(new Pending(ticksAlive + 25 + rnd().nextInt(40), iron ? SoundEvents.IRON_DOOR_CLOSE : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, x, y, z, 1.4F * gain, pitch));
        } else if (pick <= 5) {                // a torch burning out
            level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8F * gain, 1.3F + rnd().nextFloat() * 0.3F);
        } else if (pick == 6) {                // barrel
            level.playSound(null, x, y, z, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 1.3F * gain, pitch);
            pending.add(new Pending(ticksAlive + 35 + rnd().nextInt(30), SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, x, y, z, 1.3F * gain, pitch));
        } else {                               // low grumbling, from him
            m.playBear(rnd().nextBoolean() ? ModSounds.GROWL.get() : ModSounds.HUFF.get(), 1.4F + rnd().nextFloat(), 0.55F + rnd().nextFloat() * 0.15F);
        }
    }

    /** every 30 s while stalking / hiding / peeking / chasing: a 1-in-5 chance he plays back a phrase of YOUR voice */
    private void voiceMimic() {
        if (voiceCd-- > 0) return;
        voiceCd = ManbearConfig.VOICE_INTERVAL_TICKS.get();
        if (!ManbearConfig.VOICE_MIMIC.get() || target == null) return;
        boolean ok = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH;
        if (!ok || rnd().nextFloat() >= ManbearConfig.VOICE_CHANCE.get().floatValue() * (1F + rageF)) return;
        ServerPlayer who = target;
        double vx = m.getX();
        double vy = m.getY() + m.getBbHeight() * 0.7;
        double vz = m.getZ();
        PlayerProfile voiceProfile = ManbearData.get(levelServer()).profile(who.getUUID());
        int positionalPhrase = positionalVoicePhrase(who, voiceProfile);
        if (ManbearConfig.POSITIONAL_VOICE_CALLOUTS.get() && positionalPhrase >= 0
                && rnd().nextFloat() < ManbearConfig.POSITIONAL_VOICE_CALLOUT_CHANCE.get().floatValue()) {
            com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                    new com.manbear.net.PositionVoicePacket(vx, vy, vz, positionalPhrase));
            return;
        }
        // Sometimes the collage is staged at an old place the player changed or worked at, rather
        // than at the Manbear's body. The echo is spatially tied to a remembered moment, not randomly
        // scattered around the world. It still uses only that player's local microphone buffer.
        Vec3 contextual = ManbearConfig.SCRIPTED_ENCOUNTERS_ENABLED.get() && voiceProfile.scriptedEventReady("CONTEXT_VOICE", m.level().getGameTime(), 18000L)
                ? contextualVoiceAnchor((ServerLevel) m.level(), who, voiceProfile) : null;
        if (contextual != null && rnd().nextFloat() < 0.62F) {
            vx = contextual.x;
            vy = contextual.y;
            vz = contextual.z;
            voiceProfile.markScriptedEvent("CONTEXT_VOICE", m.level().getGameTime());
            ManbearData.get(levelServer()).setDirty();
            com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                    new com.manbear.net.VoiceMimicPacket(vx, vy, vz));
            return;
        }
        // multiplayer: very often use SOMEONE ELSE's voice so you think your friend is out there
        byte[] other = ManbearConfig.VOICE_SHARING.get() && rnd().nextFloat() < 0.7F
                ? com.manbear.voice.VoiceBank.randomFromOthers(who.getUUID(), new java.util.Random(rnd().nextLong())) : null;
        if (other != null) {
            com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                    new com.manbear.net.RemoteVoicePacket(vx, vy, vz, other));
        } else {
            com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                    new com.manbear.net.VoiceMimicPacket(vx, vy, vz));
        }
    }

    /** Chooses a directional phrase from the Manbear's real position relative to the player's facing. */
    private int positionalVoicePhrase(ServerPlayer player, PlayerProfile profile) {
        if (player == null || profile == null || player.level() != m.level()) return -1;
        double dx = m.getX() - player.getX();
        double dz = m.getZ() - player.getZ();
        double distanceSqr = dx * dx + dz * dz;
        if (distanceSqr < 4.0D || distanceSqr > 42.0D * 42.0D) return -1;

        // If the player is near their learned home and the Manbear is outside its immediate radius,
        // prefer the specific house cue over a generic relative-direction cue.
        BlockPos home = profile.base;
        if (home != null && home.distToCenterSqr(player.getX(), player.getY(), player.getZ()) <= 18.0D * 18.0D) {
            double bearHome = home.distToCenterSqr(m.getX(), m.getY(), m.getZ());
            if (bearHome >= 22.0D * 22.0D && bearHome <= 58.0D * 58.0D) return 1; // outside your house
        }

        double yaw = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double length = Math.sqrt(distanceSqr);
        double unitX = dx / length;
        double unitZ = dz / length;
        double forwardDot = unitX * forwardX + unitZ * forwardZ;
        double rightDot = unitX * rightX + unitZ * rightZ;
        if (forwardDot <= -0.48D) return 0; // behind you
        if (forwardDot >= 0.48D) return rnd().nextBoolean() ? 4 : 5; // in/front of you
        return rightDot >= 0.0D ? 3 : 2; // right / left
    }

    private MinecraftServer levelServer() { return m.level().getServer(); }

    private Vec3 contextualVoiceAnchor(ServerLevel level, ServerPlayer player, PlayerProfile profile) {
        if (level == null || player == null || profile == null) return null;
        long now = level.getGameTime();
        List<PlayerProfile.WorldMemory> memories = profile.recentWorldActions(now, level.dimension().location().toString(), 72000L);
        List<PlayerProfile.WorldMemory> candidates = new ArrayList<>();
        for (PlayerProfile.WorldMemory memory : memories) {
            long age = now - memory.gameTime();
            if (age < 1200L || age > 72000L) continue;
            BlockPos oldPlace = BlockPos.of(memory.pos());
            if (!level.hasChunkAt(oldPlace)) continue;
            double playerDistance = oldPlace.distToCenterSqr(player.getX(), player.getY(), player.getZ());
            if (playerDistance < 6.0D * 6.0D || playerDistance > 28.0D * 28.0D) continue;
            if (m.blockPosition().distSqr(oldPlace) > 76L * 76L) continue;
            candidates.add(memory);
        }
        if (candidates.isEmpty()) return null;
        PlayerProfile.WorldMemory memory = candidates.get(rnd().nextInt(Math.min(candidates.size(), 5)));
        BlockPos oldPlace = BlockPos.of(memory.pos());
        Vec3 center = Vec3.atCenterOf(oldPlace);
        Vec3 towardPlayer = player.position().subtract(center);
        Vec3 flat = new Vec3(towardPlayer.x, 0.0D, towardPlayer.z);
        if (flat.lengthSqr() < 0.01D) flat = new Vec3(m.getX() - center.x, 0.0D, m.getZ() - center.z);
        if (flat.lengthSqr() < 0.01D) flat = new Vec3(1.0D, 0.0D, 0.0D);
        flat = flat.normalize();
        Vec3 desired = center.subtract(flat.scale(3.5D + rnd().nextDouble() * 3.0D));
        BlockPos ground = CoverFinder.groundNear(level, desired.x, desired.z, oldPlace.getY(), 2);
        if (ground == null || !level.hasChunkAt(ground)) return null;
        Vec3 anchor = new Vec3(ground.getX() + 0.5D, ground.getY() + 1.0D, ground.getZ() + 0.5D);
        double distance = anchor.distanceTo(player.position());
        if (distance < 5.0D || distance > 34.0D) return null;
        return anchor;
    }

    /** Recombines several short fragments of the player's own in-game chat into a new, formally phrased private taunt. */
    private void chatEcho() {
        if (!ManbearConfig.CHAT_ECHO_ENABLED.get() || target == null || prof == null) return;
        if (chatEchoCd-- > 0) return;
        chatEchoCd = ManbearConfig.CHAT_ECHO_INTERVAL_TICKS.get()
                + rnd().nextInt(Math.max(1, ManbearConfig.CHAT_ECHO_INTERVAL_TICKS.get() / 2));

        boolean stalking = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK;
        Vec3 known = perceivedPlayerPosition();
        if (!stalking || known == null || (!los && mindPerceptionConfidence < 0.22F)
                || m.position().distanceTo(known) < 7.0D || m.position().distanceTo(known) > 42.0D
                || rnd().nextFloat() >= ManbearConfig.CHAT_ECHO_CHANCE.get()) return;

        String first = prof.randomSpeechFragment(rnd());
        if (first == null || first.isBlank()) return;

        // Try to find a second, different remembered fragment so each taunt can stitch together things
        // the player said at different times. If only one unique fragment is known, use a single-fragment line.
        String second = null;
        for (int i = 0; i < 5; i++) {
            String candidate = prof.randomSpeechFragment(rnd());
            if (candidate != null && !candidate.isBlank() && !candidate.equalsIgnoreCase(first)) {
                second = candidate;
                break;
            }
        }

        String[] forms;
        if (second != null) {
            String a = "“" + first + "”";
            String b = "“" + second + "”";
            forms = new String[] {
                    "I remember you speaking of " + a + ", and later of " + b + ". I have wondered why those words mattered to you.",
                    "You gave me two pieces of yourself: " + a + " and " + b + ". I wonder what else you have left unsaid.",
                    "First you said " + a + "; then you said " + b + ". It is interesting what people reveal when they think no one is listening.",
                    "Your words keep returning to me: " + a + ", " + b + ". They sound rather different here in the dark.",
                    "I have kept both phrases, " + a + " and " + b + ". Perhaps they will help me find you when you hide."
            };
        } else {
            String quote = "“" + first + "”";
            forms = new String[] {
                    "I remember you saying " + quote + ". I have considered what those words reveal about you.",
                    "You said " + quote + ". I wonder whether you will believe those words when I am beside you.",
                    "Your words were " + quote + ". They sound rather different here in the dark.",
                    "I have kept the phrase " + quote + ". It has helped me understand where you feel safe.",
                    "You once said " + quote + ". Perhaps you should consider who was listening.",
                    "I have given your words a new meaning: " + quote + ". You are not alone out here."
            };
        }

        int pick = rnd().nextInt(forms.length);
        if (forms[pick].equals(lastChatEchoLine)) pick = (pick + 1) % forms.length;
        String line = forms[pick];
        lastChatEchoLine = line;

        // This is a private system message to the targeted player, not an announcement to the server.
        target.displayClientMessage(Component.literal(line), false);
    }

    /** closer = heavier breathing */
    private void breathe() {
        if (breathCd-- > 0) return;
        breathCd = 50 + rnd().nextInt(60);
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.LAY_TRAP || mode == ManbearMode.BEHIND || mode == ManbearMode.LURKER
                || mode == ManbearMode.INTIMIDATE || mode == ManbearMode.PITLURE || mode == ManbearMode.HALLUCINATE || (mode == ManbearMode.KIDNAP && kidPhase < KID_CARRY);
        boolean soft = mode == ManbearMode.INTIMIDATE;   // the intimidator only ever breathes softly
        double range = soft ? 40.0 : 30.0;
        if (!stealthy || dist >= range) return;
        float c = 1F - (float) (dist / range);
        float vol = soft ? 0.25F + 0.9F * c : 0.35F + 2.8F * c * c;
        m.playBear(ModSounds.HUFF.get(), vol, soft ? 0.7F : 0.8F + rnd().nextFloat() * 0.2F);
    }

    // ================================================================= ambience
    /** distorted pig / cow / sheep noises, from him or from behind you */
    private void mimicAnimal() {
        if (mimicCd > 0 || !ManbearConfig.MIMIC_ANIMAL_SOUNDS.get()) return;
        mimicCd = 450 + rnd().nextInt(900);
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.LAY_TRAP || mode == ManbearMode.RETREAT;
        if (!stealthy) return;
        SoundEvent ev;
        int pick = rnd().nextInt(3);
        if (pick == 0) ev = ModSounds.MIMIC_PIG.get();
        else if (pick == 1) ev = ModSounds.MIMIC_COW.get();
        else ev = ModSounds.MIMIC_SHEEP.get();
        double x = m.getX();
        double y = m.getY();
        double z = m.getZ();
        if (!ManbearConfig.SOUNDS_FROM_BODY.get() && rnd().nextFloat() < 0.3F) { // from behind the player (old behaviour)
            Vec3 look = target.getLookAngle();
            x = target.getX() - look.x * 18;
            y = target.getY();
            z = target.getZ() - look.z * 18;
        }
        m.level().playSound(null, x, y, z, ev, SoundSource.NEUTRAL, 2.2F, 1.0F);
    }

    private void ambient() {
        if (ambientCd > 0) return;
        RandomSource r = rnd();
        ambientCd = 300 + r.nextInt(600);
        switch (mode) {
            case STALK -> {
                if (r.nextFloat() < 0.7F) m.playBear(ModSounds.GROWL.get(), 3.0F, 0.8F + r.nextFloat() * 0.3F);
                else m.playBear(ModSounds.HUFF.get(), 1.6F, 0.9F);
                if (r.nextFloat() < 0.05F) m.playBear(ModSounds.ROAR.get(), 5.5F, 0.8F);
            }
            case HIDE, PEEK -> { if (dist < 22) m.playBear(ModSounds.HUFF.get(), 1.1F, 0.9F + r.nextFloat() * 0.2F); }
            case CHASE, AMBUSH -> m.playBear(ModSounds.GROWL.get(), 2.5F, 0.9F);
            case RETREAT -> m.playBear(ModSounds.GROWL.get(), 1.5F, 0.7F);
            default -> { }
        }
    }

    /** he is gone for good (despawned, killed, /remove): an unsprung pit is filled back in */
    public void onDestroyed(Entity.RemovalReason reason) {
        if (destroyed) return;          // remove() can run more than once in the same tick: only act the first time
        destroyed = true;
        if (pit != null && !pit.sprung() && m.level() instanceof ServerLevel sl) PitTraps.removeAndFill(sl, pit);
        pit = null;
        // he vanished while stalking / hiding / peeking and you never noticed him: he leaves something behind
        boolean watching = isWatch(mode) || (mode == ManbearMode.RETREAT && leaveFromWatch);
        if (reason == Entity.RemovalReason.DISCARDED && ManbearConfig.EVIDENCE_ENABLED.get() && !noEvidence && !noticed && watching
                && m.level() instanceof ServerLevel sl) {
            Vec3 away = target != null ? new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ()) : new Vec3(1, 0, 0);
            Evidence.leave(sl, this, m.position(), away, new java.util.ArrayList<>(trail));
        }
    }

    /** test command */
    public boolean debugEvidence(ServerLevel level, String kind, Vec3 at, Vec3 away) {
        return Evidence.leaveAs(kind, level, this, at, away, new java.util.ArrayList<>(trail));
    }

    /** removed on purpose (command): no evidence */
    public void suppressEvidence() { noEvidence = true; }

    private static boolean isWatch(ManbearMode md) {
        return md == ManbearMode.STALK || md == ManbearMode.HIDE || md == ManbearMode.PEEK;
    }

    // ================================================================= PIT LURE
    /** you are in (or at) the hole: he reaches down into it from the edge instead of trying to walk in */
    private boolean targetInPit() {
        return pitChase && target != null && target.getY() < m.getY() - 1.5;
    }

    /** distance used for his melee: straight down into a pit only counts sideways */
    private double reachDist() {
        if (targetInPit()) {
            double dx = target.getX() - m.getX();
            double dz = target.getZ() - m.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        return dist;
    }

    /**
     * 0: he stands silent behind you, waiting for you to notice him.
     * 1: you did - he digs his pit and runs off past it.
     * 2: he waits on the far side, always keeping the pit between you and him. If you walk to him you step on the cover,
     *    it breaks, and the chase starts. If you do not follow (or walk around it) he leaves.
     */
    private void tickPitLure(ServerLevel level) {
        if (pitPhase == 0) {
            Vec3 look = target.getLookAngle();
            Vec3 lookH = new Vec3(look.x, 0, look.z);
            lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
            Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
            boolean seen = los && dist < 45 && look.dot(toEye) > 0.5;
            if (seen && modeTime > 30) { pitNoticed(level); return; }
            if (modeTime > ManbearConfig.PIT_NOTICE_SECONDS.get() * 20) { vanishFast(level); return; }   // never noticed him
            // stay at your back; if you stand still, so does he
            if (dist > 17 || dist < 5.5) {
                Vec3 goal = target.position().subtract(lookH.scale(11));
                BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 5);
                if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, dist > 17 ? Math.max(0.9, refSpeedMod() * 1.05) : 0.7, 8);
            } else {
                m.getNavigation().stop();
            }
            return;
        }
        if (pit == null) { vanishFast(level); return; }
        if (pit.sprung()) { startPitChase(level); return; }            // somebody stepped on the cover

        if (pitPhase == 1) {
            pitTimer++;
            Vec3 f = Vec3.atBottomCenterOf(pitFar);
            navTo(level, f.x, f.y, f.z, Math.max(1.3, refSpeedMod() * 1.5), 6);
            if (reached(pitFar, 9.0) || pitTimer > 400 || navFailCount > 8) {
                pitPhase = 2;
                pitWait = 0;
                m.getNavigation().stop();
            }
            return;
        }

        pitWait++;
        double dFar = Math.hypot(target.getX() - (pitFar.getX() + 0.5), target.getZ() - (pitFar.getZ() + 0.5));
        if (dFar < 5.5                                                     // you got to him without falling: you went around
                || dFar > 80                                               // you wandered off
                || (pitWait > ManbearConfig.PIT_FOLLOW_SECONDS.get() * 20 && dFar > 30)) {   // you are not following
            vanishFast(level);
            return;
        }
        // keep the pit exactly on the line between you and him, whichever way you approach
        if (pitWait % 20 == 0) {
            Vec3 c = Vec3.atBottomCenterOf(pit.top);
            double dx = c.x - target.getX();
            double dz = c.z - target.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 6) {
                double wx = c.x + dx / len * 8;
                double wz = c.z + dz / len * 8;
                if (Math.hypot(wx - (pitFar.getX() + 0.5), wz - (pitFar.getZ() + 0.5)) > 4.5) {
                    BlockPos g = CoverFinder.groundNear(level, wx, wz, pit.top.getY() + 1, 5);
                    if (g != null) pitFar = g;
                }
            }
        }
        if (reached(pitFar, 4.0)) {
            m.getNavigation().stop();
        } else {
            Vec3 f = Vec3.atBottomCenterOf(pitFar);
            navTo(level, f.x, f.y, f.z, 0.9, 10);
        }
    }

    /** you turned around and saw him: dig the pit beyond him and run for the far side of it */
    private void pitNoticed(ServerLevel level) {
        pit = PitTraps.plan(level, target, m.position());
        if (pit == null) { vanishFast(level); return; }              // no suitable ground: he just bolts
        Vec3 c = Vec3.atBottomCenterOf(pit.top);
        double dx = c.x - target.getX();
        double dz = c.z - target.getZ();
        double len = Math.max(1.0E-3, Math.sqrt(dx * dx + dz * dz));
        pitFar = CoverFinder.groundNear(level, c.x + dx / len * 8, c.z + dz / len * 8, pit.top.getY() + 1, 5);
        if (pitFar == null) pitFar = CoverFinder.groundNear(level, c.x + dx / len * 6, c.z + dz / len * 6, pit.top.getY() + 1, 3);
        if (pitFar == null) { vanishFast(level); return; }            // exitMode fills the pit again
        m.playBear(ModSounds.HUFF.get(), 2.0F, 0.7F);
        pitPhase = 1;
        pitTimer = 0;
        pitWait = 0;
        navFailCount = 0;
        pathCd = 0;
    }

    /** you fell in: he roars and the chase begins */
    private void startPitChase(ServerLevel level) {
        ServerPlayer victim = pit.sprungBy == null ? null : level.getServer().getPlayerList().getPlayer(pit.sprungBy);
        if (victim != null && victim.isAlive() && victim.level() == m.level()) {
            target = victim;
            targetLock = 800;
        }
        pitChase = true;
        m.setChaser(true);
        ambushing = true;             // he roars as it starts and may lunge at once
        setMode(level, ManbearMode.CHASE);
        pit = null;
        refreshTraits(level);
    }

    // ================================================================= KIDNAPPING
    private void tickKidnap(ServerLevel level) {
        switch (kidPhase) {
            case KID_SNEAK -> kidSneak(level);
            case KID_BACKOFF -> kidBackoff(level);
            case KID_CARRY -> kidCarry(level);
            case KID_FADE -> kidFade(level);
            case KID_DEN_CARRY -> tickDenCarry(level);
            case KID_DEN_GUARD -> tickDenCaptivity(level, false);
            case KID_DEN_SLEEP -> tickDenCaptivity(level, true);
            default -> kidFade(level);
        }
    }

    /** creeps up on you, crouched. Look at him and he backs off into plain stalking. Reach you and he takes you. */
    private void kidSneak(ServerLevel level) {
        Vec3 look = target.getLookAngle();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        boolean seen = los && dist < 60 && look.dot(toEye) > 0.6;
        kidSeen = seen ? kidSeen + 1 : 0;
        if (kidSeen >= 8 && modeTime > 20) {            // you spotted him
            kidPhase = KID_BACKOFF;
            kidTimer = 0;
            m.getNavigation().stop();
            m.playBear(ModSounds.HUFF.get(), 2.2F, 0.7F);
            return;
        }
        if (dist < 2.8 && target.isAlive()) { startKidnap(level); return; }
        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }
        // slowly: only a little faster than you are walking away from him
        double bps = prof != null ? prof.velocity().length() * 2.0 : 0.0;     // blocks per second
        double speed = Mth.clamp((bps + 1.7) / 4.317, 0.45, 1.5);
        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 8);
        tunnel(level, speed);
    }

    /** walks backwards, still facing you, until he is far enough away to be a stalker again */
    private void kidBackoff(ServerLevel level) {
        kidTimer++;
        Vec3 me = m.position();
        Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
        BlockPos g = CoverFinder.groundNear(level, me.x + away.x * 14, me.z + away.z * 14, m.getBlockY(), 3);
        if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0.8, 8);
        else m.getNavigation().stop();
        if (dist >= hideMin() + 2 || kidTimer > 160) setMode(level, ManbearMode.STALK);
    }

    private void startKidnap(ServerLevel level) {
        breaker.cancel(level, m);
        target.stopRiding();
        held = target;
        kidCount = 0;
        grabProgress = 0F;
        float rage = prof != null ? prof.rageFactor() : 0F;
        int lo = ManbearConfig.KIDNAP_PRESSES_MIN.get();
        int hi = Math.max(lo, ManbearConfig.KIDNAP_PRESSES_MAX.get());
        kidPresses = lo + Math.round((hi - lo) * rage);              // more rage, more presses
        // enough time to mash that many presses at a hard-but-possible ~6 per second, plus a breath
        kidMinTicks = (int) ((kidPresses / 6.0 + 2.0) * 20);
        kidTimer = 0;
        kidStuck = 0;
        targetLock = 6000;
        chooseKidnapDestination(level);
        kidPhase = KID_CARRY;
        m.triggerAttack(1);
        m.playBear(ModSounds.SNARL.get(), 6.0F, 0.6F);
        held.displayClientMessage(Component.literal("MASH SPACE!"), true);
    }

    /** the nearest cave he can really walk to; if there is none he simply carries you off into the distance */
    private void chooseKidnapDestination(ServerLevel level) {
        kidCave = null;
        kidTravelWaypoint = null;
        kidRouteAttempts = 0;
        kidnapToDen = false;
        kidSpeed = 1.6;
        ManbearData worldData = ManbearData.get(level.getServer());
        if (worldData.hasDen() && worldData.denDimension().equals(level.dimension().location().toString())) {
            // The den is the preferred destination even if its chunk was unloaded; its coordinates are persistent.
            BlockPos entrance = worldData.denEntrance();
            if (entrance != null && level.getWorldBorder().isWithinBounds(entrance)) {
                level.getChunkAt(entrance);
                kidCave = entrance.immutable();
                kidnapToDen = true;
                kidSpeed = 1.55;
                // Reserve the den guardian slot before starting a long trip, so an active-entity timeout
                // cannot replace/despawn the escort halfway through the journey.
                worldData.setDenGuardian(m.getUUID());
                m.setPersistenceRequired();
                m.extendLife(20 * 60 * 30);
                return;
            }
        }
        PathNavigation nav = m.getNavigation();
        int tries = 0;
        for (BlockPos c : CaveFinder.find(level, m.blockPosition(), ManbearConfig.KIDNAP_SEARCH_RADIUS.get(), 10, rnd())) {
            if (++tries > 6) break;
            Path path = nav.createPath(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 1);
            if (path != null && path.canReach()) {
                kidCave = c;
                // far caves: run; near caves: slower, so you are not robbed of your chance
                double secs = Math.max(4.0, kidMinTicks / 20.0 * 0.85);
                kidSpeed = Mth.clamp(path.getNodeCount() / secs / 4.317, 0.9, 2.0);
                return;
            }
        }
        for (int i = 0; i < 20; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = 35 + rnd().nextDouble() * 25;
            BlockPos g = CoverFinder.groundNear(level, m.getX() + Math.cos(ang) * d, m.getZ() + Math.sin(ang) * d, m.getBlockY(), 3);
            if (g != null) { kidCave = g; return; }
        }
    }

    /** Choose a short path-reachable waypoint along the route to the den rather than one distant, impossible path. */
    private BlockPos findReachableKidnapWaypoint(ServerLevel level, BlockPos destination) {
        Vec3 goal = Vec3.atBottomCenterOf(destination);
        Vec3 delta = goal.subtract(m.position());
        double distance = delta.length();
        if (distance < 16.0) return destination;
        Vec3 direction = delta.scale(1.0 / distance);
        Vec3 side = new Vec3(-direction.z, 0, direction.x);
        double[] offsets = {0.0, 4.0, -4.0, 8.0, -8.0, 12.0, -12.0};
        int offsetStart = kidRouteAttempts == 0 ? 0 : 1 + (kidRouteAttempts % (offsets.length - 1));
        for (double step = Math.min(20.0, distance - 6.0); step >= 8.0; step -= 3.0) {
            for (int oi = 0; oi < offsets.length; oi++) {
                double lateral = offsets[(oi + offsetStart) % offsets.length];
                double x = m.getX() + direction.x * step + side.x * lateral;
                double z = m.getZ() + direction.z * step + side.z * lateral;
                BlockPos ground = CoverFinder.groundNear(level, x, z, m.getBlockY(), 8);
                if (ground == null || !level.getFluidState(ground.below()).isEmpty()) continue;
                if (!m.fitsIgnoringLeaves(m.getDimensions(m.getPose()).makeBoundingBox(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5))) continue;
                Path path = m.getNavigation().createPath(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 1);
                if (path != null && path.canReach()) return ground;
            }
        }
        return null;
    }

    /** holds you in front of his chest, camera on his face. Done every tick: he is running. */
    private void holdInFront(ServerLevel level) {
        float yaw = m.getYRot() * ((float) Math.PI / 180F);
        Vec3 fwd = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 hold = m.position().add(fwd.scale(1.6)).add(0, 1.2, 0);
        if (!level.noCollision(held, held.getBoundingBox().move(hold.subtract(held.position())))) {
            hold = m.position().add(fwd.scale(0.9)).add(0, 0.2, 0);    // not enough room to lift you here
        }
        Vec3 d = m.getEyePosition().subtract(hold.add(0, 1.6, 0));
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float ly = (float) (Mth.atan2(d.z, d.x) * (180D / Math.PI)) - 90.0F;
        float lp = (float) -(Mth.atan2(d.y, horiz) * (180D / Math.PI));
        held.setYRot(ly);
        held.setXRot(lp);
        held.connection.teleport(hold.x, hold.y, hold.z, ly, lp);
        held.setDeltaMovement(Vec3.ZERO);
        held.fallDistance = 0F;
    }

    /** you are being carried: mash space (the exp bar is the escape bar) before he has you in the cave */
    private void kidCarry(ServerLevel level) {
        if (held == null || !held.isAlive() || held.isDeadOrDying() || held.hasDisconnected() || held.level() != m.level()) {
            restoreXpBar();
            if (kidnapToDen && level.getServer() != null) ManbearData.get(level.getServer()).clearDenGuardian(m.getUUID());
            held = null;
            vanishFast(level);
            return;
        }
        kidTimer++;
        if (kidnapToDen && hasUsableKidnapDen(level)) {
            m.setPersistenceRequired();
            m.extendLife(20 * 60 * 30);
        }
        holdInFront(level);
        if (kidTimer % 2 == 0) held.connection.send(new ClientboundSetExperiencePacket(Mth.clamp(grabProgress, 0F, 1F), 0, 0));
        if (kidTimer % 10 == 0) held.displayClientMessage(Component.literal("MASH SPACE!"), true);
        if (kidTimer % 50 == 25) m.playBear(ModSounds.GROWL.get(), 2.5F, 0.7F);
        if (kidCount >= kidPresses) { endKidnap(level, true); return; }

        boolean arrived = false;
        if (kidCave != null) {
            arrived = reached(kidCave, kidnapToDen ? 4.0 : 16.0);
            if (arrived) {
                m.getNavigation().stop();
            } else {
                BlockPos travelGoal = kidCave;
                double remaining = m.position().distanceTo(Vec3.atBottomCenterOf(kidCave));
                // The den can be up to 200 blocks away; walk it in reachable waypoints instead of asking the navigator
                // for a single path beyond its practical search range.
                boolean denRoute = kidnapToDen && hasUsableKidnapDen(level);
                if (denRoute && remaining > 18.0) {
                    if (kidTravelWaypoint == null || reached(kidTravelWaypoint, 16.0)) {
                        kidTravelWaypoint = findReachableKidnapWaypoint(level, kidCave);
                    }
                    if (kidTravelWaypoint != null) travelGoal = kidTravelWaypoint;
                    else {
                        // Short ground-aware steps are safer than a single long path across difficult terrain.
                        Vec3 toward = Vec3.atBottomCenterOf(kidCave).subtract(m.position()).normalize();
                        Vec3 waypoint = m.position().add(toward.scale(Math.min(14.0, remaining - 5.0)));
                        BlockPos ground = CoverFinder.groundNear(level, waypoint.x, waypoint.z, m.getBlockY(), 10);
                        if (ground != null) travelGoal = ground;
                    }
                } else {
                    kidTravelWaypoint = null;
                }
                Vec3 c = Vec3.atBottomCenterOf(travelGoal);
                navTo(level, c.x, c.y, c.z, kidSpeed, 6);
                Vec3 cur = m.position();
                if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
                lastPos = cur;
                if (stuckTicks > 12) {
                    kidStuck++;
                    if (kidnapToDen && hasUsableKidnapDen(level)) {
                        kidTravelWaypoint = null;
                        kidRouteAttempts++;
                        m.getNavigation().stop();
                        pathCd = 0;
                        navFailCount = 0;
                        // Drive a little toward a fresh flank point instead of steering toward the held player.
                        Vec3 route = Vec3.atBottomCenterOf(kidCave).subtract(m.position());
                        Vec3 horizontal = new Vec3(route.x, 0, route.z);
                        if (horizontal.lengthSqr() > 0.01 && kidStuck % 2 == 0) {
                            horizontal = horizontal.normalize();
                            Vec3 side = new Vec3(-horizontal.z, 0, horizontal.x);
                            double sign = (kidRouteAttempts % 2 == 0) ? 1.0 : -1.0;
                            m.getMoveControl().setWantedPosition(m.getX() + horizontal.x * 5 + side.x * 4 * sign,
                                    m.getY(), m.getZ() + horizontal.z * 5 + side.z * 4 * sign, kidSpeed);
                        } else if (kidStuck % 3 == 0) {
                            m.getJumpControl().jump();
                        }
                        stuckTicks = 0;
                    } else {
                        unstick(level, kidSpeed);
                    }
                }
            }
        } else {
            m.getNavigation().stop();
        }
        // He never finishes before the escape window. For a generated den, he then walks through the entrance
        // and drops the player in the chamber; the old fade-and-relocate behavior remains only for fallback caves.
        boolean denRoute = kidnapToDen && hasUsableKidnapDen(level);
        // A failed waypoint is not arrival: never start walking the interior route from some random position
        // far outside the den. Keep escorting and re-planning until he is actually at the entrance.
        boolean over = denRoute ? arrived
                : (arrived || kidCave == null || kidStuck > 15 || navFailCount > 12 || kidTimer > kidMinTicks + 400);
        if (kidTimer >= kidMinTicks && over) {
            if (denRoute) beginDenCarry(level);
            else beginKidnapFade(level);
        }
    }

    /** you were too slow: the screen goes black */
    private void beginKidnapFade(ServerLevel level) {
        if (kidnapToDen && hasUsableKidnapDen(level)) {
            beginDenCarry(level);
            return;
        }
        kidPhase = KID_FADE;
        kidFadeTimer = 0;
        m.getNavigation().stop();
        restoreXpBar();
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.6F);
        ServerPlayer who = held;
        com.manbear.net.ManbearNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> who),
                new com.manbear.net.BlackoutPacket(30, 90, 50));
    }

    private void kidFade(ServerLevel level) {
        if (held == null) { vanish(level); return; }
        kidFadeTimer++;
        m.getNavigation().stop();
        holdInFront(level);
        if (kidFadeTimer >= 32) {       // fully black by now
            if (!kidnapTeleport(level, true)) vanish(level);
        }
    }

    private boolean kidnapTeleport(ServerLevel level) { return kidnapTeleport(level, true); }

    /** Old fallback for worlds without a usable generated den. */
    private boolean kidnapTeleport(ServerLevel level, boolean allowDen) {
        ServerPlayer p = held;
        restoreXpBar();
        held = null;
        if (p == null || !p.isAlive() || p.hasDisconnected()) return false;
        ServerLevel pl = p.serverLevel();
        ManbearData data = ManbearData.get(level.getServer());
        if (allowDen && kidnapToDen && data.hasDen()
                && data.denDimension().equals(pl.dimension().location().toString())
                && pl == level) {
            BlockPos roomFeet = data.denCenter().below(2);
            BlockPos entrance = data.denEntrance();
            pl.getChunkAt(roomFeet);
            if (entrance != null && CoverFinder.standable(pl, roomFeet, 2)) {
                p.teleportTo(pl, roomFeet.getX() + 0.5, roomFeet.getY(), roomFeet.getZ() + 0.5, p.getYRot(), 0F);
                p.setDeltaMovement(Vec3.ZERO);
                p.fallDistance = 0F;
                p.setHealth(Math.min(p.getHealth(), 1.0F));
                target = p;
                targetLock = 20 * 60 * 60;
                denCaptiveId = p.getUUID();
                ManbearData.get(level.getServer()).setDenGuardian(m.getUUID());
                kidPhase = KID_DEN_GUARD;
                kidTimer = 0;
                denGuardWaitTicks = 1200 + rnd().nextInt(2401);
                kidnapToDen = true;
                m.getNavigation().stop();
                m.setPersistenceRequired();
                m.extendLife(20 * 60 * 60);
                return true;
            }
        }

        BlockPos dest = randomFarSpot(pl, p);
        if (dest != null) p.teleportTo(pl, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, p.getYRot(), 0F);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0F;
        p.setHealth(Math.min(p.getHealth(), 1.0F));
        return false;
    }

    private boolean hasUsableKidnapDen(ServerLevel level) {
        if (level == null || level.getServer() == null) return false;
        ManbearData data = ManbearData.get(level.getServer());
        return data.hasDen() && data.denCenter() != null && data.denEntrance() != null
                && data.denDimension().equals(level.dimension().location().toString());
    }

    /** The player has failed to escape: stop the struggle bar and carry them through the actual den tunnel. */
    private void beginDenCarry(ServerLevel level) {
        if (!hasUsableKidnapDen(level)) {
            beginKidnapFade(level);
            return;
        }
        restoreXpBar();
        kidPhase = KID_DEN_CARRY;
        denCarryWaypoint = 0;
        denCarryStuck = 0;
        kidTravelWaypoint = null;
        kidRouteAttempts = 0;
        kidTimer = 0;
        lastPos = m.position();
        pathCd = 0;
        m.getNavigation().stop();
        m.setPersistenceRequired();
        m.extendLife(20 * 60 * 10);
        m.playBear(ModSounds.GROWL.get(), 2.8F, 0.65F);
    }

    private List<BlockPos> denCarryWaypoints(ManbearData data) {
        List<BlockPos> points = new ArrayList<>();
        BlockPos center = data.denCenter();
        if (center == null) return points;
        int cx = center.getX(), cz = center.getZ();
        int surface = center.getY() + 9;
        points.add(new BlockPos(cx + 19, surface - 1, cz)); // mouth, feet position
        for (int x = 18; x >= 5; x--) {
            int floorY = surface - 12 + Math.round((x - 5) * 10.0F / 14.0F);
            points.add(new BlockPos(cx + x, floorY + 1, cz));
        }
        for (int x = 4; x >= 0; x--) points.add(new BlockPos(cx + x, surface - 11, cz));
        return points;
    }

    private void tickDenCarry(ServerLevel level) {
        if (held == null || !held.isAlive() || held.isDeadOrDying() || held.hasDisconnected() || held.level() != level) {
            restoreXpBar();
            if (held != null) denCaptiveId = held.getUUID();
            restoreDenCaptiveToRoomPending = denCaptiveId != null;
            held = null;
            m.getNavigation().stop();
            m.setPersistenceRequired();
            kidPhase = KID_DEN_GUARD;
            kidTimer = 0;
            denGuardWaitTicks = 1200 + rnd().nextInt(2401);
            return;
        }
        if (!hasUsableKidnapDen(level)) {
            beginKidnapFade(level);
            return;
        }
        holdInFront(level);
        ManbearData data = ManbearData.get(level.getServer());
        List<BlockPos> points = denCarryWaypoints(data);
        while (denCarryWaypoint < points.size() && reached(points.get(denCarryWaypoint), 2.25)) {
            denCarryWaypoint++;
            denCarryStuck = 0;
            m.getNavigation().stop();
            pathCd = 0;
        }
        if (denCarryWaypoint >= points.size()) {
            finishKidnapInsideDen(level, data);
            return;
        }

        BlockPos waypoint = points.get(denCarryWaypoint);
        navTo(level, waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, 1.2, 3);
        Vec3 here = m.position();
        if (here.distanceToSqr(lastPos) < 0.0009) denCarryStuck++; else denCarryStuck = 0;
        lastPos = here;
        // Retry pathing rather than teleporting through the cave; the player remains attached during every retry.
        if (denCarryStuck > 35) {
            clearNaturalDenLane(level, waypoint);
            m.getNavigation().stop();
            pathCd = 0;
            m.getJumpControl().jump();
            m.getMoveControl().setWantedPosition(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, 1.2);
            denCarryStuck = 0;
        }
    }

    /** Clear only natural terrain inside the generated den footprint if it blocks the escort route. */
    private void clearNaturalDenLane(ServerLevel level, BlockPos waypoint) {
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || data.denCenter() == null) return;
        BlockPos center = data.denCenter();
        int surface = center.getY() + 9;
        Vec3 to = Vec3.atBottomCenterOf(waypoint).subtract(m.position());
        Vec3 horizontal = new Vec3(to.x, 0, to.z);
        if (horizontal.lengthSqr() < 1.0E-4) return;
        horizontal = horizontal.normalize();
        for (double ahead = 0.6; ahead <= 3.2; ahead += 0.65) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                double x = m.getX() + horizontal.x * ahead - horizontal.z * lateral;
                double z = m.getZ() + horizontal.z * ahead + horizontal.x * lateral;
                for (int up = 0; up <= 4; up++) {
                    BlockPos block = BlockPos.containing(x, m.getY() + up, z);
                    if (!level.hasChunkAt(block) || block.getX() < center.getX() - 21 || block.getX() > center.getX() + 21
                            || block.getZ() < center.getZ() - 21 || block.getZ() > center.getZ() + 21
                            || block.getY() < surface - 14 || block.getY() > surface + 2) continue;
                    BlockState state = level.getBlockState(block);
                    boolean natural = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                            || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND) || state.is(Blocks.CLAY)
                            || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MUD) || state.is(BlockTags.LEAVES)
                            || state.is(BlockTags.LOGS);
                    if (natural && level.getFluidState(block).isEmpty()) level.setBlock(block, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Drops the player onto the chamber floor and leaves the same Manbear to guard the exit. */
    private void finishKidnapInsideDen(ServerLevel level, ManbearData data) {
        ServerPlayer p = held;
        if (p == null || !p.isAlive() || p.hasDisconnected()) return;
        restoreXpBar();
        BlockPos roomFeet = data.denCenter().below(2);
        BlockPos landing = roomFeet;
        if (!CoverFinder.standable(level, landing, 2)) {
            // Search a few nearby floor blocks before giving up on the chamber landing.
            BlockPos found = null;
            for (int radius = 1; radius <= 3 && found == null; radius++) {
                for (int dx = -radius; dx <= radius && found == null; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos candidate = roomFeet.offset(dx, 0, dz);
                        if (CoverFinder.standable(level, candidate, 2)) { found = candidate; break; }
                    }
                }
            }
            if (found != null) landing = found;
        }
        held = null;
        p.connection.teleport(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5, p.getYRot(), 0F);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0F;
        p.setHealth(Math.min(p.getHealth(), 1.0F));
        target = p;
        targetLock = 20 * 60 * 60;
        denCaptiveId = p.getUUID();
        data.setDenGuardian(m.getUUID());
        kidPhase = KID_DEN_GUARD;
        kidTimer = 0;
        denGuardWaitTicks = 1200 + rnd().nextInt(2401); // 1–3 minutes
        kidnapToDen = true;
        m.getNavigation().stop();
        m.setPersistenceRequired();
        m.extendLife(20 * 60 * 10);
    }

    /** Guards the exit for 1–3 minutes, then walks back to the chamber and sleeps. */
    private void tickDenCaptivity(ServerLevel level, boolean headingToSleep) {
        ManbearData data = ManbearData.get(level.getServer());
        if (!data.hasDen() || !data.denDimension().equals(level.dimension().location().toString())) {
            data.clearDenGuardian(m.getUUID());
            denCaptiveId = null;
            setMode(level, ManbearMode.STALK);
            return;
        }
        BlockPos entrance = data.denEntrance();
        BlockPos roomFeet = data.denCenter().below(2);
        if (target == null || !target.isAlive() || target.hasDisconnected() || target.level() != level) {
            ServerPlayer captive = denCaptiveId == null ? null : level.getServer().getPlayerList().getPlayer(denCaptiveId);
            if (captive != null && captive.isAlive() && captive.serverLevel() == level) {
                target = captive;
                targetLock = 20 * 60 * 60;
            } else {
                // Keep guarding the entrance if the captive is temporarily offline or unavailable; do not vanish.
                m.setPersistenceRequired();
                if (headingToSleep) {
                    Vec3 center = Vec3.atBottomCenterOf(roomFeet);
                    navTo(level, center.x, center.y, center.z, 0.85, 8);
                } else if (entrance != null) {
                    Vec3 door = Vec3.atBottomCenterOf(entrance);
                    navTo(level, door.x, door.y, door.z, 0.8, 6);
                }
                return;
            }
        }
        if (headingToSleep) {
            if (reached(roomFeet, 3.0) || (m.getNavigation().isDone() && m.position().distanceToSqr(Vec3.atBottomCenterOf(roomFeet)) < 9.0)) {
                noise = 0F;
                m.extendLife(20 * 60 * 30);
                m.setPersistenceRequired();
                setMode(level, ManbearMode.SLEEP); // existing noise listener wakes him into a real chase
                return;
            }
            Vec3 center = Vec3.atBottomCenterOf(roomFeet);
            navTo(level, center.x, center.y, center.z, 0.85, 8);
            return;
        }

        // He must reach the mouth first; the timer begins only when he is actually blocking the route out.
        if (m.position().distanceToSqr(Vec3.atBottomCenterOf(entrance)) > 2.25) {
            Vec3 door = Vec3.atBottomCenterOf(entrance);
            navTo(level, door.x, door.y, door.z, 0.8, 6);
            return;
        }
        m.getNavigation().stop();
        kidTimer++;
        double fromRoom = target.position().distanceTo(Vec3.atBottomCenterOf(roomFeet));
        double toExit = target.position().distanceTo(Vec3.atBottomCenterOf(entrance));
        if (fromRoom > 11.5 || toExit < 8.0 || target.distanceTo(m) < 4.0) {
            startDenEscapeChase(level);
            return;
        }
        if (kidTimer >= denGuardWaitTicks) {
            kidPhase = KID_DEN_SLEEP;
            kidTimer = 0;
            m.getNavigation().stop();
        }
    }

    private void startDenEscapeChase(ServerLevel level) {
        ManbearData.get(level.getServer()).clearDenGuardian(m.getUUID());
        denCaptiveId = null;
        targetLock = 20 * 40;
        m.setChaser(true);
        if (rnd().nextBoolean()) {
            setMode(level, ManbearMode.FAKE_CHASE);
        } else {
            ambushing = true;
            m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.75F);
            setMode(level, ManbearMode.CHASE);
            refreshTraits(level);
        }
    }

    private BlockPos randomFarSpot(ServerLevel level, ServerPlayer p) {
        int max = ManbearConfig.KIDNAP_TELEPORT_RADIUS.get();
        int min = Math.min(60, max / 2);
        for (int i = 0; i < 40; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = min + Math.sqrt(rnd().nextDouble()) * (max - min);
            int x = Mth.floor(p.getX() + Math.cos(ang) * d);
            int z = Mth.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 64, z))) continue;
            level.getChunk(x >> 4, z >> 4);                           // load / generate the destination
            BlockPos g = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!CoverFinder.standable(level, g, 2)) continue;
            if (!level.getFluidState(g.below()).isEmpty()) continue;
            BlockState below = level.getBlockState(g.below());
            if (below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS)) continue;
            return g;
        }
        return null;
    }

    /** you broke free: you are flung clear, he is furious */
    private void endKidnap(ServerLevel level, boolean escaped) {
        restoreXpBar();
        if (kidnapToDen && kidPhase < KID_DEN_GUARD && level.getServer() != null) {
            ManbearData.get(level.getServer()).clearDenGuardian(m.getUUID());
        }
        ServerPlayer p = held;
        held = null;
        grabCd = 300;
        if (escaped && p != null && level.getServer() != null) {
            ManbearData.get(level.getServer()).profile(p.getUUID()).addRage(ManbearConfig.RAGE_ESCAPE.get().floatValue());
        }
        if (p != null && p.isAlive()) {
            Vec3 away = p.position().subtract(m.position());
            away = new Vec3(away.x, 0, away.z);
            away = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
            p.setDeltaMovement(away.x * 0.9, 0.45, away.z * 0.9);
            p.hurtMarked = true;
            p.fallDistance = 0F;
        }
        if (ManbearConfig.KIDNAP_ESCAPE_CHASES.get()) {
            ambushing = false;
            m.setChaser(true);
            setMode(level, ManbearMode.CHASE);
            freezeTicks = 30;                                          // dazed for a moment
        } else {
            vanishFast(level);
        }
    }

    // ================================================================= CONTINUOUS CHASE VOICE
    /**
     * Fake and real chases: he never stops roaring and screaming, and the closer he is the louder it gets
     * (volume ranges from about a quarter of the maximum at 64 blocks to the full value on top of you).
     */
    private void chaseVoice() {
        if (!ManbearConfig.CHASE_VOICE.get()) return;
        boolean on = (mode == ManbearMode.CHASE || mode == ManbearMode.AMBUSH || mode == ManbearMode.FAKE_CHASE)
                && !m.leaving && manStunTicks <= 0 && target != null;
        if (!on) { roarLoopCd = Math.min(roarLoopCd, 6); return; }
        if (--roarLoopCd > 0) return;
        float max = ManbearConfig.CHASE_VOICE_MAX_VOLUME.get().floatValue();
        float close = 1F - Mth.clamp((float) (dist / 64.0), 0F, 1F);   // 0 = far away, 1 = on top of you
        float vol = max * (0.22F + 0.78F * close * close);
        float pitch = 0.75F + rnd().nextFloat() * 0.25F;
        int pick = rnd().nextInt(10);
        if (pick < 4) {
            m.playBear(ModSounds.ROAR.get(), vol, pitch);
        } else if (pick < 7) {
            m.playBear(ModSounds.CHASE_SCREAM.get(), vol, 0.9F + rnd().nextFloat() * 0.3F);
        } else if (pick < 9) {                                          // both at once
            m.playBear(ModSounds.ROAR.get(), vol, pitch);
            m.playBear(ModSounds.CHASE_SCREAM.get(), vol * 0.85F, 0.8F + rnd().nextFloat() * 0.3F);
        } else {
            m.playBear(ModSounds.ROAR_DEEP.get(), vol, pitch - 0.1F);
        }
        roarLoopCd = 14 + rnd().nextInt(14);                            // a new one every 0.7-1.4 s, overlapping the last
    }

    // ================================================================= PROJECTILES
    /**
     * Something thrown or shot hit him. While he stalks, hides, peeks (or sneaks / waits in a lure) he is scared off: he runs
     * and despawns, and his rage rises. Above the rage limit he no longer flees: it only makes him angrier.
     * In any other mode it is an ordinary hit.
     */
    public void onProjectile(ServerLevel level, ServerPlayer sp) {
        boolean passive = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.PITLURE || mode == ManbearMode.HALLUCINATE || (mode == ManbearMode.KIDNAP && kidPhase < KID_CARRY);
        if (!ManbearConfig.PROJECTILES_SCARE.get() || !passive || m.leaving) { onHurt(level, sp); return; }
        ManbearData data = ManbearData.get(level.getServer());
        PlayerProfile pr = data.profile(sp.getUUID());
        boolean furious = pr.rage > ManbearConfig.PROJECTILE_RAGE_LIMIT.get().floatValue();   // judged before this hit
        pr.addRage(ManbearConfig.RAGE_PROJECTILE.get().floatValue());
        data.setDirty();
        target = sp;
        targetLock = 200;
        noticed = true;
        if (furious) {
            if (m.getHealth() < m.getMaxHealth() * 0.3F) { caveFleeing = true; beginLeaving(level); }
            return;                                                    // he stands his ground: only angrier
        }
        m.playBear(ModSounds.SNARL.get(), 3.0F, 1.1F);
        caveFleeing = true;                                            // runs for cover and vanishes out of sight
        beginLeaving(level);
    }

    // ================================================================= FAKE DEATH
    public boolean isPlayingDead() { return mode == ManbearMode.PLAYDEAD; }

    /** you have hurt him enough: he collapses with a death cry and lies still */
    private void startPlayDead(ServerLevel level, ServerPlayer sp) {
        target = sp;
        targetLock = 1200;
        chaseHits = 0;
        m.playBear(ModSounds.DEATH.get(), 8.0F, 0.85F);
        level.playSound(null, m.getX(), m.getY(), m.getZ(), net.minecraft.sounds.SoundEvents.ANVIL_LAND, net.minecraft.sounds.SoundSource.HOSTILE, 1.5F, 0.5F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, m.getX(), m.getY() + 0.5, m.getZ(), 30, 1.2, 0.3, 1.2, 0.02);
        setMode(level, ManbearMode.PLAYDEAD);
    }

    /**
     * He is not dead. He lies there (barely breathing, a faint tell) until you let your guard down: you look away for a
     * moment, walk off, or step right up to him. Then he is on his feet and the chase is on again, with a lunge ready.
     */
    private void tickPlayDead(ServerLevel level) {
        deadTicks++;
        m.getNavigation().stop();
        m.setClimbing(false);
        if (deadTicks % 90 == 45) m.playBear(ModSounds.HUFF.get(), 0.6F, 0.55F);
        Vec3 look = target.getLookAngle();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition());
        double len = toEye.length();
        boolean watching = los && dist < 40 && len > 1.0E-3 && look.dot(toEye.scale(1.0 / len)) > 0.35;
        deadAway = watching ? 0 : deadAway + 1;
        boolean guardDown = deadTicks > 20 && (deadAway >= 25 || dist > 20 || (dist < 3.0 && deadTicks > 30));
        if (guardDown || deadTicks > ManbearConfig.FAKE_DEATH_MAX_SECONDS.get() * 20) riseFromDead(level);
    }

    private void riseFromDead(ServerLevel level) {
        ambushing = true;             // roars and may lunge at once
        m.setChaser(true);
        chaseHits = 0;
        m.playBear(ModSounds.ROAR_DEEP.get(), 8.0F, 0.7F);
        setMode(level, ManbearMode.CHASE);
        refreshTraits(level);
    }

    /** hit while "dead": that was a mistake */
    public void onDeadHit(ServerLevel level, Entity attacker) {
        if (!(attacker instanceof ServerPlayer sp) || mode != ManbearMode.PLAYDEAD) return;
        target = sp;
        targetLock = 800;
        riseFromDead(level);
    }

    // ================================================================= HALLUCINATION
    /**
     * He waits motionless among the trees. A copy of YOU (your skin, name, armour, held item; only you can see it) stands in
     * the distance until you look at it, then runs for the woods. If you follow it and get close to him, he does one of:
     * lunge once and vanish / fake chase / flee / real chase (weights in the config: 30 / 40 / 20 / 10).
     */
    private void tickHallucinate(ServerLevel level) {
        hallTimer++;
        m.getNavigation().stop();
        if (!hallSpawned) {
            if (!spawnDoppelganger(level)) { vanishFast(level); return; }
            hallSpawned = true;
        }
        net.minecraft.world.entity.Entity d = doppelId == null ? null : level.getEntity(doppelId);
        hallGone = (d != null && d.isAlive()) ? 0 : hallGone + 1;
        if (hallTreeVariant) {
            // In the tree version, seeing the double make a dash behind the trunk ends the event quietly.
            if (hallGone > 10 || hallTimer > 20 * 90 || dist > 100) setMode(level, ManbearMode.STALK);
            return;
        }
        if (dist < 15 && (los || dist < 9)) { hallucinationOutcome(level); return; }      // you followed yourself to him
        if (hallTimer > 20 * 150 || hallGone > 20 * 45 || dist > 100) vanishFast(level);  // you did not follow
    }

    /** Each hallucination is one of two equally likely forms. If there is no tree nearby, use the normal lead-away form. */
    private boolean spawnDoppelganger(ServerLevel level) {
        hallTreeVariant = rnd().nextBoolean();
        if (hallTreeVariant && spawnTreeDoppelganger(level)) return true;
        hallTreeVariant = false;
        return spawnLeadingDoppelganger(level);
    }

    /** "you", standing between you and the woods where he is, facing you */
    private boolean spawnLeadingDoppelganger(ServerLevel level) {
        Vec3 pp = target.position();
        Vec3 mp = m.position();
        double dx = mp.x - pp.x;
        double dz = mp.z - pp.z;
        double total = Math.sqrt(dx * dx + dz * dz);
        if (total < 24) return false;
        double ux = dx / total;
        double uz = dz / total;
        double[] ahead = {22, 18, 26, 15, 30};
        for (double d : ahead) {
            if (d > total - 14) continue;                                  // keep well clear of him
            BlockPos g = CoverFinder.groundNear(level, pp.x + ux * d, pp.z + uz * d, target.getBlockY(), 3);
            if (g == null || Math.abs(g.getY() - target.getBlockY()) > 6) continue;
            com.manbear.entity.DoppelgangerEntity e = com.manbear.registry.ModEntities.DOPPELGANGER.get().create(level);
            if (e == null) return false;
            BlockPos run = CoverFinder.groundNear(level, mp.x - ux * 7, mp.z - uz * 7, m.getBlockY(), 3);   // a few blocks short of him
            e.copyOf(target);
            e.setDest(run != null ? Vec3.atBottomCenterOf(run) : mp);
            float yaw = (float) (Mth.atan2(pp.z - (g.getZ() + 0.5), pp.x - (g.getX() + 0.5)) * (180D / Math.PI)) - 90.0F;
            e.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, yaw, 0F);
            e.setYHeadRot(yaw);
            e.setYBodyRot(yaw);
            level.addFreshEntity(e);
            doppelId = e.getUUID();
            return true;
        }
        return false;
    }


    /** Place a skin-copy on the player-facing side of a nearby tree; its destination is hidden behind the trunk. */
    private boolean spawnTreeDoppelganger(ServerLevel level) {
        Vec3 playerPos = target.position();
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = rnd().nextDouble() * Math.PI * 2.0;
            double radius = 10.0 + rnd().nextDouble() * 15.0;
            double tx = playerPos.x + Math.cos(angle) * radius;
            double tz = playerPos.z + Math.sin(angle) * radius;
            BlockPos ground = CoverFinder.groundNear(level, tx, tz, target.getBlockY(), 5);
            if (ground == null || Math.abs(ground.getY() - target.getBlockY()) > 7) continue;

            BlockPos trunk = null;
            outer: for (int ox = -4; ox <= 4; ox++) for (int oz = -4; oz <= 4; oz++) {
                for (int oy = 1; oy <= 11; oy++) {
                    BlockPos check = ground.offset(ox, oy, oz);
                    if (level.getBlockState(check).is(net.minecraft.tags.BlockTags.LOGS)) {
                        trunk = check;
                        break outer;
                    }
                }
            }
            if (trunk == null) continue;
            double dx = playerPos.x - (trunk.getX() + 0.5);
            double dz = playerPos.z - (trunk.getZ() + 0.5);
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.01) continue;
            dx /= len; dz /= len;
            BlockPos start = CoverFinder.groundNear(level, trunk.getX() + 0.5 + dx * 2.3,
                    trunk.getZ() + 0.5 + dz * 2.3, ground.getY(), 3);
            BlockPos hide = CoverFinder.groundNear(level, trunk.getX() + 0.5 - dx * 2.3,
                    trunk.getZ() + 0.5 - dz * 2.3, ground.getY(), 4);
            if (start == null || hide == null || start.distSqr(target.blockPosition()) < 64) continue;

            com.manbear.entity.DoppelgangerEntity e = com.manbear.registry.ModEntities.DOPPELGANGER.get().create(level);
            if (e == null) return false;
            e.copyOf(target);
            e.setTreeAmbush(Vec3.atBottomCenterOf(trunk), Vec3.atBottomCenterOf(hide), 5 + rnd().nextInt(6));
            float yaw = (float) (Mth.atan2(playerPos.z - (start.getZ() + 0.5), playerPos.x - (start.getX() + 0.5)) * (180D / Math.PI)) - 90.0F;
            e.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, yaw, 0F);
            e.setYHeadRot(yaw);
            e.setYBodyRot(yaw);
            level.addFreshEntity(e);
            doppelId = e.getUUID();
            return true;
        }
        return false;
    }

    private void discardDoppel(ServerLevel level) {
        if (doppelId == null) return;
        net.minecraft.world.entity.Entity d = level.getEntity(doppelId);
        if (d != null) d.discard();
        doppelId = null;
    }

    /** you reached him: lunge-and-vanish / fake chase / flee / chase, by weight */
    private void hallucinationOutcome(ServerLevel level) {
        discardDoppel(level);
        int lunge = ManbearConfig.HALLUCINATION_LUNGE.get();
        int fake = ManbearConfig.HALLUCINATION_FAKE_CHASE.get();
        int flee = ManbearConfig.HALLUCINATION_FLEE.get();
        int chase = ManbearConfig.HALLUCINATION_CHASE.get();
        int total = lunge + fake + flee + chase;
        int pick = total <= 0 ? 0 : rnd().nextInt(total);
        int outcome;                       // 0 lunge, 1 fake chase, 2 flee, 3 chase
        if (total <= 0) outcome = 2;
        else if ((pick -= lunge) < 0) outcome = 0;
        else if ((pick -= fake) < 0) outcome = 1;
        else if ((pick -= flee) < 0) outcome = 2;
        else outcome = 3;
        switch (outcome) {
            case 0 -> ambush(target, m.blockPosition());                    // one lunge at you, then he is gone
            case 1 -> {
                targetLock = 600;
                setMode(level, ManbearMode.FAKE_CHASE);                     // a chase that stops short
            }
            case 2 -> {
                m.playBear(ModSounds.SNARL.get(), 3.0F, 1.0F);              // seen, and gone
                caveFleeing = true;
                beginLeaving(level);
            }
            default -> {
                ambushing = true;                                           // a real chase
                m.setChaser(true);
                targetLock = 800;
                setMode(level, ManbearMode.CHASE);
                refreshTraits(level);
            }
        }
    }

}
