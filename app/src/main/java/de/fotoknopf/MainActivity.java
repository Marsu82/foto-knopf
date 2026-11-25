package de.fotoknopf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView status;
    private TextView anleitung;
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

        Button galerie = knopf("Meine Fotos ansehen", 0xFF1E88E5);
        galerie.setOnClickListener(v -> galerieOeffnen());
        box.addView(galerie);

        anleitung = text("", 21, 0xFF424242);
        anleitung.setPadding(0, pad, 0, 0);
        anleitung.setLineSpacing(0, 1.2f);
        box.addView(anleitung);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFFFFFFF);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (istEingeschaltet()) {
            status.setText("✅  Der Foto-Knopf ist an.");
            status.setTextColor(0xFF2E7D32);
            einschalten.setText("Einstellungen öffnen");
            anleitung.setText(
                    "So geht's:\n\n" +
                    "1.  Öffnen Sie WhatsApp, Instagram oder eine andere App.\n\n" +
                    "2.  Tippen Sie einmal auf den runden Knopf mit der Kamera.\n\n" +
                    "3.  Fertig! Das Foto ist in Ihrer Galerie gespeichert.\n\n" +
                    "Tipp: Wenn der Knopf im Weg ist, den Finger darauf halten " +
                    "und an eine andere Stelle schieben.");
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

    private void galerieOeffnen() {
        Intent[] versuche = {
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
                new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media"), "image/*")
        };
        for (Intent i : versuche) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) { }
        }
        Toast.makeText(this, "Bitte die App „Galerie“ oder „Fotos“ öffnen.", Toast.LENGTH_LONG).show();
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
