package com.manbear.particle;

import com.manbear.ManbearClientConfig;
import com.manbear.client.FogHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/** A soft, textured wisp of ground mist. Its transparent texture makes overlapping particles read as fog banks. */
public final class FogParticle extends TextureSheetParticle {
    private final SpriteSet sprites;
    private final float baseSize;
    private final float driftSeed;
    private final float[] colorBuffer = new float[3];

    private FogParticle(ClientLevel level, double x, double y, double z,
                        double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, xd, yd, zd);
        this.sprites = sprites;
        this.driftSeed = this.random.nextFloat() * 100F;
        this.baseSize = 3.0F + this.random.nextFloat() * 5.5F;
        this.quadSize = baseSize;
        this.roll = this.random.nextFloat() * ((float) Math.PI * 2F);
        this.oRoll = this.roll;
        this.lifetime = 150 + this.random.nextInt(100);
        this.gravity = 0F;
        this.friction = 0.985F;
        this.hasPhysics = false;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        setSpriteFromAge(sprites);
        applyConfiguredColor();
        setAlpha(0F);
    }

    @Override
    public void tick() {
        if (!ManbearClientConfig.FOG_ENABLED.get() || !ManbearClientConfig.FOG_PARTICLES_ENABLED.get()) {
            remove();
            return;
        }
        super.tick();
        if (!isAlive()) return;

        // Never let a drifting wisp enter the player camera bubble. Account for its rendered size so its
        // visible edge (not just its center) stays outside the configured minimum distance.
        Minecraft mc = Minecraft.getInstance();
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        double safeDistance = ManbearClientConfig.FOG_PARTICLE_MIN_DISTANCE.get() + Math.max(1.0D, quadSize * 0.55D);
        double cameraDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (cameraDistance < safeDistance) {
            remove();
            return;
        }
        // Fade over a configurable distance before the particle reaches the camera safety boundary.
        // Smoothstep eases the transition at both ends, so wisps do not visibly pop out of existence.
        double fadeDistance = ManbearClientConfig.FOG_PARTICLE_FADE_DISTANCE.get();
        float proximityFade = Mth.clamp((float) ((cameraDistance - safeDistance) / fadeDistance), 0F, 1F);
        proximityFade = proximityFade * proximityFade * (3F - 2F * proximityFade);

        setSpriteFromAge(sprites);
        applyConfiguredColor();

        // Gentle, changing lateral drift keeps the banks from looking like stationary billboards.
        float phase = (age + driftSeed) * 0.025F;
        oRoll = roll;
        roll += Mth.sin(phase * 0.31F) * 0.0008F;
        xd += Mth.sin(phase) * 0.00035D;
        zd += Mth.cos(phase * 0.83F) * 0.00035D;
        yd += Mth.sin(phase * 0.47F) * 0.000035D;

        float fadeIn = Mth.clamp(age / 22F, 0F, 1F);
        float fadeOut = Mth.clamp((lifetime - age) / 34F, 0F, 1F);
        float lifePulse = 0.90F + 0.10F * Mth.sin((age + driftSeed) * 0.035F);
        float exposure = FogHandler.getParticleExposure();
        if (exposure <= 0.005F) {
            remove();
            return;
        }
        float configuredAlpha = ManbearClientConfig.FOG_PARTICLE_OPACITY.get().floatValue();
        setAlpha(configuredAlpha * fadeIn * fadeOut * lifePulse * exposure * proximityFade);

        float growth = 0.82F + 0.28F * (age / (float) lifetime);
        quadSize = baseSize * growth;
    }

    private void applyConfiguredColor() {
        FogHandler.copyConfiguredParticleColor(colorBuffer);
        setColor(colorBuffer[0], colorBuffer[1], colorBuffer[2]);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new FogParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites);
        }
    }
}
