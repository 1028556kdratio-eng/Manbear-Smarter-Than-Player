package com.manbear.block;

import com.manbear.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** remembers the number clawed into the log (0-100); the client draws it */
public class RageScratchBlockEntity extends BlockEntity {
    private int value;

    public RageScratchBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RAGE_SCRATCH.get(), pos, state);
    }

    public int value() { return value; }

    public void setValue(int v) {
        this.value = v;
        this.setChanged();
        if (this.level != null) this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Value", value);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        value = tag.getInt("Value");
    }

    // sent to players when the chunk loads and when the number is set
    @Override
    public CompoundTag getUpdateTag() { return this.saveWithoutMetadata(); }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
