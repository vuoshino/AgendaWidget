package it.tiziano.agenda;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Costruisce le righe del widget a partire da eventi e promemoria. */
public final class AgendaData {

    public static final int TYPE_DAY = 0;
    public static final int TYPE_SUMMARY = 1;
    public static final int TYPE_TASK = 2;
    public static final int TYPE_EVENT = 3;

    private static final int DAYS_AHEAD = 14;
    private static final int MAX_ROWS = 60;
    private static final int MAX_UNDATED = 5;

    private static final Locale IT = Locale.ITALIAN;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", IT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", IT);

    private AgendaData() {
    }

    public static final class Row {
        public int type;
        public String title = "";
        public String subtitle = "";
        public int color;
        public long eventId;
        public long begin;
        public long end;
        public String taskId = "";
        public String listId = "";
    }

    /** Gruppo di un giorno: prima i promemoria, poi gli eventi in ordine di orario. */
    private static final class DayGroup {
        final List<Row> tasks = new ArrayList<>();
        final List<Row> events = new ArrayList<>();
    }

    public static List<Row> build(Context c) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate last = today.plusDays(DAYS_AHEAD);

        TreeMap<LocalDate, DayGroup> days = new TreeMap<>();
        List<Row> overdue = new ArrayList<>();
        List<Row> undated = new ArrayList<>();
        int taskCount = 0;

        // Promemoria dalla cache
        boolean tasksOn = Store.isConnected(c);
        if (tasksOn) {
            for (Store.TaskItem t : Store.getTasks(c)) {
                taskCount++;
                Row r = new Row();
                r.type = TYPE_TASK;
                r.title = t.title;
                r.taskId = t.id;
                r.listId = t.listId;
                if (t.due.isEmpty()) {
                    undated.add(r);
                    continue;
                }
                LocalDate due;
                try {
                    due = LocalDate.parse(t.due);
                } catch (Exception e) {
                    undated.add(r);
                    continue;
                }
                if (due.isBefore(today)) {
                    r.subtitle = "Scaduto " + capitalize(due.format(DAY));
                    overdue.add(r);
                } else if (!due.isAfter(last)) {
                    group(days, due).tasks.add(r);
                }
            }
        }

        // Eventi dal calendario del telefono
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            long from = today.atStartOfDay(zone).toInstant().toEpochMilli();
            long to = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long now = System.currentTimeMillis();

            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, from);
            ContentUris.appendId(b, to);
            String[] projection = {
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.DISPLAY_COLOR,
            };
            try (Cursor cur = c.getContentResolver().query(b.build(), projection,
                    CalendarContract.Instances.VISIBLE + "=1", null,
                    CalendarContract.Instances.BEGIN + " ASC")) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        long begin = cur.getLong(2);
                        long end = cur.getLong(3);
                        boolean allDay = cur.getInt(4) == 1;
                        if (!allDay && end > 0 && end < now) continue;

                        LocalDate day;
                        if (allDay) {
                            day = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate();
                            LocalDate endDay = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate();
                            if (!endDay.isAfter(today)) continue;
                        } else {
                            day = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate();
                        }
                        if (day.isBefore(today)) day = today;
                        if (day.isAfter(last)) continue;

                        Row r = new Row();
                        r.type = TYPE_EVENT;
                        r.eventId = cur.getLong(0);
                        String title = cur.getString(1);
                        r.title = title == null || title.trim().isEmpty() ? "(Senza titolo)" : title.trim();
                        r.begin = begin;
                        r.end = end;
                        r.color = 0xFF000000 | cur.getInt(5);
                        r.subtitle = allDay ? "Tutto il giorno" : timeRange(begin, end, zone);
                        group(days, day).events.add(r);
                    }
                }
            } catch (SecurityException ignored) {
                // permesso revocato nel frattempo
            }
        }

        // Composizione finale
        List<Row> rows = new ArrayList<>();
        if (tasksOn && taskCount > 0) {
            Row s = new Row();
            s.type = TYPE_SUMMARY;
            s.title = taskCount == 1 ? "1 promemoria" : taskCount + " promemoria";
            rows.add(s);
        }
        if (!overdue.isEmpty()) {
            rows.add(day("In ritardo"));
            rows.addAll(overdue);
        }
        for (Map.Entry<LocalDate, DayGroup> e : days.entrySet()) {
            DayGroup g = e.getValue();
            if (g.tasks.isEmpty() && g.events.isEmpty()) continue;
            rows.add(day(dayLabel(e.getKey(), today)));
            rows.addAll(g.tasks);
            rows.addAll(g.events);
            if (rows.size() >= MAX_ROWS) break;
        }
        if (!undated.isEmpty() && rows.size() < MAX_ROWS) {
            rows.add(day("Da fare"));
            for (int i = 0; i < undated.size() && i < MAX_UNDATED; i++) rows.add(undated.get(i));
        }
        // Se c'è solo il riepilogo, non serve mostrarlo da solo
        if (rows.size() == 1 && rows.get(0).type == TYPE_SUMMARY) rows.clear();
        return rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
    }

    private static DayGroup group(TreeMap<LocalDate, DayGroup> days, LocalDate d) {
        DayGroup g = days.get(d);
        if (g == null) {
            g = new DayGroup();
            days.put(d, g);
        }
        return g;
    }

    private static Row day(String label) {
        Row r = new Row();
        r.type = TYPE_DAY;
        r.title = label;
        return r;
    }

    private static String dayLabel(LocalDate d, LocalDate today) {
        if (d.equals(today)) return "Oggi";
        if (d.equals(today.plusDays(1))) return "Domani";
        return capitalize(d.format(DAY));
    }

    private static String timeRange(long begin, long end, ZoneId zone) {
        ZonedDateTime b = Instant.ofEpochMilli(begin).atZone(zone);
        if (end <= begin) return b.format(TIME);
        ZonedDateTime e = Instant.ofEpochMilli(end).atZone(zone);
        return b.format(TIME) + " – " + e.format(TIME);
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(IT) + s.substring(1);
    }
}
