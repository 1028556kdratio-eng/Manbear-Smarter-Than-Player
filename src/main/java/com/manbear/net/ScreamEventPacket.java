package com.manbear.net;

import com.manbear.event.ServerEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: reports one local loud-vocal event. No audio or transcript is transmitted. */
public final class ScreamEventPacket {
    public static void encode(ScreamEventPacket message, FriendlyByteBuf buffer) { }

    public static ScreamEventPacket decode(FriendlyByteBuf buffer) { return new ScreamEventPacket(); }

    public static void handle(ScreamEventPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) ServerEvents.screamHeard(sender);
        });
        context.setPacketHandled(true);
    }
}
