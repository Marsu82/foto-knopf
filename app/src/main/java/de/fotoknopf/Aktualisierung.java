package de.fotoknopf;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Sucht auf GitHub nach einer neuen Version und installiert sie.
 * Es wird nur die Datei version.json und ggf. die neue APK geladen – es werden keine Daten gesendet.
 */
final class Aktualisierung {

    static final String VERSION_URL =
            "https://raw.githubusercontent.com/Marsu82/foto-knopf/main/download/version.json";
    static final String EXTRA_UPDATE = "de.fotoknopf.UPDATE";
    private static final String KANAL = "updates";
    private static final int NOTIF_ID = 42;
    private static final long TAG_MS = 20L * 60 * 60 * 1000;

    /** Infos über eine neuere Version. */
    static final class Neu {
        final long code;
        final String name, apk, hinweis;
        Neu(long code, String name, String apk, String hinweis) {
            this.code = code; this.name = name; this.apk = apk; this.hinweis = hinweis;
        }
    }

    interface Ergebnis { void fertig(Neu neu, boolean fehler); }
    interface Fortschritt { void prozent(int p); }

    private Aktualisierung() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FotoService.PREFS, Context.MODE_PRIVATE);
    }

    static long eigeneVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** Prüft im Hintergrund; das Ergebnis kommt auf dem UI-Thread. */
    static void pruefen(Context ctx, Ergebnis ergebnis) {
        Context c = ctx.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Neu neu = null;
            boolean fehler = false;
            try {
                JSONObject j = new JSONObject(new String(laden(VERSION_URL, 64 * 1024, null), "UTF-8"));
                long code = j.getLong("versionCode");
                String apk = j.getString("apk");
                if (code > eigeneVersion(c) && apk.startsWith("https://")) {
                    neu = new Neu(code, j.optString("versionName", ""), apk, j.optString("hinweis", ""));
                }
                prefs(c).edit().putLong("update_zuletzt", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                fehler = true;
            }
            Neu n = neu; boolean f = fehler;
            ui.post(() -> ergebnis.fertig(n, f));
        }).start();
    }

    /** Einmal am Tag prüfen und bei Bedarf eine Benachrichtigung zeigen (vom Dienst aufgerufen). */
    static void taeglichPruefen(Context c) {
        long zuletzt = prefs(c).getLong("update_zuletzt", 0);
        if (System.currentTimeMillis() - zuletzt < TAG_MS) return;
        pruefen(c, (neu, fehler) -> { if (neu != null) benachrichtigen(c, neu); });
    }

    static void benachrichtigen(Context c, Neu neu) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(KANAL, "Updates", NotificationManager.IMPORTANCE_DEFAULT));
        Intent i = new Intent(c, MainActivity.class).putExtra(EXTRA_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new android.app.Notification.Builder(c, KANAL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Neue Version vom Foto-Knopf")
                .setContentText("Zum Aktualisieren hier antippen.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIF_ID, n);
        } catch (SecurityException ignored) { }
    }

    static void benachrichtigungWeg(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    /** Darf die App selbst Updates installieren? Sonst Einstellung öffnen. */
    static boolean installierenErlaubt(Activity a, boolean einstellungOeffnen) {
        if (a.getPackageManager().canRequestPackageInstalls()) return true;
        if (einstellungOeffnen) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        }
        return false;
    }

    /** Lädt die neue APK und übergibt sie an Android zum Installieren. */
    static void installieren(Context ctx, Neu neu, Fortschritt fortschritt, Ergebnis fehlerMeldung) {
        Context c = ctx.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                byte[] apk = laden(neu.apk, 200 * 1024 * 1024,
                        p -> ui.post(() -> fortschritt.prozent(p)));
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                params.setSize(apk.length);
                if (Build.VERSION.SDK_INT >= 31) {
                    // Ab Android 12: Updates der eigenen App oft ohne Nachfrage.
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (OutputStream out = s.openWrite("foto-knopf.apk", 0, apk.length)) {
                        out.write(apk);
                        s.fsync(out);
                    }
                    Intent rueck = new Intent(c, InstallEmpfaenger.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent p = PendingIntent.getBroadcast(c, id, rueck, flags);
                    s.commit(p.getIntentSender());
                }
                benachrichtigungWeg(c);
            } catch (Exception e) {
                ui.post(() -> fehlerMeldung.fertig(neu, true));
            }
        }).start();
    }

    private static byte[] laden(String adresse, int maxBytes, Fortschritt fortschritt) throws Exception {
        String url = adresse;
        for (int umleitung = 0; umleitung < 5; umleitung++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Cache-Control", "no-cache");
            int status = con.getResponseCode();
            if (status >= 300 && status < 400) {
                String ziel = con.getHeaderField("Location");
                con.disconnect();
                if (ziel == null || !ziel.startsWith("https://")) throw new Exception("Umleitung");
                url = ziel;
                continue;
            }
            if (status != 200) throw new Exception("HTTP " + status);
            long gesamt = con.getContentLengthLong();
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n, letzt = -1;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > maxBytes) throw new Exception("zu groß");
                    if (fortschritt != null && gesamt > 0) {
                        int p = (int) (buf.size() * 100 / gesamt);
                        if (p != letzt) { letzt = p; fortschritt.prozent(p); }
                    }
                }
                return buf.toByteArray();
            } finally {
                con.disconnect();
            }
        }
        throw new Exception("zu viele Umleitungen");
    }
}
