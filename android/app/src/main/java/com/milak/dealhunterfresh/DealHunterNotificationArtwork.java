package com.milak.dealhunterfresh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

final class DealHunterNotificationArtwork {
    static Bitmap thumbnail(Bitmap artwork, int width, int height) {
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        float scale = Math.max((float) width / artwork.getWidth(), (float) height / artwork.getHeight());
        float left = (width - artwork.getWidth() * scale) / 2f;
        float top = (height - artwork.getHeight() * scale) / 2f;
        new Canvas(result).drawBitmap(artwork, null, new android.graphics.RectF(left, top,
            left + artwork.getWidth() * scale, top + artwork.getHeight() * scale), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    private static File file(Context context, String url) throws Exception {
        File directory = new File(context.getFilesDir(), "notification_artwork");
        if (!directory.exists()) directory.mkdirs();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
        StringBuilder name = new StringBuilder();
        for (byte value : hash) name.append(String.format("%02x", value & 0xff));
        return new File(directory, name + ".jpg");
    }

    static Bitmap cached(Context context, String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            File file = file(context, url);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return file.isFile() ? BitmapFactory.decodeFile(file.getPath(), options) : null;
        } catch (Exception ignored) { return null; }
    }

    static Bitmap prepare(Context context, String url) {
        Bitmap existing = cached(context, url);
        if (existing != null || url == null || !url.startsWith("https://")) return existing;
        HttpURLConnection connection = null;
        File download = null;
        try {
            File target = file(context, url);
            download = File.createTempFile("art-", ".download", target.getParentFile());
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("User-Agent", "DealHunter/1.4");
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(download)) {
                byte[] buffer = new byte[8192];
                int size;
                int total = 0;
                long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
                while ((size = input.read(buffer)) != -1) {
                    total += size;
                    if (total > 4 * 1024 * 1024 || android.os.SystemClock.elapsedRealtime() > deadline) return null;
                    output.write(buffer, 0, size);
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(download.getPath(), options);
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 900) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(download.getPath(), options);
            if (decoded == null) return null;
            // Keep bitmap parcels comfortably below Android's notification transaction limit.
            double scale = Math.min(1.0, Math.min(800.0 / decoded.getWidth(), 450.0 / decoded.getHeight()));
            Bitmap result = Bitmap.createBitmap(Math.max(1, (int) (decoded.getWidth() * scale)),
                Math.max(1, (int) (decoded.getHeight() * scale)), Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.rgb(15, 23, 42));
            canvas.drawBitmap(decoded, null, new android.graphics.Rect(0, 0, result.getWidth(), result.getHeight()), null);
            decoded.recycle();
            try (FileOutputStream output = new FileOutputStream(target)) {
                result.compress(Bitmap.CompressFormat.JPEG, 88, output);
            }
            File[] cached = target.getParentFile().listFiles((directory, name) -> name.endsWith(".jpg"));
            if (cached != null && cached.length > 64) {
                java.util.Arrays.sort(cached, java.util.Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < cached.length - 64; i++) cached[i].delete();
            }
            return result;
        } catch (Exception ignored) { return null; }
        finally {
            if (connection != null) connection.disconnect();
            if (download != null) download.delete();
        }
    }
}
