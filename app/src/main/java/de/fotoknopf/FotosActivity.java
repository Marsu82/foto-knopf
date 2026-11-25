package de.fotoknopf;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Zeigt nur die Bildschirmfotos, die neuesten zuerst. Antippen öffnet das Foto groß. */
public class FotosActivity extends Activity {

    private static final int REQ = 7;
    private static final int REQ_LOESCHEN = 8;
    private static final int REQ_SCHREIBEN = 9;
    private Uri wartet;
    private final List<Uri> fotos = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<Uri, Bitmap> cache = new LruCache<Uri, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(Uri k, Bitmap b) { return b.getByteCount(); }
    };
    private GridView grid;
    private TextView hinweis;
    private Button erlauben;
    private Adapter adapter;
    private float d;
    private boolean schonGefragt;
    private int kachel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFFFFFFFF);
        box.setPadding(pad, pad, pad, 0);

        Button zurueck = knopf("← Zurück", 0xFF616161);
        zurueck.setOnClickListener(v -> finish());
        box.addView(zurueck);

        TextView titel = new TextView(this);
        titel.setText("Meine Bildschirmfotos");
        titel.setTextSize(30);
        titel.setTypeface(Typeface.DEFAULT_BOLD);
        titel.setTextColor(0xFF212121);
        titel.setPadding(0, pad, 0, (int) (4 * d));
        box.addView(titel);

        TextView sub = new TextView(this);
        sub.setText("Das neueste ist oben links.\nAntippen: ansehen oder verschicken.\nLänger drücken: löschen.");
        sub.setTextSize(19);
        sub.setTextColor(0xFF555555);
        sub.setPadding(0, 0, 0, pad);
        box.addView(sub);

        hinweis = new TextView(this);
        hinweis.setTextSize(22);
        hinweis.setTextColor(0xFF424242);
        hinweis.setGravity(Gravity.CENTER);
        hinweis.setPadding(0, pad * 2, 0, pad);
        hinweis.setVisibility(View.GONE);
        box.addView(hinweis);

        erlauben = knopf("Zugriff erlauben", 0xFFE53935);
        erlauben.setVisibility(View.GONE);
        erlauben.setOnClickListener(v -> zugriffAnfragen(true));
        box.addView(erlauben);

        int breite = getResources().getDisplayMetrics().widthPixels - 2 * pad;
        int spalten = 2;
        int abstand = (int) (12 * d);
        kachel = (breite - abstand) / spalten;

        grid = new GridView(this);
        grid.setNumColumns(spalten);
        grid.setHorizontalSpacing(abstand);
        grid.setVerticalSpacing(abstand);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, pad);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> oeffnen(fotos.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            loeschenFragen(fotos.get(pos));
            return true;
        });
        box.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hatZugriff()) {
            laden();
        } else if (!schonGefragt) {
            // Nur einmal automatisch fragen, sonst kommt der Dialog immer wieder.
            schonGefragt = true;
            zugriffAnfragen(false);
        }
    }

    private String recht() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hatZugriff() {
        if (checkSelfPermission(recht()) == PackageManager.PERMISSION_GRANTED) return true;
        // Android 14: "Nur ausgewählte Fotos" zählt auch als Zugriff.
        return Build.VERSION.SDK_INT >= 34 && checkSelfPermission(
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED;
    }

    private void zugriffAnfragen(boolean vomKnopf) {
        if (vomKnopf && !shouldShowRequestPermissionRationale(recht())
                && getSharedPreferences("p", MODE_PRIVATE).getBoolean("gefragt", false)) {
            // Schon dauerhaft abgelehnt: App-Einstellungen öffnen.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            Toast.makeText(this, "Bitte „Berechtigungen“ → „Fotos“ → „Immer alle zulassen“ wählen",
                    Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("gefragt", true).apply();
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{recht(), "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"}, REQ);
        } else {
            requestPermissions(new String[]{recht()}, REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_SCHREIBEN) {
            if (wartet != null && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
                Uri u = wartet; wartet = null;
                loeschen(u);
            }
            return;
        }
        if (code != REQ) return;
        if (hatZugriff()) {
            laden();
        } else {
            grid.setVisibility(View.GONE);
            hinweis.setText("Damit die App Ihre Bildschirmfotos zeigen kann, braucht sie einmal Ihre Erlaubnis.\n\nBitte unten tippen und „Alle zulassen“ wählen.");
            hinweis.setVisibility(View.VISIBLE);
            erlauben.setVisibility(View.VISIBLE);
        }
    }

    private void laden() {
        erlauben.setVisibility(View.GONE);
        pool.execute(() -> {
            List<Uri> neu = new ArrayList<>();
            Uri basis = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String spalte = Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA;
            String[] proj = {MediaStore.Images.Media._ID};
            String sel = spalte + " LIKE ?";
            String[] args = {"%Screenshot%"};
            try (Cursor c = getContentResolver().query(basis, proj, sel, args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (c.moveToNext() && neu.size() < 500) {
                        neu.add(ContentUris.withAppendedId(basis, c.getLong(idx)));
                    }
                }
            } catch (Exception ignored) { }
            ui.post(() -> {
                fotos.clear();
                fotos.addAll(neu);
                adapter.notifyDataSetChanged();
                boolean leer = fotos.isEmpty();
                grid.setVisibility(leer ? View.GONE : View.VISIBLE);
                hinweis.setText("Noch keine Bildschirmfotos.\n\nTippen Sie auf den roten Kamera-Knopf, dann erscheint das Foto hier.");
                hinweis.setVisibility(leer ? View.VISIBLE : View.GONE);
            });
        });
    }

    // ---------- Löschen ----------

    private void loeschenFragen(Uri uri) {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android fragt selbst nach ("Zulassen?") und legt das Foto in den Papierkorb.
            try {
                List<Uri> l = new ArrayList<>();
                l.add(uri);
                android.app.PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), l, true);
                startIntentSenderForResult(pi.getIntentSender(), REQ_LOESCHEN, null, 0, 0, 0);
            } catch (Exception e) {
                Toast.makeText(this, "Löschen geht gerade nicht.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Dieses Foto löschen?")
                .setMessage("Das Bildschirmfoto wird vom Handy gelöscht.")
                .setPositiveButton("Ja, löschen", (dlg, w) -> loeschen(uri))
                .setNegativeButton("Nein, behalten", null)
                .show();
    }

    private void loeschen(Uri uri) {
        try {
            getContentResolver().delete(uri, null, null);
            geloescht();
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT == 29 && e instanceof android.app.RecoverableSecurityException) {
                wartet = uri;
                try {
                    startIntentSenderForResult(((android.app.RecoverableSecurityException) e)
                            .getUserAction().getActionIntent().getIntentSender(), REQ_LOESCHEN, null, 0, 0, 0);
                } catch (Exception ignored) { }
            } else if (Build.VERSION.SDK_INT <= 28) {
                wartet = uri;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_SCHREIBEN);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Löschen geht gerade nicht.", Toast.LENGTH_LONG).show();
        }
    }

    private void geloescht() {
        Toast.makeText(this, "Foto gelöscht.", Toast.LENGTH_SHORT).show();
        laden();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_LOESCHEN || result != RESULT_OK) return;
        if (Build.VERSION.SDK_INT >= 30) {
            geloescht();
        } else if (wartet != null) {
            Uri u = wartet; wartet = null;
            loeschen(u);
        }
    }

    // ---------- Ansehen ----------

    private void oeffnen(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Das Foto lässt sich nicht öffnen.", Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap vorschau(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(uri, new Size(kachel, kachel), null);
            }
            return MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(),
                    ContentUris.parseId(uri), MediaStore.Images.Thumbnails.MINI_KIND, null);
        } catch (Exception e) {
            return null;
        }
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return fotos.size(); }
        @Override public Object getItem(int p) { return fotos.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View alt, ViewGroup parent) {
            ImageView iv = alt instanceof ImageView ? (ImageView) alt : new ImageView(FotosActivity.this);
            iv.setLayoutParams(new GridView.LayoutParams(kachel, (int) (kachel * 1.6f)));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFEEEEEE);
            iv.setContentDescription("Bildschirmfoto " + (pos + 1));
            Uri uri = fotos.get(pos);
            iv.setTag(uri);
            Bitmap bm = cache.get(uri);
            iv.setImageBitmap(bm);
            if (bm == null) {
                pool.execute(() -> {
                    Bitmap neu = vorschau(uri);
                    if (neu == null) return;
                    cache.put(uri, neu);
                    ui.post(() -> { if (uri.equals(iv.getTag())) iv.setImageBitmap(neu); });
                });
            }
            return iv;
        }
    }

    private Button knopf(String s, int color) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(22);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(18 * d);
        btn.setBackground(bg);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (64 * d)));
        return btn;
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
