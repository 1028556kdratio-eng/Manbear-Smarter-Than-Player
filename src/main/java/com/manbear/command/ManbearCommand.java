package com.manbear.command;

import com.manbear.data.ManbearData;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import com.manbear.event.LightningEvent;
import com.manbear.event.ServerEvents;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/** Operator tools for testing: /manbear spawn [chaser|faker|trapper|sleeper|behind|lurer|path|fog|cave|lurker|petnapper|lightning|intimidation|pitlure|kidnapper] | bird | rage [set <0-100>] | raid [here] | remove | mode <mode> | info | profile | reset */
public class ManbearCommand {
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("manbear").requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn").executes(c -> spawn(c.getSource(), null))
                        .then(Commands.literal("chaser").executes(c -> spawn(c.getSource(), "chaser")))
                        .then(Commands.literal("faker").executes(c -> spawn(c.getSource(), "faker")))
                        .then(Commands.literal("trapper").executes(c -> spawn(c.getSource(), "trapper")))
                        .then(Commands.literal("sleeper").executes(c -> spawn(c.getSource(), "sleeper")))
                        .then(Commands.literal("behind").executes(c -> spawn(c.getSource(), "behind")))
                        .then(Commands.literal("lurer").executes(c -> spawn(c.getSource(), "lurer")))
                        .then(Commands.literal("path").executes(c -> spawn(c.getSource(), "path")))
                        .then(Commands.literal("fog").executes(c -> spawn(c.getSource(), "fog")))
                        .then(Commands.literal("cave").executes(c -> spawn(c.getSource(), "cave")))
                        .then(Commands.literal("lurker").executes(c -> spawn(c.getSource(), "lurker")))
                        .then(Commands.literal("petnapper").executes(c -> spawn(c.getSource(), "petnapper")))
                        .then(Commands.literal("lightning").executes(c -> spawn(c.getSource(), "lightning")))
                        .then(Commands.literal("intimidation").executes(c -> spawn(c.getSource(), "intimidation")))
                        .then(Commands.literal("pitlure").executes(c -> spawn(c.getSource(), "pitlure")))
                        .then(Commands.literal("kidnapper").executes(c -> spawn(c.getSource(), "kidnapper")))
                        .then(Commands.literal("hallucination").executes(c -> spawn(c.getSource(), "hallucination"))))
                .then(Commands.literal("bird").executes(c -> bird(c.getSource())))
                .then(Commands.literal("raid").executes(c -> raid(c.getSource(), false))
                        .then(Commands.literal("here").executes(c -> raid(c.getSource(), true))))
                .then(Commands.literal("rage").executes(c -> rage(c.getSource(), null))
                        .then(Commands.literal("set").then(Commands.argument("value", FloatArgumentType.floatArg(0F, 100F))
                                .executes(c -> rage(c.getSource(), FloatArgumentType.getFloat(c, "value"))))))
                .then(Commands.literal("remove").executes(c -> remove(c.getSource())))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("profile").executes(c -> profile(c.getSource())))
                .then(Commands.literal("reset").executes(c -> reset(c.getSource())))
                .then(Commands.literal("scratch").executes(c -> scratch(c.getSource())))
                .then(Commands.literal("evidence")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.of("footprints", "trap", "animals", "leaves", "pit"), b))
                                .executes(c -> evidence(c.getSource(), StringArgumentType.getString(c, "kind")))))
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(ManbearMode.values()).map(m -> m.name().toLowerCase()), b))
                                .executes(c -> mode(c.getSource(), StringArgumentType.getString(c, "mode"))))));
    }

    private static int spawn(CommandSourceStack src, String kind) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            MinecraftServer server = src.getServer();
            if ("lightning".equals(kind)) {
                boolean ok = LightningEvent.strike(server, p.serverLevel(), p);
                if (ok) src.sendSuccess(() -> Component.literal("Lightning!"), true);
                else src.sendFailure(Component.literal("No clear spot in front of you."));
                return ok ? 1 : 0;
            }
            if (ServerEvents.findActive(server) != null) {
                src.sendFailure(Component.literal("A Manbear already exists in this world. Use /manbear remove first."));
                return 0;
            }
            ManbearData.get(server).clearAll();
            ManbearEntity e = ServerEvents.spawnNear(server, p, kind);
            if (e == null) {
                src.sendFailure(Component.literal("Could not find a hidden spot nearby. Try again."));
                return 0;
            }
            src.sendSuccess(() -> Component.literal("The Manbear is out there." + (kind == null ? "" : " (" + kind + ")")), true);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
    }

    /** (re)creates your companion bird right next to you */
    private static int bird(CommandSourceStack src) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            ManbearData data = ManbearData.get(src.getServer());
            java.util.UUID old = data.birdOf(p.getUUID());
            if (old != null && p.serverLevel().getEntity(old) != null) p.serverLevel().getEntity(old).discard();
            boolean ok = com.manbear.event.CompanionBirds.spawn(p, data, false);
            src.sendSuccess(() -> Component.literal(ok ? "Your bird is here." : "No room for a bird here."), false);
            return ok ? 1 : 0;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
    }

    private static int rage(CommandSourceStack src, Float set) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            com.manbear.data.PlayerProfile prof = ManbearData.get(src.getServer()).profile(p.getUUID());
            if (set != null) {
                prof.rage = set;
                ManbearData.get(src.getServer()).setDirty();
            }
            String msg = String.format("His rage towards you: %.1f / 100", prof.rage);
            src.sendSuccess(() -> Component.literal(msg), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
    }

    private static int raid(CommandSourceStack src, boolean here) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            ServerLevel level = p.serverLevel();
            net.minecraft.core.BlockPos center = p.blockPosition();
            if (!here) {
                net.minecraft.core.BlockPos base = ManbearData.get(src.getServer()).profile(p.getUUID()).base;
                if (base != null) center = base;
            }
            String r = com.manbear.raid.RaidHandler.rummage(level, center, level.random, p.getUUID());
            String msg = r == null ? "No house found there (needs a door or chest within 18 blocks)." : "Rummaged: " + r;
            src.sendSuccess(() -> Component.literal(msg), true);
            return r == null ? 0 : 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
    }

    private static int remove(CommandSourceStack src) {
        ManbearEntity e = ServerEvents.findActive(src.getServer());
        if (e != null) { e.ai().suppressEvidence(); e.discard(); }
        ManbearData data = ManbearData.get(src.getServer());
        data.clearAll();
        src.sendSuccess(() -> Component.literal("Manbear removed."), true);
        return 1;
    }

    /** scratches your current rage onto a tree near you right now */
    private static int scratch(CommandSourceStack src) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            boolean ok = com.manbear.event.RageScratch.scratch(p.serverLevel(), p, true);
            src.sendSuccess(() -> Component.literal(ok ? "Scratched your rage onto a tree." : "No suitable tree trunk within range."), false);
            return ok ? 1 : 0;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
    }

    /** places one kind of evidence 5 blocks in front of you (needs a Manbear to exist, the traps are his) */
    private static int evidence(CommandSourceStack src, String kind) {
        ManbearEntity e = ServerEvents.findActive(src.getServer());
        if (e == null) { src.sendFailure(Component.literal("No Manbear is loaded: /manbear spawn first.")); return 0; }
        try {
            ServerPlayer p = src.getPlayerOrException();
            net.minecraft.world.phys.Vec3 look = p.getLookAngle();
            net.minecraft.world.phys.Vec3 flat = new net.minecraft.world.phys.Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1.0E-4 ? new net.minecraft.world.phys.Vec3(0, 0, 1) : flat.normalize();
            net.minecraft.world.phys.Vec3 at = p.position().add(flat.scale(5));
            boolean ok = e.ai().debugEvidence(p.serverLevel(), kind, at, flat.scale(-1));
            src.sendSuccess(() -> Component.literal(ok ? "Evidence placed: " + kind : "Could not place '" + kind + "' there (unknown kind, or unsuitable ground)."), false);
            return ok ? 1 : 0;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
    }

    private static int mode(CommandSourceStack src, String name) {
        ManbearEntity e = ServerEvents.findActive(src.getServer());
        if (e == null) { src.sendFailure(Component.literal("No Manbear is loaded.")); return 0; }
        for (ManbearMode m : ManbearMode.values()) {
            if (m.name().equalsIgnoreCase(name)) {
                e.ai().forceMode((ServerLevel) e.level(), m);
                src.sendSuccess(() -> Component.literal("Manbear mode -> " + m), true);
                return 1;
            }
        }
        src.sendFailure(Component.literal("Unknown mode."));
        return 0;
    }

    private static int info(CommandSourceStack src) {
        ManbearEntity e = ServerEvents.findActive(src.getServer());
        if (e == null) { src.sendFailure(Component.literal("No Manbear is loaded.")); return 0; }
        String s = e.ai().debugString() + String.format(" pos=%.0f,%.0f,%.0f", e.getX(), e.getY(), e.getZ());
        src.sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static int profile(CommandSourceStack src) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            String s = ManbearData.get(src.getServer()).profile(p.getUUID()).describe();
            src.sendSuccess(() -> Component.literal("What he knows about you: " + s), false);
            return 1;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static int reset(CommandSourceStack src) {
        ManbearData.get(src.getServer()).resetProfiles();
        src.sendSuccess(() -> Component.literal("Manbear forgot everything."), true);
        return 1;
    }
}
