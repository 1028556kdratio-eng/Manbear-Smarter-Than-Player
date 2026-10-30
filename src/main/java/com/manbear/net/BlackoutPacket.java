package com.manbear.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: fade the whole screen to black, hold it, fade back in (all in ticks). Used by the kidnapping. */
public class BlackoutPacket {
    private final int fadeIn;
    private final int hold;
    private final int fadeOut;

    public BlackoutPacket(int fadeIn, int hold, int fadeOut) {
        this.fadeIn = fadeIn;
        this.hold = hold;
        this.fadeOut = fadeOut;
    }

    public static void encode(BlackoutPacket m, FriendlyByteBuf buf) {
        buf.writeVarInt(m.fadeIn);
        buf.writeVarInt(m.hold);
        buf.writeVarInt(m.fadeOut);
    }

    public static BlackoutPacket decode(FriendlyByteBuf buf) {
        return new BlackoutPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(BlackoutPacket m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.manbear.client.BlackoutOverlay.start(m.fadeIn, m.hold, m.fadeOut)));
        c.setPacketHandled(true);
    }
}
