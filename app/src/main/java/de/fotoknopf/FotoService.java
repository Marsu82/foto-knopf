package de.fotoknopf;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * Zeigt einen großen, verschiebbaren Knopf über allen Apps.
 * Ein Tipp darauf löst das normale Android-Bildschirmfoto aus.
 */
public class FotoService extends AccessibilityService {

    private WindowManager wm;
    private ImageView knopf;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SoundPool sounds;
    private int klickId;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Eigener Klick-Ton über die normale Medien-Lautstärke (Lautstärketasten).
        sounds = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        klickId = sounds.load(this, R.raw.klick, 1);
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (76 * d);

        knopf = new ImageView(this);
        knopf.setImageResource(R.drawable.ic_camera);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        bg.setStroke((int) (3 * d), 0xFFB71C1C);
        knopf.setBackground(bg);
        knopf.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        knopf.setElevation(8 * d);
        knopf.setContentDescription("Bildschirmfoto machen");

        SharedPreferences prefs = getSharedPreferences("pos", MODE_PRIVATE);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", getResources().getDisplayMetrics().widthPixels - size - (int) (12 * d));
        lp.y = prefs.getInt("y", getResources().getDisplayMetrics().heightPixels / 2);

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
                        if (Math.abs(dx) > schwelle || Math.abs(dy) > schwelle) bewegt = true;
                        if (bewegt) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            wm.updateViewLayout(knopf, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(1f);
                        if (bewegt) {
                            prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        } else {
                            fotoMachen();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1f);
                        return true;
                }
                return false;
            }
        });

        wm.addView(knopf, lp);
    }

    private void fotoMachen() {
        // Knopf kurz verstecken, damit er nicht mit auf dem Foto ist.
        knopf.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            boolean ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            if (ok) {
                rueckmeldung();
            } else {
                Toast.makeText(this, "Das Foto hat leider nicht geklappt.", Toast.LENGTH_LONG).show();
            }
            handler.postDelayed(() -> knopf.setVisibility(View.VISIBLE), 1500);
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
                // Als Bedienungshilfe kennzeichnen, sonst blendet Android sie im Hintergrund aus.
                v.vibrate(effekt, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY));
            } else {
                v.vibrate(effekt, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build());
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (sounds != null) sounds.release();
        if (knopf != null && wm != null) {
            try { wm.removeView(knopf); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
