package com.manbear.net;

import com.manbear.event.ServerEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: the speech recognizer heard the player say "man bear" or "bear". */
public class SaidNamePacket {
    public SaidNamePacket() { }

    public static void encode(SaidNamePacket msg, FriendlyByteBuf buf) { }

    public static SaidNamePacket decode(FriendlyByteBuf buf) { return new SaidNamePacket(); }

    public static void handle(SaidNamePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer sender = c.getSender();
            if (sender != null) ServerEvents.nameHeard(sender);
        });
        c.setPacketHandled(true);
    }
}
