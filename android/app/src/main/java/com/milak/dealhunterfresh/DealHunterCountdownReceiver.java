package com.milak.dealhunterfresh;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationManagerCompat;

public class DealHunterCountdownReceiver extends BroadcastReceiver {
    public static final String EXTRA_NOTIFICATION_ID = "dh_countdown_notification_id";
    public static final String EXTRA_EXPIRY_MS = "dh_countdown_expiry_ms";
    public static final String EXTRA_TITLE = "dh_countdown_title";
    public static final String EXTRA_PLATFORM = "dh_countdown_platform";
    public static final String EXTRA_LINK = "dh_countdown_link";
    public static final String EXTRA_IMAGE_URL = "dh_countdown_image_url";

    @Override
    public void onReceive(Context context, Intent intent) {
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 987654);
        long expiryAtMs = intent.getLongExtra(EXTRA_EXPIRY_MS, 0L);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String platform = intent.getStringExtra(EXTRA_PLATFORM);
        String link = intent.getStringExtra(EXTRA_LINK);

        if (title == null || title.trim().isEmpty()) title = "Deal expiring soon";
        if (platform == null || platform.trim().isEmpty()) platform = "Store";
        if (expiryAtMs <= System.currentTimeMillis() + 1000) {
            NotificationManagerCompat.from(context).cancel(notificationId);
            return;
        }

        PendingResult pending = goAsync();
        showCountdownNotification(context, notificationId, expiryAtMs, title, platform, link, intent.getStringExtra(EXTRA_IMAGE_URL), pending::finish);
    }

    public static void showCountdownNotification(
        Context context,
        int notificationId,
        long expiryAtMs,
        String title,
        String platform,
        String link,
        String imageUrl
    ) {
        showCountdownNotification(context, notificationId, expiryAtMs, title, platform, link, imageUrl, null);
    }

    private static void showCountdownNotification(Context context, int notificationId, long expiryAtMs, String title,
        String platform, String link, String imageUrl, Runnable finished) {
        if (!DealHunterNotifications.allowed(context) || expiryAtMs <= System.currentTimeMillis()) {
            if (finished != null) finished.run();
            return;
        }
        DealHunterNotifications.ensureChannels(context);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            android.graphics.Bitmap cached = DealHunterNotificationArtwork.cached(context, imageUrl);
            notificationManager.notify(notificationId, DealHunterNotifications.buildCountdown(context, notificationId,
                expiryAtMs, title, platform, link, cached));
            if (cached == null && imageUrl != null && !imageUrl.isEmpty()) {
                DealHunterNotifications.IMAGES.execute(() -> {
                    try {
                        android.graphics.Bitmap artwork = DealHunterNotificationArtwork.prepare(context, imageUrl);
                        if (artwork != null && DealHunterNotifications.allowed(context) && expiryAtMs > System.currentTimeMillis()) {
                            for (android.service.notification.StatusBarNotification active : notificationManager.getActiveNotifications()) {
                                if (active.getId() == notificationId) {
                                    notificationManager.notify(notificationId, DealHunterNotifications.buildCountdown(context,
                                        notificationId, expiryAtMs, title, platform, link, artwork));
                                    break;
                                }
                            }
                        }
                    } finally { if (finished != null) finished.run(); }
                });
                return;
            }
        }
        if (finished != null) finished.run();
    }
}
