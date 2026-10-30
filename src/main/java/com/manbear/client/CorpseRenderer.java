package com.manbear.client;

import com.manbear.entity.CorpseEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a dead animal by borrowing the real renderer of that animal: a throw-away client-side copy is set to "20 ticks
 * dead", which vanilla draws flipped onto its side with the red death tint, and it never moves on from that.
 */
public class CorpseRenderer extends EntityRenderer<CorpseEntity> {
    private final Map<String, LivingEntity> ghosts = new HashMap<>();
    private ClientLevel ghostLevel;

    public CorpseRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity entity) {
        return new ResourceLocation("textures/entity/pig/pig.png");   // required by the API, never used
    }

    private LivingEntity ghost(CorpseEntity corpse) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        if (level != ghostLevel) { ghosts.clear(); ghostLevel = level; }       // new world: old copies are invalid
        LivingEntity g = ghosts.get(corpse.kind());
        if (g != null) return g;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(corpse.kind()));
        if (type == null) return null;
        Entity e = type.create(level);
        if (!(e instanceof LivingEntity le)) return null;
        ghosts.put(corpse.kind(), le);
        return le;
    }

    @Override
    public void render(CorpseEntity corpse, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        LivingEntity g = ghost(corpse);
        if (g == null) return;
        float rot = corpse.getYRot();
        g.deathTime = 20;                              // fully fallen over, and tinted red
        g.setPos(corpse.getX(), corpse.getY(), corpse.getZ());
        g.setYRot(rot);
        g.yRotO = rot;
        g.yBodyRot = rot;
        g.yBodyRotO = rot;
        g.yHeadRot = rot;
        g.yHeadRotO = rot;
        g.setXRot(0F);
        g.xRotO = 0F;
        Minecraft.getInstance().getEntityRenderDispatcher().render(g, 0.0D, 0.0D, 0.0D, rot, partialTick, pose, buffers, light);
    }
}
