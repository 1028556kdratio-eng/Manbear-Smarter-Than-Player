package com.manbear.voice;

/** Squeezes a phrase to 8 kHz mu-law (1 byte per sample) so it can be sent over the game connection, and back. */
public final class VoiceCodec {
    public static final int SHARE_RATE = 8000;

    private VoiceCodec() { }

    /** 16 kHz 16-bit -> 8 kHz mu-law */
    public static byte[] encode(short[] pcm16k) {
        byte[] out = new byte[pcm16k.length / 2];
        for (int i = 0; i < out.length; i++) {
            int avg = (pcm16k[2 * i] + pcm16k[2 * i + 1]) / 2;   // crude 2:1 low-pass + decimate
            out[i] = linearToUlaw(avg);
        }
        return out;
    }

    public static short[] decode(byte[] ulaw) {
        short[] out = new short[ulaw.length];
        for (int i = 0; i < out.length; i++) out[i] = ulawToLinear(ulaw[i]);
        return out;
    }

    private static byte linearToUlaw(int sample) {
        int s = sample;
        int sign = (s >> 8) & 0x80;
        if (sign != 0) s = -s;
        if (s > 32635) s = 32635;
        s += 132;
        int exp = 7;
        for (int mask = 0x4000; (s & mask) == 0 && exp > 0; mask >>= 1) exp--;
        int mant = (s >> (exp + 3)) & 0x0F;
        return (byte) ~(sign | (exp << 4) | mant);
    }

    private static short ulawToLinear(byte u) {
        int v = ~u & 0xFF;
        int sign = v & 0x80;
        int exp = (v >> 4) & 7;
        int mant = v & 0x0F;
        int s = (((mant << 3) + 132) << exp) - 132;
        return (short) (sign != 0 ? -s : s);
    }
}
