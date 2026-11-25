package de.fotoknopf;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Zeigt einen großen, verschiebbaren Knopf über allen Apps.
 * Antippen: Bildschirmfoto + Klick + Vibration, danach Knopf "Verschicken".
 * An den unteren Rand ziehen: Knopf ist eine Minute versteckt.
 */
public class FotoService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String PREFS = "pos";
    static final String GROESSE = "groesse";       // 0 klein, 1 mittel, 2 groß
    static final String DURCHSICHTIG = "durchsichtig";
    private static final int[] GROESSE_DP = {60, 76, 100};
    private static final long VERSTECKT_MS = 60_000;
    private static final long VERSCHICKEN_MS = 8_000;

    private static FotoService instanz;

    private WindowManager wm;
    private SharedPreferences prefs;
    private ImageView knopf;
    private WindowManager.LayoutParams lp;
    private TextView ablage, verschicken;
    private WindowManager.LayoutParams ablageLp, verschickenLp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SoundPool sounds;
    private int klickId;
    private float d;
    private boolean versteckt;
    private long fotoZeit;

    private final Runnable zurueckholen = this::wiederZeigen;
    private final Runnable verschickenWeg = this::verschickenAusblenden;

    /** Für die App: Knopf sofort wieder anzeigen. */
    static void knopfZurueckholen() {
        if (instanz != null) instanz.wiederZeigen();
    }

    static boolean laeuft() { return instanz != null; }

    @Override
    protected void onServiceConnected() {
        instanz = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);

        sounds = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        klickId = sounds.load(this, R.raw.klick, 1);

        knopfBauen();
        ablageBauen();
        verschickenBauen();
    }

    // ---------- Der rote Knopf ----------

    private int knopfGroesse() {
        int g = Math.max(0, Math.min(2, prefs.getInt(GROESSE, 1)));
        return (int) (GROESSE_DP[g] * d);
    }

    private void knopfBauen() {
        int size = knopfGroesse();
        knopf = new ImageView(this);
        knopf.setImageResource(R.drawable.ic_camera);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        bg.setStroke((int) (3 * d), 0xFFB71C1C);
        knopf.setBackground(bg);
        int p = (int) (4 * d);
        knopf.setPadding(p, p, p, p);
        knopf.setElevation(8 * d);
        knopf.setContentDescription("Bildschirmfoto machen");
        knopf.setAlpha(grundAlpha());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", dm.widthPixels - size - (int) (12 * d));
        lp.y = prefs.getInt("y", dm.heightPixels / 2);

        knopf.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float downX, downY; boolean bewegt;
            final int schwelle = (int) (12 * d);

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x; startY = lp.y;
                        downX = e.getRawX(); downY = e.getRawY();
                        bewegt = false;
                        v.setAlpha(0.7f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!bewegt && (Math.abs(dx) > schwelle || Math.abs(dy) > schwelle)) {
                            bewegt = true;
                            ablageZeigen(true);
                        }
                        if (bewegt) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            wm.updateViewLayout(knopf, lp);
                            ablageMarkieren(inAblage(e.getRawY()));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(grundAlpha());
                        ablageZeigen(false);
                        if (bewegt) {
                            if (inAblage(e.getRawY())) {
                                // Alte Position behalten, Knopf eine Minute verstecken.
                                lp.x = startX; lp.y = startY;
                                wm.updateViewLayout(knopf, lp);
                                verstecken();
                            } else {
                                prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                            }
                        } else {
                            fotoMachen();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(grundAlpha());
                        ablageZeigen(false);
                        return true;
                }
                return false;
            }
        });
        wm.addView(knopf, lp);
    }

    private float grundAlpha() {
        return prefs.getBoolean(DURCHSICHTIG, false) ? 0.55f : 1f;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (knopf == null || key == null) return;
        if (key.equals(GROESSE)) {
            int alt = lp.width, neu = knopfGroesse();
            // Mittelpunkt beibehalten.
            lp.x += (alt - neu) / 2;
            lp.y += (alt - neu) / 2;
            lp.width = neu; lp.height = neu;
            wm.updateViewLayout(knopf, lp);
        } else if (key.equals(DURCHSICHTIG)) {
            knopf.setAlpha(grundAlpha());
        }
    }

    // ---------- Foto ----------

    private void fotoMachen() {
        verschickenAusblenden();
        knopf.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            fotoZeit = System.currentTimeMillis();
            boolean ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            if (ok) {
                rueckmeldung();
            } else {
                Toast.makeText(this, "Das Foto hat leider nicht geklappt.", Toast.LENGTH_LONG).show();
            }
            handler.postDelayed(() -> {
                if (!versteckt) knopf.setVisibility(View.VISIBLE);
                if (ok) verschickenZeigen();
            }, 1500);
        }, 250);
    }

    /** Klick-Ton und kurzes Vibrieren, damit man merkt: Foto ist gemacht. */
    private void rueckmeldung() {
        try {
            sounds.play(klickId, 1f, 1f, 1, 0, 1f);
        } catch (Exception ignored) { }
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect effekt = VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE);
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effekt, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY));
            } else {
                v.vibrate(effekt, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build());
            }
        } catch (Exception ignored) { }
    }

    // ---------- Verschicken ----------

    private void verschickenBauen() {
        verschicken = new TextView(this);
        verschicken.setText("➤  Verschicken");
        verschicken.setTextSize(24);
        verschicken.setTypeface(Typeface.DEFAULT_BOLD);
        verschicken.setTextColor(0xFFFFFFFF);
        verschicken.setGravity(Gravity.CENTER);
        int ph = (int) (28 * d), pv = (int) (16 * d);
        verschicken.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2E7D32);
        bg.setCornerRadius(40 * d);
        bg.setStroke((int) (3 * d), 0xFFFFFFFF);
        verschicken.setBackground(bg);
        verschicken.setElevation(10 * d);
        verschicken.setVisibility(View.GONE);
        verschicken.setOnClickListener(v -> {
            verschickenAusblenden();
            neuestesFotoTeilen(0);
        });
        verschickenLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        verschickenLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        verschickenLp.y = (int) (90 * d);
        wm.addView(verschicken, verschickenLp);
    }

    private void verschickenZeigen() {
        verschicken.setVisibility(View.VISIBLE);
        handler.removeCallbacks(verschickenWeg);
        handler.postDelayed(verschickenWeg, VERSCHICKEN_MS);
    }

    private void verschickenAusblenden() {
        handler.removeCallbacks(verschickenWeg);
        if (verschicken != null) verschicken.setVisibility(View.GONE);
    }

    private boolean darfFotosLesen() {
        String recht = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(recht) == PackageManager.PERMISSION_GRANTED;
    }

    /** Sucht das gerade gemachte Bildschirmfoto (das Speichern dauert manchmal etwas). */
    private void neuestesFotoTeilen(int versuch) {
        if (!darfFotosLesen()) {
            Toast.makeText(this, "Bitte einmal „Alle zulassen“ wählen. Danach klappt Verschicken direkt.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, FotosActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Uri uri = neuestesFoto();
        if (uri == null && versuch < 15) {
            handler.postDelayed(() -> neuestesFotoTeilen(versuch + 1), 400);
            return;
        }
        if (uri == null) {
            startActivity(new Intent(this, FotosActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("image/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("Bildschirmfoto", uri));
        Intent chooser = Intent.createChooser(send, "Foto verschicken mit …")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(this, "Verschicken geht gerade nicht.", Toast.LENGTH_LONG).show();
        }
    }

    private Uri neuestesFoto() {
        Uri basis = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String spalte = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA;
        String sel = spalte + " LIKE ? AND " + MediaStore.Images.Media.DATE_ADDED + " >= ?";
        String[] args = {"%Screenshot%", String.valueOf(fotoZeit / 1000 - 3)};
        try (Cursor c = getContentResolver().query(basis, new String[]{MediaStore.Images.Media._ID},
                sel, args, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(basis, c.getLong(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- Verstecken ----------

    private void ablageBauen() {
        ablage = new TextView(this);
        ablage.setText("Hier loslassen zum Verstecken\n(kommt nach 1 Minute zurück)");
        ablage.setTextSize(19);
        ablage.setTypeface(Typeface.DEFAULT_BOLD);
        ablage.setTextColor(0xFFFFFFFF);
        ablage.setGravity(Gravity.CENTER);
        ablage.setBackgroundColor(0xCC424242);
        ablage.setVisibility(View.GONE);
        ablageLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, (int) (120 * d),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ablageLp.gravity = Gravity.BOTTOM;
        wm.addView(ablage, ablageLp);
    }

    private int bildschirmHoehe() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds().height();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private boolean inAblage(float rawY) {
        return rawY > bildschirmHoehe() - ablageLp.height;
    }

    private void ablageZeigen(boolean an) {
        ablage.setVisibility(an ? View.VISIBLE : View.GONE);
        if (!an) ablageMarkieren(false);
    }

    private void ablageMarkieren(boolean drin) {
        ablage.setBackgroundColor(drin ? 0xEEC62828 : 0xCC424242);
    }

    private void verstecken() {
        versteckt = true;
        knopf.setVisibility(View.GONE);
        verschickenAusblenden();
        Toast.makeText(this, "Der Foto-Knopf kommt in 1 Minute zurück.", Toast.LENGTH_LONG).show();
        handler.removeCallbacks(zurueckholen);
        handler.postDelayed(zurueckholen, VERSTECKT_MS);
    }

    private void wiederZeigen() {
        handler.removeCallbacks(zurueckholen);
        versteckt = false;
        if (knopf != null) knopf.setVisibility(View.VISIBLE);
    }

    // ---------- Sonstiges ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        instanz = null;
        handler.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (sounds != null) sounds.release();
        for (View v : new View[]{knopf, ablage, verschicken}) {
            if (v != null && wm != null) {
                try { wm.removeView(v); } catch (Exception ignored) { }
            }
        }
        super.onDestroy();
    }
}
