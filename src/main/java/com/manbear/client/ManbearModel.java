package com.manbear.client;

import com.manbear.ManbearMod;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

public class ManbearModel extends GeoModel<ManbearEntity> {
    private static final ResourceLocation MODEL = new ResourceLocation(ManbearMod.MODID, "geo/manbear.geo.json");
    private static final ResourceLocation TEXTURE = new ResourceLocation(ManbearMod.MODID, "textures/entity/manbear.png");
    private static final ResourceLocation ANIM = new ResourceLocation(ManbearMod.MODID, "animations/manbear.animation.json");

    @Override
    public ResourceLocation getModelResource(ManbearEntity animatable) { return MODEL; }

    @Override
    public ResourceLocation getTextureResource(ManbearEntity animatable) { return TEXTURE; }

    @Override
    public ResourceLocation getAnimationResource(ManbearEntity animatable) { return ANIM; }

    /** Head always follows the player. Pitch is skipped in the on-all-fours poses (the pose already aims the head). */
    @Override
    public void setCustomAnimations(ManbearEntity animatable, long instanceId, AnimationState<ManbearEntity> state) {
        CoreGeoBone head = getAnimationProcessor().getBone("Head");
        if (head == null || animatable.getMode() == ManbearMode.SLEEP || animatable.getMode() == ManbearMode.PLAYDEAD) return;
        EntityModelData data = state.getData(DataTickets.ENTITY_MODEL_DATA);
        boolean onAllFours = animatable.getMode().isChaseLike() || animatable.isClimbingFlag()
                || animatable.getStance() == com.manbear.entity.Stance.CRAWL || animatable.getStance() == com.manbear.entity.Stance.SWIM
                || animatable.getStance() == com.manbear.entity.Stance.SIT || animatable.getStance() == com.manbear.entity.Stance.DUCK;
        float yaw = Mth.clamp(data.netHeadYaw(), -50F, 50F) * Mth.DEG_TO_RAD;
        head.setRotY(head.getRotY() + yaw * (onAllFours ? 0.5F : 1F));
        if (!onAllFours) {
            float pitch = Mth.clamp(data.headPitch(), -30F, 30F) * Mth.DEG_TO_RAD;
            head.setRotX(head.getRotX() + pitch);
        }
    }
}
