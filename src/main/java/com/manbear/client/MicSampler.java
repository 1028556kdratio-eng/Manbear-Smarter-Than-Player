package com.manbear.client;

import com.manbear.ManbearClientConfig;
import com.manbear.ManbearMod;
import com.manbear.voice.VoiceCodec;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listens to the local microphone while the player is in a world. Speech is kept in RAM only,
 * segmented into short audio fragments, and shuffled to make the Manbear's voice collage.
 * Nothing is written to disk. Sharing fragments with a multiplayer server is a separate opt-in.
 */
public final class MicSampler {
    public static final int RATE = 16000;
    private static final int CHUNK_BYTES = 3200; // 100 ms of 16 kHz mono, signed 16-bit PCM
    private static final int MAX_FRAGMENTS = 48;
    private static final int FRAME_SAMPLES = RATE / 50; // 20 ms energy frames
    private static final int MIN_FRAGMENT_SAMPLES = RATE * 18 / 100; // 180 ms
    private static final int MAX_FRAGMENT_SAMPLES = RATE * 92 / 100; // 920 ms
    private static final int TARGET_FRAGMENT_SAMPLES = RATE * 48 / 100; // ~480 ms
    private static final int SENTENCE_GAP_MIN_SAMPLES = RATE * 7 / 100; // 70 ms
    private static final int SENTENCE_GAP_RANGE_SAMPLES = RATE * 11 / 100; // up to 180 ms
    private static final int EDGE_FADE_SAMPLES = RATE / 100; // 10 ms click-prevention fade
    private static final int MAX_WORD_SAMPLES_PER_TOKEN = 8;
    private static final int MAX_WORD_SAMPLE_SECONDS = 2;

    private static final List<VoiceFragment> FRAGMENTS = new ArrayList<>();
    private static final Map<String, List<WordFragment>> WORD_FRAGMENTS = new HashMap<>();
    private static final AtomicLong PHRASE_IDS = new AtomicLong(1L);
    private static final Random RANDOM = new Random();
    private static final ConcurrentLinkedQueue<byte[]> UPLOADS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Integer> SCREAM_EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger LOCAL_SCREAM_COUNT = new AtomicInteger();
    private static volatile boolean running;

    private MicSampler() { }

    public static synchronized void start() {
        if (running || !ManbearClientConfig.MIC_SAMPLING.get()) return;
        running = true;
        Thread t = new Thread(MicSampler::run, "manbear-mic");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        running = false;
        UPLOADS.clear();
        synchronized (FRAGMENTS) {
            FRAGMENTS.clear(); // audio snippets never outlive the current session
        }
        synchronized (WORD_FRAGMENTS) {
            WORD_FRAGMENTS.clear(); // recognized word audio never outlives the current session
        }
        SCREAM_EVENTS.clear();
        LOCAL_SCREAM_COUNT.set(0);
    }

    public static boolean isRunning() { return running; }

    public static int fragmentCount() {
        synchronized (FRAGMENTS) { return FRAGMENTS.size(); }
    }

    /** Number of distinct speech moments represented in the current in-memory buffer. */
    public static int phraseCount() {
        synchronized (FRAGMENTS) {
            java.util.HashSet<Long> ids = new java.util.HashSet<>();
            for (VoiceFragment fragment : FRAGMENTS) ids.add(fragment.phraseId);
            return ids.size();
        }
    }

    /** Number of locally captured recognized word-audio samples, not transcripts. */
    public static int wordSampleCount() {
        synchronized (WORD_FRAGMENTS) {
            int count = 0;
            for (List<WordFragment> list : WORD_FRAGMENTS.values()) count += list.size();
            return count;
        }
    }

    public static int recognizedWordCount() {
        synchronized (WORD_FRAGMENTS) { return WORD_FRAGMENTS.size(); }
    }

