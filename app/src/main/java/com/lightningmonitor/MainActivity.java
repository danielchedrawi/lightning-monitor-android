package com.lightningmonitor;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String SFL8_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL8?activeComponent=Yard+Monitoring+Widgets";

    private static final String SFL1_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL1?activeComponent=Yard+Monitoring+Widgets";

    private WebView webView;
    private Button sfl8Button;
    private Button sfl1Button;
    private TextView alarmStatus;

    private final Handler handler = new Handler();
    private ToneGenerator tone;

    private boolean currentClosed = false;
    private boolean alarmActive = false;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            checkKeplerState();
            handler.postDelayed(this, 5000);
        }
    };

    private final Runnable alarmSound = new Runnable() {
        @Override
        public void run() {
            if (!alarmActive) {
                return;
            }

            if (tone != null) {
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350);
            }

            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        android.view.View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        webView = findViewById(R.id.webView);
        sfl8Button = findViewById(R.id.sfl8Button);
        sfl1Button = findViewById(R.id.sfl1Button);
        alarmStatus = findViewById(R.id.alarmStatus);
        Button testAlarmButton = findViewById(R.id.testAlarmButton);

        configureWebView();

        tone = new ToneGenerator(
                AudioManager.STREAM_ALARM,
                100
        );

        sfl8Button.setOnClickListener(v -> loadStation("SFL8"));
        sfl1Button.setOnClickListener(v -> loadStation("SFL1"));
        testAlarmButton.setOnClickListener(v -> triggerAlarm());

        loadStation("SFL8");

        handler.postDelayed(poller, 5000);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);

        // Dejamos que Kepler51 determine su propio tamaño,
        // sin aplicar un zoom artificial.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
    }

    private void loadStation(String station) {
        currentClosed = false;
        stopAlarm();

        if ("SFL1".equals(station)) {
            sfl1Button.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.rgb(55, 105, 175))
            );
            sfl8Button.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 58))
            );

            webView.loadUrl(SFL1_URL);

        } else {
            sfl8Button.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.rgb(55, 105, 175))
            );
            sfl1Button.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 58))
            );

            webView.loadUrl(SFL8_URL);
        }

        alarmStatus.setText("🔔");
        alarmStatus.setBackgroundColor(Color.rgb(37, 40, 45));
    }

    private void checkKeplerState() {
        if (webView == null) {
            return;
        }

        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                value -> {

                    if (value == null) {
                        return;
                    }

                    String text = value
                            .replace("\\n", " ")
                            .replace("\\\"", "\"")
                            .toUpperCase();

                    boolean closed = text.contains("CLOSED")
                            || text.contains("CERRADO");

                    if (closed && !currentClosed) {
                        currentClosed = true;
                        triggerAlarm();
                    }

                    if (!closed) {
                        currentClosed = false;
                    }

                    if (closed) {
                        alarmStatus.setText("⚠️");
                        alarmStatus.setBackgroundColor(Color.rgb(190, 35, 35));
                    } else {
                        alarmStatus.setText("🔔");
                        alarmStatus.setBackgroundColor(Color.rgb(37, 40, 45));
                    }
                }
        );
    }

    private void triggerAlarm() {
        if (alarmActive) {
            return;
        }

        alarmActive = true;
        alarmStatus.setText("🚨");
        alarmStatus.setBackgroundColor(Color.rgb(190, 35, 35));

        handler.post(alarmSound);

        // La alarma se detiene automáticamente después de 5 segundos.
        handler.postDelayed(this::stopAlarm, 5000);
    }

    private void stopAlarm() {
        alarmActive = false;
        handler.removeCallbacks(alarmSound);

        if (alarmStatus != null) {
            if (currentClosed) {
                alarmStatus.setText("⚠️");
                alarmStatus.setBackgroundColor(Color.rgb(190, 35, 35));
            } else {
                alarmStatus.setText("🔔");
                alarmStatus.setBackgroundColor(Color.rgb(37, 40, 45));
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopAlarm();

        if (tone != null) {
            tone.release();
            tone = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }

        super.onDestroy();
    }
}
