package com.manbear.entity;

import net.minecraft.world.entity.EntityDimensions;

public enum Stance {
    STAND(EntityDimensions.scalable(3.0F, 5.0F)),
    CROUCH(EntityDimensions.scalable(2.4F, 3.3F)),
    CRAWL(EntityDimensions.scalable(0.9F, 0.95F)),
    /** lying on his stomach, asleep */
    SLEEP(EntityDimensions.scalable(2.8F, 1.1F)),
    /** paddling through deep water */
    SWIM(EntityDimensions.scalable(1.4F, 1.3F)),
    /** sitting on the ground (cave event) */
    SIT(EntityDimensions.scalable(2.2F, 2.5F)),
    /** squatting low behind a wall or inside a bush */
    DUCK(EntityDimensions.scalable(1.7F, 1.9F));

    public final EntityDimensions dims;

    Stance(EntityDimensions dims) { this.dims = dims; }

    public static Stance byId(int id) {
        Stance[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
