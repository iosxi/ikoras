package com.ikoras;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Settings per output device. The same curve can be fine on the phone's speaker and distort
 * on one pair of headphones (reported on the Sony WH-1000XM5 over Bluetooth), so each output
 * keeps its own bands and BASS: changing them saves for the current output, and a change of
 * output loads what that output had. Bluetooth devices are told apart by name.
 * An output may also keep settings for one app (機器プリセット): YouTube's voices and YT Music's
 * songs want different curves on the same headphones. They apply while that app is the one
 * last seen playing; every other app gets the output's own.
 */
final class Outputs {

    private Outputs() {}

    /** An output, as a stable key for the store and a name for the screen. */
    static final class Out {
        final String key;
        final String label;

        Out(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("outputs", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return Eq.prefs(c).getBoolean("perOutput", true);
    }

    static void setEnabled(Context c, boolean on) {
        Eq.prefs(c).edit().putBoolean("perOutput", on).apply();
        Diag.note(c, on ? "出力機器ごとに覚える: ON" : "出力機器ごとに覚える: OFF");
        // From now on the current values belong to the current output.
        if (on) check(c);
    }

    /** The output whose settings are in the faders now, or null before the first check. */
    static String activeKey(Context c) {
        return Eq.prefs(c).getString("output", null);
    }

    static String activeLabel(Context c) {
        return Eq.prefs(c).getString("outputLabel", "");
    }

    // --- Which output plays music -----------------------------------------------------------

    /** Where music goes now. */
    static Out current(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        AudioDeviceInfo best = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: the route the system has actually chosen for music.
            List<AudioDeviceInfo> route = am.getAudioDevicesForAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (!route.isEmpty()) best = route.get(0);
        }
        if (best == null) {
            // Older: the connected output that Android prefers for music.
            int bestRank = -1;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int r = rank(d.getType());
                if (r > bestRank) {
                    bestRank = r;
                    best = d;
                }
            }
        }
        return best == null ? new Out("speaker", "本体スピーカー") : describe(best);
    }

