package com.milak.dealhunterfresh;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "DealHunterNotifications")
public class DealHunterNotificationsPlugin extends Plugin {
    @PluginMethod
    public void configure(PluginCall call) {
        DealHunterNotifications.ensureChannels(getContext());
        call.resolve();
    }

    @PluginMethod
    public void showAlert(PluginCall call) {
        JSObject payload = call.getObject("payload");
        if (payload == null || !payload.has("title") || payload.optInt("id") <= 0) {
            call.reject("A game title and notification ID are required");
            return;
        }
        DealHunterNotifications.showAlert(getContext(), payload, null);
        call.resolve();
    }

    @PluginMethod
    public void clearAlerts(PluginCall call) {
        androidx.core.app.NotificationManagerCompat.from(getContext()).cancelAll();
        call.resolve();
    }

    @PluginMethod
    public void syncReminders(PluginCall call) {
        JSArray entries = call.getArray("notifications", new JSArray());
        try {
            DealHunterNotifications.syncReminders(getContext(), entries);
            call.resolve();
        } catch (Exception error) { call.reject("Could not schedule game reminders", error); }
    }
}
