package com.manbear.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: play ANOTHER player's shared phrase from this spot (he is mimicking your friend). */
public class RemoteVoicePacket {
    private final double x;
    private final double y;
    private final double z;
    private final byte[] data;

    public RemoteVoicePacket(double x, double y, double z, byte[] data) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.data = data;
    }

    public static void encode(RemoteVoicePacket m, FriendlyByteBuf buf) {
        buf.writeDouble(m.x);
        buf.writeDouble(m.y);
        buf.writeDouble(m.z);
        buf.writeByteArray(m.data);
    }

    public static RemoteVoicePacket decode(FriendlyByteBuf buf) {
        return new RemoteVoicePacket(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readByteArray(120000));
    }

    public static void handle(RemoteVoicePacket m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.manbear.client.VoicePlayer.playShared(m.x, m.y, m.z, m.data)));
        c.setPacketHandled(true);
    }
}
