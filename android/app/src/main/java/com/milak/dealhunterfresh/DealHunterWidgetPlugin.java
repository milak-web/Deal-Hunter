package com.milak.dealhunterfresh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "DealHunterWidget")
public class DealHunterWidgetPlugin extends Plugin {
    private static final String IMAGE_DIR_NAME = "dealhunter_widget_art";
    static final String KEY_FEED_PAYLOAD = "feed_payload";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PluginMethod
    public void syncWidgetSnapshot(PluginCall call) {
        JSArray deals = call.getArray("deals");
        Context context = getContext();
        executor.execute(() -> {
        try {
            JSObject payload = call.getObject("payload");
            if (payload != null) {
                storeFeedSnapshot(context, payload);
                DealHunterWidgetProvider.requestImmediateRefresh(context, false);
                call.resolve();
                return;
            }
            if (deals == null || deals.length() == 0) {
                call.resolve();
                return;
            }

            JSONArray normalized = normalizeDeals(context, deals);
            prefs(context).edit()
                .putString(DealHunterWidgetProvider.KEY_DEALS_JSON, normalized.toString())
                .putInt(DealHunterWidgetProvider.KEY_INDEX, 0)
                .apply();

            DealHunterWidgetProvider.requestImmediateRefresh(context, true);
            call.resolve();
        } catch (Exception error) {
            call.reject("Failed to sync widget snapshot", error);
        }
        });
    }

    @PluginMethod
    public void getCachedDeals(PluginCall call) {
        JSObject result = new JSObject();
        try {
            String raw = prefs(getContext()).getString(KEY_FEED_PAYLOAD, null);
            result.put("payload", raw == null ? JSONObject.NULL : new JSONObject(raw));
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Could not read the saved feed", error);
        }
    }

