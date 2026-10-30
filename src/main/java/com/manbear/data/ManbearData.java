package com.manbear.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-wide state: which Manbear is the one-and-only, cool-downs and every player's profile. */
public class ManbearData extends SavedData {
    private static final String NAME = "manbear_data";
    /** if the active Manbear has not "beaten its heart" for this long, he is considered gone */
    private static final long STALE_TICKS = 6000L;
    /** Increment when the generated den geometry/decorations change; old worlds are upgraded once. */
    public static final int CURRENT_DEN_BUILD_VERSION = 3;

    private UUID activeId;
    private long lastBeat;
    private long cooldownUntil;
    // persistent one-per-world den state (stored in overworld SavedData)
    private boolean denUnlocked;
    private boolean denPresent;
    private BlockPos denCenter;
    private BlockPos denEntrance;
    private String denDimension = "minecraft:overworld";
    private UUID denOwner;
    private UUID denRageTarget;
    /** Keeps the same Manbear assigned to an active den kidnapping/guard duty across chunk unloads. */
    private UUID denGuardianId;
    private long denCooldownUntil;
    private int denBuildVersion;
    /** Whether the once-per-den "borrowed room" has been constructed from the owner's stolen belongings. */
    private boolean denBorrowedRoomBuilt;
    private boolean denRemodelPending;
    private long denLastVisitAt = Long.MIN_VALUE;
    private long denLastRemodelAt = Long.MIN_VALUE;
    private int denRemodelCount;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final List<Trap> traps = new ArrayList<>();
    private final List<Pit> pits = new ArrayList<>();
    /** owner -> the one companion bird that belongs to them */
    private final Map<UUID, UUID> birds = new HashMap<>();
    /** players who have had a bird before (a missing bird then flies IN from afar instead of just appearing) */
    private final java.util.Set<UUID> birdSeen = new java.util.HashSet<>();
    /** Items taken from player containers during a home intrusion and awaiting placement in the den trophy chest. */
    private final List<StolenTrophy> stolenTrophies = new ArrayList<>();

    public record StolenTrophy(UUID id, UUID owner, ItemStack stack) {
        public StolenTrophy { stack = stack == null ? ItemStack.EMPTY : stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public List<StolenTrophy> pendingStolenTrophies() {
        List<StolenTrophy> copy = new ArrayList<>();
        for (StolenTrophy t : stolenTrophies) copy.add(new StolenTrophy(t.id(), t.owner(), t.stack()));
        return copy;
    }

    public boolean hasPendingStolenTrophies() { return !stolenTrophies.isEmpty(); }

    public void recordStolenTrophy(UUID owner, ItemStack stack) {
        if (owner == null || stack == null || stack.isEmpty()) return;
        // Bound the world ledger so an unattended base cannot grow SavedData without limit.
        while (stolenTrophies.size() >= 128) stolenTrophies.remove(0);
        stolenTrophies.add(new StolenTrophy(UUID.randomUUID(), owner, stack.copy()));
        setDirty();
    }

    /** Called after an item has been inserted into the den chest; keep any uninserted remainder pending. */
    public void updateStolenTrophy(UUID id, ItemStack remainder) {
        if (id == null) return;
        for (int i = 0; i < stolenTrophies.size(); i++) {
            StolenTrophy t = stolenTrophies.get(i);
            if (!t.id().equals(id)) continue;
            if (remainder == null || remainder.isEmpty()) stolenTrophies.remove(i);
            else stolenTrophies.set(i, new StolenTrophy(t.id(), t.owner(), remainder.copy()));
            setDirty();
            return;
        }
    }

    /** a bear trap he laid; they stay in the world after he vanishes */
    public record Trap(String dim, long pos) { }

    public int trapCount() { return traps.size(); }

    /** @return the oldest traps that had to be removed to stay under the cap */
    public List<Trap> addTrap(Trap t, int max) {
        traps.add(t);
        List<Trap> evicted = new ArrayList<>();
        while (traps.size() > max && !traps.isEmpty()) evicted.add(traps.remove(0));
        setDirty();
        return evicted;
    }

    // ---- pits
    public List<Pit> pits() { return pits; }

    /** @return the oldest pits that had to be dropped to stay under the cap (the caller fills them in) */
    public List<Pit> addPit(Pit pit, int max) {
        pits.add(pit);
        List<Pit> evicted = new ArrayList<>();
        while (pits.size() > max && pits.size() > 1) evicted.add(pits.remove(0));
        setDirty();
        return evicted;
    }

    public void removePit(Pit pit) {
        if (pits.remove(pit)) setDirty();
    }

    // ---- companion birds
    public UUID birdOf(UUID owner) { return birds.get(owner); }
    public boolean hadBird(UUID owner) { return birdSeen.contains(owner); }

    public void setBird(UUID owner, UUID bird) {
        birds.put(owner, bird);
        birdSeen.add(owner);
        setDirty();
    }

    public static ManbearData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ManbearData::load, ManbearData::new, NAME);
    }

