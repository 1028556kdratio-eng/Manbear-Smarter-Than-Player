package com.manbear.client;

import com.manbear.entity.DoppelgangerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Draws the hallucination as the player it copies: that player's own skin, the right (wide or slim) arm model, their
 * armour and held item, and their name above the head.
 */
public class DoppelgangerRenderer extends LivingEntityRenderer<DoppelgangerEntity, PlayerModel<DoppelgangerEntity>> {
    private final PlayerModel<DoppelgangerEntity> wide;
    private final PlayerModel<DoppelgangerEntity> slim;

    public DoppelgangerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wide = this.model;
        this.slim = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
        this.addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
    }

    private static PlayerInfo info(DoppelgangerEntity e) {
        UUID owner = e.ownerId();
        var connection = Minecraft.getInstance().getConnection();
        return owner == null || connection == null ? null : connection.getPlayerInfo(owner);
    }

    @Override
    public ResourceLocation getTextureLocation(DoppelgangerEntity e) {
        PlayerInfo info = info(e);
        if (info != null) return info.getSkinLocation();
        return DefaultPlayerSkin.getDefaultSkin(e.ownerId() != null ? e.ownerId() : e.getUUID());
    }

    @Override
    public void render(DoppelgangerEntity e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        PlayerInfo info = info(e);
        this.model = info != null && "slim".equals(info.getModelName()) ? slim : wide;
        super.render(e, yaw, partialTick, pose, buffers, light);
    }
}
