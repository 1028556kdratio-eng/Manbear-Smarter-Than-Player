package com.manbear.ai;

import com.manbear.entity.ManbearEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Like vanilla, but when the Manbear is "facing" something (always the player) the body keeps
 * pointing at it and movement is converted into forward/sideways input. That is what lets him
 * circle you, back away and side-step while staring straight at you.
 * Input is always a unit vector, so speed = attribute * speedModifier exactly like a player
 * (attribute 0.1 * modifier 1.0 = player walking speed, 1.3 = sprinting).
 */
public class ManbearMoveControl extends MoveControl {
    private final ManbearEntity bear;

    public ManbearMoveControl(ManbearEntity bear) {
        super(bear);
        this.bear = bear;
    }

    @Override
    public void tick() {
        float speed = (float) (this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED));

        if (this.operation == Operation.MOVE_TO) {
            this.operation = Operation.WAIT;
            double dx = this.wantedX - this.mob.getX();
            double dz = this.wantedZ - this.mob.getZ();
            double dy = this.wantedY - this.mob.getY();
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr < 2.5000003E-7D) {
                this.mob.setZza(0.0F);
                this.mob.setXxa(0.0F);
                return;
            }
            this.mob.setSpeed(speed);
            double hLen = Math.sqrt(dx * dx + dz * dz);

            if (this.bear.isFacingMode() && hLen > 1.0E-4D) {
                float yawRad = this.mob.getYRot() * ((float) Math.PI / 180F);
                float sin = Mth.sin(yawRad);
                float cos = Mth.cos(yawRad);
                double nx = dx / hLen;
                double nz = dz / hLen;
                this.mob.setZza((float) (-nx * sin + nz * cos));
                this.mob.setXxa((float) (nx * cos + nz * sin));
            } else {
                float targetYaw = (float) (Mth.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
                this.mob.setYRot(this.rotlerp(this.mob.getYRot(), targetYaw, 90.0F));
                this.mob.setZza(1.0F);
                this.mob.setXxa(0.0F);
            }

            BlockPos pos = this.mob.blockPosition();
            BlockState state = this.mob.level().getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(this.mob.level(), pos);
            if ((dy > (double) this.mob.maxUpStep() && dx * dx + dz * dz < (double) Math.max(1.0F, this.mob.getBbWidth()))
                    || (!shape.isEmpty() && this.mob.getY() < shape.max(Direction.Axis.Y) + (double) pos.getY()
                    && !state.is(BlockTags.DOORS) && !state.is(BlockTags.FENCES))) {
                this.mob.getJumpControl().jump();
                this.operation = Operation.JUMPING;
            }
        } else if (this.operation == Operation.JUMPING) {
            this.mob.setSpeed(speed);
            if (this.mob.onGround()) {
                this.operation = Operation.WAIT;
            }
        } else {
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
        }
    }
}