    public UUID activeId() { return activeId; }

    public boolean denUnlocked() { return denUnlocked; }
    public boolean hasDen() { return denPresent && denCenter != null && denEntrance != null; }
    public BlockPos denCenter() { return denCenter; }
    public BlockPos denEntrance() { return denEntrance; }
    public String denDimension() { return denDimension; }
    public UUID denOwner() { return denOwner; }
    public UUID denGuardianId() { return denGuardianId; }
    public long denCooldownUntil() { return denCooldownUntil; }
    public int denBuildVersion() { return denBuildVersion; }
    public boolean denBorrowedRoomBuilt() { return denBorrowedRoomBuilt; }
    public boolean denRemodelPending() { return denRemodelPending; }
    public long denLastVisitAt() { return denLastVisitAt; }
    public long denLastRemodelAt() { return denLastRemodelAt; }
    public int denRemodelCount() { return denRemodelCount; }
    public void noteDenVisit(long now) {
        if (!denRemodelPending || denLastVisitAt == Long.MIN_VALUE || now - denLastVisitAt >= 80L) {
            denRemodelPending = true;
            denLastVisitAt = now;
            setDirty();
        }
    }
    public boolean canRemodelDen(long now) {
        return denRemodelPending && denRemodelCount < 5 && denLastVisitAt != Long.MIN_VALUE
                && now - denLastVisitAt >= 300L
                && (denLastRemodelAt == Long.MIN_VALUE || now - denLastRemodelAt >= 6000L);
    }
    public void markDenRemodeled(long now) {
        denRemodelPending = false;
        denLastRemodelAt = now;
        denRemodelCount++;
        setDirty();
    }
    public void setDenBorrowedRoomBuilt(boolean built) {
        if (denBorrowedRoomBuilt != built) {
            denBorrowedRoomBuilt = built;
            setDirty();
        }
    }
    public boolean denSpawnBlocked(long now) { return denCooldownUntil > now; }

    public void setDenBuildVersion(int version) {
        if (denBuildVersion != version) {
            denBuildVersion = version;
            setDirty();
        }
    }

    /** Updates the entrance coordinate when an existing den's entrance geometry is upgraded. */
    public void setDenGuardian(UUID id) {
        if (id != null && !id.equals(denGuardianId)) {
            denGuardianId = id;
            activeId = id;
            setDirty();
        }
    }

    public void clearDenGuardian(UUID id) {
        if (denGuardianId != null && (id == null || denGuardianId.equals(id))) {
            denGuardianId = null;
            setDirty();
        }
    }

    public void updateDenEntrance(BlockPos entrance) {
        if (entrance != null && !entrance.equals(denEntrance)) {
            denEntrance = entrance.immutable();
            setDirty();
        }
    }

    public void setDen(String dimension, BlockPos center, BlockPos entrance, UUID owner) {
        this.denDimension = dimension;
        this.denCenter = center.immutable();
        this.denEntrance = entrance.immutable();
        this.denOwner = owner;
        this.denPresent = true;
        this.denUnlocked = true;
        this.denBuildVersion = CURRENT_DEN_BUILD_VERSION;
        this.denBorrowedRoomBuilt = false;
        this.denRemodelPending = false; this.denLastVisitAt = Long.MIN_VALUE; this.denLastRemodelAt = Long.MIN_VALUE; this.denRemodelCount = 0;
        this.denCooldownUntil = 0L;
        this.denRageTarget = null;
        this.denGuardianId = null;
        setDirty();
    }

    /** Clear a legacy mispositioned den so the corrected generator can rebuild it without a destruction cooldown. */
    public void relocateDenForRegeneration() {
        denPresent = false;
        denCenter = null;
        denEntrance = null;
        denDimension = "minecraft:overworld";
        denBuildVersion = 0;
        denBorrowedRoomBuilt = false;
        denRemodelPending = false; denLastVisitAt = Long.MIN_VALUE; denLastRemodelAt = Long.MIN_VALUE; denRemodelCount = 0;
        denCooldownUntil = 0L;
        denRageTarget = null;
        denGuardianId = null;
        // Keep denUnlocked and denOwner so the replacement stays tied to the original learned home.
        setDirty();
    }