    private static int rank(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return 4;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return 3;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 1;
            default:
                return -1;
        }
    }

    private static Out describe(AudioDeviceInfo d) {
        String name = d.getProductName() == null ? "" : d.getProductName().toString().trim();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                // One pair of headphones may be A2DP one day and LE Audio the next: key by name.
                return name.isEmpty() ? new Out("bt", "Bluetooth") : new Out("bt:" + name, name);
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return name.isEmpty() ? new Out("usb", "USB") : new Out("usb:" + name, name + "（USB）");
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return new Out("wired", "有線イヤホン");
            default:
                return new Out("speaker", "本体スピーカー");
        }
    }

    // --- Following the output -----------------------------------------------------------------

    private static final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Watch outputs come and go for as long as the process lives. The route to a new pair of
     * headphones settles a moment after they appear, so look again a little later too.
     */
    static void watch(Context c) {
        Context app = c.getApplicationContext();
        Runnable look = () -> check(app);
        app.getSystemService(AudioManager.class).registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                later(look);
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                later(look);
            }
        }, main);
    }

    private static void later(Runnable look) {
        look.run();
        main.removeCallbacks(look);
        main.postDelayed(look, 1000);
        main.postDelayed(look, 3000);
    }

    /**
     * If music now goes somewhere else, or another app plays, load the settings for that output
     * and app: the app's own for this output if set (機器プリセット), else the output's. An output
     * seen for the first time starts from the current values.
     */
    static void check(Context c) {
        if (!isEnabled(c)) return;
        Out now = current(c);
        String app = app(c);
        String want = app != null && prefs(c).contains(entryKey(now.key, app)) ? entryKey(now.key, app) : now.key;
        String was = activeEntry(c);
        if (now.key.equals(activeKey(c)) && want.equals(was)) return;
        Eq.prefs(c).edit().putString("output", now.key).putString("outputLabel", now.label)
                .putString("entry", want).apply();
        int[] steps = new int[Eq.N];
        int[] bass = new int[1];
        if (load(c, want, steps, bass)) {
            Diag.note(c, "「" + entryLabel(c, want) + "」の設定に切り替えた（BASS " + bass[0] + "）");
            Eq.setAll(c, steps, bass[0]);
        } else {
            Diag.note(c, "出力が「" + now.label + "」に: 初めての機器なので今の設定を引き継ぐ");
            remember(c);
        }
    }

    /** Save the current values where they were loaded from. Called on every change of them. */
    static void remember(Context c) {
        String key = activeEntry(c);
        if (key == null || !isEnabled(c)) return;
        int[] steps = new int[Eq.N];
        for (int i = 0; i < Eq.N; i++) steps[i] = Eq.step(c, i);
        save(c, key, steps, Eq.bass(c));
    }

    /** The stored settings in the faders now: the output's, or the output's for one app. */
    static String activeEntry(Context c) {
        // Before v8 only the output was kept: its settings are the ones in use.
        return Eq.prefs(c).getString("entry", activeKey(c));
    }

    // --- Which app plays ----------------------------------------------------------------------

    /** Between an output's key and an app's package, in the key of the app's own settings. */
    private static final String APP = "|app:";

    /**
     * The app last seen starting to play, kept while it pauses: the faders should not jump to
     * other values on every pause. Null before any app was seen.
     */
    static String app(Context c) {
        return Eq.prefs(c).getString("app", null);
    }

    /**
     * An app started playing: a player told its session, or its media session plays (seen
     * with 通知へのアクセス). Its settings for this output apply, if set.
     */
    static void setApp(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(c.getPackageName())) return;
        java.util.Set<String> seen = new java.util.HashSet<>(prefs(c).getStringSet(PLAYERS, new java.util.HashSet<>()));
        if (seen.add(pkg)) prefs(c).edit().putStringSet(PLAYERS, seen).apply();
        if (pkg.equals(app(c))) return;
        Eq.prefs(c).edit().putString("app", pkg).apply();
        Diag.note(c, "再生中のアプリ: " + appLabel(c, pkg));
        check(c);
    }

    /** Every app seen playing, for the picker of 機器プリセット. Not an output: no settings. */
    private static final String PLAYERS = "#players";

    static java.util.Set<String> players(Context c) {
        return prefs(c).getStringSet(PLAYERS, java.util.Collections.emptySet());
    }

    static String appLabel(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // --- Set up ahead of time (the 機器プリセット tab) -----------------------------------------

    static String entryKey(String output, String pkg) {
        return output + APP + pkg;
    }

    /** The output part of a stored key. */
    static String outputOf(String key) {
        int at = key.lastIndexOf(APP);
        return at < 0 ? key : key.substring(0, at);
    }

    /** The app part of a stored key, or null for an output's own settings. */
    static String appOf(String key) {
        int at = key.lastIndexOf(APP);
        return at < 0 ? null : key.substring(at + APP.length());
    }

    /** Every output that has settings saved (its own, or for an app), in no particular order. */
    static java.util.Set<String> savedKeys(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String k : prefs(c).getAll().keySet()) if (!k.equals(PLAYERS)) out.add(outputOf(k));
        return out;
    }

    /** The apps that have settings of their own for the output. */
    static java.util.List<String> appsOf(Context c, String output) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : prefs(c).getAll().keySet()) {
            if (!k.equals(PLAYERS) && appOf(k) != null && outputOf(k).equals(output)) out.add(appOf(k));
        }
        return out;
    }

    /** "WH-1000XM5", or "WH-1000XM5 ＋ YouTube". */
    static String entryLabel(Context c, String key) {
        String app = appOf(key);
        return labelOf(outputOf(key)) + (app == null ? "" : " ＋ " + appLabel(c, app));
    }

    /** A Bluetooth device's key, as it will be seen once it plays: by name. */
    static String btKey(String name) {
        return "bt:" + name;
    }

    /** The screen's name for a stored key. */
    static String labelOf(String key) {
        if (key.equals("speaker")) return "本体スピーカー";
        if (key.equals("wired")) return "有線イヤホン";
        if (key.equals("bt")) return "Bluetooth（名前不明）";
        if (key.equals("usb")) return "USB";
        if (key.startsWith("bt:")) return key.substring(3);
        if (key.startsWith("usb:")) return key.substring(4) + "（USB）";
        return key;
    }

    /**
     * Settings for an output that may not be connected: used the next time it plays. If it is
     * the output playing now, they apply at once.
     */
    static void assign(Context c, String key, int[] steps, int bass) {
        save(c, key, steps, bass);
        Diag.note(c, "機器プリセット: 「" + entryLabel(c, key) + "」に割り当て（BASS " + bass + "）");
        if (!isEnabled(c)) return;
        if (key.equals(activeEntry(c))) {
            Eq.setAll(c, steps, bass);
        } else if (appOf(key) != null) {
            // Set for the output and app playing now: it takes over from the output's own.
            check(c);
        }
    }

    /**
     * Forget an output's settings: next time it starts from whatever is current then. An app's
     * own settings: the output's apply to it again, at once if it plays now.
     */
    static void forget(Context c, String key) {
        prefs(c).edit().remove(key).apply();
        if (appOf(key) != null && key.equals(activeEntry(c))) check(c);
    }

    private static void save(Context c, String key, int[] steps, int bass) {
        JSONArray s = new JSONArray();
        for (int v : steps) s.put(v);
        try {
            prefs(c).edit().putString(key, new JSONObject()
                    .put("steps", s).put("bass", bass).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static boolean load(Context c, String key, int[] steps, int[] bass) {
        String saved = prefs(c).getString(key, null);
        if (saved == null) return false;
        try {
            JSONObject o = new JSONObject(saved);
            JSONArray s = o.getJSONArray("steps");
            // Saved under another band count (a future change): start afresh rather than misread.
            if (s.length() != Eq.N) return false;
            for (int i = 0; i < Eq.N; i++) steps[i] = s.getInt(i);
            bass[0] = o.optInt("bass", 0);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }
}
