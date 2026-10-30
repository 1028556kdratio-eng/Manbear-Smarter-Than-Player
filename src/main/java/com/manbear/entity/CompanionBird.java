package com.manbear.entity;

import com.manbear.ManbearConfig;
import com.manbear.ai.CoverFinder;
import com.manbear.data.ManbearData;
import com.manbear.event.CompanionBirds;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Your bird. It looks and flies like a parrot but is driven entirely from here: it hovers around you (never further than
 * {@code birdRadius} blocks), chirps now and then, and the moment the Manbear exists it stops chirping and flies away.
 * The silence is the warning. It comes back (see {@link CompanionBirds}) once he is gone.
 */
public class CompanionBird extends Parrot {
    private UUID ownerId;
    private boolean fleeing;
    private boolean returning;
    private int fleeTicks;
    private Vec3 fleeTo;
    private Vec3 hover;
    private int hoverCd;
    private int chirpCd = 60;
    private boolean danger;
    private Vec3 lastPos = Vec3.ZERO;
    private int stuck;
    private int wedged;

    public CompanionBird(EntityType<? extends CompanionBird> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setInvulnerable(true);
        this.chirpCd = 60 + this.random.nextInt(200);
    }

    public void setCompanionOwner(UUID owner) { this.ownerId = owner; }
    public UUID companionOwner() { return ownerId; }
    public void setReturning(boolean b) { this.returning = b; if (b) this.chirpCd = 60 + this.random.nextInt(100); }

