package com.ikoras;

import android.content.Context;
import android.media.audiofx.DynamicsProcessing;
import android.os.SystemClock;
import android.util.Log;

/**
 * The one DynamicsProcessing on the whole output (session 0), shared by the two halves of
 * ikoras: the fine volume of the volume keys (its input gain, as volzz did) and the
 * equalizer while it works on the whole output (its pre-EQ, and the compressor and limiter
 * of BASS; YouTube plays, or whole-output mode).
 *
 * Why one: Android makes one engine per effect type and session, and the newest handle of
 * equal priority takes control of it (measured on the AQUOS SH-M06). As two apps, volzz and
 * ikora-lite could not both use it: whoever came second reset the other's settings, and
 * ikora-lite's fallback (an Equalizer on session 0) is suspended by Android while any player
 * has an effect of its own, without telling the app (XQ-FS44, AQUOS sense4 plus: -15 dB asked,
 * -0.03 dB measured). A DynamicsProcessing is not suspended from Android 11, and one of them
 * carrying both an input gain of -6 dB and a pre-EQ of -6 dB gave -11.89 dB on the sense4
 * plus, still -12.14 dB with a player's own effect enabled.
 *
 * Main thread only, like the rest of the audio state.
 */
final class Mix {

    private static DynamicsProcessing dp;
    private static boolean forVolume;
    private static boolean forEq;
    /** The fine volume's gain, kept to rebuild the engine and to read back. */
    private static float gainDb;
    /** Why the engine could not be had, or null. */
    static String error;
    /** Another app held session 0 at this time: do not try on every key press. */
    private static long refusedAt;
    private static final long RETRY_MS = 10_000;

    private Mix() {}

    static DynamicsProcessing dp() {
        return dp;
    }

    /**
     * The engine, made on first use with every stage either half needs, so neither has to
     * rebuild it (which would reset the other's values). Null while another app holds it.
     */
    static DynamicsProcessing acquire(Context c, boolean volume) {
        if (volume) forVolume = true;
        else forEq = true;
        if (dp != null) return dp;
        if (refusedAt != 0 && SystemClock.elapsedRealtime() - refusedAt < RETRY_MS) return null;
        if (Eq.dpTaken(Eq.GLOBAL)) {
            refusedAt = SystemClock.elapsedRealtime();
            if (!Eq.GLOBAL_TAKEN.equals(error) && c != null) Diag.note(c, "全体: " + Eq.GLOBAL_TAKEN);
            error = Eq.GLOBAL_TAKEN;
            return null;
        }
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, Eq.N, true, 2, false, 0, true).build();
            cfg.setPreEqAllChannelsTo(new DynamicsProcessing.Eq(true, true, Eq.N));
            for (int i = 0; i < Eq.N; i++) {
                cfg.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, Eq.cutoff(i), 0f));
            }
            cfg.setMbcAllChannelsTo(new DynamicsProcessing.Mbc(true, false, 2));
            cfg.setMbcBandAllChannelsTo(0, Eq.lowBand(0));
            cfg.setMbcBandAllChannelsTo(1, Eq.passBand(20000f));
            cfg.setLimiterAllChannelsTo(Eq.limiter(0));
            cfg.setInputGainAllChannelsTo(gainDb);
            DynamicsProcessing d = new DynamicsProcessing(0, Eq.GLOBAL, cfg);
            d.setEnabled(true);
            Context app = c == null ? null : c.getApplicationContext();
            d.setControlStatusListener((fx, granted) -> onControl(app, granted));
            dp = d;
            error = null;
            refusedAt = 0;
            if (c != null) Diag.note(c, "全体: DynamicsProcessing を用意した（" + (volume ? "細かい音量" : "イコライザ") + "）");
            return dp;
        } catch (RuntimeException e) {
            Eq.collectOrphans();
            error = String.valueOf(e.getMessage());
            refusedAt = SystemClock.elapsedRealtime();
            if (c != null) Diag.note(c, "全体: DynamicsProcessing を作れない: " + e);
            return null;
        }
    }

    /** One half is done with it; the engine goes when both are. */
    static void release(boolean volume) {
        if (volume) {
            forVolume = false;
            gainDb = 0f;
        } else {
            forEq = false;
        }
        DynamicsProcessing d = dp;
        if (d == null) return;
        try {
            if (volume) {
                d.setInputGainAllChannelsTo(0f);
            } else {
                for (int i = 0; i < Eq.N; i++) {
                    d.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, Eq.cutoff(i), 0f));
                }
                Eq.applyBass(d, 0);
            }
        } catch (RuntimeException ignored) {
            // Lost control meanwhile: nothing of ours applies then.
        }
        if (forVolume || forEq) return;
        dp = null;
        try {
            d.setEnabled(false);
        } catch (RuntimeException ignored) {
        }
        d.release();
    }

    static boolean usedForEq() {
        return forEq && dp != null;
    }

    /** The fine volume's gain (dB, 0 or below). Tries again if another app had held it. */
    static void setGain(float db) {
        gainDb = db;
        DynamicsProcessing d = dp != null ? dp : (forVolume ? acquire(null, true) : null);
        if (d == null) return;
        try {
            d.setInputGainAllChannelsTo(db);
        } catch (RuntimeException e) {
            Log.w(Eq.TAG, "gain not set: " + e.getMessage());
        }
    }

    static float readGain() {
        DynamicsProcessing d = dp;
        if (d == null) return Float.NaN;
        try {
            return d.getInputGainByChannelIndex(0);
        } catch (RuntimeException e) {
            return Float.NaN;
        }
    }

    /**
     * Another app took the engine (a higher priority, or joined later): keep out of its
     * settings. Both halves lose it; the equalizer goes back to the players' own effects, the
     * volume keys to whole hardware steps until it is free again (tried on the next key).
     */
    private static void onControl(Context app, boolean granted) {
        DynamicsProcessing d = dp;
        if (d == null) return;
        if (granted) {
            try {
                d.setInputGainAllChannelsTo(gainDb);
            } catch (RuntimeException ignored) {
            }
            if (app != null) Eq.globalRegained(app, d);
            return;
        }
        dp = null;
        error = Eq.GLOBAL_TAKEN;
        refusedAt = SystemClock.elapsedRealtime();
        d.release();
        if (app != null) {
            Diag.note(app, "全体: DynamicsProcessing をほかのアプリに取られたので手放す");
            Eq.globalLost(app);
        }
    }
}
