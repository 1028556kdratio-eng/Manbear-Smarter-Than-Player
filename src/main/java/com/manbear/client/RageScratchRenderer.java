package com.manbear.client;

import com.manbear.block.RageScratchBlock;
import com.manbear.block.RageScratchBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/** Draws the clawed-in number on the face of the log, the way a sign draws its text (pale, bold, with a dark gouge shadow). */
public class RageScratchRenderer implements BlockEntityRenderer<RageScratchBlockEntity> {
    private static final int PALE_WOOD = 0xE2C896;
    private final Font font;

    public RageScratchRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @Override
    public void render(RageScratchBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof RageScratchBlock)) return;
        Direction facing = state.getValue(RageScratchBlock.FACING);
        Component text = Component.literal(Integer.toString(be.value())).withStyle(ChatFormatting.BOLD);

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));      // same orientation rule as a wall sign
        pose.translate(0.0, 0.0, -0.494);                              // flat against the log, which is behind the decal
        float scale = 0.046F;                                          // three digits are about 0.85 of a block wide
        pose.scale(scale, -scale, scale);
        float x = -font.width(text) / 2.0F;
        font.drawInBatch(text, x, -4.0F, PALE_WOOD, true, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
    }
}
