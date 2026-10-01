package com.ikoras;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The 機器プリセット tab (a screen of its own until v7): choose each output's preset and BASS
 * ahead of time, whether or not it is connected, so tomorrow's headphones need only be put on;
 * and, under an output, other settings for one app (YouTube's voices, YT Music's songs). Lists
 * the outputs ikora has seen and, with the nearby-devices permission, the phone's paired
 * Bluetooth audio devices.
 */
final class DevicesPage {

    /** Request code of the nearby-devices permission; MainActivity hands the answer back. */
    static final int ASK_PAIRED = 2;

    private final Activity a;
    private final Switch enabled;
    private final TextView offNote;
    private final LinearLayout list;
    private final View pairedBox;
    /** Set while the code, not the user, moves the switch. */
    private boolean syncing;

    /** The page's view, for the tab. */
    final View view;

    DevicesPage(Activity activity) {
        a = activity;
        int pad = dp(16);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(8), pad, dp(32));

        // This tab's own switch, as on the other tabs (was in the equalizer's 動作の設定).
        enabled = new Switch(a);
        enabled.setText(R.string.per_output);
        enabled.setTextSize(16);
        enabled.setTypeface(Typeface.DEFAULT_BOLD);
        enabled.setOnCheckedChangeListener((b, on) -> {
            if (syncing) return;
            Outputs.setEnabled(a, on);
            fill();
        });
        col.addView(enabled);
        col.addView(hint(R.string.per_output_hint));
        offNote = new TextView(a);
        offNote.setText(R.string.devices_off);
        offNote.setTextColor(a.getColor(R.color.text_warn));
        offNote.setPadding(0, dp(8), 0, 0);
        col.addView(offNote);