    /** Mark the den as destroyed and persist a world-wide spawn lockout. */
    public void destroyDen(long cooldownUntil, UUID rageTarget) {
        if (!denPresent) return;
        denPresent = false;
        denBorrowedRoomBuilt = false;
        denCooldownUntil = cooldownUntil;
        denRageTarget = rageTarget != null ? rageTarget : denOwner;
        denGuardianId = null;
        setDirty();
    }

    /** Called once when the 30-minute lockout expires; rage reaches 100 before den regeneration. */
    public void completeDenCooldown() {
        if (denCooldownUntil == 0L) return;
        UUID target = denRageTarget != null ? denRageTarget : denOwner;
        if (target != null) profile(target).rage = 100.0F;
        denCooldownUntil = 0L;
        denRageTarget = null;
        setDirty();
    }

    public boolean isActive(long now) {
        return activeId != null && now - lastBeat < STALE_TICKS;
    }

    /** Returns true if this Manbear is (or just became) the active one. */
    public boolean claim(UUID id, long now) {
        // A captive's guardian is a persistent set-piece. Do not let the ordinary stale-active timeout
        // replace it with a new Manbear while the player is still imprisoned in the den.
        if (denGuardianId != null) {
            if (!denGuardianId.equals(id)) return false;
            activeId = id;
            lastBeat = now;
            setDirty();
            return true;
        }
        if (activeId == null || activeId.equals(id) || now - lastBeat >= STALE_TICKS) {

            activeId = id;
            lastBeat = now;
            setDirty();
            return true;
        }
        return false;
    }

    public void release(UUID id, long cooldownTicks, long now) {
        if (id.equals(activeId)) {
            activeId = null;
            cooldownUntil = Math.max(cooldownUntil, now + cooldownTicks);
            setDirty();
        }
    }

    public boolean onCooldown(long now) { return now < cooldownUntil; }

    public void clearAll() {
        activeId = null;
        denGuardianId = null;
        cooldownUntil = 0;
        setDirty();
    }

    public PlayerProfile profile(UUID id) {
        return profiles.computeIfAbsent(id, k -> new PlayerProfile());
    }

    public void resetProfiles() { profiles.clear(); setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (activeId != null) tag.putUUID("active", activeId);
        tag.putLong("beat", lastBeat);
        tag.putLong("cooldown", cooldownUntil);
        tag.putBoolean("denUnlocked", denUnlocked);
        tag.putBoolean("denPresent", denPresent);
        if (denCenter != null) tag.putLong("denCenter", denCenter.asLong());
        if (denEntrance != null) tag.putLong("denEntrance", denEntrance.asLong());
        tag.putString("denDimension", denDimension == null ? "minecraft:overworld" : denDimension);
        if (denOwner != null) tag.putUUID("denOwner", denOwner);
        if (denRageTarget != null) tag.putUUID("denRageTarget", denRageTarget);
        if (denGuardianId != null) tag.putUUID("denGuardianId", denGuardianId);
        tag.putLong("denCooldownUntil", denCooldownUntil);
        tag.putInt("denBuildVersion", denBuildVersion);
        tag.putBoolean("denBorrowedRoomBuilt", denBorrowedRoomBuilt);
        tag.putBoolean("denRemodelPending", denRemodelPending); tag.putLong("denLastVisitAt", denLastVisitAt);
        tag.putLong("denLastRemodelAt", denLastRemodelAt); tag.putInt("denRemodelCount", denRemodelCount);
        CompoundTag pt = new CompoundTag();
        for (Map.Entry<UUID, PlayerProfile> e : profiles.entrySet()) {
            pt.put(e.getKey().toString(), e.getValue().save());
        }
        tag.put("profiles", pt);
        ListTag tl = new ListTag();
        for (Trap t : traps) {
            CompoundTag c = new CompoundTag();
            c.putString("d", t.dim()); c.putLong("p", t.pos());
            tl.add(c);
        }
        tag.put("traps", tl);
        ListTag pl = new ListTag();
        for (Pit pit : pits) {
            CompoundTag c = new CompoundTag();
            c.putString("d", pit.dim); c.putLong("p", pit.top.asLong()); c.putInt("n", pit.depth);
            pl.add(c);
        }
        tag.put("pits", pl);
        ListTag bl = new ListTag();
        for (Map.Entry<UUID, UUID> e : birds.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("o", e.getKey()); c.putUUID("b", e.getValue());
            bl.add(c);
        }
        tag.put("birds", bl);
        ListTag sl = new ListTag();
        for (UUID u : birdSeen) {
            CompoundTag c = new CompoundTag();
            c.putUUID("o", u);
            sl.add(c);
        }
        tag.put("birdSeen", sl);
        ListTag trophyList = new ListTag();
        for (StolenTrophy trophy : stolenTrophies) {
            if (trophy.stack().isEmpty()) continue;
            CompoundTag c = new CompoundTag();
            c.putUUID("id", trophy.id());
            if (trophy.owner() != null) c.putUUID("owner", trophy.owner());
            c.put("stack", trophy.stack().save(new CompoundTag()));
            trophyList.add(c);
        }
        tag.put("stolenTrophies", trophyList);
        return tag;
    }

