package com.milak.dealhunterfresh;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "DealHunterCountdown")
public class DealHunterCountdownPlugin extends Plugin {
    private static final String PREFS = "dealhunter_countdown_prefs";
    private static final String KEY_NOTIFICATION_ID = "notification_id";
    private static final String KEY_ALARM_REQUEST_ID = "alarm_request_id";
    private static final int DEFAULT_NOTIFICATION_ID = 987654;

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void syncCountdown(PluginCall call) {
        JSObject payload = call.getObject("payload");
        if (payload == null || payload.length() == 0) {
            clearCountdownInternal();
            JSObject ret = new JSObject();
            ret.put("active", false);
            call.resolve(ret);
            return;
        }

        long now = System.currentTimeMillis();
        int notificationId = payload.has("id") ? payload.optInt("id", DEFAULT_NOTIFICATION_ID) : DEFAULT_NOTIFICATION_ID;
        long expiryAtMs = payload.optLong("expiryAtMs", 0);
        long startAtMs = payload.optLong("startAtMs", expiryAtMs - (60 * 60 * 1000L));
        String title = payload.optString("title", "Deal expiring soon");
        String platform = payload.optString("platform", "Store");
        String link = payload.optString("link", "");

        clearCountdownInternal();

        if (expiryAtMs <= now + 1000) {
            JSObject ret = new JSObject();
            ret.put("active", false);
            ret.put("reason", "expired");
            call.resolve(ret);
            return;
        }

        int alarmRequestId = buildAlarmRequestId(notificationId);
        PendingIntent alarmIntent = buildAlarmPendingIntent(
            notificationId,
            alarmRequestId,
            expiryAtMs,
            title,
            platform,
            link
        );

        if (startAtMs <= now + 2000) {
            DealHunterCountdownReceiver.showCountdownNotification(
                getContext(),
                notificationId,
                expiryAtMs,
                title,
                platform,
                link
            );
        } else {
            AlarmManager alarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                try {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAtMs, alarmIntent);
                } catch (Exception ignored) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, startAtMs, alarmIntent);
                }
            }
        }

        prefs().edit()
            .putInt(KEY_NOTIFICATION_ID, notificationId)
            .putInt(KEY_ALARM_REQUEST_ID, alarmRequestId)
            .apply();

        JSObject ret = new JSObject();
        ret.put("active", true);
        ret.put("notificationId", notificationId);
        ret.put("startAtMs", startAtMs);
        ret.put("expiryAtMs", expiryAtMs);
        call.resolve(ret);
    }

    @PluginMethod
    public void clearCountdown(PluginCall call) {
        clearCountdownInternal();
        JSObject ret = new JSObject();
        ret.put("active", false);
        call.resolve(ret);
    }

    private void clearCountdownInternal() {
        SharedPreferences preferences = prefs();
        int alarmRequestId = preferences.getInt(KEY_ALARM_REQUEST_ID, -1);
        int notificationId = preferences.getInt(KEY_NOTIFICATION_ID, -1);

        AlarmManager alarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null && alarmRequestId >= 0) {
            PendingIntent cancelIntent = buildAlarmPendingIntent(notificationId, alarmRequestId, 0, "", "", "");
            alarmManager.cancel(cancelIntent);
            cancelIntent.cancel();
        }

        if (notificationId >= 0) {
            NotificationManagerCompat.from(getContext()).cancel(notificationId);
        }

        preferences.edit().remove(KEY_ALARM_REQUEST_ID).remove(KEY_NOTIFICATION_ID).apply();
    }

    private int buildAlarmRequestId(int notificationId) {
        int safe = Math.abs(notificationId);
        if (safe == 0) safe = DEFAULT_NOTIFICATION_ID;
        return safe + 7;
    }

    private PendingIntent buildAlarmPendingIntent(
        int notificationId,
        int requestId,
        long expiryAtMs,
        String title,
        String platform,
        String link
    ) {
        Intent intent = new Intent(getContext(), DealHunterCountdownReceiver.class);
        intent.putExtra(DealHunterCountdownReceiver.EXTRA_NOTIFICATION_ID, notificationId);
        intent.putExtra(DealHunterCountdownReceiver.EXTRA_EXPIRY_MS, expiryAtMs);
        intent.putExtra(DealHunterCountdownReceiver.EXTRA_TITLE, title);
        intent.putExtra(DealHunterCountdownReceiver.EXTRA_PLATFORM, platform);
        intent.putExtra(DealHunterCountdownReceiver.EXTRA_LINK, link);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(getContext(), requestId, intent, flags);
    }
}
