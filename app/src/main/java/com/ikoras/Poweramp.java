package com.ikoras;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Poweramp tells its audio session only when its "MusicFX" button (Tone/Vol screen) is pressed,
 * and only with its "MusicFX" setting (key allow_platform_fx) on, off by default: then it sends
 * OPEN_AUDIO_EFFECT_CONTROL_SESSION and opens the device's equalizer panel with the session,
 * which ikoras is (read in build-1031; nothing is sent on playing alone, measured on the
 * XQ-FS44). Opened so, ikoras shapes that session like YT Music's: -10 dB was heard for a
 * -12 dB curve while Poweramp played. Here: knowing it plays without ikoras, for the screen.
 */
final class Poweramp {
    static final String PKG = "com.maxmpz.audioplayer";

    /** Poweramp's media session says it plays (from {@link PlayingListener}). */
    private static boolean playing;

    private Poweramp() {}

    static void setPlaying(Context c, boolean now) {
        if (now == playing) return;
        playing = now;
        Eq.refreshScreens();
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getApplicationInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether Poweramp ever handed ikoras a session: the MusicFX way has been found. */
    static boolean seen(Context c) {
        return Eq.prefs(c).getBoolean("poweramp_seen", false);
    }

    static void markSeen(Context c) {
        if (!seen(c)) Eq.prefs(c).edit().putBoolean("poweramp_seen", true).apply();
    }

    /** Poweramp plays, and nothing of ikoras is on it: no session from it, no whole output. */
    static boolean missed(Context c) {
        return playing && Eq.isOn(c) && !Eq.usesGlobal(c) && !Eq.sessions.containsValue(PKG);
    }
}
