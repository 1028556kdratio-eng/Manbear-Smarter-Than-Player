package com.manbear.entity;

public enum ManbearMode {
    STALK, HIDE, PEEK, FAKE_CHASE, CHASE, LAY_TRAP, AMBUSH, RETREAT, SLEEP, BEHIND, LURE, PATH, FOG, CAVE, GRAB, LURKER, PETNAP, FLASH, INTIMIDATE, PITLURE, KIDNAP, PLAYDEAD, HALLUCINATE;

    public static ManbearMode byId(int id) {
        ManbearMode[] v = values();
        return v[Math.floorMod(id, v.length)];
    }

    public boolean isChaseLike() { return this == CHASE || this == AMBUSH; }
}
