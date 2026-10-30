package com.manbear.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Server-side, RAM only: phrases that players have chosen to share. Dropped when the player logs out
 * or the server stops. Never written to disk.
 */
public final class VoiceBank {
    private static final int MAX_CLIPS_PER_PLAYER = 12;
    private static final int MAX_CHUNKS = 4;
    private static final int MAX_CHUNK_BYTES = 20000;

    private static final class Assembly {
        final byte[][] parts;
        int got;
        final long created = System.currentTimeMillis();
        Assembly(int n) { parts = new byte[n][]; }
    }

    private static final Map<UUID, List<byte[]>> CLIPS = new HashMap<>();
    private static final Map<String, Assembly> BUILDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_START = new HashMap<>();

    private VoiceBank() { }

    public static synchronized void addChunk(UUID player, long clipId, int index, int total, byte[] data) {
        if (total < 1 || total > MAX_CHUNKS || index < 0 || index >= total || data == null
                || data.length == 0 || data.length > MAX_CHUNK_BYTES) return;
        long now = System.currentTimeMillis();
        for (Iterator<Assembly> it = BUILDING.values().iterator(); it.hasNext(); ) {
            if (now - it.next().created > 30000) it.remove();   // stale half-finished uploads
        }
        String key = player + ":" + clipId;
        Assembly a = BUILDING.get(key);
        if (a == null) {
            Long last = LAST_START.get(player);
            if (last != null && now - last < 2000) return;       // rate limit: one phrase per 2 s
            LAST_START.put(player, now);
            a = new Assembly(total);
            BUILDING.put(key, a);
        }
        if (a.parts.length != total || a.parts[index] != null) return;
        a.parts[index] = data;
        if (++a.got < total) return;
        BUILDING.remove(key);
        int len = 0;
        for (byte[] p : a.parts) len += p.length;
        byte[] clip = new byte[len];
        int pos = 0;
        for (byte[] p : a.parts) { System.arraycopy(p, 0, clip, pos, p.length); pos += p.length; }
        List<byte[]> list = CLIPS.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(clip);
        while (list.size() > MAX_CLIPS_PER_PLAYER) list.remove(0);
    }

    /** a random shared phrase from anyone except {@code exclude}, or null */
    public static synchronized byte[] randomFromOthers(UUID exclude, Random rnd) {
        List<byte[]> pool = new ArrayList<>();
        for (Map.Entry<UUID, List<byte[]>> e : CLIPS.entrySet()) {
            if (!e.getKey().equals(exclude)) pool.addAll(e.getValue());
        }
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    public static synchronized void forget(UUID player) {
        CLIPS.remove(player);
        LAST_START.remove(player);
        BUILDING.keySet().removeIf(k -> k.startsWith(player.toString()));
    }

    public static synchronized void clear() {
        CLIPS.clear();
        BUILDING.clear();
        LAST_START.clear();
    }
}
