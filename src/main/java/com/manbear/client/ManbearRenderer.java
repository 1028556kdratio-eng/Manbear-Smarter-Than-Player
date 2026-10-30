package com.manbear.client;

import com.manbear.entity.ManbearEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ManbearRenderer extends GeoEntityRenderer<ManbearEntity> {
    /** At 1.2 the model stands about 5 blocks tall, matching his 5.0-block hitbox. Tweak freely. */
    public static final float MODEL_SCALE = 1.2F;

    public ManbearRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new ManbearModel());
        this.shadowRadius = 1.1F;
        withScale(MODEL_SCALE);
        addRenderLayer(new ManbearEyesLayer(this));
    }
}