    public static void clearDirectionalWords() {
        synchronized (WORD_FRAGMENTS) { WORD_FRAGMENTS.clear(); }
    }

    public static int localScreamCount() { return LOCAL_SCREAM_COUNT.get(); }

    /** Server notifications contain only a numeric event, never audio. */
    public static boolean pollScreamEvent() { return SCREAM_EVENTS.poll() != null; }

    /** One original phrase for the optional multiplayer-sharing path. */
    public static byte[] pollUpload() { return UPLOADS.poll(); }

    /** Purges queued audio immediately when the player disables multiplayer voice sharing. */
    public static void clearPendingUploads() { UPLOADS.clear(); }

    /**
     * Adds the actual audio for one recognized cue word. Called by the Windows-local recognizer;
     * only a small allowlist is accepted and samples remain on this client in RAM.
     */
    public static void addRecognizedWord(String rawWord, long utteranceId, byte[] wavBytes) {
        if (!ManbearClientConfig.MIC_SAMPLING.get() || !ManbearClientConfig.POSITIONAL_VOICE_WORDS.get()) return;
        if (rawWord == null || wavBytes == null || wavBytes.length < 48 || wavBytes.length > 96_000) return;
        String word = rawWord.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (!isDirectionalWord(word)) return;
        short[] pcm = decodeWavePcm16(wavBytes);
        if (pcm == null || pcm.length < RATE / 12 || pcm.length > RATE * MAX_WORD_SAMPLE_SECONDS) return;
        normalizeSnippet(pcm, 22000.0, 2.2);
        synchronized (WORD_FRAGMENTS) {
            List<WordFragment> list = WORD_FRAGMENTS.computeIfAbsent(word, ignored -> new ArrayList<>());
            for (WordFragment existing : list) if (existing.utteranceId == utteranceId) return;
            list.add(new WordFragment(utteranceId, pcm));
            while (list.size() > MAX_WORD_SAMPLES_PER_TOKEN) list.remove(0);
        }
    }

    /**
     * Phrase codes: 0 behind you, 1 outside your house, 2 to your left, 3 to your right,
     * 4 in front of you, 5 front of you. Audio is assembled in grammatical order from actual words.
     */
    public static short[] directionalClip(int phraseCode) {
        List<String> words = switch (phraseCode) {
            case 0 -> List.of("behind", "you");
            case 1 -> List.of("outside", "your", "house");
            case 2 -> List.of("to", "your", "left");
            case 3 -> List.of("to", "your", "right");
            case 4 -> List.of("in", "front", "of", "you");
            case 5 -> List.of("front", "of", "you");
            default -> List.of();
        };
        if (words.isEmpty()) return null;
        List<WordFragment> chosen = new ArrayList<>();
        Set<Long> usedUtterances = new HashSet<>();
        synchronized (WORD_FRAGMENTS) {
            for (String word : words) {
                List<WordFragment> pool = WORD_FRAGMENTS.get(word);
                if ((pool == null || pool.isEmpty()) && phraseCode == 4 && "in".equals(word)) {
                    // If the player has supplied the other components, the shorter "front of you" is valid.
                    continue;
                }
                if (pool == null || pool.isEmpty()) return null;
                WordFragment selected = null;
                for (int attempt = 0; attempt < pool.size() * 2; attempt++) {
                    WordFragment candidate = pool.get(RANDOM.nextInt(pool.size()));
                    if (!usedUtterances.contains(candidate.utteranceId)) { selected = candidate; break; }
                }
                if (selected == null) selected = pool.get(RANDOM.nextInt(pool.size()));
                chosen.add(selected);
                usedUtterances.add(selected.utteranceId);
            }
        }
        if (chosen.isEmpty()) return null;
        List<short[]> pieces = new ArrayList<>();
        for (WordFragment fragment : chosen) pieces.add(fragment.samples);
        return joinOrderedWordSamples(pieces);
    }

