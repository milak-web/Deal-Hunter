package com.milak.dealhunterfresh;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class DealHunterWidgetProvider extends AppWidgetProvider {
    public static final String PREFS_NAME = "dealhunter_widget_prefs";
    public static final String KEY_DEALS_JSON = "widget_deals_json";
    public static final String KEY_INDEX = "widget_index";
    public static final String ACTION_ROTATE = "com.milak.dealhunterfresh.action.WIDGET_ROTATE";
    public static final String ACTION_REFRESH = "com.milak.dealhunterfresh.action.WIDGET_REFRESH";
    public static final String ACTION_PREVIOUS = "com.milak.dealhunterfresh.action.WIDGET_PREVIOUS";
    public static final String ACTION_NEXT = "com.milak.dealhunterfresh.action.WIDGET_NEXT";
    public static final String EXTRA_DEAL_LINK = "widget_deal_link";
    public static final String EXTRA_DEAL_PLATFORM = "widget_deal_platform";

    private static final int ROTATE_REQUEST_CODE = 55221;
    private static final int NEXT_REQUEST_CODE = 55222;
    private static final int PREVIOUS_REQUEST_CODE = 55223;
    private static final long ROTATE_INTERVAL_MS = 20_000L;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        DealHunterRefreshService.schedule(context, true);
        refreshAllWidgets(context, appWidgetManager, appWidgetIds, false);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        cancelRotation(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (context == null || intent == null) return;

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName componentName = new ComponentName(context, DealHunterWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(componentName);
        String action = intent.getAction();

        if (ACTION_ROTATE.equals(action)) {
            advanceIndex(context);
            refreshAllWidgets(context, manager, ids, false);
            return;
        }

        if (ACTION_NEXT.equals(action)) {
            advanceIndex(context);
            refreshAllWidgets(context, manager, ids, false);
            return;
        }

        if (ACTION_PREVIOUS.equals(action)) {
            retreatIndex(context);
            refreshAllWidgets(context, manager, ids, false);
            return;
        }

        if (ACTION_REFRESH.equals(action)) {
            boolean resetIndex = intent.getBooleanExtra("resetIndex", false);
            refreshAllWidgets(context, manager, ids, resetIndex);
        }
    }

    public static void requestImmediateRefresh(Context context, boolean resetIndex) {
        Intent intent = new Intent(context, DealHunterWidgetProvider.class);
        intent.setAction(ACTION_REFRESH);
        intent.putExtra("resetIndex", resetIndex);
        context.sendBroadcast(intent);
    }

    private static void refreshAllWidgets(Context context, AppWidgetManager manager, int[] ids, boolean resetIndex) {
        if (ids == null || ids.length == 0) {
            cancelRotation(context);
            return;
        }

        List<WidgetDeal> deals = loadDeals(context);
        if (resetIndex) {
            prefs(context).edit().putInt(KEY_INDEX, 0).apply();
        }

        if (deals.isEmpty()) {
            prefs(context).edit().putInt(KEY_INDEX, 0).apply();
        } else {
            int currentIndex = prefs(context).getInt(KEY_INDEX, 0);
            if (currentIndex >= deals.size()) {
                prefs(context).edit().putInt(KEY_INDEX, 0).apply();
            }
        }

        for (int appWidgetId : ids) {
            updateWidget(context, manager, appWidgetId, deals);
        }

        if (deals.size() > 1) {
            scheduleNextRotation(context);
        } else {
            cancelRotation(context);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId, List<WidgetDeal> deals) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.dealhunter_widget);
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);

        if (deals.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty_state, View.VISIBLE);
            views.setViewVisibility(R.id.widget_content, View.GONE);
            manager.updateAppWidget(appWidgetId, views);
            return;
        }

        int currentIndex = Math.max(0, prefs(context).getInt(KEY_INDEX, 0));
        WidgetDeal deal = deals.get(currentIndex % deals.size());
        long remainingMs = Math.max(0L, deal.expiryMs - System.currentTimeMillis());

        views.setViewVisibility(R.id.widget_empty_state, View.GONE);
        views.setViewVisibility(R.id.widget_content, View.VISIBLE);
        views.setOnClickPendingIntent(R.id.widget_content, buildLaunchPendingIntent(context, appWidgetId, deal));
        views.setTextViewText(R.id.widget_store, deal.platform.toUpperCase(Locale.US));
        views.setTextViewText(R.id.widget_title, deal.title);
        views.setTextViewText(R.id.widget_subtitle, buildSubtitle(deal, remainingMs));
        views.setTextViewText(R.id.widget_timer, formatRemaining(remainingMs));
        views.setTextViewText(R.id.widget_footer, buildFooter(deal, deals.size()));
        views.setTextColor(R.id.widget_timer, pickTimerColor(remainingMs));
        views.setTextColor(R.id.widget_store, pickStoreColor(deal.platform));
        views.setViewVisibility(R.id.widget_prev, deals.size() > 1 ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_next, deals.size() > 1 ? View.VISIBLE : View.GONE);
        if (deals.size() > 1) {
            views.setOnClickPendingIntent(R.id.widget_prev, buildNavPendingIntent(context, appWidgetId, ACTION_PREVIOUS, PREVIOUS_REQUEST_CODE + appWidgetId));
            views.setOnClickPendingIntent(R.id.widget_next, buildNavPendingIntent(context, appWidgetId, ACTION_NEXT, NEXT_REQUEST_CODE + appWidgetId));
        }

        Bitmap artwork = loadBitmap(deal.imagePath);
        if (artwork != null) {
            views.setViewVisibility(R.id.widget_image, View.VISIBLE);
            views.setImageViewBitmap(R.id.widget_image, artwork);
        } else {
            views.setViewVisibility(R.id.widget_image, View.GONE);
        }

        manager.updateAppWidget(appWidgetId, views);
    }

    private static void advanceIndex(Context context) {
        List<WidgetDeal> deals = loadDeals(context);
        if (deals.isEmpty()) {
            prefs(context).edit().putInt(KEY_INDEX, 0).apply();
            return;
        }
        int current = prefs(context).getInt(KEY_INDEX, 0);
        int next = (current + 1) % deals.size();
        prefs(context).edit().putInt(KEY_INDEX, next).apply();
    }

    private static void retreatIndex(Context context) {
        List<WidgetDeal> deals = loadDeals(context);
        if (deals.isEmpty()) {
            prefs(context).edit().putInt(KEY_INDEX, 0).apply();
            return;
        }
        int current = prefs(context).getInt(KEY_INDEX, 0);
        int next = (current - 1 + deals.size()) % deals.size();
        prefs(context).edit().putInt(KEY_INDEX, next).apply();
    }

    private static void scheduleNextRotation(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent pendingIntent = buildRotatePendingIntent(context);
        long nextAt = System.currentTimeMillis() + ROTATE_INTERVAL_MS;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextAt, pendingIntent);
            }
        } catch (Exception ignored) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextAt, pendingIntent);
        }
    }

    private static void cancelRotation(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        PendingIntent pendingIntent = buildRotatePendingIntent(context);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    private static PendingIntent buildRotatePendingIntent(Context context) {
        Intent intent = new Intent(context, DealHunterWidgetProvider.class);
        intent.setAction(ACTION_ROTATE);
        return PendingIntent.getBroadcast(
            context,
            ROTATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent buildNavPendingIntent(Context context, int appWidgetId, String action, int requestCode) {
        Intent intent = new Intent(context, DealHunterWidgetProvider.class);
        intent.setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent buildLaunchPendingIntent(Context context, int appWidgetId, WidgetDeal deal) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_DEAL_LINK, deal.link);
        intent.putExtra(EXTRA_DEAL_PLATFORM, deal.platform);
        return PendingIntent.getActivity(
            context,
            appWidgetId + deal.link.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static List<WidgetDeal> loadDeals(Context context) {
        String raw = prefs(context).getString(KEY_DEALS_JSON, "[]");
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();

        List<WidgetDeal> deals = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            long now = System.currentTimeMillis();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title", "").trim();
                String platform = item.optString("platform", "").trim();
                String link = item.optString("link", "").trim();
                long expiryMs = item.optLong("expiryMs", 0L);
                if (title.isEmpty() || platform.isEmpty() || link.isEmpty() || expiryMs <= now) continue;
                deals.add(new WidgetDeal(
                    title,
                    platform,
                    link,
                    expiryMs,
                    item.optString("imagePath", "").trim(),
                    item.optString("originalPrice", "").trim(),
                    item.optString("currentPrice", "FREE").trim()
                ));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }

        deals.sort(Comparator.comparingLong(d -> d.expiryMs));
        return deals;
    }

    private static Bitmap loadBitmap(String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) return null;
        File file = new File(imagePath);
        if (!file.isFile() || file.length() <= 0L) return null;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String buildSubtitle(WidgetDeal deal, long remainingMs) {
        if (remainingMs <= 60 * 60 * 1000L) {
            return "Final hour";
        }
        if (remainingMs <= 6 * 60 * 60 * 1000L) {
            return "Ending soon on " + deal.platform;
        }
        return "Free deal on " + deal.platform;
    }

    private static String buildFooter(WidgetDeal deal, int dealCount) {
        String current = safePrice(deal.currentPrice);
        String original = safePrice(deal.originalPrice);
        String priceSummary;

        if (!original.isEmpty() && !"N/A".equalsIgnoreCase(original) && !original.equalsIgnoreCase(current)) {
            priceSummary = current + " - Was " + original;
        } else {
            priceSummary = current;
        }

        return dealCount > 1
            ? priceSummary + " - Shortlist"
            : priceSummary + " - Tap to open";
    }

    private static String safePrice(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? "FREE" : trimmed;
    }

    private static String formatRemaining(long remainingMs) {
        long totalSeconds = Math.max(0L, remainingMs / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (days > 0) return String.format(Locale.US, "%dd %02dh", days, hours);
        if (hours > 0) return String.format(Locale.US, "%dh %02dm", hours, minutes);
        if (minutes > 0) return String.format(Locale.US, "%dm %02ds", minutes, seconds);
        return String.format(Locale.US, "%ds", seconds);
    }

    private static int pickTimerColor(long remainingMs) {
        if (remainingMs <= 15 * 60 * 1000L) return Color.parseColor("#EF4444");
        if (remainingMs <= 60 * 60 * 1000L) return Color.parseColor("#FB923C");
        if (remainingMs <= 6 * 60 * 60 * 1000L) return Color.parseColor("#FACC15");
        if (remainingMs <= 24 * 60 * 60 * 1000L) return Color.parseColor("#A3E635");
        return Color.parseColor("#34D399");
    }

    private static int pickStoreColor(String platform) {
        String p = platform == null ? "" : platform.toLowerCase(Locale.US);
        if (p.contains("epic")) return Color.parseColor("#F8FAFC");
        if (p.contains("steam")) return Color.parseColor("#67E8F9");
        if (p.contains("gog")) return Color.parseColor("#C084FC");
        if (p.contains("itch")) return Color.parseColor("#FCA5A5");
        if (p.contains("mobile")) return Color.parseColor("#86EFAC");
        return Color.parseColor("#E2E8F0");
    }

    private static final class WidgetDeal {
        final String title;
        final String platform;
        final String link;
        final long expiryMs;
        final String imagePath;
        final String originalPrice;
        final String currentPrice;

        WidgetDeal(String title, String platform, String link, long expiryMs, String imagePath, String originalPrice, String currentPrice) {
            this.title = title;
            this.platform = platform;
            this.link = link;
            this.expiryMs = expiryMs;
            this.imagePath = imagePath;
            this.originalPrice = originalPrice;
            this.currentPrice = currentPrice;
        }
    }
}
