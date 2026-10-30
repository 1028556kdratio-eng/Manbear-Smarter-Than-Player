package com.manbear.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * "You". A copy of one player (same skin, same model, same armour and held item, same name above its head) that ONLY
 * that player can see. It stands in the distance until you notice it, then runs for the woods, where he is waiting.
 * It is a hallucination: hit it (or touch it) and it is gone, and it is never saved with the world.
 */
public class DoppelgangerEntity extends PathfinderMob {
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(DoppelgangerEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    private static final int STARE = 0;   // stands facing you, waiting until you really look at it
    private static final int RUN = 1;     // running for the woods
    private static final int WAIT = 2;    // arrived: stands still until you come close

    private int state = STARE;
    private int stateTicks;
    private int seenTicks;
    private int stuck;
    private Vec3 dest;
    private Vec3 lastPos = Vec3.ZERO;
    private boolean treeAmbush;
    private Vec3 treePosition;
    private int treeNoticeRadius = 8;
    private int hiddenTicks;

    public DoppelgangerEntity(EntityType<? extends DoppelgangerEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_OWNER, Optional.empty());
    }

    public UUID ownerId() { return this.entityData.get(DATA_OWNER).orElse(null); }

    /** become a copy of this player */
    public void copyOf(ServerPlayer owner) {
        this.entityData.set(DATA_OWNER, Optional.of(owner.getUUID()));
        this.setCustomName(owner.getName());
        this.setCustomNameVisible(true);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setItemSlot(slot, owner.getItemBySlot(slot).copy());
            this.setDropChance(slot, 0.0F);
        }
    }

    public void setDest(Vec3 dest) { this.dest = dest; }

    /** Alternate hallucination: freeze beside a tree, then sprint behind it when noticed or approached. */
    public void setTreeAmbush(Vec3 treePosition, Vec3 dest, int noticeRadius) {
        this.treeAmbush = true;
        this.treePosition = treePosition;
        this.dest = dest;
        this.treeNoticeRadius = Math.max(5, Math.min(10, noticeRadius));
    }

    // ------------------------------------------------------------------ only its owner can see it
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        UUID owner = ownerId();
        return owner != null && owner.equals(player.getUUID());
    }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    protected void registerGoals() { /* driven by customServerAiStep */ }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean canBeLeashed(Player player) { return false; }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    /** it is not real: any blow dispels it */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && source.getEntity() instanceof Player) vanish();
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) vanish();
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    private void vanish() {
        if (this.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.9, getZ(), 18, 0.3, 0.6, 0.3, 0.02);
        }
        this.discard();
    }

    // ------------------------------------------------------------------ behaviour
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl)) return;
        UUID id = ownerId();
        ServerPlayer owner = id == null ? null : sl.getServer().getPlayerList().getPlayer(id);
        if (owner == null || owner.level() != this.level() || !owner.isAlive()) { vanish(); return; }

        stateTicks++;
        double away = this.distanceTo(owner);
        switch (state) {
            case STARE -> {
                this.getNavigation().stop();
                this.getLookControl().setLookAt(owner, 30F, 30F);
                Vec3 look = owner.getLookAngle();
                Vec3 to = this.position().add(0, 0.9, 0).subtract(owner.getEyePosition());
                double len = to.length();
                boolean seen = len > 1.0E-3 && len < 70 && look.dot(to.scale(1.0 / len)) > 0.88 && owner.hasLineOfSight(this);
                seenTicks = seen ? seenTicks + 1 : 0;
                boolean approachedTreeDouble = treeAmbush && away <= treeNoticeRadius;
                if (seenTicks >= 14 || approachedTreeDouble) {
                    state = RUN;
                    stateTicks = 0;
                    hiddenTicks = 0;
                    this.setSprinting(true);
                } else if (stateTicks > 20 * 45) vanish();                    // you never looked
            }
            case RUN -> {
                this.setSprinting(true);
                if (treeAmbush) {
                    Vec3 toDouble = this.position().add(0, 0.9, 0).subtract(owner.getEyePosition());
                    double viewLen = toDouble.length();
                    boolean inView = viewLen > 1.0E-3 && viewLen < 70
                            && owner.getLookAngle().dot(toDouble.scale(1.0 / viewLen)) > 0.12
                            && owner.hasLineOfSight(this);
                    hiddenTicks = inView ? 0 : hiddenTicks + 1;
                    if (hiddenTicks >= 3 || (dest != null && this.position().distanceToSqr(dest) < 2.25)) {
                        vanish();
                        return;
                    }
                }
                if (away > 40) {                                              // do not leave you behind: wait for you
                    this.getNavigation().stop();
                    this.getLookControl().setLookAt(owner, 30F, 30F);
                } else if (dest == null || this.position().distanceToSqr(dest) < 16) {
                    if (treeAmbush) { vanish(); return; }
                    state = WAIT;
                    stateTicks = 0;
                    this.setSprinting(false);
                    this.getNavigation().stop();
                } else if (this.tickCount % 10 == 0 || this.getNavigation().isDone()) {
                    this.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.0D);
                }
                if (this.tickCount % 20 == 0) {                               // wedged against something: give up
                    stuck = away <= 40 && this.position().distanceToSqr(lastPos) < 0.2 ? stuck + 1 : 0;
                    lastPos = this.position();
                    if (stuck >= 5) vanish();
                }
                if (stateTicks > 20 * 70) vanish();
            }
            default -> {                                                       // WAIT: reached the woods
                this.getNavigation().stop();
                if (away < 16 || stateTicks > 20 * 40) vanish();               // you came close: it is gone
            }
        }
    }
}
