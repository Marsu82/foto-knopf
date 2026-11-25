package de.fotoknopf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Rückmeldung von Android zur Update-Installation. */
public class InstallEmpfaenger extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                // Android möchte, dass man das Update bestätigt.
                Intent bestaetigen = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (bestaetigen != null) {
                    bestaetigen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(bestaetigen);
                    } catch (Exception e) {
                        Toast.makeText(c, "Bitte die Foto-Knopf-App öffnen und nochmal „Aktualisieren“ tippen.",
                                Toast.LENGTH_LONG).show();
                    }
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText(c, "Foto-Knopf ist jetzt auf dem neuesten Stand.", Toast.LENGTH_LONG).show();
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                Toast.makeText(c, "Update abgebrochen. Es kann später nachgeholt werden.", Toast.LENGTH_LONG).show();
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                Toast.makeText(c, "Das Handy hat das Update blockiert (z. B. „Automatische Sperre“ oder Play Protect).",
                        Toast.LENGTH_LONG).show();
                break;
            default:
                Toast.makeText(c, "Das Update hat leider nicht geklappt.", Toast.LENGTH_LONG).show();
        }
    }
}
