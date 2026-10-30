package com.manbear.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Which clips does the CURRENT animation file actually contain? Optional clips (Intimidation, leaf walk / run) are only
 * played when they exist, so the mod never falls into a T-pose because a clip name is missing. Names are matched ignoring
 * upper/lower case (Blockbench exports whatever you typed), and the file's own spelling is what gets played.
 */
public final class AnimClips {
    private static Map<String, String> names;      // lower-case -> real spelling

    private AnimClips() { }

    /** the clip's real name in the file, or null if the file does not have it */
    public static String find(String clip) {
        if (names == null) names = load();
        return names.get(clip.toLowerCase());
    }

    /** forget the cache (resource reload / leaving a world) */
    public static void reload() { names = null; }

    private static Map<String, String> load() {
        Map<String, String> out = new HashMap<>();
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(new ResourceLocation("manbear", "animations/manbear.animation.json"));
            if (res.isPresent()) {
                try (Reader r = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                    JsonObject anims = root.getAsJsonObject("animations");
                    if (anims != null) for (String k : anims.keySet()) out.put(k.toLowerCase(), k);
                }
            }
        } catch (Exception ignored) {
            // no file / unreadable: no optional clips
        }
        return out;
    }
}
