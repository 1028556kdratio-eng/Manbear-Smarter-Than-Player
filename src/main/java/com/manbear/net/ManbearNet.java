package com.manbear.net;

import com.manbear.ManbearMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ManbearNet {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ManbearMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, StrugglePacket.class, StrugglePacket::encode, StrugglePacket::decode, StrugglePacket::handle);
        CHANNEL.registerMessage(1, VoiceMimicPacket.class, VoiceMimicPacket::encode, VoiceMimicPacket::decode, VoiceMimicPacket::handle);
        CHANNEL.registerMessage(2, VoiceClipPacket.class, VoiceClipPacket::encode, VoiceClipPacket::decode, VoiceClipPacket::handle);
        CHANNEL.registerMessage(3, RemoteVoicePacket.class, RemoteVoicePacket::encode, RemoteVoicePacket::decode, RemoteVoicePacket::handle);
        CHANNEL.registerMessage(4, SaidNamePacket.class, SaidNamePacket::encode, SaidNamePacket::decode, SaidNamePacket::handle);
        CHANNEL.registerMessage(5, BlackoutPacket.class, BlackoutPacket::encode, BlackoutPacket::decode, BlackoutPacket::handle);
        CHANNEL.registerMessage(6, PositionVoicePacket.class, PositionVoicePacket::encode, PositionVoicePacket::decode, PositionVoicePacket::handle);
        CHANNEL.registerMessage(7, ScreamEventPacket.class, ScreamEventPacket::encode, ScreamEventPacket::decode, ScreamEventPacket::handle);
    }
}
