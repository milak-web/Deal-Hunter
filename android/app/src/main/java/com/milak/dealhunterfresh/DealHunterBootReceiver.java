package com.milak.dealhunterfresh;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class DealHunterBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        DealHunterRefreshService.schedule(context, true);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName componentName = new ComponentName(context, DealHunterWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(componentName);
        if (ids == null || ids.length == 0) return;

        DealHunterWidgetProvider.requestImmediateRefresh(context, false);
    }
}
