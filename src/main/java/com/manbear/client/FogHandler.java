package com.manbear.client;

import com.manbear.ManbearClientConfig;
import com.manbear.ManbearMod;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Atmospheric fog, entirely on your own computer.
 *
 * The distances are worked out from YOUR render distance, so the same settings look right at 6 chunks or at 32. How thick it
 * is depends on the time of day (morning mist), the weather, the biome and how high you are, and fades out as you go
 * underground. Everything can be tuned in manbear-client.toml or live with /manbearfog.
 *
 * It is applied through Forge's fog events (not by replacing the fog renderer), so it feeds the same fog values that shader
 * mods such as Iris and Oculus read. Shader packs that draw their own fog on top of that can be compensated with
 * shaderMultiplier.
 */
@Mod.EventBusSubscriber(modid = ManbearMod.MODID, value = Dist.CLIENT)
public final class FogHandler {
    /** smoothed density multiplier (1 = the default thickness) */
    private static float density = 1F;
    /** 0 = no fog of ours (underground, other dimension), 1 = fully applied */
    private static float exposure = 0F;
    private static boolean shaders;
    private static Object lastLevel;
    private static float particleSpawnBudget = 0F;
    /** Extra spawn clearance so the visible edge of the largest wisp stays outside the configured radius. */
    public static final double PARTICLE_VISUAL_MARGIN = 5.2D;

    private static String parsedColor = "";
    private static final float[] RGB = {0.71F, 0.75F, 0.78F};

    private FogHandler() { }

    // ------------------------------------------------------------------ once per tick: work out how thick it should be
    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;

