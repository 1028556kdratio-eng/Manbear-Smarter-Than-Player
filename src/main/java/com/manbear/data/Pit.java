package com.manbear.data;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * A hidden pit dug by the Manbear: a 3x3 hole, {@code depth} blocks deep, roofed by a layer of ordinary-looking
 * grass blocks (the "cover", at {@code top}) that gives way when a player steps on it.
 */
public class Pit {
    public final String dim;
    /** the middle block of the 3x3 cover layer (the block you would stand ON) */
    public final BlockPos top;
    public final int depth;

    /** set the moment a player steps on the cover (transient: not saved) */
    public UUID sprungBy;
    public long collapseAt;

    public Pit(String dim, BlockPos top, int depth) {
        this.dim = dim;
        this.top = top;
        this.depth = depth;
    }

    public boolean sprung() { return sprungBy != null; }

    /** is this one of the nine cover blocks? */
    public boolean covers(BlockPos p) {
        return p.getY() == top.getY() && Math.abs(p.getX() - top.getX()) <= 1 && Math.abs(p.getZ() - top.getZ()) <= 1;
    }
}
