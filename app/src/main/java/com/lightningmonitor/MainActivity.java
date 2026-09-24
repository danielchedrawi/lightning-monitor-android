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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String SFL8_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL8?activeComponent=Yard+Monitoring+Widgets";

    private static final String SFL1_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL1?activeComponent=Yard+Monitoring+Widgets";

    private WebView webView;
    private Button sfl8Button;
    private Button sfl1Button;
    private TextView alarmStatus;
    private Button keplerTab;
    private Button lightningMapTab;
    private boolean showingLightningMap = false;

    private final Handler handler = new Handler();
    private ToneGenerator tone;

    private boolean currentClosed = false;
    private boolean alarmActive = false;
    private TextView lastEventText;
    private TextView updateText;
    private TextView connectionText;

    private String currentStation = "SFL8";
    private String lastEventSFL8 = "—";
    private String lastEventSFL1 = "—";
    private String sfl8Indicator = "OPEN";
    private String sfl1Indicator = "OPEN";
    private long lastUpdateAt = 0;
    private static final Pattern DISTANCE_PATTERN = Pattern.compile("DISTANCE\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*MI");
    private static final Pattern ISSUED_PATTERN = Pattern.compile("ISSUED\\s*AT\\s*:\\s*([0-9:]+\\s*[AP]M)");


    private final Runnable updateTicker = new Runnable() {
        @Override
        public void run() {
            if (updateText != null && lastUpdateAt > 0) {
                long seconds = Math.max(0, (System.currentTimeMillis() - lastUpdateAt) / 1000);
                updateText.setText("🔄 Actualizado: hace " + seconds + " s");
                if (connectionText != null) {
                    if (seconds <= 15) {
                        connectionText.setText("🟢 Conectado");
                    } else {
                        connectionText.setText("🔴 Sin conexión");
                    }
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

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
        lastEventText = findViewById(R.id.lastEventText);
        updateText = findViewById(R.id.updateText);
        updateText.setOnClickListener(v -> {
            lastUpdateAt = System.currentTimeMillis();
            webView.reload();
        });
        connectionText = findViewById(R.id.connectionText);
        Button stopAlarmButton = findViewById(R.id.stopAlarmButton);
        keplerTab = findViewById(R.id.keplerTab);
        lightningMapTab = findViewById(R.id.lightningMapTab);

        configureWebView();

        tone = new ToneGenerator(
                AudioManager.STREAM_ALARM,
                100
        );

        sfl8Button.setOnClickListener(v -> loadStation("SFL8"));
        sfl1Button.setOnClickListener(v -> loadStation("SFL1"));
        stopAlarmButton.setOnClickListener(v -> stopAlarm());

        keplerTab.setOnClickListener(v -> {
            showingLightningMap = false;
            loadStation(currentStation);
            keplerTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(55, 105, 175)));
            lightningMapTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 58)));
        });

        lightningMapTab.setOnClickListener(v -> {
            showingLightningMap = true;
            webView.loadUrl("https://lightningtracker.app/lightning-map/florida/orlando/");
            lightningMapTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(55, 105, 175)));
            keplerTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 58)));
        });
        stopAlarmButton.setText("🔕");
        stopAlarmButton.setTextSize(18);
        stopAlarmButton.setTextColor(Color.WHITE);
        stopAlarmButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(55, 59, 66))
        );

        sfl8Button.setTextSize(17);
        sfl8Button.setTextColor(Color.WHITE);
        sfl8Button.setAllCaps(false);

        sfl1Button.setTextSize(17);
        sfl1Button.setTextColor(Color.WHITE);
        sfl1Button.setAllCaps(false);


        loadStation("SFL8");

        handler.postDelayed(poller, 5000);
        handler.post(updateTicker);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);

        // Dejamos que Kepler51 determine su propio tamaño,
        // sin aplicar un zoom artificial.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                String desktopCanvas = "(function(){"
        + "var m=document.querySelector('meta[name=viewport]');"
        + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
        + "m.setAttribute('content','width=1200, initial-scale=1, maximum-scale=1, user-scalable=no');"
        + "function fit(){"
        + "var desktopW=1200;"
        + "var phoneW=document.documentElement.clientWidth;"
        + "var scale=phoneW/desktopW;"
        + "document.documentElement.style.width=desktopW+'px';"
        + "document.body.style.width=desktopW+'px';"
        + "document.body.style.margin='0';"
        + "document.body.style.transformOrigin='0 0';"
        + "document.body.style.transform='scale('+scale+')';"
        + "document.body.style.zoom='';"
        + "document.documentElement.style.overflowX='hidden';"
        + "document.body.style.overflowX='hidden';"
        + "document.body.style.minHeight=(document.body.scrollHeight*scale)+'px';"
        + "}"
        + "fit();"
        + "setTimeout(fit,1000);"
        + "setTimeout(fit,3000);"
        + "setTimeout(fit,6000);"
        + "})();";

                view.evaluateJavascript(desktopCanvas, null);
            }
        });
        webView.setInitialScale(60);
    }

    private void loadStation(String station) {
        currentStation = station;
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

        updateStationIndicator();
        updateLastEventLabel();

        alarmStatus.setText("🔔");
        alarmStatus.setBackgroundColor(Color.rgb(37, 40, 45));
    }

    private void updateStationIndicator() {
        sfl8Button.setText(android.text.Html.fromHtml(
                "SFL8  <font color='#00C853'>●</font>"));
        sfl1Button.setText(android.text.Html.fromHtml(
                "SFL1  <font color='#00C853'>●</font>"));
    }

    private void updateLastEventLabel() {
        if (lastEventText == null) return;
        String event = "SFL1".equals(currentStation) ? lastEventSFL1 : lastEventSFL8;
        lastEventText.setText("⚡ Último evento: " + event);
    }

    private void checkKeplerState() {
        if (showingLightningMap) {
            return;
        }
        if (webView == null) {
            return;
        }

        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                value -> {
                    if (value == null) {
                        return;
                   }

                     lastUpdateAt = System.currentTimeMillis();

                    if (connectionText != null) {
                          connectionText.setText("❍ Conectado");
                   }

                    String text = value
                          .replace("\\n", " ")
                            
                          .toUpperCase();

                    boolean closed = text.contains("CLOSED")
                            || text.contains("CERRADO");

                    boolean warning = text.contains("WARNING")
                             || text.contains("WARN");


                     if (closed && !currentClosed) {
                        currentClosed = true;

                          Matcher distanceMatcher = DISTANCE_PATTERN.matcher(text);
                          Matcher issuedMatcher = ISSUED_PATTERN.matcher(text);

                          String distance = distanceMatcher.find()
                                ? distanceMatcher.group(1) + "mi"
                               : "distancia no disponible";

                          String issued = issuedMatcher.find()
                                ? issuedMatcher.group(1)
                                 : "hora no disponible";

                          String event = issued + " ‗ " + distance;

                         if ("SFL11".equals(currentStation)) {
                               lastEventSFL1 = event;
                         } else {
                                lastEventSFL8= event;
                           }

                           updateLastEventLabel();
                           triggerAlarm();
                    } else if (!closed) {
                          currentClosed = false;
                    }

                    if ("SFL11".equals(currentStation)) {
                         if (closed) {
                               sfl1Indicator = "🍬";
                           } else if (warning) {
                                sfl1Indicator = "🟡";
                           } else {
                                sfl1Indicator = "<👰";
                           }
                        } else {
                         if (closed) {
                               sfl8Indicator = "🍬";
                           } else if (warning) {
                                sfl8Indicator = "🟡";
                           } else {
                                sfl8Indicator = "<👰";
                           }
                        }

                        updateStationIndicator();

                        if (closed) {
                            alarmStatus.setText("⟇️");
                          alarmStatus.setBackgroundColor(Color.rgb(190, 35, 35));
                        } else {
                          alarmStatus.setText("🐼");
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
