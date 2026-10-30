package com.manbear.net;

import com.manbear.ManbearConfig;
import com.manbear.voice.VoiceBank;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: one piece of a phrase the player has chosen to share. */
public class VoiceClipPacket {
    private final long clipId;
    private final int index;
    private final int total;
    private final byte[] data;

    public VoiceClipPacket(long clipId, int index, int total, byte[] data) {
        this.clipId = clipId;
        this.index = index;
        this.total = total;
        this.data = data;
    }

    public static void encode(VoiceClipPacket m, FriendlyByteBuf buf) {
        buf.writeLong(m.clipId);
        buf.writeVarInt(m.index);
        buf.writeVarInt(m.total);
        buf.writeByteArray(m.data);
    }

    public static VoiceClipPacket decode(FriendlyByteBuf buf) {
        return new VoiceClipPacket(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(20000));
    }

    public static void handle(VoiceClipPacket m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer sender = c.getSender();
            if (sender != null && ManbearConfig.VOICE_SHARING.get()) {
                VoiceBank.addChunk(sender.getUUID(), m.clipId, m.index, m.total, m.data);
            }
        });
        c.setPacketHandled(true);
    }
}