    private static boolean isDirectionalWord(String word) {
        return switch (word) {
            case "behind", "you", "outside", "your", "house", "to", "left", "right", "in", "front", "of" -> true;
            default -> false;
        };
    }

    /** Parses a PCM WAV in memory, downmixes to mono, and resamples to the sampler's 16 kHz format. */
    private static short[] decodeWavePcm16(byte[] wav) {
        try {
            if (wav.length < 44 || !ascii(wav, 0, "RIFF") || !ascii(wav, 8, "WAVE")) return null;
            int format = 0, channels = 0, sampleRate = 0, bits = 0, dataOffset = -1, dataLength = 0;
            int pos = 12;
            while (pos + 8 <= wav.length) {
                String id = new String(wav, pos, 4, StandardCharsets.US_ASCII);
                long rawLength = Integer.toUnsignedLong(ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                int length = (int) Math.min(rawLength, wav.length - pos - 8L);
                int payload = pos + 8;
                if ("fmt ".equals(id) && length >= 16) {
                    ByteBuffer fmt = ByteBuffer.wrap(wav, payload, length).order(ByteOrder.LITTLE_ENDIAN);
                    format = Short.toUnsignedInt(fmt.getShort());
                    channels = Short.toUnsignedInt(fmt.getShort());
                    sampleRate = fmt.getInt();
                    fmt.getInt(); fmt.getShort();
                    bits = Short.toUnsignedInt(fmt.getShort());
                } else if ("data".equals(id)) {
                    dataOffset = payload; dataLength = length;
                }
                long next = (long) payload + length + (length & 1);
                if (next > Integer.MAX_VALUE || next <= pos) break;
                pos = (int) next;
            }
            if (format != 1 || channels < 1 || channels > 2 || bits != 16 || sampleRate < 8000 || sampleRate > 48000
                    || dataOffset < 0 || dataLength < 2 * channels) return null;
            int sourceFrames = dataLength / (2 * channels);
            int outputFrames = Math.max(1, (int) Math.round(sourceFrames * (double) RATE / sampleRate));
            short[] result = new short[outputFrames];
            ByteBuffer pcm = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < outputFrames; i++) {
                double sourceIndex = i * (double) sampleRate / RATE;
                int frameA = Math.min(sourceFrames - 1, (int) sourceIndex);
                int frameB = Math.min(sourceFrames - 1, frameA + 1);
                double fraction = sourceIndex - frameA;
                double a = readMonoFrame(pcm, dataOffset, frameA, channels);
                double b = readMonoFrame(pcm, dataOffset, frameB, channels);
                result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(a + (b - a) * fraction)));
            }
            return result;
        } catch (Throwable ignored) { return null; }
    }

    private static double readMonoFrame(ByteBuffer pcm, int dataOffset, int frame, int channels) {
        int offset = dataOffset + frame * channels * 2;
        int a = pcm.getShort(offset);
        if (channels == 1) return a;
        int b = pcm.getShort(offset + 2);
        return (a + b) * 0.5D;
    }

    private static boolean ascii(byte[] data, int offset, String value) {
        if (offset < 0 || offset + value.length() > data.length) return false;
        for (int i = 0; i < value.length(); i++) if (data[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static void normalizeSnippet(short[] samples, double targetPeak, double maxGain) {
        int peak = 1;
        for (short sample : samples) peak = Math.max(peak, Math.abs((int) sample));
        double gain = Math.min(maxGain, targetPeak / peak);
        for (int i = 0; i < samples.length; i++) samples[i] = (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(samples[i] * gain)));
    }

    private static short[] joinOrderedWordSamples(List<short[]> pieces) {
        if (pieces.isEmpty()) return null;
        int total = 0;
        int[] gaps = new int[Math.max(0, pieces.size() - 1)];
        for (short[] piece : pieces) total += piece.length;
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = RATE * (70 + RANDOM.nextInt(111)) / 1000;
            total += gaps[i];
        }
        short[] result = new short[total];
        int offset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            short[] piece = pieces.get(i);
            copyWithFade(piece, result, offset);
            offset += piece.length;
            if (i < gaps.length) offset += gaps[i];
        }
        return result;
    }

    /**
     * Builds a collage from several separate things the player said. Every collage uses snippets
     * from at least two distinct utterances; if the buffer only contains one utterance, wait for
     * another instead of replaying pieces from the same saying as though they were new sentences.
     */
    public static short[] randomClip() {
        List<VoiceFragment> snapshot;
        synchronized (FRAGMENTS) {
            if (FRAGMENTS.size() < 2) return null;
            snapshot = new ArrayList<>(FRAGMENTS);
        }

        Map<Long, List<VoiceFragment>> byPhrase = new LinkedHashMap<>();
        for (VoiceFragment fragment : snapshot) {
            byPhrase.computeIfAbsent(fragment.phraseId, ignored -> new ArrayList<>()).add(fragment);
        }
        if (byPhrase.size() < 2) return null;

        List<List<VoiceFragment>> sayings = new ArrayList<>(byPhrase.values());
        Collections.shuffle(sayings, RANDOM);
        int maxPieces = Math.min(4, snapshot.size());
        int targetPieces = 2 + RANDOM.nextInt(Math.max(1, maxPieces - 1)); // 2-4 snippets
        List<VoiceFragment> selected = new ArrayList<>(targetPieces);

        // Choose one snippet from each distinct saying first, guaranteeing cross-saying audio.
        for (List<VoiceFragment> saying : sayings) {
            if (selected.size() >= targetPieces) break;
            selected.add(saying.get(RANDOM.nextInt(saying.size())));
        }

        // If there were only two or three sayings in memory, fill the remaining slots with other
        // snippets, still without repeating a fragment in the same collage.
        if (selected.size() < targetPieces) {
            List<VoiceFragment> extras = new ArrayList<>(snapshot);
            Collections.shuffle(extras, RANDOM);
            for (VoiceFragment extra : extras) {
                if (selected.size() >= targetPieces) break;
                if (!selected.contains(extra)) selected.add(extra);
            }
        }

        List<short[]> samples = new ArrayList<>(selected.size());
        for (VoiceFragment fragment : selected) samples.add(fragment.samples);
        return spliceFragments(samples, samples.size(), samples.size(), RANDOM);
    }

    /** Reorders short fragments within one shared phrase for a remote-player voice echo. */
    public static short[] splicePhrase(short[] source) {
        if (source == null || source.length == 0) return null;
        List<short[]> pieces = splitIntoFragments(source);
        if (pieces.size() < 2) return source;
        return spliceFragments(pieces, 2, 4, RANDOM);
    }

    private static void run() {
        TargetDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false); // signed 16-bit little-endian mono
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                ManbearMod.LOGGER.info("Manbear: no usable microphone, voice sampling disabled");
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();

            byte[] buf = new byte[CHUNK_BYTES];
            List<short[]> current = new ArrayList<>();
            boolean talking = false;
            int silent = 0;
            long currentPhraseId = 0L;
            double floor = 150;
            int screamHighChunks = 0;
            int screamQuietChunks = 0;
            boolean screamLatched = false;
            long lastScreamNanos = 0L;
            while (running) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 1) continue;
                short[] pcm = new short[n / 2];
                double sum = 0;
                for (int i = 0; i < pcm.length; i++) {
                    short v = (short) ((buf[2 * i + 1] << 8) | (buf[2 * i] & 0xFF));
                    pcm[i] = v;
                    sum += (double) v * v;
                }
                double rms = Math.sqrt(sum / Math.max(1, pcm.length));
                if (ManbearClientConfig.SCREAM_DETECTION.get()) {
                    double screamThreshold = Math.max(ManbearClientConfig.SCREAM_RMS_THRESHOLD.get(), floor * 5.0D);
                    if (rms >= screamThreshold && rms >= floor * 5.0D) {
                        screamHighChunks++;
                        screamQuietChunks = 0;
                    } else {
                        screamHighChunks = 0;
                        if (++screamQuietChunks >= 5) screamLatched = false; // 500 ms quiet rearms the detector
                    }
                    int requiredChunks = Math.max(1, (ManbearClientConfig.SCREAM_MIN_DURATION_MS.get() + 99) / 100);
                    long nowNanos = System.nanoTime();
                    if (!screamLatched && screamHighChunks >= requiredChunks
                            && (lastScreamNanos == 0L || nowNanos - lastScreamNanos >= 15_000_000_000L)) {
                        if (SCREAM_EVENTS.size() < 3) SCREAM_EVENTS.add(1);
                        LOCAL_SCREAM_COUNT.incrementAndGet();
                        lastScreamNanos = nowNanos;
                        screamLatched = true;
                    }
                } else {
                    screamHighChunks = 0;
                    screamQuietChunks = 0;
                    screamLatched = false;
                }
                double threshold = Math.max(ManbearClientConfig.MIC_THRESHOLD.get(), floor * 3.5);
                if (rms > threshold) {
                    if (!talking) {
                        talking = true;
                        currentPhraseId = PHRASE_IDS.getAndIncrement();
                    }
                    silent = 0;
                    current.add(pcm);
                } else {
                    floor = floor * 0.98 + rms * 0.02; // gently learn room noise
                    if (talking) {
                        current.add(pcm);
                        if (++silent >= 7) { // 0.7 s of quiet ends the phrase
                            finish(current, currentPhraseId);
                            current = new ArrayList<>();
                            talking = false;
                            currentPhraseId = 0L;
                            silent = 0;
                        }
                    }
                }
                if (current.size() > 60) { // cap each audio chunk at ~6 s without pretending it was a new saying
                    finish(current, currentPhraseId);
                    current = new ArrayList<>();
                    // Keep the same phrase id and talking state until a real quiet gap ends the saying.
                }
            }
        } catch (Throwable t) {
            if (running) ManbearMod.LOGGER.info("Manbear: microphone sampling stopped ({})", t.toString());
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            running = false;
        }
    }

    private static void finish(List<short[]> chunks, long phraseId) {
        if (!running || chunks.size() < 5) return;
        int total = 0;
        for (short[] c : chunks) total += c.length;
        if (total < RATE / 3) return;

        short[] phrase = new short[total];
        int pos = 0;
        int peak = 1;
        for (short[] c : chunks) {
            for (short v : c) peak = Math.max(peak, Math.abs((int) v));
            System.arraycopy(c, 0, phrase, pos, c.length);
            pos += c.length;
        }
        double gain = Math.min(4.0, 24000.0 / peak);
        for (int i = 0; i < phrase.length; i++) {
            phrase[i] = (short) Math.max(-32768, Math.min(32767, Math.round(phrase[i] * gain)));
        }

        // The server receives a complete short phrase only if the user explicitly enabled sharing.
        // Local playback uses smaller fragments kept in this process's RAM.
        if (running && ManbearClientConfig.SHARE_VOICE.get() && UPLOADS.size() < 4) {
            UPLOADS.add(VoiceCodec.encode(phrase));
        }

        List<short[]> fragments = splitIntoFragments(phrase);
        synchronized (FRAGMENTS) {
            if (!running) return;
            for (short[] fragment : fragments) {
                FRAGMENTS.add(new VoiceFragment(phraseId, fragment));
                while (FRAGMENTS.size() > MAX_FRAGMENTS) FRAGMENTS.remove(RANDOM.nextInt(FRAGMENTS.size()));
            }
        }
    }

    /**
     * Splits speech at short low-energy gaps, then divides long continuous sections near energy valleys.
     * This is waveform-based (no transcription or external service), so fragments are word-like rather than
     * guaranteed word-aligned; the resulting collage deliberately sounds uncanny.
     */
    private static List<short[]> splitIntoFragments(short[] pcm) {
        List<short[]> out = new ArrayList<>();
        if (pcm == null || pcm.length < MIN_FRAGMENT_SAMPLES) return out;

        int frameCount = (pcm.length + FRAME_SAMPLES - 1) / FRAME_SAMPLES;
        double[] rms = new double[frameCount];
        double peakFrameRms = 0;
        for (int f = 0; f < frameCount; f++) {
            int from = f * FRAME_SAMPLES;
            int to = Math.min(pcm.length, from + FRAME_SAMPLES);
            double sum = 0;
            for (int i = from; i < to; i++) sum += (double) pcm[i] * pcm[i];
            rms[f] = Math.sqrt(sum / Math.max(1, to - from));
            peakFrameRms = Math.max(peakFrameRms, rms[f]);
        }
        double threshold = Math.max(160.0, peakFrameRms * 0.09);
        boolean[] active = new boolean[frameCount];
        for (int i = 0; i < frameCount; i++) active[i] = rms[i] >= threshold;

        // Fill brief energy dips (e.g. between consonants) but preserve actual pauses between phrases/words.
        int maxGapFrames = 3; // 60 ms
        for (int i = 0; i < active.length; ) {
            if (active[i]) { i++; continue; }
            int start = i;
            while (i < active.length && !active[i]) i++;
            int length = i - start;
            if (start > 0 && i < active.length && length <= maxGapFrames) {
                for (int j = start; j < i; j++) active[j] = true;
            }
        }

        List<int[]> ranges = new ArrayList<>();
        int startFrame = -1;
        for (int i = 0; i <= active.length; i++) {
            boolean on = i < active.length && active[i];
            if (on && startFrame < 0) startFrame = i;
            if (!on && startFrame >= 0) {
                int start = Math.max(0, startFrame * FRAME_SAMPLES - RATE / 40); // ~25 ms consonant padding
                int end = Math.min(pcm.length, i * FRAME_SAMPLES + RATE / 40);
                if (end > start) ranges.add(new int[]{start, end});
                startFrame = -1;
            }
        }

        // Join very short syllables to adjacent fragments instead of throwing them away.
        List<int[]> merged = new ArrayList<>();
        int pendingStart = -1;
        for (int[] range : ranges) {
            int s = pendingStart >= 0 ? pendingStart : range[0];
            int e = range[1];
            if (e - s < MIN_FRAGMENT_SAMPLES) {
                pendingStart = s;
                continue;
            }
            merged.add(new int[]{s, e});
            pendingStart = -1;
        }
        if (pendingStart >= 0) {
            if (!merged.isEmpty()) merged.get(merged.size() - 1)[1] = ranges.get(ranges.size() - 1)[1];
            else if (pcm.length - pendingStart >= MIN_FRAGMENT_SAMPLES) merged.add(new int[]{pendingStart, pcm.length});
        }

        for (int[] range : merged) {
            int start = range[0];
            int end = range[1];
            while (end - start > MAX_FRAGMENT_SAMPLES) {
                int desired = start + TARGET_FRAGMENT_SAMPLES;
                int lowSample = Math.max(start + MIN_FRAGMENT_SAMPLES, desired - RATE / 10);
                int highSample = Math.min(end - MIN_FRAGMENT_SAMPLES, desired + RATE / 10);
                int lowFrame = Math.max(0, lowSample / FRAME_SAMPLES);
                int highFrame = Math.min(frameCount - 1, highSample / FRAME_SAMPLES);
                int valleyFrame = Math.max(lowFrame, Math.min(highFrame, desired / FRAME_SAMPLES));
                double valleyRms = Double.MAX_VALUE;
                for (int f = lowFrame; f <= highFrame; f++) {
                    if (rms[f] < valleyRms) {
                        valleyRms = rms[f];
                        valleyFrame = f;
                    }
                }
                int cut = valleyFrame * FRAME_SAMPLES;
                if (cut - start < MIN_FRAGMENT_SAMPLES || end - cut < MIN_FRAGMENT_SAMPLES) cut = desired;
                cut = Math.max(start + MIN_FRAGMENT_SAMPLES, Math.min(end - MIN_FRAGMENT_SAMPLES, cut));
                addFragment(out, pcm, start, cut);
                start = cut;
            }
            if (end - start >= MIN_FRAGMENT_SAMPLES) addFragment(out, pcm, start, end);
        }
        return out;
    }

    private static void addFragment(List<short[]> out, short[] source, int start, int end) {
        start = Math.max(0, start);
        end = Math.min(source.length, end);
        if (end - start < MIN_FRAGMENT_SAMPLES) return;
        short[] fragment = new short[end - start];
        System.arraycopy(source, start, fragment, 0, fragment.length);

        int peak = 1;
        for (short sample : fragment) peak = Math.max(peak, Math.abs((int) sample));
        double gain = Math.min(2.0, 20000.0 / peak);
        for (int i = 0; i < fragment.length; i++) {
            fragment[i] = (short) Math.max(-32768, Math.min(32767, Math.round(fragment[i] * gain)));
        }
        out.add(fragment);
    }

    private static short[] spliceFragments(List<short[]> pool, int minPieces, int maxPieces, Random random) {
        if (pool == null || pool.isEmpty()) return null;
        if (pool.size() == 1) return copyAndFade(pool.get(0));

        List<short[]> choices = new ArrayList<>(pool);
        Collections.shuffle(choices, random);
        int count = Math.min(choices.size(), minPieces + random.nextInt(Math.max(1, Math.min(maxPieces, choices.size()) - minPieces + 1)));
        if (count < 2) count = 2;
        count = Math.min(count, choices.size());

        int total = 0;
        int[] gaps = new int[Math.max(0, count - 1)];
        for (int i = 0; i < count; i++) total += choices.get(i).length;
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = SENTENCE_GAP_MIN_SAMPLES + random.nextInt(SENTENCE_GAP_RANGE_SAMPLES + 1);
            total += gaps[i];
        }
        short[] result = new short[total];
        int offset = 0;
        for (int i = 0; i < count; i++) {
            short[] fragment = choices.get(i);
            copyWithFade(fragment, result, offset);
            offset += fragment.length;
            if (i < gaps.length) offset += gaps[i]; // leave a natural, eerie gap between snippets
        }
        return result;
    }

    private static short[] copyAndFade(short[] input) {
        short[] result = new short[input.length];
        copyWithFade(input, result, 0);
        return result;
    }

    private static void copyWithFade(short[] input, short[] output, int offset) {
        System.arraycopy(input, 0, output, offset, input.length);
        int fade = Math.min(EDGE_FADE_SAMPLES, input.length / 3);
        for (int i = 0; i < fade; i++) {
            double inGain = (double) i / Math.max(1, fade);
            double outGain = (double) (fade - i - 1) / Math.max(1, fade);
            int inIndex = offset + i;
            int outIndex = offset + input.length - fade + i;
            output[inIndex] = (short) (output[inIndex] * inGain);
            output[outIndex] = (short) (output[outIndex] * outGain);
        }
    }
    private static final class WordFragment {
        private final long utteranceId;
        private final short[] samples;
        private WordFragment(long utteranceId, short[] samples) { this.utteranceId = utteranceId; this.samples = samples; }
    }

    /** A waveform snippet tied to the separate utterance it came from. */
    private static final class VoiceFragment {
        private final long phraseId;
        private final short[] samples;

        private VoiceFragment(long phraseId, short[] samples) {
            this.phraseId = phraseId;
            this.samples = samples;
        }
    }

}
