package com.manbear.client;

import com.manbear.ManbearMod;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/** Re-draws the model with the eyes texture, full-bright, so the eyes glow in the dark. */
public class ManbearEyesLayer extends GeoRenderLayer<ManbearEntity> {
    private static final ResourceLocation EYES = new ResourceLocation(ManbearMod.MODID, "textures/entity/manbear_eyes.png");

    public ManbearEyesLayer(GeoRenderer<ManbearEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, ManbearEntity animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        ManbearMode mode = animatable.getMode();
        // dark while he sleeps, hides, peeks or ducks: a stealthy hunter does not show his eyes
        if (mode == ManbearMode.SLEEP || mode == ManbearMode.PLAYDEAD || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK || animatable.isDucking() || animatable.getStance() == com.manbear.entity.Stance.DUCK) return;
        RenderType eyes = RenderType.eyes(EYES);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, eyes, bufferSource.getBuffer(eyes),
                partialTick, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1F, 1F, 1F, 1F);
    }
}
