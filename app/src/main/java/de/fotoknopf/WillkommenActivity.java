package de.fotoknopf;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Kurze Erklärung in drei Bildern, beim ersten Start und auf Wunsch. */
public class WillkommenActivity extends Activity {

    static final String GESEHEN = "willkommen_gesehen";

    private static final String[] TITEL = {
            "Ein Tipp – ein Foto",
            "Sofort verschicken",
            "Verschieben und verstecken",
    };
    private static final String[] TEXT = {
            "Tippen Sie einmal auf den roten Kamera-Knopf. Es klickt, und das Bildschirmfoto ist gespeichert.\n\n"
                    + "Selbstauslöser: Finger auf dem Knopf lassen, bis es kurz vibriert. Dann loslassen. Das Foto kommt nach 3 Sekunden.",
            "Nach dem Foto erscheint oben für ein paar Sekunden ein grüner Knopf.\n\n"
                    + "Antippen, dann öffnet sich WhatsApp mit dem Foto. Nur noch auf Senden tippen.",
            "Finger auf den Knopf legen und schieben: So kommt er an eine andere Stelle.\n\n"
                    + "Ganz nach unten ziehen und loslassen: Der Knopf ist eine Minute versteckt und kommt dann von selbst zurück.",
    };

    private int seite;
    private FrameLayout bildBox;
    private TextView titel, text, punkte;
    private Button weiter, zurueck;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * d);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFFFFFFFF);
        box.setPadding(pad, pad, pad, pad);

        bildBox = new FrameLayout(this);
        bildBox.setBackground(rund(0xFFFDECEA, 28));
        box.addView(bildBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (230 * d)));

        punkte = new TextView(this);
        punkte.setTextSize(22);
        punkte.setGravity(Gravity.CENTER);
        punkte.setTextColor(0xFFE53935);
        punkte.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(punkte);

        titel = new TextView(this);
        titel.setTextSize(30);
        titel.setTypeface(Typeface.DEFAULT_BOLD);
        titel.setTextColor(0xFF212121);
        titel.setPadding(0, (int) (8 * d), 0, (int) (12 * d));
        box.addView(titel);

        text = new TextView(this);
        text.setTextSize(21);
        text.setTextColor(0xFF424242);
        text.setLineSpacing(0, 1.2f);
        box.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout reihe = new LinearLayout(this);
        reihe.setOrientation(LinearLayout.HORIZONTAL);
        zurueck = knopf("Zurück", 0xFF9E9E9E);
        zurueck.setOnClickListener(v -> { seite--; zeigen(); });
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(0, (int) (80 * d), 1f);
        zlp.rightMargin = (int) (12 * d);
        reihe.addView(zurueck, zlp);
        weiter = knopf("Weiter", 0xFFE53935);
        weiter.setOnClickListener(v -> {
            if (seite < TITEL.length - 1) { seite++; zeigen(); }
            else fertig();
        });
        reihe.addView(weiter, new LinearLayout.LayoutParams(0, (int) (80 * d), 2f));
        box.addView(reihe);

        setContentView(box);
        zeigen();
    }

    private void zeigen() {
        titel.setText(TITEL[seite]);
        text.setText(TEXT[seite]);
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < TITEL.length; i++) p.append(i == seite ? "●  " : "○  ");
        punkte.setText(p.toString().trim());
        zurueck.setVisibility(seite == 0 ? View.INVISIBLE : View.VISIBLE);
        weiter.setText(seite == TITEL.length - 1 ? "Los geht's!" : "Weiter");
        bildBox.removeAllViews();
        bildBox.addView(bild(seite), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** Einfache Bilder aus den echten Bausteinen der App. */
    private View bild(int s) {
        if (s == 1) {
            TextView pille = new TextView(this);
            pille.setText("➤  An … schicken");
            pille.setTextSize(24);
            pille.setTypeface(Typeface.DEFAULT_BOLD);
            pille.setTextColor(0xFFFFFFFF);
            int ph = (int) (26 * d), pv = (int) (15 * d);
            pille.setPadding(ph, pv, ph, pv);
            GradientDrawable bg = rund(0xFF2E7D32, 40);
            bg.setStroke((int) (3 * d), 0xFFFFFFFF);
            pille.setBackground(bg);
            pille.setElevation(8 * d);
            return pille;
        }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.addView(kameraKnopf((int) (110 * d)));
        if (s == 2) {
            TextView pfeil = new TextView(this);
            pfeil.setText("↓");
            pfeil.setTextSize(44);
            pfeil.setTypeface(Typeface.DEFAULT_BOLD);
            pfeil.setTextColor(0xFF616161);
            pfeil.setGravity(Gravity.CENTER);
            l.addView(pfeil);
        }
        return l;
    }

    private View kameraKnopf(int size) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_camera);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        bg.setStroke((int) (4 * d), 0xFFB71C1C);
        iv.setBackground(bg);
        int p = (int) (5 * d);
        iv.setPadding(p, p, p, p);
        iv.setElevation(8 * d);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return iv;
    }

    private void fertig() {
        getSharedPreferences(FotoService.PREFS, MODE_PRIVATE).edit().putBoolean(GESEHEN, true).apply();
        finish();
    }

    @Override
    public void onBackPressed() {
        fertig();
    }

    private GradientDrawable rund(int farbe, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(farbe);
        g.setCornerRadius(radiusDp * d);
        return g;
    }

    private Button knopf(String s, int color) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(24);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(rund(color, 20));
        return btn;
    }
}
