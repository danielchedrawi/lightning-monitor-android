package com.lightningmonitor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.media.ToneGenerator;
import android.media.AudioManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String SFL8_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL8?activeComponent=Yard+Monitoring+Widgets";

    private static final String SFL1_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL1?activeComponent=Yard+Monitoring+Widgets";

    private WebView webSfl8;
    private WebView webSfl1;
    private TextView alarmStatus;
    private TextView sfl8Last;
    private TextView sfl1Last;
    private Switch alertSwitch;

    private boolean sfl8Closed = false;
    private boolean sfl1Closed = false;
    private boolean alarmsEnabled = true;
    private boolean alarmActive = false;

    private final Handler handler = new Handler();
    private ToneGenerator tone;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            checkState(webSfl8, "SFL8");
            checkState(webSfl1, "SFL1");
            handler.postDelayed(this, 5000);
        }
    };

    private final Runnable alarmSound = new Runnable() {
        @Override
        public void run() {
            if (alarmActive) {
                if (tone != null) {
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
                }
                handler.postDelayed(this, 1400);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        alarmStatus = findViewById(R.id.alarmStatus);
        sfl8Last = findViewById(R.id.sfl8Last);
        sfl1Last = findViewById(R.id.sfl1Last);
        alertSwitch = findViewById(R.id.alertSwitch);

        webSfl8 = findViewById(R.id.webSfl8);
        webSfl1 = findViewById(R.id.webSfl1);

        tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);

        configureWebView(webSfl8, SFL8_URL);
        configureWebView(webSfl1, SFL1_URL);

        alertSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            alarmsEnabled = isChecked;
            if (!isChecked) {
                stopAlarm();
                alarmStatus.setText("MONITOREANDO — Alertas desactivadas");
                alarmStatus.setBackgroundColor(Color.DKGRAY);
            } else {
                alarmStatus.setText("MONITOREANDO — Alertas activadas");
                alarmStatus.setBackgroundColor(Color.rgb(23, 107, 44));
            }
        });

        Button testButton = findViewById(R.id.testButton);
        testButton.setOnClickListener(v -> triggerAlarm("PRUEBA DE ALARMA"));

        handler.postDelayed(poller, 8000);
    }

    private void configureWebView(WebView w, String url) {
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setLoadsImagesAutomatically(true);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setTextZoom(85);
        w.setInitialScale(85);

        w.setWebViewClient(new WebViewClient());
        w.loadUrl(url);
    }

    private void checkState(WebView web, String site) {
        web.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                value -> {
                    if (value == null) return;

                    String text = value
                            .replace("\\n", " ")
                            .replace("\\\"", "\"")
                            .toUpperCase(Locale.US);

                    boolean closed = text.contains("CLOSED");

                    if (site.equals("SFL8")) {
                        if (closed && !sfl8Closed) {
                            sfl8Last.setText("Último CLOSED: " + now());
                            if (alarmsEnabled) triggerAlarm("SFL8 — CLOSED");
                        }
                        sfl8Closed = closed;
                    } else {
                        if (closed && !sfl1Closed) {
                            sfl1Last.setText("Último CLOSED: " + now());
                            if (alarmsEnabled) triggerAlarm("SFL1 — CLOSED");
                        }
                        sfl1Closed = closed;
                    }
                }
        );
    }

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private void triggerAlarm(String message) {
        alarmActive = true;
        alarmStatus.setText("🔴 ALERTA: " + message);
        alarmStatus.setBackgroundColor(Color.RED);

        findViewById(R.id.root).setBackgroundColor(Color.RED);

        handler.removeCallbacks(alarmSound);
        handler.post(alarmSound);
    }

    private void stopAlarm() {
        alarmActive = false;
        handler.removeCallbacks(alarmSound);

        if (tone != null) {
            tone.stopTone();
        }

        alarmStatus.setText(alarmsEnabled
                ? "MONITOREANDO — Alertas activadas"
                : "MONITOREANDO — Alertas desactivadas");

        alarmStatus.setBackgroundColor(
                alarmsEnabled ? Color.rgb(23, 107, 44) : Color.DKGRAY
        );

        findViewById(R.id.root).setBackgroundColor(Color.rgb(16, 16, 16));
    }

    @Override
    public void onBackPressed() {
        if (alarmActive) {
            stopAlarm();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (tone != null) {
            tone.release();
            tone = null;
        }

        super.onDestroy();
    }
}
