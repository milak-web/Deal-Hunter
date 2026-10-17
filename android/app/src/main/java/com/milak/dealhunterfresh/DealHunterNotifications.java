package com.milak.dealhunterfresh;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DealHunterNotifications {
    static final String ALERT_CHANNEL = "dealhunter-game-alerts-v2";
    static final String COUNTDOWN_CHANNEL = "dealhunter-live-countdown-v2";
    static final String PREFS = "dealhunter_rich_alerts";
    static final String REMINDERS = "reminders";
    static final ExecutorService IMAGES = Executors.newFixedThreadPool(2);

    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        Uri sound = sound(context);
        NotificationChannel previous = manager.getNotificationChannel("dealhunter-alerts");
        int importance = previous == null ? NotificationManager.IMPORTANCE_HIGH : previous.getImportance();
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL, "Game giveaways", importance);
        alerts.setDescription("New free games and last-day reminders with the Deal Hunter chime");
        alerts.setSound(previous != null && previous.getSound() == null ? null : sound,
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(alerts);
        NotificationChannel countdown = new NotificationChannel(COUNTDOWN_CHANNEL, "Live expiry countdown",
            importance == NotificationManager.IMPORTANCE_NONE ? NotificationManager.IMPORTANCE_NONE : NotificationManager.IMPORTANCE_DEFAULT);
        countdown.setDescription("One final-hour alert followed by a quietly ticking game countdown");
        countdown.setSound(previous != null && previous.getSound() == null ? null : sound,
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        countdown.enableVibration(false);
        manager.createNotificationChannel(countdown);
    }

    static Uri sound(Context context) {
        return Uri.parse("android.resource://" + context.getPackageName() + "/raw/dealhunter_chime");
    }

    static boolean allowed(Context context) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    static PendingIntent openGame(Context context, int id, String link, String platform) {
        Intent intent = new Intent(context, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(Uri.parse("dealhunter://notification/" + id));
        intent.putExtra(DealHunterWidgetProvider.EXTRA_DEAL_LINK, link);
        intent.putExtra(DealHunterWidgetProvider.EXTRA_DEAL_PLATFORM, platform);
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification buildAlert(Context context, JSONObject payload, Bitmap artwork) {
        int id = payload.optInt("id");
        String title = payload.optString("title", "Free game alert");
        String body = payload.optString("body", "A free game is available.");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_bolt)
            .setContentTitle(title).setContentText(body)
            .setSubText(payload.optString("platform", "Deal Hunter"))
            .setContentIntent(openGame(context, id, payload.optString("link"), payload.optString("platform")))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSound(sound(context));
        long expiry = payload.optLong("expiryAtMs");
        if (expiry > 0) builder.setTimeoutAfter(Math.max(1, expiry - System.currentTimeMillis()));
        if (artwork != null) {
            builder.setLargeIcon(DealHunterNotificationArtwork.thumbnail(artwork, 96, 96));
            builder.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(artwork)
                .bigLargeIcon((Bitmap) null).setBigContentTitle(title).setSummaryText(body));
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        }
        return builder.build();
    }

    static void showAlert(Context context, JSONObject payload, Runnable finished) {
        ensureChannels(context);
        if (!allowed(context) || (payload.optLong("expiryAtMs") > 0 && payload.optLong("expiryAtMs") <= System.currentTimeMillis())) {
            if (finished != null) finished.run();
            return;
        }
        int id = payload.optInt("id");
        String imageUrl = payload.optString("imageUrl");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Bitmap image = DealHunterNotificationArtwork.cached(context, imageUrl);
        manager.notify(id, buildAlert(context, payload, image));
        if (image != null || imageUrl.isEmpty()) {
            if (finished != null) finished.run();
            return;
        }
        IMAGES.execute(() -> {
            try {
                Bitmap prepared = DealHunterNotificationArtwork.prepare(context, imageUrl);
                long expiry = payload.optLong("expiryAtMs");
                if (prepared != null && allowed(context) && (expiry == 0 || expiry > System.currentTimeMillis())) {
                    for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
                        if (active.getId() == id) {
                            manager.notify(id, buildAlert(context, payload, prepared));
                            break;
                        }
                    }
                }
            } finally { if (finished != null) finished.run(); }
        });
    }

    static RemoteViews countdownView(Context context, boolean expanded, long expiry, String title, String platform, Bitmap artwork) {
        RemoteViews views = new RemoteViews(context.getPackageName(), expanded ? R.layout.notification_countdown_expanded : R.layout.notification_countdown);
        views.setTextViewText(R.id.notification_game_title, title);
        views.setTextViewText(R.id.notification_game_store, platform);
        if (artwork != null) {
            views.setImageViewBitmap(R.id.notification_game_thumbnail, DealHunterNotificationArtwork.thumbnail(artwork, 96, 64));
        }
        if (expanded) {
            views.setViewVisibility(R.id.notification_game_artwork, artwork == null ? View.GONE : View.VISIBLE);
            if (artwork != null) views.setImageViewBitmap(R.id.notification_game_artwork, artwork);
        }
        long remaining = Math.max(0, expiry - System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 24) {
            views.setChronometer(R.id.notification_live_timer, SystemClock.elapsedRealtime() + remaining, null, true);
            views.setChronometerCountDown(R.id.notification_live_timer, true);
        } else {
            views.setViewVisibility(R.id.notification_live_timer, View.GONE);
            views.setViewVisibility(R.id.notification_static_timer, View.VISIBLE);
            views.setTextViewText(R.id.notification_static_timer, "< " + Math.max(1, (remaining + 59999) / 60000) + " min");
        }
        return views;
    }

    static Notification buildCountdown(Context context, int id, long expiry, String title, String platform, String link, Bitmap artwork) {
        return new NotificationCompat.Builder(context, COUNTDOWN_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_bolt).setContentTitle(title)
            .setContentText("Final hour on " + platform).setSubText("Ending soon")
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(countdownView(context, false, expiry, title, platform, artwork))
            .setCustomBigContentView(countdownView(context, true, expiry, title, platform, artwork))
            .setContentIntent(openGame(context, id, link, platform))
            .setOnlyAlertOnce(true).setSound(sound(context)).setOngoing(true).setAutoCancel(false)
            .setShowWhen(false).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(Math.max(1, expiry - System.currentTimeMillis()))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build();
    }

    static JSONObject reminders(Context context) {
        try {
            return new JSONObject(context.getSharedPreferences(PREFS, 0).getString(REMINDERS, "{}"));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    static PendingIntent reminderIntent(Context context, int id) {
        Intent intent = new Intent(context, DealHunterAlertReceiver.class)
            .setData(Uri.parse("dealhunter://reminder/" + id)).putExtra("id", id);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context, JSONObject payload) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, payload.optLong("atMs"), reminderIntent(context, payload.optInt("id")));
    }

    static synchronized void syncReminders(Context context, JSONArray entries) throws Exception {
        JSONObject previous = reminders(context);
        JSONObject desired = new JSONObject();
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(120, entries.length()); i++) {
            JSONObject payload = entries.getJSONObject(i);
            if (payload.optInt("id") <= 0 || payload.optLong("atMs") <= now || payload.optLong("expiryAtMs") <= now) continue;
            desired.put(String.valueOf(payload.optInt("id")), payload);
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(REMINDERS, desired.toString()).commit();
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Iterator<String> keys = previous.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!desired.has(key)) {
                PendingIntent pending = reminderIntent(context, Integer.parseInt(key));
                if (alarms != null) alarms.cancel(pending);
                pending.cancel();
            }
        }
        keys = desired.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject payload = desired.getJSONObject(key);
            if (!payload.toString().equals(String.valueOf(previous.optJSONObject(key)))) schedule(context, payload);
        }
    }

    static void restoreReminders(Context context) {
        JSONObject saved = reminders(context);
        Iterator<String> keys = saved.keys();
        while (keys.hasNext()) {
            JSONObject payload = saved.optJSONObject(keys.next());
            if (payload != null && payload.optLong("atMs") > System.currentTimeMillis()) schedule(context, payload);
        }
    }
}
