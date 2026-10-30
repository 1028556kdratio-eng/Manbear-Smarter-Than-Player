package com.manbear.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> target client: play a locally assembled positional phrase from the Manbear's location. */
public final class PositionVoicePacket {
    private final double x;
    private final double y;
    private final double z;
    private final int phraseCode;

    public PositionVoicePacket(double x, double y, double z, int phraseCode) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.phraseCode = phraseCode;
    }

    public static void encode(PositionVoicePacket message, FriendlyByteBuf buffer) {
        buffer.writeDouble(message.x);
        buffer.writeDouble(message.y);
        buffer.writeDouble(message.z);
        buffer.writeVarInt(message.phraseCode);
    }

    public static PositionVoicePacket decode(FriendlyByteBuf buffer) {
        return new PositionVoicePacket(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readVarInt());
    }

    public static void handle(PositionVoicePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            int code = message.phraseCode;
            if (code >= 0 && code <= 5) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.manbear.client.VoicePlayer.playDirectional(message.x, message.y, message.z, code));
            }
        });
        context.setPacketHandled(true);
    }
}
