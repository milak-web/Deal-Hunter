package com.milak.dealhunterfresh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public class DealHunterAlertReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("id", 0);
        JSONObject payload = DealHunterNotifications.reminders(context).optJSONObject(String.valueOf(id));
        if (payload == null || payload.optLong("expiryAtMs") <= System.currentTimeMillis()) return;
        PendingResult pending = goAsync();
        DealHunterNotifications.showAlert(context.getApplicationContext(), payload, pending::finish);
    }
}
