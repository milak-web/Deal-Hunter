package com.milak.dealhunterfresh;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;

/** Refreshes the same published feed as the website without needing a WebView. */
public class DealHunterRefreshService extends JobService {
    private static final int PERIODIC_JOB = 55301;
    private static final int IMMEDIATE_JOB = 55302;
    private static final String[] FEEDS = {
        "https://milak-web.github.io/Deal-Hunter/data/deals.json",
        "https://raw.githubusercontent.com/milak-web/Deal-Hunter/master/data/deals.json"
    };
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, HttpURLConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Future<?>> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, JobParameters> activeJobs = new ConcurrentHashMap<>();

    public static void schedule(Context context, boolean refreshNow) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        ComponentName component = new ComponentName(context, DealHunterRefreshService.class);
        boolean scheduled = false;
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == PERIODIC_JOB) scheduled = true;
        }
        if (!scheduled) {
            scheduler.schedule(new JobInfo.Builder(PERIODIC_JOB, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(30 * 60 * 1000L)
                .build());
        }
        long lastFetch = context.getSharedPreferences(DealHunterWidgetProvider.PREFS_NAME, MODE_PRIVATE)
            .getLong("feed_fetched_at", 0L);
        if (refreshNow && System.currentTimeMillis() - lastFetch > 5 * 60 * 1000L) {
            scheduler.schedule(new JobInfo.Builder(IMMEDIATE_JOB, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int jobId = params.getJobId();
        activeJobs.put(jobId, params);
        running.put(jobId, executor.submit(() -> {
            boolean retry = true;
            for (String feed : FEEDS) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    JSONObject payload = download(feed, jobId);
                    DealHunterWidgetPlugin.storeFeedSnapshot(this, payload);
                    getSharedPreferences(DealHunterWidgetProvider.PREFS_NAME, MODE_PRIVATE).edit()
                        .putLong("feed_fetched_at", System.currentTimeMillis()).apply();
                    DealHunterWidgetProvider.requestImmediateRefresh(this, false);
                    retry = false;
                    break;
                } catch (Exception error) {
                    Log.w("DealHunterRefresh", "Feed refresh failed; keeping the saved snapshot", error);
                }
            }
            if (activeJobs.remove(jobId, params)) {
                running.remove(jobId);
                jobFinished(params, retry);
            }
        }));
        return true;
    }

    private JSONObject download(String feed, int jobId) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL(feed + "?t=" + System.currentTimeMillis()).openConnection();
        connections.put(jobId, request);
        request.setConnectTimeout(12_000);
        request.setReadTimeout(12_000);
        request.setRequestProperty("Accept", "application/json");
        request.setRequestProperty("User-Agent", "DealHunter/1.3");
        request.setUseCaches(false);
        try {
            if (request.getResponseCode() != 200) throw new IllegalStateException("Feed HTTP " + request.getResponseCode());
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + size > 4 * 1024 * 1024) throw new IllegalStateException("Feed exceeds size limit");
                    output.write(buffer, 0, size);
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally {
            request.disconnect();
            connections.remove(jobId, request);
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        int jobId = params.getJobId();
        activeJobs.remove(jobId);
        Future<?> task = running.remove(jobId);
        if (task != null) task.cancel(true);
        HttpURLConnection request = connections.remove(jobId);
        if (request != null) request.disconnect();
        return true;
    }

    @Override
    public void onDestroy() {
        for (HttpURLConnection request : connections.values()) request.disconnect();
        executor.shutdownNow();
        super.onDestroy();
    }
}