        TextView how = hint(R.string.devices_hint);
        how.setPadding(0, dp(12), 0, dp(8));
        col.addView(how);

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);
        Button paired = new Button(a);
        paired.setText("ペアリング済みの Bluetooth 機器も表示する");
        paired.setAllCaps(false);
        paired.setOnClickListener(v -> a.requestPermissions(
                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, ASK_PAIRED));
        box.addView(paired);
        box.addView(hint(R.string.paired_hint));
        pairedBox = box;
        col.addView(box);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(col);
        view = scroll;
    }

    /** Before Android 12 the paired list needs only an install-time permission. */
    private boolean canReadPaired() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** The speaker and wired first, then every named device, by name. */
    private List<String> keys() {
        Set<String> out = new LinkedHashSet<>(Arrays.asList("speaker", "wired"));
        Set<String> named = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        named.addAll(Outputs.savedKeys(a));
        named.addAll(pairedAudio());
        String active = Outputs.activeKey(a);
        if (active != null) named.add(active);
        out.addAll(named);
        return new ArrayList<>(out);
    }

    /**
     * Paired Bluetooth devices of the audio kind (headphones, speakers, car audio), keyed by
     * name as their output will be. Empty without the permission.
     */
    private Set<String> pairedAudio() {
        Set<String> keys = new TreeSet<>();
        if (!canReadPaired()) return keys;
        try {
            BluetoothAdapter ad = a.getSystemService(BluetoothManager.class).getAdapter();
            if (ad == null) return keys;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                BluetoothClass k = d.getBluetoothClass();
                if (k == null || k.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
                String name = d.getName();
                if (name != null && !name.trim().isEmpty()) keys.add(Outputs.btKey(name.trim()));
            }
        } catch (SecurityException e) {
            // Permission taken back meanwhile: show what ikora has seen itself.
        }
        return keys;
    }

    /** Draw the list again: on showing the tab, and when the output or the app changes. */
    void fill() {
        syncing = true;
        boolean on = Outputs.isEnabled(a);
        enabled.setChecked(on);
        syncing = false;
        offNote.setVisibility(on ? View.GONE : View.VISIBLE);
        pairedBox.setVisibility(canReadPaired() ? View.GONE : View.VISIBLE);
        list.removeAllViews();
        list.setAlpha(on ? 1f : 0.5f);
        String output = on ? Outputs.activeKey(a) : null;
        String entry = on ? Outputs.activeEntry(a) : null;
        for (String key : keys()) list.addView(device(key, key.equals(output), entry));
    }

    /**
     * One output: its name, its own settings (for every app), each app's own, and a way to
     * add one more app. What applies now is ticked.
     */
    private View device(String key, boolean active, String entry) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(a);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        bold(sb, Outputs.labelOf(key));
        if (active) sb.append("（いま使用中）");
        name.setText(sb);
        name.setTextSize(16);
        name.setPadding(dp(4), dp(14), dp(4), dp(2));
        box.addView(name);

        box.addView(row(key, "すべてのアプリ", key.equals(entry)));
        List<String> apps = Outputs.appsOf(a, key);
        List<String[]> named = new ArrayList<>();
        for (String pkg : apps) named.add(new String[]{Outputs.appLabel(a, pkg), pkg});
        sortByName(named);
        for (String[] app : named) {
            String k = Outputs.entryKey(key, app[1]);
            box.addView(row(k, app[0], k.equals(entry)));
        }

        TextView add = new TextView(a);
        add.setText("＋ アプリ別の設定を追加");
        add.setTextColor(a.getColor(R.color.text_accent));
        add.setTextSize(14);
        add.setPadding(dp(24), dp(10), dp(12), dp(10));
        add.setBackgroundResource(ripple());
        add.setOnClickListener(v -> pickApp(key, apps));
        box.addView(add);

        View rule = new View(a);
        rule.setBackgroundColor(0x33808080);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        box.addView(rule, lp);
        return box;
    }

    /** "すべてのアプリ: 声 ・ BASS 2"; tap to choose, long press to forget. */
    private View row(String key, String who, boolean now) {
        TextView t = new TextView(a);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (now) {
            // A tick, not a colour: the theme's accent is near white on some devices (Xperia).
            bold(sb, "✓ " + who);
        } else {
            sb.append(who);
        }
        sb.append(": ").append(summary(key));
        t.setText(sb);
        t.setTextSize(14);
        t.setPadding(dp(24), dp(8), dp(12), dp(8));
        t.setBackgroundResource(ripple());
        t.setOnClickListener(v -> choose(key));
        t.setOnLongClickListener(v -> {
            askForget(key);
            return true;
        });
        return t;
    }

    private String summary(String key) {
        int[] steps = new int[Eq.N];
        int[] bass = new int[1];
        if (!Outputs.load(a, key, steps, bass)) return "未設定（初めて鳴らしたときの設定を引き継ぎます）";
        return presetName(steps) + " ・ BASS " + (bass[0] == 0 ? "OFF" : String.valueOf(bass[0]));
    }

    private String presetName(int[] steps) {
        for (Presets.Preset p : Presets.all(a)) if (p.matches(steps)) return p.name;
        return "カスタム";
    }

    // --- Which app ------------------------------------------------------------------------

    /**
     * The apps that play: those seen playing, music apps (a MediaBrowserService) and YouTube;
     * then every app with a home-screen icon, for one that is none of these.
     */
    private void pickApp(String output, List<String> taken) {
        Toast.makeText(a, R.string.assign_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Set<String> pkgs = new LinkedHashSet<>(Outputs.players(a));
            for (ResolveInfo r : a.getPackageManager().queryIntentServices(
                    new Intent("android.media.browse.MediaBrowserService"), 0)) {
                pkgs.add(r.serviceInfo.packageName);
            }
            pkgs.addAll(Arrays.asList(Watch.SILENT));
            List<String[]> apps = named(pkgs, taken);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                CharSequence[] items = new CharSequence[apps.size() + 1];
                for (int i = 0; i < apps.size(); i++) items[i] = apps.get(i)[0];
                items[apps.size()] = "ほかのアプリ…";
                new AlertDialog.Builder(a)
                        .setTitle(Outputs.labelOf(output) + " で使うアプリ")
                        .setItems(items, (d, which) -> {
                            if (which < apps.size()) choose(Outputs.entryKey(output, apps.get(which)[1]));
                            else pickAnyApp(output, taken);
                        })
                        .show();
            });
        }).start();
    }

    /** Every app with a home-screen icon (all that the manifest's queries let ikoras see). */
    private void pickAnyApp(String output, List<String> taken) {
        new Thread(() -> {
            Set<String> pkgs = new LinkedHashSet<>();
            for (ResolveInfo r : a.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                pkgs.add(r.activityInfo.packageName);
            }
            List<String[]> apps = named(pkgs, taken);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                CharSequence[] items = new CharSequence[apps.size()];
                for (int i = 0; i < apps.size(); i++) items[i] = apps.get(i)[0];
                new AlertDialog.Builder(a)
                        .setTitle(Outputs.labelOf(output) + " で使うアプリ")
                        .setItems(items, (d, which) -> choose(Outputs.entryKey(output, apps.get(which)[1])))
                        .show();
            });
        }).start();
    }

    /** {name, package} of each installed one, by name; not ikoras, not those set already. */
    private List<String[]> named(Set<String> pkgs, List<String> taken) {
        PackageManager pm = a.getPackageManager();
        List<String[]> out = new ArrayList<>();
        for (String pkg : pkgs) {
            if (pkg.equals(a.getPackageName()) || taken.contains(pkg)) continue;
            try {
                out.add(new String[]{pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), pkg});
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled since it was seen.
            }
        }
        sortByName(out);
        return out;
    }

    private static void sortByName(List<String[]> apps) {
        Collator collator = Collator.getInstance();
        Collections.sort(apps, (x, y) -> collator.compare(x[0], y[0]));
    }

    // --- Choosing -------------------------------------------------------------------------

    /** A preset and a BASS level for the output, or for the output and one app. */
    private void choose(String key) {
        int[] steps = new int[Eq.N];
        int[] bass = new int[1];
        boolean has = Outputs.load(a, key, steps, bass);
        // Nothing yet: offer what it would start from. An app, the output's own settings.
        if (!has && !(Outputs.appOf(key) != null && Outputs.load(a, Outputs.outputOf(key), steps, bass))) {
            for (int i = 0; i < Eq.N; i++) steps[i] = Eq.step(a, i);
            bass[0] = Eq.bass(a);
        }

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        RadioGroup presets = new RadioGroup(a);
        boolean matched = false;
        for (Presets.Preset p : Presets.all(a)) {
            RadioButton b = new RadioButton(a);
            b.setId(View.generateViewId());
            b.setText(p.name);
            b.setTag(p.steps);
            presets.addView(b);
            if (!matched && p.matches(steps)) {
                presets.check(b.getId());
                matched = true;
            }
        }
        if (!matched) {
            // Not any preset (moved faders, or the 全体 fader): keep it as it is.
            RadioButton b = new RadioButton(a);
            b.setId(View.generateViewId());
            b.setText("今の値のまま（カスタム）");
            b.setTag(steps.clone());
            presets.addView(b, 0);
            presets.check(b.getId());
        }
        box.addView(presets);

        TextView bassLabel = new TextView(a);
        bassLabel.setText("BASS");
        bassLabel.setTypeface(Typeface.DEFAULT_BOLD);
        bassLabel.setPadding(0, dp(12), 0, 0);
        box.addView(bassLabel);
        RadioGroup levels = new RadioGroup(a);
        levels.setOrientation(RadioGroup.HORIZONTAL);
        for (int l = 0; l <= Eq.BASS_MAX; l++) {
            RadioButton b = new RadioButton(a);
            b.setId(View.generateViewId());
            b.setText(l == 0 ? "OFF" : String.valueOf(l));
            b.setTag(l);
            levels.addView(b);
            if (l == bass[0]) levels.check(b.getId());
        }
        // Six BASS choices may not fit a narrow dialog: let them scroll sideways.
        android.widget.HorizontalScrollView fit = new android.widget.HorizontalScrollView(a);
        fit.setHorizontalScrollBarEnabled(false);
        fit.addView(levels);
        box.addView(fit);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle(Outputs.entryLabel(a, key))
                .setView(sv)
                .setPositiveButton("決定", (d, w) -> {
                    int[] s = (int[]) presets.findViewById(presets.getCheckedRadioButtonId()).getTag();
                    int l = (int) levels.findViewById(levels.getCheckedRadioButtonId()).getTag();
                    Outputs.assign(a, key, s, l);
                    fill();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void askForget(String key) {
        if (!Outputs.load(a, key, new int[Eq.N], new int[1])) return;
        String app = Outputs.appOf(key);
        String then = app == null ? "次に鳴らしたときは、その時の設定を引き継ぎます。"
                : Outputs.appLabel(a, app) + " にも「すべてのアプリ」の設定が効くようになります。";
        new AlertDialog.Builder(a)
                .setMessage("「" + Outputs.entryLabel(a, key) + "」の設定を消しますか？\n" + then)
                .setPositiveButton("消す", (d, w) -> {
                    Outputs.forget(a, key);
                    fill();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // --- Helpers --------------------------------------------------------------------------

    private TextView hint(int text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12);
        return t;
    }

    private static void bold(SpannableStringBuilder sb, String s) {
        int start = sb.length();
        sb.append(s);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private int ripple() {
        android.util.TypedValue t = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, t, true);
        return t.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
