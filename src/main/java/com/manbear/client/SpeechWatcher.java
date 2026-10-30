package com.manbear.client;

import com.manbear.ManbearClientConfig;
import com.manbear.ManbearMod;
import com.manbear.net.ManbearNet;
import com.manbear.net.SaidNamePacket;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Hears you say "man bear" / "bear". Uses the speech recognizer that is BUILT INTO WINDOWS (System.Speech),
 * run through PowerShell: nothing is downloaded, the audio never leaves your PC, and only the fact that you said
 * the word is reported to the server. Windows only; on other systems this does nothing.
 */
public final class SpeechWatcher {
    private static volatile boolean running;
    private static volatile Process process;
    private static boolean hookInstalled;

    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "Add-Type -AssemblyName System.Speech",
            "$engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine",
            "$engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))",
            "$engine.SetInputToDefaultAudioDevice()",
            "$null = Register-ObjectEvent -InputObject $engine -EventName SpeechRecognized -Action {",
            "  $r = $Event.SourceEventArgs.Result",
            "  $t = $r.Text",
            "  if ($t -match '(?i)\\bman ?bear\\b|\\bbear\\b') { [Console]::Out.WriteLine('HEARD ' + $t); [Console]::Out.Flush() }",
            "  $utteranceId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()",
            "  foreach ($w in $r.Words) {",
            "    $token = ([string]$w.Text).ToLowerInvariant() -replace '[^a-z]', ''",
            "    if (@('behind','you','outside','your','house','to','left','right','in','front','of') -contains $token -and $w.Confidence -ge 0.55) {",
            "      try {",
            "        $audio = $r.GetAudioForWordRange($w, $w)",
            "        $memory = New-Object System.IO.MemoryStream",
            "        $audio.WriteToWaveStream($memory)",
            "        $encoded = [Convert]::ToBase64String($memory.ToArray())",
            "        [Console]::Out.WriteLine('WORD|' + $token + '|' + $utteranceId + '|' + $encoded)",
            "        [Console]::Out.Flush()",
            "        $memory.Dispose()",
            "      } catch { }",
            "    }",
            "  }",
            "}",
            "$engine.RecognizeAsync([System.Speech.Recognition.RecognizeMode]::Multiple)",
            "while ($true) { Start-Sleep -Milliseconds 300 }");

    private SpeechWatcher() { }

    public static synchronized void start() {
        if (running || !ManbearClientConfig.SPEECH_RECOGNITION.get()) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            ManbearMod.LOGGER.info("Manbear: speech recognition only works on Windows, skipping");
            return;
        }
        running = true;
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(SpeechWatcher::stop));
        }
        Thread t = new Thread(SpeechWatcher::run, "manbear-speech");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        running = false;
        Process p = process;
        if (p != null) p.destroy();
    }

    private static void run() {
        try {
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            process = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith("HEARD ")) {
                        heard();
                    } else if (line.startsWith("WORD|") && line.length() < 96_000) {
                        captureWord(line);
                    }
                }
            }
        } catch (Throwable t) {
            ManbearMod.LOGGER.info("Manbear: speech recognition unavailable ({})", t.toString());
        } finally {
            running = false;
            Process p = process;
            if (p != null) p.destroy();
        }
    }

    private static void captureWord(String line) {
        if (!ManbearClientConfig.MIC_SAMPLING.get() || !ManbearClientConfig.POSITIONAL_VOICE_WORDS.get()) return;
        try {
            String[] parts = line.split("\\|", 4);
            if (parts.length != 4) return;
            String word = parts[1];
            long utteranceId = Long.parseLong(parts[2]);
            byte[] wav = Base64.getDecoder().decode(parts[3]);
            if (wav.length > 96_000) return;
            MicSampler.addRecognizedWord(word, utteranceId, wav);
        } catch (Throwable ignored) {
            // A malformed or truncated local recognizer result is discarded without affecting the game.
        }
    }

    private static void heard() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.getConnection() != null && mc.player != null) ManbearNet.CHANNEL.sendToServer(new SaidNamePacket());
        });
    }
}