    // ------------------------------------------------------------------ not a pet, not food, not a toy
    @Override
    protected void registerGoals() { /* driven by customServerAiStep */ }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) { return InteractionResult.PASS; }

    @Override
    public boolean canBeLeashed(Player player) { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    /** vanilla parrots squawk and imitate mobs; this one only makes the sounds we choose */
    @Override
    @Nullable
    public SoundEvent getAmbientSound() { return null; }

    // ------------------------------------------------------------------ save
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("CompanionOwner", ownerId);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("CompanionOwner")) ownerId = tag.getUUID("CompanionOwner");
    }

    // ------------------------------------------------------------------ brain
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl) || ownerId == null) return;
        MinecraftServer server = sl.getServer();

        if (this.tickCount % 40 == 7 && isStaleDuplicate(server)) { this.discard(); return; }
        if (this.tickCount % 5 == 0) danger = CompanionBirds.danger(server);

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null || owner.level() != this.level() || !owner.isAlive()) {
            hoverStill();
            return;
        }
        if (danger) {
            if (!fleeing) startFleeing(owner);
            tickFlee(owner);
            return;
        }
        fleeing = false;
        tickFollow(sl, owner);
        tickChirp(sl);
    }

    /** if the player has a newer bird, this one (stranded in an unloaded chunk until now) leaves */
    private boolean isStaleDuplicate(MinecraftServer server) {
        ManbearData data = ManbearData.get(server);
        UUID current = data.birdOf(ownerId);
        if (current == null) { data.setBird(ownerId, this.getUUID()); return false; }
        return !current.equals(this.getUUID());
    }

    // ---- follow
    private void tickFollow(ServerLevel level, ServerPlayer owner) {
        double d = this.distanceTo(owner);
        double radius = ManbearConfig.BIRD_RADIUS.get();

        if (returning) {                       // flying in from far away after the danger has passed
            if (d < 14) returning = false;
            else { flyAt(owner.getX(), owner.getY() + 4, owner.getZ(), 1.7); return; }
        }
        if (d > radius) { teleportNear(level, owner); return; }
        if (d > radius * 0.6) { flyAt(owner.getX(), owner.getY() + 3, owner.getZ(), 1.6); return; }

        // a bird that has not moved in a while is wedged on something
        if (this.tickCount % 20 == 0) {
            stuck = this.position().distanceToSqr(lastPos) < 0.25 && hover != null ? stuck + 1 : 0;
            lastPos = this.position();
            if (stuck >= 3) {             // pick somewhere else; if that keeps failing, just hop to the player
                stuck = 0;
                hoverCd = 0;
                if (++wedged >= 3) { wedged = 0; teleportNear(level, owner); return; }
            }
        }
        if (hover == null || --hoverCd <= 0 || this.position().distanceToSqr(hover) < 1.5) pickHover(level, owner);
        if (hover != null) flyAt(hover.x, hover.y, hover.z, 0.9);
    }

    private void pickHover(ServerLevel level, ServerPlayer owner) {
        hoverCd = 30 + this.random.nextInt(70);
        Vec3 from = this.position().add(0, 0.4, 0);
        for (int i = 0; i < 8; i++) {
            double ang = this.random.nextDouble() * Math.PI * 2;
            double r = 3 + this.random.nextDouble() * 8;
            Vec3 p = new Vec3(owner.getX() + Math.cos(ang) * r, owner.getY() + 1.5 + this.random.nextDouble() * 4.5, owner.getZ() + Math.sin(ang) * r);
            if (!level.getBlockState(BlockPos.containing(p)).isAir()) continue;
            if (CoverFinder.lineClear(level, from, p, this)) { hover = p; return; }
        }
        hover = owner.position().add(0, 3.0, 0);
    }

    private void flyAt(double x, double y, double z, double speed) {
        this.getMoveControl().setWantedPosition(x, y, z, speed);
    }

    /** keeps it airborne when there is nobody to follow */
    private void hoverStill() {
        if (hover != null) flyAt(hover.x, hover.y, hover.z, 0.5);
    }

    private void teleportNear(ServerLevel level, ServerPlayer owner) {
        for (int i = 0; i < 12; i++) {
            double ang = this.random.nextDouble() * Math.PI * 2;
            double r = 4 + this.random.nextDouble() * 6;
            Vec3 p = new Vec3(owner.getX() + Math.cos(ang) * r, owner.getY() + 2 + this.random.nextDouble() * 3, owner.getZ() + Math.sin(ang) * r);
            BlockPos bp = BlockPos.containing(p);
            if (level.hasChunkAt(bp) && level.getBlockState(bp).isAir() && level.getBlockState(bp.above()).isAir()) {
                this.moveTo(p.x, p.y, p.z, this.getYRot(), 0F);
                this.setDeltaMovement(Vec3.ZERO);
                hover = null;
                return;
            }
        }
        this.moveTo(owner.getX(), owner.getY() + 2.5, owner.getZ(), this.getYRot(), 0F);
        hover = null;
    }

    // ---- chirp
    private void tickChirp(ServerLevel level) {
        if (--chirpCd > 0) return;
        int min = ManbearConfig.BIRD_CHIRP_MIN_SECONDS.get() * 20;
        int max = Math.max(min + 20, ManbearConfig.BIRD_CHIRP_MAX_SECONDS.get() * 20);
        chirpCd = min + this.random.nextInt(max - min);
        float pitch = 0.92F + this.random.nextFloat() * 0.22F;
        level.playSound(null, this.getX(), this.getY(), this.getZ(), ModSounds.BIRD_CHIRP.get(), SoundSource.AMBIENT, 1.7F, pitch);
        if (this.random.nextFloat() < 0.3F) chirpCd = 12 + this.random.nextInt(25);   // sometimes it answers itself
    }

    // ---- danger
    private void startFleeing(ServerPlayer owner) {
        fleeing = true;
        returning = false;
        fleeTicks = 0;
        Vec3 away;
        ManbearEntity bear = ManbearEntity.current();
        if (bear != null && !bear.isRemoved() && bear.level() == this.level()) {
            away = new Vec3(this.getX() - bear.getX(), 0, this.getZ() - bear.getZ());
        } else {
            double ang = this.random.nextDouble() * Math.PI * 2;
            away = new Vec3(Math.cos(ang), 0, Math.sin(ang));
        }
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
        away = away.normalize();
        fleeTo = new Vec3(this.getX() + away.x * 90, Math.max(this.getY(), owner.getY()) + 18, this.getZ() + away.z * 90);
        level().playSound(null, this.getX(), this.getY(), this.getZ(), net.minecraft.sounds.SoundEvents.PARROT_FLY, SoundSource.AMBIENT, 0.8F, 1.4F);
    }

    /** it leaves in a straight line, high, and is gone once it is well out of sight */
    private void tickFlee(ServerPlayer owner) {
        fleeTicks++;
        if (fleeTo != null) flyAt(fleeTo.x, fleeTo.y, fleeTo.z, 2.2);
        if (fleeTicks > 600 || this.distanceTo(owner) > 58) this.discard();
    }
}
