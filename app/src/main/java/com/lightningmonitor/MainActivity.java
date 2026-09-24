package com.lightningmonitor;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String SFL8_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL8?activeComponent=Yard+Monitoring+Widgets";

    private static final String SFL1_URL =
            "https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL1?activeComponent=Yard+Monitoring+Widgets";

    // Station coordinates used by the clean map overlay.
    private static final double SFL8_LAT = 28.4227649;
    private static final double SFL8_LON = -81.33935997;
    private static final double SFL1_LAT = 28.459635;
    private static final double SFL1_LON = -81.439527;

    private WebView webView;
    private WebView monitorWebView;
    private Button sfl8Button;
    private Button sfl1Button;
    private TextView alarmStatus;
    private Button lightningMapTab;
    private Button sfl8MapButton;
    private Button sfl1MapButton;
    private boolean showingLightningMap = false;

    private final Handler handler = new Handler();
    private ToneGenerator tone;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1001;
    private static final String ALERT_CHANNEL_ID = "lightning_alerts_v2";

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
        monitorWebView = findViewById(R.id.monitorWebView);
        sfl8Button = findViewById(R.id.sfl8Button);
        sfl1Button = findViewById(R.id.sfl1Button);
        alarmStatus = findViewById(R.id.alarmStatus);
        lastEventText = findViewById(R.id.lastEventText);
        updateText = findViewById(R.id.updateText);
        updateText.setOnClickListener(v -> {
            lastUpdateAt = System.currentTimeMillis();
            if (monitorWebView != null) monitorWebView.reload();
            if (webView != null) webView.reload();
        });
        connectionText = findViewById(R.id.connectionText);
        Button stopAlarmButton = findViewById(R.id.stopAlarmButton);
        lightningMapTab = findViewById(R.id.lightningMapTab);
        sfl8MapButton = findViewById(R.id.sfl8MapButton);
        sfl1MapButton = findViewById(R.id.sfl1MapButton);

        configureWebView();
        configureMonitorWebView();
        monitorWebView.loadUrl(SFL8_URL);

        tone = new ToneGenerator(
                AudioManager.STREAM_ALARM,
                100
        );

        createNotificationChannel();
        requestNotificationPermission();

        sfl8Button.setOnClickListener(v -> loadStation("SFL8"));
        sfl1Button.setOnClickListener(v -> loadStation("SFL1"));
        stopAlarmButton.setOnClickListener(v -> stopAlarm());

        sfl8MapButton.setOnClickListener(v -> {
            if ("SFL8".equals(currentStation)) showLightningMapForCurrentStation();
        });
        sfl1MapButton.setOnClickListener(v -> {
            if ("SFL1".equals(currentStation)) showLightningMapForCurrentStation();
        });
        stopAlarmButton.setText("🔕");
        stopAlarmButton.setTextSize(18);
        stopAlarmButton.setTextColor(Color.WHITE);
        stopAlarmButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(55, 59, 66))
        );

        sfl8Button.setTextSize(18);
        sfl8Button.setTextColor(Color.WHITE);
        sfl8Button.setAllCaps(false);

        sfl1Button.setTextSize(18);
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

                if (showingLightningMap && url != null && url.contains("lightningtracker.app")) {
                    final double lat = "SFL1".equals(currentStation) ? SFL1_LAT : SFL8_LAT;
                    final double lon = "SFL1".equals(currentStation) ? SFL1_LON : SFL8_LON;
                    String mapOnly = "(function(){"
                            + "document.documentElement.style.background='#000';"
                            + "document.body.style.background='#000';"
                            + "document.body.style.margin='0';"
                            + "document.body.style.padding='0';"
                            + "document.body.style.overflow='hidden';"
                            + "function findMap(){"
                            + "var best=null,bestArea=0;"
                            + "var ifr=Array.from(document.querySelectorAll('iframe'));"
                            + "ifr.forEach(function(e){var r=e.getBoundingClientRect(),a=r.width*r.height;if(a>bestArea){best=e;bestArea=a;}});"
                            + "if(best&&bestArea>40000)return best;"
                            + "var selectors=['.leaflet-container','.maplibregl-map','.mapboxgl-map','.ol-viewport','[class*=map-container]','[class*=mapContainer]','[id*=map-container]','[id*=mapContainer]','[class*=interactive-map]','[class*=lightning-map]','[id*=lightning-map]','canvas','svg'];"
                            + "selectors.forEach(function(sel){Array.from(document.querySelectorAll(sel)).forEach(function(e){var r=e.getBoundingClientRect(),a=r.width*r.height;if(a>bestArea){best=e;bestArea=a;}});});"
                            + "return best;"
                            + "}"
                            + "function mapObject(){"
                            + "var found=null;"
                            + "try{for(var k in window){if(k==='window'||k==='document')continue;var v=window[k];if(v&&typeof v.setView==='function'&&typeof v.getCenter==='function'){found=v;break;}if(v&&typeof v.setCenter==='function'&&(typeof v.setZoom==='function'||typeof v.flyTo==='function')){found=v;break;}}}catch(e){}"
                            + "return found;"
                            + "}"
                            + "function clean(){"
                            + "var mapEl=findMap();"
                            + "if(!mapEl)return false;"
                            + "var keep=mapEl;"
                            + "if(keep.tagName==='CANVAS'||keep.tagName==='SVG'){while(keep.parentElement&&keep.parentElement!==document.body){var pr=keep.parentElement,r=pr.getBoundingClientRect();if(r.width>250&&r.height>200)keep=pr;else break;}}"
                            + "if(keep.tagName==='IFRAME'){keep.style.cssText='position:fixed;inset:0;width:100vw;height:100vh;border:0;margin:0;padding:0;z-index:1;display:block;';}"
                            + "else{keep.style.cssText+=';position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;z-index:1!important;display:block!important;';}"
                            + "Array.from(document.body.children).forEach(function(ch){if(ch!==keep&&ch.id!=='lm-station-overlay')ch.style.display='none';});"
                            + "var map=mapObject();"
                            + "var lat="+lat+",lon="+lon+";"
                            + "try{if(map){if(typeof map.setView==='function')map.setView([lat,lon],12);else if(typeof map.setCenter==='function')map.setCenter([lon,lat]);if(typeof map.setZoom==='function')map.setZoom(12);}}catch(e){}"
                            + "var overlay=document.getElementById('lm-station-overlay');"
                            + "if(!overlay){overlay=document.createElement('div');overlay.id='lm-station-overlay';document.body.appendChild(overlay);}"
                            + "overlay.style.cssText='position:fixed;inset:0;pointer-events:none;z-index:2147483647;';"
                            + "overlay.innerHTML='<div style=\"position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:210px;height:210px;border:3px solid #FFD400;border-radius:50%;box-sizing:border-box;\"></div><div style=\"position:absolute;left:50%;top:50%;width:16px;height:16px;background:#1976FF;border:3px solid #fff;border-radius:50%;transform:translate(-50%,-50%);box-shadow:0 0 10px rgba(25,118,255,.9);\"></div>';"
                            + "return true;"
                            + "}"
                            + "var tries=0;var timer=setInterval(function(){tries++;if(clean()||tries>20)clearInterval(timer);},750);"
                            + "setTimeout(clean,300);setTimeout(clean,1500);setTimeout(clean,3000);setTimeout(clean,6000);setTimeout(clean,10000);"
                            + "})();";
                    view.evaluateJavascript(mapOnly, null);
                    view.setBackgroundColor(Color.BLACK);
                    return;
                }

                if (url == null || !url.startsWith("https://us.kepler51.com/")) {
                    return;
                }

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

    private void configureMonitorWebView() {
        WebSettings settings = monitorWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        monitorWebView.setWebViewClient(new WebViewClient());
        monitorWebView.setAlpha(0.01f);
        monitorWebView.setBackgroundColor(Color.TRANSPARENT);
    }

    private void loadStation(String station) {
        currentStation = station;
        currentClosed = false;
        showingLightningMap = false;
        stopAlarm();

        if ("SFL1".equals(station)) {
            sfl1Button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(55, 105, 175)));
            sfl8Button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 58)));
        } else {
            sfl8Button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(55, 105, 175)));
            sfl1Button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 58)));
        }

        // Switching stations always returns to that station's Kepler view.
        // Explicitly cancel the map page first so a previous map load/callback
        // cannot leave the visible WebView on Lightning Tracker.
        setMapButton(false);
        webView.stopLoading();
        webView.setVisibility(android.view.View.VISIBLE);
        webView.setBackgroundColor(Color.BLACK);
        String stationUrl = "SFL1".equals(station) ? SFL1_URL : SFL8_URL;
        monitorWebView.stopLoading();
        monitorWebView.loadUrl(stationUrl);
        webView.loadUrl(stationUrl);
        updateStationIndicator();
        updateLastEventLabel();
        alarmStatus.setText("🔔");
        alarmStatus.setBackgroundColor(Color.rgb(37, 40, 45));
    }

    private void showLightningMapForCurrentStation() {
        stopAlarm();
        showingLightningMap = true;
        setMapButton(true);
        webView.stopLoading();
        webView.setVisibility(android.view.View.VISIBLE);
        webView.setBackgroundColor(Color.BLACK);
        webView.loadUrl("https://lightningtracker.app/lightning-map/florida/orlando/");
    }

    private void setMapButton(boolean active) {
        Button selected = "SFL1".equals(currentStation) ? sfl1MapButton : sfl8MapButton;
        Button other = "SFL1".equals(currentStation) ? sfl8MapButton : sfl1MapButton;
        if (selected != null) {
            selected.setText("⚡");
            selected.setTextSize(19);
            selected.setEnabled(true);
            selected.setAlpha(1.0f);
            selected.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    active ? Color.rgb(55, 105, 175) : Color.rgb(48, 52, 58)));
        }
        if (other != null) {
            other.setText("⚡");
            other.setTextSize(19);
            other.setEnabled(false);
            other.setAlpha(0.28f);
            other.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 58)));
        }
        if (lightningMapTab != null) lightningMapTab.setVisibility(android.view.View.GONE);
    }

    private void updateStationIndicator() {
        sfl8Button.setText(android.text.Html.fromHtml(
                "SFL8  " + indicatorHtml(sfl8Indicator)));
        sfl1Button.setText(android.text.Html.fromHtml(
                "SFL1  " + indicatorHtml(sfl1Indicator)));
    }

    private String indicatorHtml(String indicator) {
        if ("🔴".equals(indicator)) return "<font color='#FF3B30'>●</font>";
        if ("🟡".equals(indicator)) return "<font color='#FFD60A'>●</font>";
        return "<font color='#00E676'>●</font>";
    }

    private void updateLastEventLabel() {
        if (lastEventText == null) return;
        String event = "SFL1".equals(currentStation) ? lastEventSFL1 : lastEventSFL8;
        lastEventText.setText("⚡ Último evento: " + event);
    }

    private void checkKeplerState() {
        if (monitorWebView == null) {
            return;
        }

        monitorWebView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                value -> {
                    if (value == null) {
                        return;
                    }

                    lastUpdateAt = System.currentTimeMillis();
                    if (connectionText != null) {
                        connectionText.setText("🟢 Conectado");
                    }

                    String text = value
                            .replace("\\n", " ")
                            .replace("\\\"", "\"")
                            .toUpperCase();

                    boolean closed = text.contains("CLOSED") || text.contains("CERRADO");
                    boolean warning = text.contains("WARNING") || text.contains("WARN");

                    if (closed && !currentClosed) {
                        currentClosed = true;

                        Matcher distanceMatcher = DISTANCE_PATTERN.matcher(text);
                        Matcher issuedMatcher = ISSUED_PATTERN.matcher(text);

                        String distance = distanceMatcher.find()
                                ? distanceMatcher.group(1) + " mi"
                                : "distancia no disponible";
                        String issued = issuedMatcher.find()
                                ? issuedMatcher.group(1)
                                : "hora no disponible";

                        String event = issued + " • " + distance;
                        if ("SFL1".equals(currentStation)) {
                            lastEventSFL1 = event;
                        } else {
                            lastEventSFL8 = event;
                        }

                        updateLastEventLabel();
                        showLightningNotification(distance, issued);
                        triggerAlarm();
                    } else if (!closed) {
                        currentClosed = false;
                    }

                    if ("SFL1".equals(currentStation)) {
                        if (closed) {
                            sfl1Indicator = "🔴";
                        } else if (warning) {
                            sfl1Indicator = "🟡";
                        } else {
                            sfl1Indicator = "🟢";
                        }
                    } else {
                        if (closed) {
                            sfl8Indicator = "🔴";
                        } else if (warning) {
                            sfl8Indicator = "🟡";
                        } else {
                            sfl8Indicator = "🟢";
                        }
                    }

                    updateStationIndicator();

                    if (alarmStatus != null) {
                        if (closed) {
                            alarmStatus.setText("🚨");
                            alarmStatus.setBackgroundColor(Color.rgb(190, 35, 35));
                        } else {
                            alarmStatus.setText("🔔");
                            alarmStatus.setBackgroundColor(Color.rgb(37, 40, 45));
                        }
                    }
                }
        );
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        android.net.Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel channel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "Lightning Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Alertas cuando Kepler51 detecta CLOSED por lightning");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 500, 300, 500});
        channel.setSound(soundUri, audioAttributes);
        manager.createNotificationChannel(channel);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private void showLightningNotification(String distance, String issued) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String details = "SFL" + ("SFL1".equals(currentStation) ? "1" : "8")
                + " • " + distance + " • " + issued;

        android.app.Notification notification = new android.app.Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚡ LIGHTNING ALERT")
                .setContentText(details)
                .setStyle(new android.app.Notification.BigTextStyle()
                        .bigText("Lightning detectado dentro del rango. " + details))
                .setPriority(android.app.Notification.PRIORITY_MAX)
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .setAutoCancel(false)
                .setOngoing(false)
                .build();

        manager.notify(7001, notification);
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
        if (monitorWebView != null) {
            monitorWebView.stopLoading();
            monitorWebView.destroy();
        }

        super.onDestroy();
    }
}
