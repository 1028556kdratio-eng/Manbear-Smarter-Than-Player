package com.manbear.entity;

import com.manbear.ManbearConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A dead animal the Manbear left behind. It is a marker for the client-side lying animal renderer.
 * Permanent den corpses are still removable: striking one drops rotten flesh, while removing its support makes it vanish.
 */
public class CorpseEntity extends Entity {
    private static final EntityDataAccessor<String> DATA_KIND = SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);

    private int age;
    private boolean permanent;

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(false);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_KIND, "minecraft:cow");
    }

    /** registry name of the animal, e.g. "minecraft:pig" */
    public String kind() { return this.entityData.get(DATA_KIND); }
    public void setKind(String id) { this.entityData.set(DATA_KIND, id); }
    public void setPermanent(boolean permanent) { this.permanent = permanent; }
    public boolean isPermanent() { return permanent; }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;
        if (!permanent && ++age > ManbearConfig.CORPSE_MINUTES.get() * 1200) {
            this.discard();
            return;
        }
        // The corpse is held just above its support block. If the support is mined or replaced with air,
        // remove the marker as well so den remains do not hover in mid-air forever.
        if (this.tickCount % 5 == 0) {
            BlockPos feet = this.blockPosition();
            BlockPos support = feet.below();
            if (this.level().hasChunkAt(support)) {
                var state = this.level().getBlockState(support);
                if (state.isAir() || state.canBeReplaced() || state.getCollisionShape(this.level(), support).isEmpty()
                        || !state.isFaceSturdy(this.level(), support, Direction.UP)) {
                    this.discard();
                }
            }
        }
    }

    @Override
    public boolean isPickable() { return true; }

    @Override
    public boolean isAttackable() { return true; }

    @Override
    public boolean canBeHitByProjectile() { return true; }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) return true;
        this.spawnAtLocation(Items.ROTTEN_FLESH, 1);
        this.discard();
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Kind")) setKind(tag.getString("Kind"));
        age = tag.getInt("Age");
        permanent = tag.getBoolean("Permanent");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("Kind", kind());
        tag.putInt("Age", age);
        tag.putBoolean("Permanent", permanent);
    }
}
