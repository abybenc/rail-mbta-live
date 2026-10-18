package com.abybenc.mbta;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Weekday commute alert. Saved config (JSON):
 * {fromId, fromName, toId, toName, time:"HH:mm", threshold:10}
 * From 90 minutes before departure until departure, it checks MBTA live data
 * about every 10 minutes and notifies when the train is 10+ minutes off, cancelled,
 * or not running, and again when it gets worse or returns to schedule.
 */
public class CommuteAlert {
    static final ZoneId TZ = ZoneId.of("America/New_York");
    static final String ACTION_CHECK = "com.abybenc.mbta.CHECK";
    static final String CHANNEL = "commute";
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    static final DateTimeFormatter NICE = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("commute", Context.MODE_PRIVATE);
    }

    static JSONObject config(Context c) {
        try {
            String s = prefs(c).getString("cfg", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context c, String json) {
        prefs(c).edit().putString("cfg", json).remove("last").apply();
        schedule(c);
    }

    static void clear(Context c) {
        prefs(c).edit().remove("cfg").remove("last").apply();
        alarm(c).cancel(pending(c));
    }

    static AlarmManager alarm(Context c) {
        return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    }

    static PendingIntent pending(Context c) {
        Intent i = new Intent(c, CommuteReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static LocalTime target(JSONObject cfg) {
        return LocalTime.parse(cfg.optString("time", "08:00"), HM);
    }

    static boolean weekday(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
    }

    /** Books the next check: every ~10 min in the 90 min before departure, weekdays only. */
    static void schedule(Context c) {
        JSONObject cfg = config(c);
        if (cfg == null) { alarm(c).cancel(pending(c)); return; }
        ZonedDateTime now = ZonedDateTime.now(TZ);
        LocalTime t = target(cfg);
        ZonedDateTime next = null;
        for (int k = 0; k < 8 && next == null; k++) {
            LocalDate d = now.toLocalDate().plusDays(k);
            if (!weekday(d)) continue;
            ZonedDateTime dep = ZonedDateTime.of(d, t, TZ);
            ZonedDateTime start = dep.minusMinutes(90);
            ZonedDateTime end = dep.plusMinutes(2);
            if (now.isBefore(start)) next = start;
            else if (now.isBefore(end)) {
                ZonedDateTime n = now.plusMinutes(10);
                next = n.isBefore(end) ? n : end;
            }
        }
        if (next == null) return;
        alarm(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending(c));
    }

    static boolean inWindow(JSONObject cfg) {
        ZonedDateTime now = ZonedDateTime.now(TZ);
        if (!weekday(now.toLocalDate())) return false;
        ZonedDateTime dep = ZonedDateTime.of(now.toLocalDate(), target(cfg), TZ);
        return !now.isBefore(dep.minusMinutes(91)) && !now.isAfter(dep.plusMinutes(5));
    }

    static String get(String path) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL("https://api-v3.mbta.com" + path).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        try (InputStream in = h.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            h.disconnect();
        }
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    static String rel(JSONObject o, String key) {
        JSONObject r = o.optJSONObject("relationships");
        if (r == null) return null;
        JSONObject k = r.optJSONObject(key);
        if (k == null) return null;
        JSONObject d = k.optJSONObject("data");
        return d == null ? null : d.optString("id", null);
    }

    static String nice(LocalTime t) {
        return t.format(NICE).toLowerCase(Locale.US);
    }

    static String nice(OffsetDateTime t) {
        return nice(t.atZoneSameInstant(TZ).toLocalTime());
    }

    /** Checks the saved train now. force=true always posts a notification (used by "Test now"). */
    static String check(Context c, boolean force) throws Exception {
        JSONObject cfg = config(c);
        if (cfg == null) return "No alert set";
        LocalDate today = LocalDate.now(TZ);
        LocalTime t = target(cfg);
        String from = cfg.getString("fromId"), to = cfg.getString("toId");
        String fromName = cfg.optString("fromName", "your station");
        String toName = cfg.optString("toName", "");
        int threshold = cfg.optInt("threshold", 10);

        LocalTime minT = t.minusMinutes(10);
        LocalTime maxT = t.plusMinutes(150);
        String max = maxT.isBefore(t) ? "23:59" : maxT.format(HM);
        JSONObject sch = new JSONObject(get("/schedules?filter%5Bstop%5D=" + enc(from + "," + to)
                + "&filter%5Bdate%5D=" + today + "&filter%5Broute_type%5D=2"
                + "&filter%5Bmin_time%5D=" + minT.format(HM) + "&filter%5Bmax_time%5D=" + max
                + "&include=stop,trip"));

        Map<String, String> parent = new HashMap<>();
        Map<String, String> trainNo = new HashMap<>();
        JSONArray inc = sch.optJSONArray("included");
        if (inc != null) for (int i = 0; i < inc.length(); i++) {
            JSONObject o = inc.getJSONObject(i);
            if ("stop".equals(o.optString("type"))) {
                String p = rel(o, "parent_station");
                parent.put(o.getString("id"), p == null ? o.getString("id") : p);
            } else if ("trip".equals(o.optString("type"))) {
                trainNo.put(o.getString("id"), o.getJSONObject("attributes").optString("name", ""));
            }
        }

        Map<String, Integer> fromSeq = new HashMap<>(), toSeq = new HashMap<>();
        Map<String, String> fromDep = new HashMap<>();
        JSONArray data = sch.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject s = data.getJSONObject(i);
            JSONObject a = s.getJSONObject("attributes");
            String trip = rel(s, "trip"), stop = rel(s, "stop");
            if (trip == null || stop == null) continue;
            String p = parent.containsKey(stop) ? parent.get(stop) : stop;
            if (p.equals(from)) {
                if (a.optInt("pickup_type", 0) == 1 || a.isNull("departure_time")) continue;
                fromSeq.put(trip, a.getInt("stop_sequence"));
                fromDep.put(trip, a.getString("departure_time"));
            } else if (p.equals(to)) {
                toSeq.put(trip, a.getInt("stop_sequence"));
            }
        }

        String trip = null;
        OffsetDateTime sched = null;
        long best = 99;
        for (String tr : fromSeq.keySet()) {
            if (!toSeq.containsKey(tr) || toSeq.get(tr) <= fromSeq.get(tr)) continue;
            OffsetDateTime d = OffsetDateTime.parse(fromDep.get(tr));
            long diff = Math.abs(Duration.between(t, d.atZoneSameInstant(TZ).toLocalTime()).toMinutes());
            if (diff <= 3 && diff < best) { best = diff; trip = tr; sched = d; }
        }

        String status, title, body;
        String route = fromName + (toName.isEmpty() ? "" : " to " + toName);
        if (trip == null) {
            status = "NOTRUN";
            title = "No " + nice(t) + " train today";
            body = "There's no " + nice(t) + " train from " + route + " in today's timetable. It may be cancelled or the schedule changed. Open the app for other trains.";
        } else {
            String num = trainNo.containsKey(trip) ? trainNo.get(trip) : "";
            JSONObject pre = new JSONObject(get("/predictions?filter%5Btrip%5D=" + enc(trip) + "&filter%5Bstop%5D=" + enc(from)));
            JSONArray pd = pre.getJSONArray("data");
            long delay = 0;
            boolean cancelled = false;
            OffsetDateTime predicted = null;
            if (pd.length() > 0) {
                JSONObject a = pd.getJSONObject(0).getJSONObject("attributes");
                String r = a.isNull("schedule_relationship") ? "" : a.getString("schedule_relationship");
                if ("CANCELLED".equals(r) || "SKIPPED".equals(r)) cancelled = true;
                String pt = !a.isNull("departure_time") ? a.getString("departure_time")
                        : (!a.isNull("arrival_time") ? a.getString("arrival_time") : null);
                if (pt != null) {
                    predicted = OffsetDateTime.parse(pt);
                    delay = Math.round(Duration.between(sched, predicted).getSeconds() / 60.0);
                }
            }
            String train = num.isEmpty() ? "Your train" : "Train " + num;
            if (cancelled) {
                status = "CANCEL";
                title = "Your " + nice(t) + " train is cancelled";
                body = train + " from " + route + " is cancelled today. Open the app for the next train.";
            } else if (Math.abs(delay) >= threshold) {
                status = "DELAY" + (delay / 5);
                title = train + " is " + Math.abs(delay) + " min " + (delay > 0 ? "late" : "early");
                body = "Your " + nice(t) + " from " + route + " now leaves at " + nice(predicted) + ".";
            } else {
                status = "OK";
                title = "Your " + nice(t) + " train is on schedule";
                body = train + " from " + route + (predicted != null ? " leaves at " + nice(predicted) + "." : " has no live update yet, it's running to the timetable.");
            }
        }

        String key = today + "|" + status;
        String last = prefs(c).getString("last", "");
        if (force) {
            notify(c, title, body, 7);
        } else if (!"OK".equals(status) && !key.equals(last)) {
            notify(c, title, body, 7);
            prefs(c).edit().putString("last", key).apply();
        } else if ("OK".equals(status) && last.startsWith(today + "|") && !last.endsWith("|OK")) {
            notify(c, "Back on schedule", body, 7);
            prefs(c).edit().putString("last", key).apply();
        }
        return title;
    }

    static void notify(Context c, String title, String body, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Commute alerts", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent p = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(p)
                .build();
        nm.notify(id, n);
    }
}
