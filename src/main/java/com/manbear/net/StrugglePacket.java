package com.manbear.net;

import com.manbear.entity.ManbearEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Sent by the client every time the jump key is pressed. The server ignores it unless you are being held. */
public class StrugglePacket {
    public StrugglePacket() { }

    public static void encode(StrugglePacket msg, FriendlyByteBuf buf) { }

    public static StrugglePacket decode(FriendlyByteBuf buf) { return new StrugglePacket(); }

    public static void handle(StrugglePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer sender = c.getSender();
            if (sender != null) ManbearEntity.onStruggle(sender);
        });
        c.setPacketHandled(true);
    }
}
