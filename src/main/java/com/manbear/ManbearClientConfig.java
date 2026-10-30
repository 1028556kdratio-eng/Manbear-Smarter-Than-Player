package com.manbear;

import net.minecraftforge.common.ForgeConfigSpec;

/** Settings that only exist on your own computer. */
public class ManbearClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue MIC_SAMPLING;
    public static final ForgeConfigSpec.BooleanValue SHARE_VOICE;
    public static final ForgeConfigSpec.BooleanValue SPEECH_RECOGNITION;
    public static final ForgeConfigSpec.BooleanValue POSITIONAL_VOICE_WORDS;
    public static final ForgeConfigSpec.BooleanValue SCREAM_DETECTION;
    public static final ForgeConfigSpec.IntValue SCREAM_RMS_THRESHOLD;
    public static final ForgeConfigSpec.IntValue SCREAM_MIN_DURATION_MS;
    public static final ForgeConfigSpec.IntValue MIC_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue VOICE_PITCH;
    public static final ForgeConfigSpec.DoubleValue VOICE_VOLUME;
    // fog (update 20)
    public static final ForgeConfigSpec.BooleanValue FOG_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FOG_THICKNESS;
    public static final ForgeConfigSpec.DoubleValue FOG_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FOG_START;
    public static final ForgeConfigSpec.ConfigValue<String> FOG_COLOR;
    public static final ForgeConfigSpec.DoubleValue FOG_COLOR_BLEND;
    public static final ForgeConfigSpec.DoubleValue FOG_BRIGHTNESS;
    public static final ForgeConfigSpec.BooleanValue FOG_TIME_OF_DAY;
    public static final ForgeConfigSpec.BooleanValue FOG_WEATHER;
    public static final ForgeConfigSpec.BooleanValue FOG_BIOMES;
    public static final ForgeConfigSpec.BooleanValue FOG_ALTITUDE;
    public static final ForgeConfigSpec.BooleanValue FOG_OTHER_DIMENSIONS;
    public static final ForgeConfigSpec.DoubleValue FOG_SHADER_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue FOG_PARTICLES_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FOG_PARTICLE_DENSITY;
    public static final ForgeConfigSpec.DoubleValue FOG_PARTICLE_OPACITY;
    public static final ForgeConfigSpec.DoubleValue FOG_PARTICLE_MIN_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FOG_PARTICLE_FADE_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FOG_PARTICLE_MAX_DISTANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        MIC_SAMPLING = b.comment("Listen to your microphone while in a world. Short speech fragments are kept in RAM only and shuffled into new Manbear audio collages; no transcript or audio file is saved. Set false to disable microphone sampling.").define("micSampling", true);
        SHARE_VOICE = b.comment("OFF by default. If enabled, short recordings from your microphone are sent to the multiplayer server and kept in server RAM so the Manbear can mimic your voice to other players. Only enable this on servers where you are comfortable sharing your voice. Toggle with /manbearvoice share on|off.").define("shareVoiceWithServer", false);
        SPEECH_RECOGNITION = b.comment("Windows only: uses the speech recognition built into Windows (nothing is downloaded, nothing leaves your PC) to hear you say 'man bear' or 'bear'. Saying it enrages him and may trigger a chase, fake chase or despawn.").define("speechRecognition", true);
        POSITIONAL_VOICE_WORDS = b.comment("Windows only: recognizes a small allowlist of position words locally and keeps the actual word-audio snippets in RAM for directional callouts. The word samples and transcript stay on this PC; the server receives only a phrase code. Requires micSampling and speechRecognition.").define("positionalVoiceWords", true);
        SCREAM_DETECTION = b.comment("Optional local loud-vocal-burst detector. It counts sustained high-volume microphone bursts; it cannot reliably distinguish a scream from very loud speech, laughter, or other loud sounds. Only a numeric event count is sent to the server; this detector does not create or upload a separate scream recording. The separate shareVoiceWithServer option, if explicitly enabled, still shares the normal voice-collage snippets. OFF by default; enable with /manbearvoice screams on. Requires micSampling.").define("screamDetection", false);
        SCREAM_RMS_THRESHOLD = b.comment("Local microphone RMS threshold for a loud-vocal-burst event. Increase this to reduce false positives.").defineInRange("screamRmsThreshold", 9500, 2500, 28000);
        SCREAM_MIN_DURATION_MS = b.comment("How long microphone audio must remain above the threshold before one loud-vocal event is counted.").defineInRange("screamMinDurationMs", 220, 100, 1200);
        MIC_THRESHOLD = b.comment("How loud you must be (0-32767) before a phrase is recorded. Raise it if background noise gets recorded.").defineInRange("micThreshold", 450, 50, 20000);
        VOICE_PITCH = b.comment("Playback pitch. Below 1 = deeper and creepier.").defineInRange("voicePitch", 0.88, 0.5, 1.5);
        VOICE_VOLUME = b.defineInRange("voiceVolume", 1.0, 0.0, 4.0);
        b.push("fog");
        FOG_ENABLED = b.comment("Atmospheric fog (only on your computer). It always scales with YOUR render distance. Change any of these in game with /manbearfog.").define("enabled", true);
        FOG_THICKNESS = b.comment("How thick the fog is. 1.0 = default, 2.0 = twice as thick (you see about half as far), 0.5 = thin mist.").defineInRange("thickness", 1.0, 0.1, 5.0);
        FOG_DISTANCE = b.comment("How close the fog is. Scales the distance at which it is fully opaque, as a fraction of your render distance. 1.0 = default, 0.5 = fog closes in at half the distance, 2.0 = very far away.").defineInRange("distance", 1.0, 0.2, 2.0);
        FOG_START = b.comment("Where the fog starts, as a fraction of where it ends. 0 = it begins right in front of your face (a wall of haze), 0.2 = default, 0.8 = clear until it suddenly closes in.").defineInRange("startFraction", 0.20, 0.0, 0.9);
        FOG_COLOR = b.comment("Fog colour as a hex RGB value, e.g. #B4BEC8 (a cool grey-white). It is darkened automatically at night and in rain.").define("color", "#B4BEC8", o -> o instanceof String str && str.matches("#?[0-9a-fA-F]{6}"));
        FOG_COLOR_BLEND = b.comment("How much of your colour is used. 0 = keep the normal sky-coloured fog, 1 = always exactly your colour. In between looks the most natural.").defineInRange("colorBlend", 0.65, 0.0, 1.0);
        FOG_BRIGHTNESS = b.comment("Brightness of the fog colour (1.0 = unchanged, 0.5 = darker and gloomier, 1.5 = glowing).").defineInRange("brightness", 1.0, 0.0, 2.0);
        FOG_TIME_OF_DAY = b.comment("Realism: thickest at sunrise (morning mist), a little at dusk and through the night, thinnest at midday.").define("timeOfDay", true);
        FOG_WEATHER = b.comment("Realism: rain and thunderstorms thicken the fog.").define("weather", true);
        FOG_BIOMES = b.comment("Realism: swamps, dark forests, taigas, forests and rivers are foggier; deserts and badlands are clearer.").define("biomes", true);
        FOG_ALTITUDE = b.comment("Realism: the fog thins out as you climb above sea level, like ground fog.").define("altitude", true);
        FOG_OTHER_DIMENSIONS = b.comment("Also apply the fog in the Nether and the End (normally only the Overworld).").define("otherDimensions", false);
        FOG_SHADER_MULTIPLIER = b.comment("Extra multiplier on the thickness while a shader pack (Iris, Oculus, OptiFine) is running. Shader packs draw some of their own fog, so raise this if the fog looks too thin with shaders, lower it if too thick.").defineInRange("shaderMultiplier", 1.0, 0.2, 4.0);
        FOG_PARTICLES_ENABLED = b.comment("Render textured, drifting fog wisps in the world in addition to distance fog.").define("particlesEnabled", true);
        FOG_PARTICLE_DENSITY = b.comment("How often world fog particles are emitted. 1.0 = default, higher values make the mist fuller. Can also be changed with /manbearfog particle-density.").defineInRange("particleDensity", 1.0, 0.25, 3.0);
        FOG_PARTICLE_OPACITY = b.comment("Opacity of each textured fog wisp. Lower values blend into the landscape more naturally.").defineInRange("particleOpacity", 0.28, 0.02, 0.5);
        FOG_PARTICLE_MIN_DISTANCE = b.comment("Minimum distance in blocks from the camera to the visible edge of each fog particle. Wisps fade out as you approach this boundary. Default 10. Can also be changed with /manbearfog particle-min-distance.").defineInRange("particleMinDistance", 10.0, 0.0, 32.0);
        FOG_PARTICLE_FADE_DISTANCE = b.comment("How many blocks before the minimum particle distance fog wisps begin smoothly fading out. Larger values make the fade begin farther away. Default 8. Can also be changed with /manbearfog particle-fade-distance.").defineInRange("particleFadeDistance", 8.0, 1.0, 24.0);
        FOG_PARTICLE_MAX_DISTANCE = b.comment("Maximum horizontal spawn distance in blocks from the player for textured fog wisps. Wisps are distributed across the whole area between the protected inner radius and this outer radius. Default 48. Can also be changed with /manbearfog particle-max-distance.").defineInRange("particleMaxDistance", 48.0, 16.0, 128.0);
        b.pop();
        SPEC = b.build();
    }
}