    static synchronized void storeFeedSnapshot(Context context, JSONObject payload) throws Exception {
        JSONArray sourceDeals = payload.optJSONArray("deals");
        long timestamp = parseExpiry(payload.optString("lastUpdated"));
        if (sourceDeals == null || timestamp == 0L) throw new IllegalArgumentException("Invalid feed snapshot");
        String previous = prefs(context).getString(KEY_FEED_PAYLOAD, null);
        if (previous != null) {
            long previousTimestamp = parseExpiry(new JSONObject(previous).optString("lastUpdated"));
            if (previousTimestamp > timestamp) return;
        }

        ArrayList<JSONObject> active = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < sourceDeals.length(); i++) {
            JSONObject deal = sourceDeals.optJSONObject(i);
            if (deal == null || deal.optString("title").isEmpty() || deal.optString("platform").isEmpty()
                || !deal.optString("link").matches("https?://.+")) {
                throw new IllegalArgumentException("Invalid deal in feed");
            }
            long expiry = parseExpiry(deal.optString("expiryDate"));
            if (expiry <= now) continue;
            JSONObject entry = new JSONObject(deal.toString());
            entry.put("expiryMs", expiry);
            active.add(entry);
        }
        active.sort(Comparator.comparingLong(d -> d.optLong("expiryMs")));
        JSONArray shortlist = new JSONArray();
        Set<String> stores = new HashSet<>();
        for (JSONObject entry : active) {
            if (stores.add(entry.optString("platform"))) shortlist.put(entry);
            if (shortlist.length() >= 12) break;
        }
        JSONArray normalized = normalizeDeals(context, shortlist);
        prefs(context).edit()
            .putString(KEY_FEED_PAYLOAD, payload.toString())
            .putString(DealHunterWidgetProvider.KEY_DEALS_JSON, normalized.toString())
            .commit();
    }

    private static long parseExpiry(String value) {
        for (String pattern : new String[] {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            java.util.Date date = format.parse(value, position);
            if (date != null && position.getIndex() == value.length()) return date.getTime();
        }
        return 0L;
    }

    @PluginMethod
    public void clearWidgetSnapshot(PluginCall call) {
        executor.execute(() -> {
            synchronized (DealHunterWidgetPlugin.class) {
                clearStoredSnapshot(getContext());
            }
            DealHunterWidgetProvider.requestImmediateRefresh(getContext(), true);
            call.resolve();
        });
    }

    @PluginMethod
    public void consumeLaunchTarget(PluginCall call) {
        JSObject result = new JSObject();
        Intent intent = getActivity() != null ? getActivity().getIntent() : null;

        if (intent == null) {
            result.put("hasTarget", false);
            call.resolve(result);
            return;
        }

        String link = intent.getStringExtra(DealHunterWidgetProvider.EXTRA_DEAL_LINK);
        String platform = intent.getStringExtra(DealHunterWidgetProvider.EXTRA_DEAL_PLATFORM);

        if (link == null || link.trim().isEmpty()) {
            result.put("hasTarget", false);
            call.resolve(result);
            return;
        }

        intent.removeExtra(DealHunterWidgetProvider.EXTRA_DEAL_LINK);
        intent.removeExtra(DealHunterWidgetProvider.EXTRA_DEAL_PLATFORM);

        result.put("hasTarget", true);
        result.put("link", link);
        result.put("platform", platform == null ? "" : platform);
        call.resolve(result);
    }

    private static JSONArray normalizeDeals(Context context, JSONArray rawDeals) throws Exception {
        JSONArray normalized = new JSONArray();
        File imageDir = ensureImageDir(context);
        Set<String> keepNames = new HashSet<>();

        for (int i = 0; i < rawDeals.length(); i++) {
            JSONObject item = rawDeals.optJSONObject(i);
            if (item == null) continue;

            String title = item.optString("title", "").trim();
            String platform = item.optString("platform", "").trim();
            String link = item.optString("link", "").trim();
            long expiryMs = item.optLong("expiryMs", 0L);
            if (title.isEmpty() || platform.isEmpty() || link.isEmpty() || expiryMs <= 0L) continue;

            String imageUrl = item.optString("imageUrl", "").trim();
            String imagePath = cacheRemoteImage(imageDir, imageUrl, link);
            if (!imagePath.isEmpty()) {
                keepNames.add(new File(imagePath).getName());
            }

            JSONObject out = new JSONObject();
            out.put("title", title);
            out.put("platform", platform);
            out.put("link", link);
            out.put("expiryMs", expiryMs);
            out.put("imagePath", imagePath);
            out.put("originalPrice", item.optString("originalPrice", "").trim());
            out.put("currentPrice", item.optString("currentPrice", "FREE").trim());
            normalized.put(out);
        }

        cleanupOrphanedImages(imageDir, keepNames);
        return normalized;
    }

    private static void clearStoredSnapshot(Context context) {
        prefs(context).edit()
            .remove(DealHunterWidgetProvider.KEY_DEALS_JSON)
            .putInt(DealHunterWidgetProvider.KEY_INDEX, 0)
            .apply();
        cleanupOrphanedImages(ensureImageDir(context), new HashSet<>());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(DealHunterWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static File ensureImageDir(Context context) {
        File dir = new File(context.getFilesDir(), IMAGE_DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static void cleanupOrphanedImages(File imageDir, Set<String> keepNames) {
        File[] files = imageDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            if (keepNames.contains(file.getName())) continue;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String cacheRemoteImage(File imageDir, String imageUrl, String linkKey) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return "";

        String key = !linkKey.isEmpty() ? linkKey : imageUrl;
        String extension = guessExtension(imageUrl);
        String fileName = sha1(key) + extension;
        File target = new File(imageDir, fileName);
        if (target.isFile() && target.length() > 0L) {
            return target.getAbsolutePath();
        }

        HttpURLConnection connection = null;
        File temp = new File(imageDir, fileName + ".tmp");
        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "DealHunterWidget/1.0");
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return "";

            String contentType = connection.getContentType();
            if (contentType != null && extension.equals(".img")) {
                extension = guessExtensionFromContentType(contentType);
                fileName = sha1(key) + extension;
                target = new File(imageDir, fileName);
                temp = new File(imageDir, fileName + ".tmp");
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }

            if (temp.length() <= 0L) return "";
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
            return target.isFile() ? target.getAbsolutePath() : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (temp.exists() && (!target.isFile() || temp.equals(target))) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String guessExtension(String imageUrl) {
        String lower = imageUrl == null ? "" : imageUrl.toLowerCase(Locale.US);
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".webp")) return ".webp";
        if (lower.contains(".gif")) return ".gif";
        if (lower.contains(".jpeg") || lower.contains(".jpg")) return ".jpg";
        return ".img";
    }

    private static String guessExtensionFromContentType(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (lower.contains("png")) return ".png";
        if (lower.contains("webp")) return ".webp";
        if (lower.contains("gif")) return ".gif";
        if (lower.contains("jpeg") || lower.contains("jpg")) return ".jpg";
        return ".img";
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(String.valueOf(value).getBytes());
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format(Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }
}
