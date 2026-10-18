package com.abybenc.mbta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Wakes up on the alarm (or after a reboot), checks the saved train, then books the next check. */
public class CommuteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        new Thread(() -> {
            try {
                if (CommuteAlert.ACTION_CHECK.equals(action)) {
                    JSONObject cfg = CommuteAlert.config(app);
                    if (cfg != null && CommuteAlert.inWindow(cfg)) CommuteAlert.check(app, false);
                }
            } catch (Exception ignored) {
            } finally {
                try { CommuteAlert.schedule(app); } catch (Exception ignored) { }
                result.finish();
            }
        }).start();
    }
}
