package com.milak.dealhunterfresh;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DealHunterCountdownPlugin.class);
        registerPlugin(DealHunterWidgetPlugin.class);
        super.onCreate(savedInstanceState);
        DealHunterRefreshService.schedule(this, true);

        if (getBridge() != null && getBridge().getWebView() != null) {
            WebView webView = getBridge().getWebView();
            webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            webView.setHorizontalScrollBarEnabled(false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }
}
