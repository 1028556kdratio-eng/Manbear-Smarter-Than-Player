package com.manbear.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: build a collage from this player's locally recorded mic fragments at this spot. */
public class VoiceMimicPacket {
    private final double x;
    private final double y;
    private final double z;

    public VoiceMimicPacket(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static void encode(VoiceMimicPacket m, FriendlyByteBuf buf) {
        buf.writeDouble(m.x);
        buf.writeDouble(m.y);
        buf.writeDouble(m.z);
    }

    public static VoiceMimicPacket decode(FriendlyByteBuf buf) {
        return new VoiceMimicPacket(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(VoiceMimicPacket m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.manbear.client.VoicePlayer.play(m.x, m.y, m.z)));
        c.setPacketHandled(true);
    }
}
