package com.manbear.client;

import com.manbear.ManbearMod;
import com.manbear.ManbearConfig;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import com.manbear.net.ManbearNet;
import com.manbear.net.StrugglePacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import com.manbear.ManbearClientConfig;
import com.manbear.net.VoiceClipPacket;
import com.manbear.net.ScreamEventPacket;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Reports space-bar presses so you can mash your way out of his grip. */
@Mod.EventBusSubscriber(modid = ManbearMod.MODID, value = Dist.CLIENT)
public class ClientEvents {
    private static boolean notice;
    private static int torchPollTicks;
    private static int torchFlickerTicks;
    private static int torchParticleTicks;
    private static final java.util.List<BlockPos> FLICKERING_TORCHES = new java.util.ArrayList<>();
    private static final java.util.Random TORCH_RANDOM = new java.util.Random();

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        MicSampler.start();
        SpeechWatcher.start();
        notice = MicSampler.isRunning();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MicSampler.stop();
        SpeechWatcher.stop();
        BlackoutOverlay.stop();
        AnimClips.reload();
        notice = false;
        torchPollTicks = torchFlickerTicks = torchParticleTicks = 0;
        FLICKERING_TORCHES.clear();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        VoicePlayer.tick();
        BlackoutOverlay.tick();
        if (ManbearClientConfig.SHARE_VOICE.get() && Minecraft.getInstance().getConnection() != null) {
            byte[] ulaw = MicSampler.pollUpload();
            if (ulaw != null) sendShared(ulaw);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null && ManbearClientConfig.SCREAM_DETECTION.get() && MicSampler.pollScreamEvent()) {
            ManbearNet.CHANNEL.sendToServer(new ScreamEventPacket());
        }
        tickTorchFlicker(mc);
        if (notice && mc.player != null) {
            notice = false;
            mc.player.displayClientMessage(Component.literal(
                    "[Manbear] Microphone sampling is enabled: short snippets are kept in RAM on this PC and rearranged into new voice collages. "
                    + "Audio files are not saved. Sharing voice with the multiplayer server is "
                    + (ManbearClientConfig.SHARE_VOICE.get() ? "ON" : "OFF")
                    + " (OFF by default in a fresh config); change with /manbearvoice share on|off. "
                    + "Turn microphone sampling off with /manbearvoice sampling off."
                    + " On Windows, selected words may be kept as local RAM-only samples for directional callouts (toggle with /manbearvoice directions off). "
                    + "Optional loud-vocal detection is " + (ManbearClientConfig.SCREAM_DETECTION.get() ? "ON" : "OFF")
                    + " (toggle with /manbearvoice screams on|off; it sends only an event count, never audio). "
                    + "Saying \"man bear\" or \"bear\" can also trigger an event; set speechRecognition=false to disable Windows speech recognition."), false);
        }
    }

    /**
     * Client-side torch flicker cue. Vanilla torches have fixed block light, so this leaves world/block state alone
     * and makes the flame itself look unstable with brief flame/smoke bursts while the Manbear is stalking nearby.
     */
    private static void tickTorchFlicker(Minecraft mc) {
        if (!ManbearConfig.TORCH_FLICKER_ENABLED.get() || mc.player == null || mc.level == null || mc.player.isSpectator()) {
            torchFlickerTicks = 0;
            FLICKERING_TORCHES.clear();
            return;
        }
        if (torchFlickerTicks > 0) {
            torchFlickerTicks--;
            if (++torchParticleTicks % 2 != 0) return;
            ItemStack offhand = mc.player.getOffhandItem();
            if (isTorchItem(offhand)) {
                float yaw = mc.player.getYRot() * ((float) Math.PI / 180F);
                double leftX = -Math.cos(yaw);
                double leftZ = -Math.sin(yaw);
                double x = mc.player.getX() + leftX * 0.38;
                double y = mc.player.getY() + 1.15;
                double z = mc.player.getZ() + leftZ * 0.38;
                mc.level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.015, 0.0);
                if (TORCH_RANDOM.nextFloat() < 0.3F) mc.level.addParticle(ParticleTypes.SMOKE, x, y + 0.08, z, 0.0, 0.025, 0.0);
            }
            for (int i = 0; i < Math.min(3, FLICKERING_TORCHES.size()); i++) {
                if (FLICKERING_TORCHES.isEmpty()) break;
                BlockPos pos = FLICKERING_TORCHES.get(TORCH_RANDOM.nextInt(FLICKERING_TORCHES.size()));
                if (!isTorchBlock(mc.level.getBlockState(pos).getBlock())) continue;
                double x = pos.getX() + 0.5 + (TORCH_RANDOM.nextDouble() - 0.5) * 0.12;
                double y = pos.getY() + 0.68;
                double z = pos.getZ() + 0.5 + (TORCH_RANDOM.nextDouble() - 0.5) * 0.12;
                mc.level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.012, 0.0);
                if (TORCH_RANDOM.nextFloat() < 0.2F) mc.level.addParticle(ParticleTypes.SMOKE, x, y + 0.05, z, 0.0, 0.018, 0.0);
            }
            return;
        }

        if (++torchPollTicks < 100) return; // evaluate at most every five seconds
        torchPollTicks = 0;
        if (TORCH_RANDOM.nextDouble() >= ManbearConfig.TORCH_FLICKER_CHANCE.get()) return;
        boolean nearbyStalker = false;
        for (ManbearEntity bear : mc.level.getEntitiesOfClass(ManbearEntity.class, mc.player.getBoundingBox().inflate(40.0))) {
            ManbearMode mode = bear.getMode();
            if ((mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK)
                    && bear.distanceToSqr(mc.player) <= 40.0 * 40.0) {
                nearbyStalker = true;
                break;
            }
        }
        if (!nearbyStalker) return;

        FLICKERING_TORCHES.clear();
        BlockPos origin = mc.player.blockPosition();
        // Bounded local scan, once per few seconds; break early when enough torches are found.
        search:
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunkAt(pos)) continue;
                    if (isTorchBlock(mc.level.getBlockState(pos).getBlock())) FLICKERING_TORCHES.add(pos.immutable());
                    if (FLICKERING_TORCHES.size() >= 8) break search;
                }
            }
        }
        boolean heldTorch = isTorchItem(mc.player.getOffhandItem());
        if (FLICKERING_TORCHES.isEmpty() && !heldTorch) return;
        torchFlickerTicks = 35 + TORCH_RANDOM.nextInt(46); // 1.75–4 seconds of flickering
        torchParticleTicks = 0;
        if (!FLICKERING_TORCHES.isEmpty()) {
            BlockPos soundAt = FLICKERING_TORCHES.get(TORCH_RANDOM.nextInt(FLICKERING_TORCHES.size()));
            mc.level.playLocalSound(soundAt.getX() + 0.5, soundAt.getY() + 0.5, soundAt.getZ() + 0.5,
                    SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.18F, 1.25F + TORCH_RANDOM.nextFloat() * 0.35F, false);
        }
    }

    private static boolean isTorchItem(ItemStack stack) {
        return stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH) || stack.is(Items.REDSTONE_TORCH);
    }

    private static boolean isTorchBlock(net.minecraft.world.level.block.Block block) {
        return block == Blocks.TORCH || block == Blocks.WALL_TORCH
                || block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH
                || block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH;
    }

    private static void sendShared(byte[] ulaw) {
        long id = System.nanoTime();
        int chunk = 20000;
        int total = (ulaw.length + chunk - 1) / chunk;
        if (total > 4) return;
        for (int i = 0; i < total; i++) {
            int from = i * chunk;
            int to = Math.min(ulaw.length, from + chunk);
            byte[] part = java.util.Arrays.copyOfRange(ulaw, from, to);
            ManbearNet.CHANNEL.sendToServer(new VoiceClipPacket(id, i, total, part));
        }
    }

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(FogHandler.command());
        event.getDispatcher().register(Commands.literal("manbearvoice")
                .then(Commands.literal("sampling")
                        .then(Commands.literal("on").executes(c -> setSampling(c, true)))
                        .then(Commands.literal("off").executes(c -> setSampling(c, false))))
                .then(Commands.literal("share")
                        .then(Commands.literal("on").executes(c -> setShare(c, true)))
                        .then(Commands.literal("off").executes(c -> setShare(c, false))))
                .then(Commands.literal("directions")
                        .then(Commands.literal("on").executes(c -> setDirectionalWords(c, true)))
                        .then(Commands.literal("off").executes(c -> setDirectionalWords(c, false))))
                .then(Commands.literal("screams")
                        .then(Commands.literal("on").executes(c -> setScreamDetection(c, true)))
                        .then(Commands.literal("off").executes(c -> setScreamDetection(c, false))))
                .then(Commands.literal("status").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("Manbear voice: microphone sampling "
                            + (ManbearClientConfig.MIC_SAMPLING.get() ? "ON" : "OFF") + " (" + MicSampler.fragmentCount()
                            + " fragments from " + MicSampler.phraseCount() + " separate sayings in RAM), positional words "
                            + (ManbearClientConfig.POSITIONAL_VOICE_WORDS.get() ? "ON" : "OFF") + " (" + MicSampler.wordSampleCount()
                            + " local word samples across " + MicSampler.recognizedWordCount() + " cue words), loud-vocal detection "
                            + (ManbearClientConfig.SCREAM_DETECTION.get() ? "ON" : "OFF") + " (" + MicSampler.localScreamCount()
                            + " local events counted), server voice sharing "
                            + (ManbearClientConfig.SHARE_VOICE.get() ? "ON" : "OFF")), false);
                    return 1;
                })));
    }


    private static int setSampling(CommandContext<CommandSourceStack> c, boolean on) {
        ManbearClientConfig.MIC_SAMPLING.set(on);
        try { ManbearClientConfig.MIC_SAMPLING.save(); } catch (Exception ignored) { }
        if (on) MicSampler.start(); else MicSampler.stop();
        c.getSource().sendSuccess(() -> Component.literal(on
                ? "Microphone sampling ON. Snippets are kept from separate sayings; an echo combines snippets from at least two sayings."
                : "Microphone sampling OFF. The local fragment buffer has been cleared."), false);
        return 1;
    }

    private static int setDirectionalWords(CommandContext<CommandSourceStack> c, boolean on) {
        ManbearClientConfig.POSITIONAL_VOICE_WORDS.set(on);
        if (!on) MicSampler.clearDirectionalWords();
        try { ManbearClientConfig.POSITIONAL_VOICE_WORDS.save(); } catch (Exception ignored) { }
        c.getSource().sendSuccess(() -> Component.literal(on
                ? "Directional word capture ON. On Windows, selected recognized words are kept as short audio snippets in RAM on this PC only. Requires speechRecognition and microphone sampling."
                : "Directional word capture OFF. Local word-audio samples have been cleared."), false);
        return 1;
    }

    private static int setScreamDetection(CommandContext<CommandSourceStack> c, boolean on) {
        if (on && !ManbearClientConfig.MIC_SAMPLING.get()) {
            c.getSource().sendFailure(Component.literal("Enable microphone sampling first with /manbearvoice sampling on. No audio is uploaded by loud-vocal detection."));
            return 0;
        }
        ManbearClientConfig.SCREAM_DETECTION.set(on);
        try { ManbearClientConfig.SCREAM_DETECTION.save(); } catch (Exception ignored) { }
        c.getSource().sendSuccess(() -> Component.literal(on
                ? "Loud-vocal detection ON. Sustained high-volume bursts may count as scream-like events; no separate audio is saved or uploaded. You do not need to shout to use the mod."
                : "Loud-vocal detection OFF."), false);
        return 1;
    }

    private static int setShare(CommandContext<CommandSourceStack> c, boolean on) {
        ManbearClientConfig.SHARE_VOICE.set(on);
        if (!on) MicSampler.clearPendingUploads();
        try { ManbearClientConfig.SHARE_VOICE.save(); } catch (Exception ignored) { }
        c.getSource().sendSuccess(() -> Component.literal(on
                ? "Sharing ON: short microphone recordings will be sent to this server and kept in server RAM so the Manbear can mimic your voice to other players. Only enable this if you consent to sharing your voice here."
                : "Sharing OFF: microphone audio stays on this PC."), false);
        return 1;
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (event.getAction() == 1 /* GLFW_PRESS */ && mc.options.keyJump.matches(event.getKey(), event.getScanCode())) {
            ManbearNet.CHANNEL.sendToServer(new StrugglePacket());
        }
    }
}
