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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    static final String PERSON_NAME = "person_name";
    static final String PERSON_NUMMER = "person_nummer";
    static final String[] WHATSAPP = {"com.whatsapp", "com.whatsapp.w4b"};
    private static final int[] GROESSE_DP = {60, 76, 100};
    private static final long VERSTECKT_MS = 60_000;
    private static final long VERSCHICKEN_MS = 10_000;
    private static final long LANG_MS = 800;

    private static FotoService instanz;

    private WindowManager wm;
    private SharedPreferences prefs;
    private FrameLayout knopf;
    private TextView zahl;
    private WindowManager.LayoutParams lp;
    private TextView ablage, anPerson, verschicken;
    private LinearLayout leiste;
    private WindowManager.LayoutParams ablageLp, verschickenLp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SoundPool sounds;
    private int klickId;
    private float d;
    private boolean versteckt;
    private int countdown;
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
        knopf = new FrameLayout(this);
        ImageView bild = new ImageView(this);
        bild.setImageResource(R.drawable.ic_camera);
        knopf.addView(bild, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        zahl = new TextView(this);
        zahl.setGravity(Gravity.CENTER);
        zahl.setTypeface(Typeface.DEFAULT_BOLD);
        zahl.setTextColor(0xFFFFFFFF);
        GradientDrawable kreis = new GradientDrawable();
        kreis.setShape(GradientDrawable.OVAL);
        kreis.setColor(0xFFE53935);
        zahl.setBackground(kreis);
        zahl.setVisibility(View.GONE);
        knopf.addView(zahl, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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
            int startX, startY; float downX, downY; boolean bewegt, lang;
            final int schwelle = (int) (12 * d);
            final Runnable langGedrueckt = () -> {
                // Lange gehalten: Selbstauslöser vormerken (startet beim Loslassen).
                lang = true;
                kurzVibrieren(40);
                zahlZeigen("3");
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (countdown > 0) {
                            // Antippen während des Countdowns bricht ihn ab.
                            countdownAbbrechen();
                            bewegt = true; lang = false;
                            return true;
                        }
                        startX = lp.x; startY = lp.y;
                        downX = e.getRawX(); downY = e.getRawY();
                        bewegt = false; lang = false;
                        v.setAlpha(0.7f);
                        handler.postDelayed(langGedrueckt, LANG_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!bewegt && (Math.abs(dx) > schwelle || Math.abs(dy) > schwelle)) {
                            bewegt = true;
                            handler.removeCallbacks(langGedrueckt);
                            if (lang) { lang = false; zahlVerbergen(); }
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
                        handler.removeCallbacks(langGedrueckt);
                        v.setAlpha(grundAlpha());
                        ablageZeigen(false);
                        if (lang && !bewegt) {
                            selbstausloeser();
                        } else if (bewegt) {
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
                        handler.removeCallbacks(langGedrueckt);
                        if (lang) { lang = false; zahlVerbergen(); }
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
            if (countdown > 0) return;
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

    // ---------- Selbstauslöser ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            countdown--;
            if (countdown <= 0) {
                zahlVerbergen();
                fotoMachen();
                return;
            }
            zahlZeigen(String.valueOf(countdown));
            kurzVibrieren(30);
            handler.postDelayed(this, 1000);
        }
    };

    private void selbstausloeser() {
        countdown = 3;
        zahlZeigen("3");
        Toast.makeText(this, "Foto in 3 Sekunden …", Toast.LENGTH_SHORT).show();
        handler.postDelayed(tick, 1000);
    }

    private void countdownAbbrechen() {
        handler.removeCallbacks(tick);
        countdown = 0;
        zahlVerbergen();
        Toast.makeText(this, "Selbstauslöser abgebrochen.", Toast.LENGTH_SHORT).show();
    }

    private void zahlZeigen(String s) {
        zahl.setText(s);
        zahl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, lp.width * 0.5f);
        zahl.setVisibility(View.VISIBLE);
    }

    private void zahlVerbergen() {
        zahl.setVisibility(View.GONE);
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
        kurzVibrieren(120);
    }

    private void kurzVibrieren(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect effekt = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE);
            if (Build.VERSION.SDK_INT >= 33) {
                // Als Bedienungshilfe kennzeichnen, sonst blendet Android sie im Hintergrund aus.
                v.vibrate(effekt, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY));
            } else {
                v.vibrate(effekt, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build());
            }
        } catch (Exception ignored) { }
    }

    // ---------- Verschicken ----------

    private TextView pille(String text, int farbe) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(23);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        int ph = (int) (26 * d), pv = (int) (15 * d);
        t.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(farbe);
        bg.setCornerRadius(40 * d);
        bg.setStroke((int) (3 * d), 0xFFFFFFFF);
        t.setBackground(bg);
        t.setElevation(10 * d);
        return t;
    }

    private void verschickenBauen() {
        leiste = new LinearLayout(this);
        leiste.setOrientation(LinearLayout.VERTICAL);
        leiste.setGravity(Gravity.CENTER_HORIZONTAL);
        int r = (int) (8 * d);
        leiste.setPadding(r, r, r, r);

        anPerson = pille("", 0xFF2E7D32);
        anPerson.setOnClickListener(v -> {
            verschickenAusblenden();
            neuestesFotoTeilen(0, true);
        });
        leiste.addView(anPerson);

        verschicken = pille("➤  Verschicken …", 0xFF1565C0);
        verschicken.setOnClickListener(v -> {
            verschickenAusblenden();
            neuestesFotoTeilen(0, false);
        });
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = (int) (10 * d);
        leiste.addView(verschicken, vlp);

        leiste.setVisibility(View.GONE);
        verschickenLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        verschickenLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        verschickenLp.y = (int) (80 * d);
        wm.addView(leiste, verschickenLp);
    }

    /** Welche WhatsApp-App ist installiert? (null = keine) */
    private String whatsapp() {
        for (String pkg : WHATSAPP) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return pkg;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private boolean personEingerichtet() {
        return !prefs.getString(PERSON_NUMMER, "").isEmpty() && whatsapp() != null;
    }

    private void verschickenZeigen() {
        boolean person = personEingerichtet();
        anPerson.setVisibility(person ? View.VISIBLE : View.GONE);
        if (person) anPerson.setText("➤  An " + prefs.getString(PERSON_NAME, "") + " schicken");
        verschicken.setText(person ? "Jemand anderem schicken …" : "➤  Verschicken …");
        leiste.setVisibility(View.VISIBLE);
        handler.removeCallbacks(verschickenWeg);
        handler.postDelayed(verschickenWeg, VERSCHICKEN_MS);
    }

    private void verschickenAusblenden() {
        handler.removeCallbacks(verschickenWeg);
        if (leiste != null) leiste.setVisibility(View.GONE);
    }

    private boolean darfFotosLesen() {
        String recht = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(recht) == PackageManager.PERMISSION_GRANTED;
    }

    /** Sucht das gerade gemachte Bildschirmfoto (das Speichern dauert manchmal etwas). */
    private void neuestesFotoTeilen(int versuch, boolean anPersonSchicken) {
        if (!darfFotosLesen()) {
            Toast.makeText(this, "Bitte einmal „Alle zulassen“ wählen. Danach klappt Verschicken direkt.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, FotosActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Uri uri = neuestesFoto();
        if (uri == null && versuch < 15) {
            handler.postDelayed(() -> neuestesFotoTeilen(versuch + 1, anPersonSchicken), 400);
            return;
        }
        if (uri == null) {
            startActivity(new Intent(this, FotosActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (anPersonSchicken && direktAnPerson(uri)) return;
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

    /** Öffnet WhatsApp direkt im Chat mit der eingestellten Person. */
    private boolean direktAnPerson(Uri uri) {
        String pkg = whatsapp();
        String nummer = prefs.getString(PERSON_NUMMER, "");
        if (pkg == null || nummer.isEmpty()) return false;
        Intent i = new Intent(Intent.ACTION_SEND)
                .setPackage(pkg)
                .setType("image/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra("jid", nummer + "@s.whatsapp.net")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setClipData(ClipData.newRawUri("Bildschirmfoto", uri));
        try {
            startActivity(i);
            Toast.makeText(this, "Jetzt unten auf den grünen Pfeil tippen zum Senden.", Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception e) {
            return false;
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
        for (View v : new View[]{knopf, ablage, leiste}) {
            if (v != null && wm != null) {
                try { wm.removeView(v); } catch (Exception ignored) { }
            }
        }
        super.onDestroy();
    }
}
