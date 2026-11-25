package de.fotoknopf;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView status;
    private TextView anleitung;
    private LinearLayout einstellungen;
    private final Button[] groesse = new Button[3];
    private Button durchsichtig;
    private Button updateBanner, updatesEinrichten;
    private TextView updateStatus;
    private Aktualisierung.Neu neu;
    private boolean installNachErlaubnis;
    private static final int REQ_NOTIF = 4;
    private TextView personText;
    private Button person, personWeg;
    private static final int REQ_KONTAKT = 3;
    private Button einschalten;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * d);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(0xFFFFFFFF);

        TextView titel = text("Foto-Knopf", 34, 0xFF212121);
        titel.setTypeface(Typeface.DEFAULT_BOLD);
        titel.setGravity(Gravity.CENTER);
        box.addView(titel);

        updateBanner = knopf("⬇  Neue Version – jetzt aktualisieren", 0xFFEF6C00);
        LinearLayout.LayoutParams ulp = (LinearLayout.LayoutParams) updateBanner.getLayoutParams();
        ulp.topMargin = pad;
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(v -> updateStarten());
        box.addView(updateBanner);

        status = text("", 24, 0xFF212121);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad, 0, pad);
        box.addView(status);

        einschalten = knopf("Foto-Knopf einschalten", 0xFFE53935);
        einschalten.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Bitte auf „Foto-Knopf“ tippen und einschalten",
                    Toast.LENGTH_LONG).show();
        });
        box.addView(einschalten);

        Button galerie = knopf("Meine Bildschirmfotos", 0xFF1E88E5);
        galerie.setOnClickListener(v -> startActivity(new Intent(this, FotosActivity.class)));
        box.addView(galerie);

        einstellungen = new LinearLayout(this);
        einstellungen.setOrientation(LinearLayout.VERTICAL);
        einstellungen.setPadding(0, pad / 2, 0, 0);
        TextView gTitel = text("Größe des Knopfes", 22, 0xFF212121);
        gTitel.setTypeface(Typeface.DEFAULT_BOLD);
        gTitel.setPadding(0, 0, 0, (int) (8 * d));
        einstellungen.addView(gTitel);
        LinearLayout reihe = new LinearLayout(this);
        reihe.setOrientation(LinearLayout.HORIZONTAL);
        String[] namen = {"Klein", "Mittel", "Groß"};
        for (int i = 0; i < 3; i++) {
            final int g = i;
            Button wahl = auswahl(namen[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (72 * d), 1f);
            if (i > 0) lp.leftMargin = (int) (10 * d);
            wahl.setLayoutParams(lp);
            wahl.setOnClickListener(v -> {
                prefs().edit().putInt(FotoService.GROESSE, g).apply();
                auswahlAnzeigen();
            });
            groesse[i] = wahl;
            reihe.addView(wahl);
        }
        einstellungen.addView(reihe);
        durchsichtig = auswahl("");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * d));
        dlp.topMargin = (int) (12 * d);
        durchsichtig.setLayoutParams(dlp);
        durchsichtig.setOnClickListener(v -> {
            boolean an = !prefs().getBoolean(FotoService.DURCHSICHTIG, false);
            prefs().edit().putBoolean(FotoService.DURCHSICHTIG, an).apply();
            auswahlAnzeigen();
        });
        einstellungen.addView(durchsichtig);
        TextView pTitel = text("Schnell an eine Person schicken", 22, 0xFF212121);
        pTitel.setTypeface(Typeface.DEFAULT_BOLD);
        pTitel.setPadding(0, pad, 0, (int) (6 * d));
        einstellungen.addView(pTitel);
        personText = text("", 19, 0xFF555555);
        personText.setPadding(0, 0, 0, (int) (10 * d));
        einstellungen.addView(personText);
        person = auswahl("");
        person.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * d)));
        person.setOnClickListener(v -> personWaehlen());
        einstellungen.addView(person);
        personWeg = auswahl("Person entfernen");
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (64 * d));
        wlp.topMargin = (int) (10 * d);
        personWeg.setLayoutParams(wlp);
        personWeg.setOnClickListener(v -> {
            prefs().edit().remove(FotoService.PERSON_NAME).remove(FotoService.PERSON_NUMMER).apply();
            personAnzeigen();
        });
        einstellungen.addView(personWeg);
        box.addView(einstellungen);

        anleitung = text("", 21, 0xFF424242);
        anleitung.setPadding(0, pad, 0, 0);
        anleitung.setLineSpacing(0, 1.2f);
        box.addView(anleitung);

        Button erklaerung = auswahl("Kurze Erklärung ansehen");
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * d));
        elp.topMargin = pad;
        erklaerung.setLayoutParams(elp);
        auswahlFaerben(erklaerung, false);
        erklaerung.setOnClickListener(v -> startActivity(new Intent(this, WillkommenActivity.class)));
        box.addView(erklaerung);

        TextView uTitel = text("Updates", 22, 0xFF212121);
        uTitel.setTypeface(Typeface.DEFAULT_BOLD);
        uTitel.setPadding(0, pad, 0, (int) (6 * d));
        box.addView(uTitel);
        updateStatus = text("", 19, 0xFF555555);
        updateStatus.setPadding(0, 0, 0, (int) (10 * d));
        box.addView(updateStatus);
        updatesEinrichten = auswahl("Updates einrichten");
        updatesEinrichten.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * d)));
        updatesEinrichten.setOnClickListener(v -> updatesEinrichten());
        box.addView(updatesEinrichten);
        Button suchen = auswahl("Jetzt nach Updates suchen");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (64 * d));
        slp.topMargin = (int) (10 * d);
        suchen.setLayoutParams(slp);
        auswahlFaerben(suchen, false);
        suchen.setOnClickListener(v -> updateSuchen(true, false));
        box.addView(suchen);

        if (!prefs().getBoolean(WillkommenActivity.GESEHEN, false)) {
            startActivity(new Intent(this, WillkommenActivity.class));
        }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFFFFFFF);
        sv.addView(box);
        setContentView(sv);

        if (getIntent().getBooleanExtra(Aktualisierung.EXTRA_UPDATE, false)) {
            updateSuchen(true, true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(Aktualisierung.EXTRA_UPDATE, false)) updateSuchen(true, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Falls der Knopf gerade versteckt ist: beim Öffnen der App sofort zurückholen.
        FotoService.knopfZurueckholen();
        auswahlAnzeigen();
        personAnzeigen();
        updatesAnzeigen();
        if (installNachErlaubnis && getPackageManager().canRequestPackageInstalls()) {
            installNachErlaubnis = false;
            updateStarten();
        } else {
            updateSuchen(false, false);
        }
        einstellungen.setVisibility(istEingeschaltet() ? View.VISIBLE : View.GONE);
        if (istEingeschaltet()) {
            status.setText("✅  Der Foto-Knopf ist an.");
            status.setTextColor(0xFF2E7D32);
            einschalten.setText("Einstellungen öffnen");
            anleitung.setText(
                    "So geht's:\n\n" +
                    "1.  Öffnen Sie WhatsApp, Instagram oder eine andere App.\n\n" +
                    "2.  Tippen Sie einmal auf den runden Knopf mit der Kamera. Es klickt.\n\n" +
                    "3.  Oben erscheint kurz der grüne Knopf. Antippen, dann öffnet sich " +
                    "WhatsApp mit dem Foto. Nur noch auf Senden tippen.\n\n" +
                    "Selbstauslöser: Finger auf dem Knopf lassen, bis es vibriert, dann loslassen. " +
                    "Das Foto kommt nach 3 Sekunden.\n\n" +
                    "Alle Fotos finden Sie über den blauen Knopf „Meine Bildschirmfotos“.\n\n" +
                    "Knopf verschieben: Finger darauf halten und schieben.\n\n" +
                    "Knopf verstecken: ganz nach unten ziehen und loslassen. " +
                    "Nach einer Minute ist er wieder da. Oder einfach diese App öffnen.");
        } else {
            status.setText("Der Foto-Knopf ist noch aus.");
            status.setTextColor(0xFFC62828);
            einschalten.setText("Foto-Knopf einschalten");
            anleitung.setText(
                    "Einmalig einschalten:\n\n" +
                    "1.  Auf den roten Knopf oben tippen.\n\n" +
                    "2.  In der Liste „Foto-Knopf“ suchen (evtl. unter " +
                    "„Installierte Apps“ oder „Heruntergeladene Apps“).\n\n" +
                    "3.  Einschalten und mit „Zulassen“ bestätigen.\n\n" +
                    "Danach erscheint ein runder Kamera-Knopf auf dem Bildschirm.");
        }
    }

    private boolean istEingeschaltet() {
        String an = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (an == null) return false;
        ComponentName me = new ComponentName(this, FotoService.class);
        for (String s : an.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    // ---------- Updates ----------

    private boolean benachrichtigungenErlaubt() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void updatesAnzeigen() {
        boolean ok = benachrichtigungenErlaubt() && getPackageManager().canRequestPackageInstalls();
        updatesEinrichten.setVisibility(ok ? View.GONE : View.VISIBLE);
        auswahlFaerben(updatesEinrichten, true);
        String v = "Version " + versionName();
        if (neu != null) {
            updateStatus.setText(v + ". Neue Version " + neu.name + " ist da.");
        } else if (ok) {
            updateStatus.setText(v + ". Updates sind eingerichtet: Die App meldet sich, wenn es etwas Neues gibt.");
        } else {
            updateStatus.setText(v + ". Bitte einmal „Updates einrichten“ tippen, damit die App Bescheid sagt, wenn es etwas Neues gibt.");
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void updatesEinrichten() {
        if (!benachrichtigungenErlaubt()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Bitte „Dieser Quelle vertrauen“ einschalten und dann zurück.",
                    Toast.LENGTH_LONG).show();
            Aktualisierung.installierenErlaubt(this, true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code != REQ_NOTIF) return;
        updatesAnzeigen();
        if (!getPackageManager().canRequestPackageInstalls()) updatesEinrichten();
    }

    private void updateSuchen(boolean manuell, boolean direktInstallieren) {
        if (manuell) updateStatus.setText("Suche nach Updates …");
        Aktualisierung.pruefen(this, (n, fehler) -> {
            if (isFinishing()) return;
            neu = n;
            updateBanner.setVisibility(n != null ? View.VISIBLE : View.GONE);
            if (n != null) updateBanner.setText("⬇  Neue Version " + n.name + " – jetzt aktualisieren");
            updatesAnzeigen();
            if (manuell && n == null) {
                Toast.makeText(this, fehler ? "Keine Internetverbindung. Bitte später nochmal versuchen."
                        : "Alles aktuell. Es gibt keine neue Version.", Toast.LENGTH_LONG).show();
            }
            if (direktInstallieren && n != null) updateStarten();
        });
    }

    private void updateStarten() {
        if (neu == null) { updateSuchen(true, true); return; }
        if (!Aktualisierung.installierenErlaubt(this, false)) {
            installNachErlaubnis = true;
            Toast.makeText(this, "Einmalig: Bitte „Dieser Quelle vertrauen“ einschalten und dann zurück.",
                    Toast.LENGTH_LONG).show();
            Aktualisierung.installierenErlaubt(this, true);
            return;
        }
        ProgressBar balken = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        balken.setMax(100);
        int p = (int) (24 * d);
        balken.setPadding(p, p, p, p);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Update wird geladen …")
                .setView(balken)
                .setCancelable(false)
                .show();
        updateBanner.setEnabled(false);
        Aktualisierung.installieren(this, neu, pr -> {
            balken.setProgress(pr);
            if (pr >= 100) {
                // Geladen: Ab hier übernimmt Android (ggf. mit der Frage „Aktualisieren?“).
                dlg.dismiss();
                updateBanner.setEnabled(true);
            }
        }, (n, fehler) -> {
            if (dlg.isShowing()) dlg.dismiss();
            updateBanner.setEnabled(true);
            Toast.makeText(this, "Das Update konnte nicht geladen werden. Bitte später nochmal versuchen.",
                    Toast.LENGTH_LONG).show();
        });
    }

    // ---------- Person für "An ... schicken" ----------

    private void personWaehlen() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(i, REQ_KONTAKT);
        } catch (Exception e) {
            Toast.makeText(this, "Die Kontakte lassen sich nicht öffnen.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_KONTAKT || result != RESULT_OK || data == null || data.getData() == null) return;
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cur = getContentResolver().query(data.getData(), proj, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String name = cur.getString(0);
                String nummer = nummerFuerWhatsApp(cur.getString(1));
                if (nummer.length() < 8) {
                    Toast.makeText(this, "Diese Nummer passt nicht. Bitte eine Handynummer wählen.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (name == null || name.trim().isEmpty()) name = "+" + nummer;
                // Nur den Vornamen anzeigen, damit der Knopf kurz bleibt.
                String kurz = name.trim().split("\\s+")[0];
                prefs().edit().putString(FotoService.PERSON_NAME, kurz)
                        .putString(FotoService.PERSON_NUMMER, nummer).apply();
                personAnzeigen();
                Toast.makeText(this, "Fertig! Nach jedem Foto gibt es jetzt den Knopf „An " + kurz + " schicken“.",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Das hat leider nicht geklappt.", Toast.LENGTH_LONG).show();
        }
    }

    /** "0151 123 456" -> "49151123456" (deutsche Nummern ohne Vorwahl bekommen +49). */
    static String nummerFuerWhatsApp(String roh) {
        if (roh == null) return "";
        String s = roh.trim();
        boolean plus = s.startsWith("+");
        String ziffern = s.replaceAll("[^0-9]", "");
        if (plus) return ziffern;
        if (ziffern.startsWith("00")) return ziffern.substring(2);
        if (ziffern.startsWith("0")) return "49" + ziffern.substring(1);
        return ziffern;
    }

    private void personAnzeigen() {
        String name = prefs().getString(FotoService.PERSON_NAME, "");
        String nummer = prefs().getString(FotoService.PERSON_NUMMER, "");
        if (nummer.isEmpty()) {
            personText.setText("Wählen Sie eine Person aus Ihren Kontakten. Dann geht ein Foto mit einem Tipp per WhatsApp an sie.");
            person.setText("Person auswählen");
            auswahlFaerben(person, true);
            personWeg.setVisibility(View.GONE);
        } else {
            personText.setText("Fotos gehen mit einem Tipp an: " + name + " (+" + nummer + ")");
            person.setText("Andere Person wählen");
            auswahlFaerben(person, false);
            personWeg.setVisibility(View.VISIBLE);
            auswahlFaerben(personWeg, false);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(FotoService.PREFS, MODE_PRIVATE);
    }

    private Button auswahl(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(21);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    private void auswahlFaerben(Button b, boolean aktiv) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * d);
        bg.setColor(aktiv ? 0xFFE53935 : 0xFFFFFFFF);
        bg.setStroke((int) (2 * d), aktiv ? 0xFFE53935 : 0xFFBDBDBD);
        b.setBackground(bg);
        b.setTextColor(aktiv ? 0xFFFFFFFF : 0xFF424242);
    }

    private void auswahlAnzeigen() {
        int g = prefs().getInt(FotoService.GROESSE, 1);
        for (int i = 0; i < 3; i++) auswahlFaerben(groesse[i], i == g);
        boolean an = prefs().getBoolean(FotoService.DURCHSICHTIG, false);
        durchsichtig.setText(an ? "Etwas durchsichtig: AN" : "Etwas durchsichtig: AUS");
        auswahlFaerben(durchsichtig, an);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button knopf(String s, int color) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(24);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(20 * d);
        btn.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (88 * d));
        p.bottomMargin = (int) (16 * d);
        btn.setLayoutParams(p);
        return btn;
    }
}