        float targetExposure = 0F;
        float targetDensity = 1F;
        if (ManbearClientConfig.FOG_ENABLED.get() && level != null && p != null) {
            boolean overworld = level.dimension() == Level.OVERWORLD;
            if (overworld || ManbearClientConfig.FOG_OTHER_DIMENSIONS.get()) {
                if (p.tickCount % 100 == 0 || lastLevel != level) shaders = detectShaders();
                if (overworld) {
                    int sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(p.getEyePosition()));
                    targetExposure = Mth.clamp((sky - 2) / 11F, 0F, 1F);        // caves and bases: no fog of ours
                } else {
                    targetExposure = 1F;
                }
                targetDensity = targetDensity(level, p, overworld);
            }
        }
        if (lastLevel != level) {           // new world / dimension: no slow fade from the old values
            lastLevel = level;
            exposure = targetExposure;
            density = targetDensity;
            particleSpawnBudget = 0F;
            tickFogParticles(level, p);
            return;
        }
        exposure += (targetExposure - exposure) * 0.05F;
        density += (targetDensity - density) * 0.03F;
        tickFogParticles(level, p);
    }

    private static float targetDensity(ClientLevel level, LocalPlayer p, boolean overworld) {
        double f = ManbearClientConfig.FOG_THICKNESS.get();

        if (overworld && ManbearClientConfig.FOG_TIME_OF_DAY.get()) {
            long t = level.getDayTime() % 24000L;
            f *= 1.0 + 1.1 * bump(t, 0, 1800)           // sunrise: the morning mist
                    + 0.4 * bump(t, 12600, 1200)         // dusk
                    + 0.25 * bump(t, 18000, 3500);       // the dead of night
        }
        if (ManbearClientConfig.FOG_WEATHER.get()) {
            f *= 1.0 + 0.6 * level.getRainLevel(1F) + 0.3 * level.getThunderLevel(1F);
        }
        if (overworld && ManbearClientConfig.FOG_BIOMES.get()) {
            Holder<Biome> b = level.getBiome(p.blockPosition());
            double k = 1.0;
            if (b.is(Biomes.SWAMP) || b.is(Biomes.MANGROVE_SWAMP)) k += 0.8;
            else if (b.is(Biomes.DARK_FOREST)) k += 0.4;
            else if (b.is(BiomeTags.IS_TAIGA)) k += 0.25;
            else if (b.is(BiomeTags.IS_RIVER)) k += 0.3;
            else if (b.is(BiomeTags.IS_FOREST)) k += 0.15;
            if (b.is(Biomes.DESERT) || b.is(BiomeTags.IS_BADLANDS)) k -= 0.5;
            f *= Math.max(0.4, k);
        }
        if (overworld && ManbearClientConfig.FOG_ALTITUDE.get()) {
            f *= Mth.clamp(1.0 - (p.getY() - 70.0) / 110.0, 0.3, 1.0);
        }
        if (shaders) f *= ManbearClientConfig.FOG_SHADER_MULTIPLIER.get();
        return (float) Mth.clamp(f, 0.05, 12.0);
    }

    /** Spawns actual textured world-space mist; distance fog remains as the depth/far-distance layer. */
    private static void tickFogParticles(ClientLevel level, LocalPlayer p) {
        if (level == null || p == null || !ManbearClientConfig.FOG_ENABLED.get()
                || !ManbearClientConfig.FOG_PARTICLES_ENABLED.get() || exposure < 0.02F
                || p.hasEffect(MobEffects.BLINDNESS) || p.hasEffect(MobEffects.DARKNESS)
                || Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            if (!ManbearClientConfig.FOG_ENABLED.get() || !ManbearClientConfig.FOG_PARTICLES_ENABLED.get()) {
                particleSpawnBudget = 0F;
            }
            return;
        }

        RandomSource random = p.getRandom();
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 cameraPos = camera.getPosition();
        double minDistance = ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE.get();
        double minimumSpawnRadius = minDistance + PARTICLE_VISUAL_MARGIN;
        // Keep the configured outer radius wider than the protected inner radius even if someone
        // manually configures an unusually large minimum distance. The command normally prevents this.
        double maximumSpawnRadius = Math.max(minimumSpawnRadius + 1.0D, ManbearClientConfig.FOG_PARTICLE_MAX_DISTANCE.get());
        float densityFactor = Mth.clamp((float) Math.sqrt(Math.max(0.1F, density)), 0.5F, 1.6F);
        float emission = 2.35F * exposure * densityFactor * ManbearClientConfig.FOG_PARTICLE_DENSITY.get().floatValue();
        particleSpawnBudget = Math.min(8F, particleSpawnBudget + emission);
        int count = Math.min(8, (int) particleSpawnBudget);
        particleSpawnBudget -= count;

        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            // Pick radius uniformly by area, not uniformly by distance. This fills the whole annular
            // area around the player instead of creating a visually concentrated circular band.
            // The center remains outside the protected near-camera zone and inside the configured outer radius.
            double innerRadiusSquared = minimumSpawnRadius * minimumSpawnRadius;
            double outerRadiusSquared = maximumSpawnRadius * maximumSpawnRadius;
            double radius = Math.sqrt(innerRadiusSquared + random.nextDouble() * (outerRadiusSquared - innerRadiusSquared));
            double x = p.getX() + Math.cos(angle) * radius;
            double z = p.getZ() + Math.sin(angle) * radius;
            double y = p.getY() - 0.45 + random.nextDouble() * 3.1;
            double cameraDx = x - cameraPos.x;
            double cameraDy = y - cameraPos.y;
            double cameraDz = z - cameraPos.z;
            if (cameraDx * cameraDx + cameraDy * cameraDy + cameraDz * cameraDz < minimumSpawnRadius * minimumSpawnRadius) continue;
            BlockPos pos = BlockPos.containing(x, y, z);
            if (!level.hasChunkAt(pos)) continue;

            // Avoid creating the center of a cloud inside solid blocks; the soft texture still overlaps terrain naturally.
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                y += 1.0 + random.nextDouble() * 1.8;
                pos = BlockPos.containing(x, y, z);
                if (!level.hasChunkAt(pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) continue;
            }

            double driftX = (random.nextDouble() - 0.5) * 0.010;
            double driftY = 0.0001 + random.nextDouble() * 0.0022;
            double driftZ = (random.nextDouble() - 0.5) * 0.010;
            level.addParticle(com.manbear.registry.ModParticles.FOG.get(), x, y, z, driftX, driftY, driftZ);
        }
    }

    /** a bell curve on the 24000-tick day that wraps around midnight */
    private static double bump(double t, double center, double sigma) {
        double d = Math.abs(t - center);
        d = Math.min(d, 24000.0 - d);
        return Math.exp(-(d * d) / (2.0 * sigma * sigma));
    }

    // ------------------------------------------------------------------ the fog itself
    @SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog event) {
        if (!ManbearClientConfig.FOG_ENABLED.get() || exposure < 0.01F) return;
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;      // the sky and clouds keep their own fog
        if (event.getType() != FogType.NONE) return;                          // water, lava, powder snow: vanilla
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || p.hasEffect(MobEffects.BLINDNESS) || p.hasEffect(MobEffects.DARKNESS)) return;

        float vanillaEnd = event.getFarPlaneDistance();
        float renderDistance = Math.max(32F, mc.options.getEffectiveRenderDistance() * 16F);   // YOUR render distance, in blocks
        float end = (float) (renderDistance * 0.85 * ManbearClientConfig.FOG_DISTANCE.get()) / density;
        end = Math.max(end, 10F);
        end = vanillaEnd + (end - vanillaEnd) * exposure;                   // fades in and out smoothly
        if (end >= vanillaEnd - 1F) return;                                  // never thinner than vanilla: leave it alone
        float start = Mth.clamp(end * ManbearClientConfig.FOG_START.get().floatValue(), 0F, end - 4F);

        event.setNearPlaneDistance(start);
        event.setFarPlaneDistance(end);
        event.setFogShape(FogShape.SPHERE);                                   // round, like real fog
        event.setCanceled(true);                                              // Forge only applies our values when cancelled
    }

    @SubscribeEvent
    public static void onColor(ViewportEvent.ComputeFogColor event) {
        if (!ManbearClientConfig.FOG_ENABLED.get() || exposure < 0.01F) return;
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || p.hasEffect(MobEffects.BLINDNESS) || p.hasEffect(MobEffects.DARKNESS)) return;

        float pt = (float) event.getPartialTick();
        float day = 1F;
        if (level.dimension() == Level.OVERWORLD) {   // same day/night curve the vanilla sky uses
            day = Mth.clamp(Mth.cos(level.getTimeOfDay(pt) * ((float) Math.PI * 2F)) * 2F + 0.5F, 0.05F, 1F);
        }
        float gloom = Mth.clamp(1F - 0.4F * level.getRainLevel(pt) - 0.25F * level.getThunderLevel(pt), 0.3F, 1F);
        float bright = ManbearClientConfig.FOG_BRIGHTNESS.get().floatValue();
        float[] c = rgb();
        float k = ManbearClientConfig.FOG_COLOR_BLEND.get().floatValue() * exposure;
        event.setRed(Mth.lerp(k, event.getRed(), Mth.clamp(c[0] * day * gloom * bright, 0F, 1F)));
        event.setGreen(Mth.lerp(k, event.getGreen(), Mth.clamp(c[1] * day * gloom * bright, 0F, 1F)));
        event.setBlue(Mth.lerp(k, event.getBlue(), Mth.clamp(c[2] * day * gloom * bright, 0F, 1F)));
    }

    /** Current smoothed fog exposure, used to fade already-spawned wisps when entering shelter or caves. */
    public static float getParticleExposure() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && (player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS))) return 0F;
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) return 0F;
        return Mth.clamp(exposure, 0F, 1F);
    }

    /** Copies the configured particle tint into a caller-owned RGB buffer, with a softer night dimming curve. */
    public static void copyConfiguredParticleColor(float[] target) {
        if (target == null || target.length < 3) return;
        float[] current = rgb();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float day = 1F;
        if (level != null && level.dimension() == Level.OVERWORLD) {
            float skyCycle = Mth.cos(level.getTimeOfDay(1F) * ((float) Math.PI * 2F));
            day = Mth.clamp(skyCycle * 1.2F + 0.25F, 0.20F, 1F);
        }
        float gloom = 1F;
        if (level != null) gloom = Mth.clamp(1F - 0.4F * level.getRainLevel(1F) - 0.25F * level.getThunderLevel(1F), 0.3F, 1F);
        float brightness = ManbearClientConfig.FOG_BRIGHTNESS.get().floatValue();
        target[0] = Mth.clamp(current[0] * day * gloom * brightness, 0F, 1F);
        target[1] = Mth.clamp(current[1] * day * gloom * brightness, 0F, 1F);
        target[2] = Mth.clamp(current[2] * day * gloom * brightness, 0F, 1F);
    }

    private static float[] rgb() {
        String s = ManbearClientConfig.FOG_COLOR.get();
        if (!s.equals(parsedColor)) {
            try {
                int v = Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16);
                RGB[0] = ((v >> 16) & 255) / 255F;
                RGB[1] = ((v >> 8) & 255) / 255F;
                RGB[2] = (v & 255) / 255F;
            } catch (NumberFormatException ignored) {
                // keep the previous colour
            }
            parsedColor = s;
        }
        return RGB;
    }

    // ------------------------------------------------------------------ shader packs
    /** Iris, Oculus and OptiFine are looked up by reflection so none of them is needed to run the mod */
    private static boolean detectShaders() {
        String[] iris = {"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"};
        for (String name : iris) {
            try {
                Class<?> c = Class.forName(name);
                Object api = c.getMethod("getInstance").invoke(null);
                return (Boolean) c.getMethod("isShaderPackInUse").invoke(api);
            } catch (Throwable ignored) {
                // not this one
            }
        }
        try {
            Class<?> c = Class.forName("net.optifine.Config");
            return (Boolean) c.getMethod("isShaders").invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ /manbearfog
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("manbearfog")
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.literal("on").executes(c -> toggle(c.getSource(), true)))
                .then(Commands.literal("off").executes(c -> toggle(c.getSource(), false)))
                .then(Commands.literal("thickness").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.1, 5.0))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_THICKNESS, DoubleArgumentType.getDouble(c, "value"), "thickness"))))
                .then(Commands.literal("distance").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.2, 2.0))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_DISTANCE, DoubleArgumentType.getDouble(c, "value"), "distance"))))
                .then(Commands.literal("start").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 0.9))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_START, DoubleArgumentType.getDouble(c, "value"), "start"))))
                .then(Commands.literal("brightness").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 2.0))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_BRIGHTNESS, DoubleArgumentType.getDouble(c, "value"), "brightness"))))
                .then(Commands.literal("particles")
                        .then(Commands.literal("on").executes(c -> toggleParticles(c.getSource(), true)))
                        .then(Commands.literal("off").executes(c -> toggleParticles(c.getSource(), false))))
                .then(Commands.literal("particle-density").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.25, 3.0))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_PARTICLE_DENSITY, DoubleArgumentType.getDouble(c, "value"), "particle density"))))
                .then(Commands.literal("particle-opacity").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.02, 0.5))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_PARTICLE_OPACITY, DoubleArgumentType.getDouble(c, "value"), "particle opacity"))))
                .then(Commands.literal("particle-min-distance").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 32.0))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE, DoubleArgumentType.getDouble(c, "value"), "particle minimum distance (blocks)"))))
                .then(Commands.literal("particle-fade-distance").then(Commands.argument("value", DoubleArgumentType.doubleArg(1.0, 24.0))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_PARTICLE_FADE_DISTANCE, DoubleArgumentType.getDouble(c, "value"), "particle fade distance (blocks)"))))
                .then(Commands.literal("particle-max-distance").then(Commands.argument("value", DoubleArgumentType.doubleArg(16.0, 128.0))
                        .executes(c -> setMaxParticleDistance(c.getSource(), DoubleArgumentType.getDouble(c, "value")))))
                .then(Commands.literal("blend").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                        .executes(c -> set(c.getSource(), ManbearClientConfig.FOG_COLOR_BLEND, DoubleArgumentType.getDouble(c, "value"), "colour blend"))))
                .then(Commands.literal("color").then(Commands.argument("hex", StringArgumentType.word())
                        .executes(c -> color(c.getSource(), StringArgumentType.getString(c, "hex")))))
                .then(Commands.literal("reset").executes(c -> reset(c.getSource())));
    }

    private static void save(ForgeConfigSpec.ConfigValue<?> v) {
        try { v.save(); } catch (Exception ignored) { }
    }

    private static int set(CommandSourceStack src, ForgeConfigSpec.DoubleValue v, double x, String what) {
        v.set(x);
        save(v);
        src.sendSuccess(() -> Component.literal("Fog " + what + " set to " + x), false);
        return 1;
    }

    private static int setMaxParticleDistance(CommandSourceStack src, double requested) {
        double minimumSpawnRadius = ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE.get() + PARTICLE_VISUAL_MARGIN;
        double actual = Math.max(requested, minimumSpawnRadius + 1.0D);
        ManbearClientConfig.FOG_PARTICLE_MAX_DISTANCE.set(actual);
        save(ManbearClientConfig.FOG_PARTICLE_MAX_DISTANCE);
        src.sendSuccess(() -> Component.literal(String.format("Fog particle maximum spawn distance set to %.1f blocks", actual)), false);
        if (actual > requested) {
            src.sendSuccess(() -> Component.literal(String.format("Raised to %.1f blocks so it stays outside the current minimum-distance boundary", actual)), false);
        }
        return 1;
    }

    private static int toggle(CommandSourceStack src, boolean on) {
        ManbearClientConfig.FOG_ENABLED.set(on);
        save(ManbearClientConfig.FOG_ENABLED);
        src.sendSuccess(() -> Component.literal("Fog " + (on ? "ON" : "OFF")), false);
        return 1;
    }

    private static int toggleParticles(CommandSourceStack src, boolean on) {
        ManbearClientConfig.FOG_PARTICLES_ENABLED.set(on);
        save(ManbearClientConfig.FOG_PARTICLES_ENABLED);
        src.sendSuccess(() -> Component.literal("Textured fog particles " + (on ? "ON" : "OFF")), false);
        return 1;
    }

    private static int color(CommandSourceStack src, String hex) {
        if (!hex.matches("#?[0-9a-fA-F]{6}")) {
            src.sendFailure(Component.literal("Use a hex colour such as B4BEC8 (the # is optional)."));
            return 0;
        }
        String value = hex.startsWith("#") ? hex : "#" + hex;
        ManbearClientConfig.FOG_COLOR.set(value);
        save(ManbearClientConfig.FOG_COLOR);
        src.sendSuccess(() -> Component.literal("Fog colour set to " + value), false);
        return 1;
    }

    private static int reset(CommandSourceStack src) {
        ManbearClientConfig.FOG_ENABLED.set(ManbearClientConfig.FOG_ENABLED.getDefault());
        ManbearClientConfig.FOG_THICKNESS.set(ManbearClientConfig.FOG_THICKNESS.getDefault());
        ManbearClientConfig.FOG_DISTANCE.set(ManbearClientConfig.FOG_DISTANCE.getDefault());
        ManbearClientConfig.FOG_START.set(ManbearClientConfig.FOG_START.getDefault());
        ManbearClientConfig.FOG_COLOR.set(ManbearClientConfig.FOG_COLOR.getDefault());
        ManbearClientConfig.FOG_COLOR_BLEND.set(ManbearClientConfig.FOG_COLOR_BLEND.getDefault());
        ManbearClientConfig.FOG_BRIGHTNESS.set(ManbearClientConfig.FOG_BRIGHTNESS.getDefault());
        ManbearClientConfig.FOG_PARTICLES_ENABLED.set(ManbearClientConfig.FOG_PARTICLES_ENABLED.getDefault());
        ManbearClientConfig.FOG_PARTICLE_DENSITY.set(ManbearClientConfig.FOG_PARTICLE_DENSITY.getDefault());
        ManbearClientConfig.FOG_PARTICLE_OPACITY.set(ManbearClientConfig.FOG_PARTICLE_OPACITY.getDefault());
        ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE.set(ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE.getDefault());
        ManbearClientConfig.FOG_PARTICLE_FADE_DISTANCE.set(ManbearClientConfig.FOG_PARTICLE_FADE_DISTANCE.getDefault());
        ManbearClientConfig.FOG_PARTICLE_MAX_DISTANCE.set(ManbearClientConfig.FOG_PARTICLE_MAX_DISTANCE.getDefault());
        save(ManbearClientConfig.FOG_ENABLED);
        save(ManbearClientConfig.FOG_PARTICLES_ENABLED);
        save(ManbearClientConfig.FOG_PARTICLE_DENSITY);
        save(ManbearClientConfig.FOG_PARTICLE_OPACITY);
        save(ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE);
        save(ManbearClientConfig.FOG_PARTICLE_FADE_DISTANCE);
        save(ManbearClientConfig.FOG_PARTICLE_MAX_DISTANCE);
        src.sendSuccess(() -> Component.literal("Fog settings reset to the defaults."), false);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        Minecraft mc = Minecraft.getInstance();
        float rd = mc.options.getEffectiveRenderDistance() * 16F;
        float end = (float) (rd * 0.85 * ManbearClientConfig.FOG_DISTANCE.get()) / density;
        src.sendSuccess(() -> Component.literal(String.format(
                "Fog %s | thickness %.2f | distance %.2f | start %.2f | colour %s (blend %.2f) | brightness %.2f | "
                        + "particles %s (density %.2f, opacity %.2f, min distance %.1f blocks, fade distance %.1f blocks, max spawn distance %.1f blocks) | right now: density x%.2f, closes in at ~%.0f of your %.0f blocks | shader pack: %s",
                ManbearClientConfig.FOG_ENABLED.get() ? "ON" : "OFF", ManbearClientConfig.FOG_THICKNESS.get(),
                ManbearClientConfig.FOG_DISTANCE.get(), ManbearClientConfig.FOG_START.get(), ManbearClientConfig.FOG_COLOR.get(),
                ManbearClientConfig.FOG_COLOR_BLEND.get(), ManbearClientConfig.FOG_BRIGHTNESS.get(),
                ManbearClientConfig.FOG_PARTICLES_ENABLED.get() ? "ON" : "OFF", ManbearClientConfig.FOG_PARTICLE_DENSITY.get(),
                ManbearClientConfig.FOG_PARTICLE_OPACITY.get(), ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE.get(),
                ManbearClientConfig.FOG_PARTICLE_FADE_DISTANCE.get(), ManbearClientConfig.FOG_PARTICLE_MAX_DISTANCE.get(), density, end, rd,
                shaders ? "detected" : "none")), false);
        return 1;
    }
}
