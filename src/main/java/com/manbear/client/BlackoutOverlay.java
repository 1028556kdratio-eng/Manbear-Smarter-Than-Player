package com.manbear.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/** A full-screen black fade drawn above everything (the kidnapping blackout). */
public final class BlackoutOverlay {
    private static int fadeIn;
    private static int hold;
    private static int fadeOut;
    private static int age = -1;   // < 0 = not running

    private BlackoutOverlay() { }

    public static void start(int in, int h, int out) {
        fadeIn = Math.max(1, in);
        hold = Math.max(0, h);
        fadeOut = Math.max(1, out);
        age = 0;
    }

    /** once per client tick */
    public static void tick() {
        if (age < 0) return;
        if (Minecraft.getInstance().level == null) { age = -1; return; }
        if (++age > fadeIn + hold + fadeOut) age = -1;
    }

    public static void stop() { age = -1; }

    private static float alpha(float partial) {
        if (age < 0) return 0F;
        float a = age + partial;
        if (a < fadeIn) return a / fadeIn;
        if (a < fadeIn + hold) return 1F;
        return Math.max(0F, 1F - (a - fadeIn - hold) / fadeOut);
    }

    public static final IGuiOverlay OVERLAY = (gui, graphics, partialTick, width, height) -> {
        float a = alpha(partialTick);
        if (a <= 0F) return;
        int alpha = Math.min(255, (int) (a * 255F));
        graphics.fill(0, 0, width, height, alpha << 24);
    };
}
