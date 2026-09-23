package com.lightningmonitor;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Color;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView web8, web1;
    private TextView sfl8, sfl1, event, status;
    private Handler handler = new Handler();
    private boolean wasClosed8=false, wasClosed1=false;

    private final String URL8="https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL8?activeComponent=Yard+Monitoring+Widgets";
    private final String URL1="https://us.kepler51.com/facilitymonitoring/#/yardmonitoring/amazon/SFL1?activeComponent=Yard+Monitoring+Widgets";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        web8=findViewById(R.id.web8); web1=findViewById(R.id.web1);
        sfl8=findViewById(R.id.sfl8); sfl1=findViewById(R.id.sfl1);
        event=findViewById(R.id.event); status=findViewById(R.id.status);
        setup(web8, URL8); setup(web1, URL1);
        handler.postDelayed(this::poll, 8000);
    }

    private void setup(WebView w, String url) {
        WebSettings st=w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setLoadsImagesAutomatically(true);
        w.setWebViewClient(new WebViewClient());
        w.loadUrl(url);
    }

    private void poll() {
        scan(web8, "SFL8");
        scan(web1, "SFL1");
        handler.postDelayed(this::poll, 5000);
    }

    private void scan(WebView w, String site) {
        w.evaluateJavascript("(function(){return document.body?document.body.innerText:'';})()", value -> {
            if(value==null) return;
            String t=value.toUpperCase();
            boolean closed=t.contains("CLOSED");
            boolean warning=t.contains("WARNING");
            boolean open=t.contains("OPEN");
            update(site, closed, warning, open);
            if(site.equals("SFL8")) {
                if(closed && !wasClosed8) { wasClosed8=true; alert(site); }
                if(!closed) wasClosed8=false;
            } else {
                if(closed && !wasClosed1) { wasClosed1=true; alert(site); }
                if(!closed) wasClosed1=false;
            }
        });
    }

    private void update(String site, boolean closed, boolean warning, boolean open) {
        String state=closed?"CLOSED":warning?"WARNING":open?"OPEN":"UNKNOWN";
        TextView v=site.equals("SFL8")?sfl8:sfl1;
        v.setText(site+" — "+state);
        if(closed) v.setBackgroundColor(Color.rgb(155,17,30));
        else if(warning) v.setBackgroundColor(Color.rgb(91,23,32));
        else if(open) v.setBackgroundColor(Color.rgb(22,60,42));
        else v.setBackgroundColor(Color.rgb(41,49,64));
        status.setText("Última revisión: "+android.text.format.DateFormat.format("hh:mm:ss a", System.currentTimeMillis()));
    }

    private void alert(String site) {
        getWindow().getDecorView().setBackgroundColor(Color.rgb(155,17,30));
        event.setText("⚡ "+site+" — CLOSED detectado: "+
                android.text.format.DateFormat.format("hh:mm:ss a", System.currentTimeMillis()));
        ToneGenerator tg=new ToneGenerator(AudioManager.STREAM_ALARM,100);
        tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,1200);
    }
}
