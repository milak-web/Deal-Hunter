package com.milak.dealhunterfresh;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class DealHunterCountdownReceiver extends BroadcastReceiver {
    public static final String EXTRA_NOTIFICATION_ID = "dh_countdown_notification_id";
    public static final String EXTRA_EXPIRY_MS = "dh_countdown_expiry_ms";
    public static final String EXTRA_TITLE = "dh_countdown_title";
    public static final String EXTRA_PLATFORM = "dh_countdown_platform";
    public static final String EXTRA_LINK = "dh_countdown_link";

    private static final String CHANNEL_ID = "dealhunter-alerts";

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

        showCountdownNotification(context, notificationId, expiryAtMs, title, platform, link);
    }

    public static void showCountdownNotification(
        Context context,
        int notificationId,
        long expiryAtMs,
        String title,
        String platform,
        String link
    ) {
        Intent openIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (openIntent != null) {
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (link != null && !link.isEmpty()) {
                openIntent.putExtra("deal_link", link);
            }
        } else {
            openIntent = new Intent();
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(context, Math.abs(notificationId), openIntent, flags);

        long now = System.currentTimeMillis();
        long timeoutMs = Math.max(5000L, (expiryAtMs - now) + 4000L);
        String body = "Claim on " + platform + " before the timer reaches 0:00.";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setWhen(expiryAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setSubText("Final hour countdown");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter(timeoutMs);
        }

        Notification notification = builder.build();
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(notificationId, notification);
        }
    }
}