    public static ManbearData load(CompoundTag tag) {
        ManbearData d = new ManbearData();
        if (tag.hasUUID("active")) d.activeId = tag.getUUID("active");
        d.lastBeat = tag.getLong("beat");
        d.cooldownUntil = tag.getLong("cooldown");
        d.denUnlocked = tag.getBoolean("denUnlocked");
        d.denPresent = tag.getBoolean("denPresent");
        if (tag.contains("denCenter")) d.denCenter = BlockPos.of(tag.getLong("denCenter"));
        if (tag.contains("denEntrance")) d.denEntrance = BlockPos.of(tag.getLong("denEntrance"));
        if (tag.contains("denDimension")) d.denDimension = tag.getString("denDimension");
        if (tag.hasUUID("denOwner")) d.denOwner = tag.getUUID("denOwner");
        if (tag.hasUUID("denRageTarget")) d.denRageTarget = tag.getUUID("denRageTarget");
        if (tag.hasUUID("denGuardianId")) d.denGuardianId = tag.getUUID("denGuardianId");
        d.denCooldownUntil = tag.getLong("denCooldownUntil");
        d.denBuildVersion = tag.contains("denBuildVersion") ? tag.getInt("denBuildVersion") : 0;
        d.denBorrowedRoomBuilt = tag.getBoolean("denBorrowedRoomBuilt");
        d.denRemodelPending = tag.getBoolean("denRemodelPending");
        d.denLastVisitAt = tag.contains("denLastVisitAt") ? tag.getLong("denLastVisitAt") : Long.MIN_VALUE;
        d.denLastRemodelAt = tag.contains("denLastRemodelAt") ? tag.getLong("denLastRemodelAt") : Long.MIN_VALUE;
        d.denRemodelCount = Math.max(0, Math.min(5, tag.getInt("denRemodelCount")));
        CompoundTag pt = tag.getCompound("profiles");
        for (String key : pt.getAllKeys()) {
            try {
                d.profiles.put(UUID.fromString(key), PlayerProfile.load(pt.getCompound(key)));
            } catch (IllegalArgumentException ignored) { }
        }
        ListTag tl = tag.getList("traps", Tag.TAG_COMPOUND);
        for (int i = 0; i < tl.size(); i++) {
            CompoundTag c = tl.getCompound(i);
            d.traps.add(new Trap(c.getString("d"), c.getLong("p")));
        }
        ListTag pl = tag.getList("pits", Tag.TAG_COMPOUND);
        for (int i = 0; i < pl.size(); i++) {
            CompoundTag c = pl.getCompound(i);
            d.pits.add(new Pit(c.getString("d"), net.minecraft.core.BlockPos.of(c.getLong("p")), c.getInt("n")));
        }
        ListTag bl = tag.getList("birds", Tag.TAG_COMPOUND);
        for (int i = 0; i < bl.size(); i++) {
            CompoundTag c = bl.getCompound(i);
            if (c.hasUUID("o") && c.hasUUID("b")) d.birds.put(c.getUUID("o"), c.getUUID("b"));
        }
        ListTag sl = tag.getList("birdSeen", Tag.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) {
            CompoundTag c = sl.getCompound(i);
            if (c.hasUUID("o")) d.birdSeen.add(c.getUUID("o"));
        }
        ListTag trophyList = tag.getList("stolenTrophies", Tag.TAG_COMPOUND);
        for (int i = 0; i < trophyList.size() && d.stolenTrophies.size() < 128; i++) {
            CompoundTag c = trophyList.getCompound(i);
            ItemStack stack = ItemStack.of(c.getCompound("stack"));
            if (stack.isEmpty()) continue;
            UUID id = c.hasUUID("id") ? c.getUUID("id") : UUID.randomUUID();
            UUID owner = c.hasUUID("owner") ? c.getUUID("owner") : null;
            d.stolenTrophies.add(new StolenTrophy(id, owner, stack));
        }
        return d;
    }
}
