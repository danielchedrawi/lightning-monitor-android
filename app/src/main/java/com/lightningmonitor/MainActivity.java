package com.lightningmonitor;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String SFL8_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL8?activeComponent=Yard+Monitoring+Widgets";

    private static final String SFL1_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL1?activeComponent=Yard+Monitoring+Widgets";

    private WebView webActive;
    private TextView alarmStatus;
    private TextView lastIssued;
    private TextView lastUpdated;
    private TextView stationHeader;
    private TextView stationIndicator;
    private TextView stationDots;
    private Switch alertSwitch;

    private boolean sfl8Closed = false;
    private boolean sfl1Closed = false;
    private boolean alarmsEnabled = true;
    private boolean alarmActive = false;
    private String activeSite = "SFL8";

    private final Handler handler = new Handler();
    private ToneGenerator tone;

    private final Runnable fallbackPoller = new Runnable() {
        @Override
        public void run() {
            checkState(webActive, activeSite);
            handler.postDelayed(this, 30000);
        }
    };

    private final Runnable alarmSound = new Runnable() {
        @Override
        public void run() {
            if (alarmActive) {
                if (tone != null) {
                    tone.startTone(
                            ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
                            700
                    );
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
        lastIssued = findViewById(R.id.lastIssued);
        lastUpdated = findViewById(R.id.lastUpdated);
        alertSwitch = findViewById(R.id.alertSwitch);

        webActive = findViewById(R.id.webActive);

        tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);

        configureWebView();


        alertSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            alarmsEnabled = isChecked;

            if (!isChecked) {
                stopAlarm();
            } else {
                alarmStatus.setText("MONITOREANDO — Alertas activadas");
                alarmStatus.setBackgroundColor(Color.rgb(23, 107, 44));
            }
        });

        Button testButton = findViewById(R.id.testButton);
        testButton.setOnClickListener(v -> triggerAlarm("PRUEBA DE ALARMA"));

        stationHeader = findViewById(R.id.stationHeader);
        stationIndicator = findViewById(R.id.stationIndicator);
        stationDots = findViewById(R.id.stationDots);

        Button prevButton = findViewById(R.id.prevButton);
        Button nextButton = findViewById(R.id.nextButton);

        prevButton.setOnClickListener(v -> switchStation("SFL8"));
        nextButton.setOnClickListener(v -> switchStation("SFL1"));

        webActive.loadUrl(SFL8_URL);
        updateStationNavigation();

        handler.postDelayed(fallbackPoller, 8000);
    }

    private void updateStationNavigation() {
        boolean sfl8 = activeSite.equals("SFL8");
        stationHeader.setText(activeSite);
        stationIndicator.setText(sfl8 ? "Estación 1 de 2" : "Estación 2 de 2");
        stationDots.setText(sfl8 ? "●  ○" : "○  ●");
    }

    private void configureWebView() {
        WebSettings st = webActive.getSettings();

        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setLoadsImagesAutomatically(true);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setTextZoom(85);

        webActive.setInitialScale(85);

        webActive.addJavascriptInterface(
                new KeplerBridge(),
                "Android"
        );

        webActive.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                installRealtimeObserver(view);
                checkState(view, activeSite);
            }
        });
    }

    private void switchStation(String site) {
        if (site.equals(activeSite)) return;

        activeSite = site;

        if (activeSite.equals("SFL8")) {
            webActive.loadUrl(SFL8_URL);
        } else {
            webActive.loadUrl(SFL1_URL);
        }

        updateStationNavigation();
    }

    private void installRealtimeObserver(WebView web) {
        String js =
                "(function(){"
                + "if(window.__lightningObserver){"
                + "window.__lightningObserver.disconnect();"
                + "}"
                + "var target=document.body||document.documentElement;"
                + "if(!target)return;"
                + "var timer;"
                + "function send(){"
                + "clearTimeout(timer);"
                + "timer=setTimeout(function(){"
                + "try{"
                + "if(window.Android){"
                + "window.Android.state("
                + "document.body?document.body.innerText:''"
                + ");"
                + "}"
                + "}catch(e){}"
                + "},250);"
                + "}"
                + "window.__lightningObserver="
                + "new MutationObserver(function(){send();});"
                + "window.__lightningObserver.observe("
                + "target,"
                + "{subtree:true,childList:true,"
                + "characterData:true,attributes:true}"
                + ");"
                + "send();"
                + "})()";

        web.evaluateJavascript(js, value -> {});
    }

    private class KeplerBridge {

        @JavascriptInterface
        public void state(String text) {
            runOnUiThread(() -> processText(activeSite, text));
        }
    }

    private void processText(String site, String rawText) {
        if (rawText == null) return;

        String text = rawText.replace('\u00A0', ' ');

        String upper = text.toUpperCase(Locale.US);

        boolean closed = upper.contains("CLOSED");

        String issued = extractValue(
                text,
                "Issued\\s+at\\s*:?\\s*([^\\r\\n]+)"
        );

        String updated = extractValue(
                text,
                "Updated\\s+at\\s*:?\\s*([^\\r\\n]+)"
        );

        if (site.equals("SFL8")) {

            if (closed && !sfl8Closed) {
                if (issued != null) {
                    lastIssued.setText("Issued at: " + cleanTime(issued));
                }

                if (updated != null) {
                    lastUpdated.setText("Updated at: " + cleanTime(updated));
                }

                if (alarmsEnabled) {
                    triggerAlarm("SFL8 — CLOSED");
                }
            }

            sfl8Closed = closed;

        } else {

            if (closed && !sfl1Closed) {
                if (issued != null) {
                    lastIssued.setText("Issued at: " + cleanTime(issued));
                }

                if (updated != null) {
                    lastUpdated.setText("Updated at: " + cleanTime(updated));
                }

                if (alarmsEnabled) {
                    triggerAlarm("SFL1 — CLOSED");
                }
            }

            sfl1Closed = closed;
        }

        if (closed) {
            if (issued != null) {
                lastIssued.setText("Issued at: " + cleanTime(issued));
            }

            if (updated != null) {
                lastUpdated.setText("Updated at: " + cleanTime(updated));
            }
        }
    }

    private String extractValue(String text, String patternText) {
        Pattern pattern = Pattern.compile(
                patternText,
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    private String cleanTime(String value) {
        String cleaned = value.trim();

        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }

        return cleaned;
    }

    private void checkState(WebView web, String site) {
        if (web == null) return;

        web.evaluateJavascript(
                "(function(){"
                + "return document.body ? "
                + "document.body.innerText : '';"
                + "})()",
                value -> {
                    if (value == null) return;

                    processText(site, value);
                }
        );
    }

    private void triggerAlarm(String message) {
        alarmActive = true;

        alarmStatus.setText("🔴 ALERTA: " + message);
        alarmStatus.setBackgroundColor(Color.RED);

        findViewById(R.id.root)
                .setBackgroundColor(Color.RED);

        handler.removeCallbacks(alarmSound);
        handler.post(alarmSound);
    }

    private void stopAlarm() {
        alarmActive = false;
        handler.removeCallbacks(alarmSound);

        if (tone != null) {
            tone.stopTone();
        }

        alarmStatus.setText(
                alarmsEnabled
                        ? "MONITOREANDO — Alertas activadas"
                        : "MONITOREANDO — Alertas desactivadas"
        );

        alarmStatus.setBackgroundColor(
                alarmsEnabled
                        ? Color.rgb(23, 107, 44)
                        : Color.DKGRAY
        );

        findViewById(R.id.root)
                .setBackgroundColor(Color.rgb(16, 16, 16));
    }

    private String now() {
        return new SimpleDateFormat(
                "HH:mm:ss",
                Locale.US
        ).format(new Date());
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
