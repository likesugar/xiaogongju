package com.wink.pillmate;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** 本地存储：用药计划 JSON 存 SharedPreferences，完全离线 */
public class MedStore {

    public static class Plan {
        public int id;
        public String name;
        public String spec;      // 规格剂量
        public String dose;      // 单次用量，如 "2 片"
        public List<int[]> times = new ArrayList<int[]>(); // {h,m}
        public String relation;  // before / after / sleep / 空
        public int repeat;       // 0=每天 1=工作日 2=自定义
        public int weekdays;     // 位掩码 周一=bit0 ... 周日=bit6
        public long startDay;    // yyyyMMdd
        public long endDay;      // 0=未设置
        public String sound;     // system / vibrate / silent
        public String doneDate;  // 当天完成为该日期

        public String timeText(int[] t) {
            return String.format(Locale.US, "%02d:%02d", t[0], t[1]);
        }

        public String timesText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < times.size(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(timeText(times.get(i)));
            }
            return sb.toString();
        }

        public String relationText() {
            if ("before".equals(relation)) return "饭前";
            if ("after".equals(relation)) return "饭后";
            if ("sleep".equals(relation)) return "睡前";
            return "";
        }

        /** 给定日期该计划是否生效 */
        public boolean activeToday(Calendar c) {
            long day = c.get(Calendar.YEAR) * 10000L + (c.get(Calendar.MONTH) + 1) * 100L
                    + c.get(Calendar.DAY_OF_MONTH);
            if (startDay > 0 && day < startDay) return false;
            if (endDay > 0 && day > endDay) return false;
            if (repeat == 0) return true;
            int dow = c.get(Calendar.DAY_OF_WEEK); // SUNDAY=1..SATURDAY=7
            int bit = (dow == Calendar.SUNDAY) ? 6 : dow - 2; // 周一=0..周日=6
            if (repeat == 1) return bit <= 4; // 周一~周五
            return (weekdays & (1 << bit)) != 0;
        }
    }

    private static final String KEY = "plans";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("pillmate", Context.MODE_PRIVATE);
    }

    public static List<Plan> load(Context c) {
        List<Plan> out = new ArrayList<Plan>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Plan p = new Plan();
                p.id = o.optInt("id");
                p.name = o.optString("name");
                p.spec = o.optString("spec");
                p.dose = o.optString("dose");
                p.relation = o.optString("relation");
                p.repeat = o.optInt("repeat");
                p.weekdays = o.optInt("weekdays", 0b1111111);
                p.startDay = o.optLong("startDay");
                p.endDay = o.optLong("endDay");
                p.sound = o.optString("sound", "system");
                p.doneDate = o.optString("doneDate");
                JSONArray ts = o.optJSONArray("times");
                if (ts != null) {
                    for (int j = 0; j < ts.length(); j++) {
                        String[] hm = ts.getString(j).split(":");
                        p.times.add(new int[]{Integer.parseInt(hm[0]), Integer.parseInt(hm[1])});
                    }
                }
                out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(Context c, List<Plan> plans) {
        try {
            JSONArray arr = new JSONArray();
            for (Plan p : plans) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("spec", p.spec == null ? "" : p.spec);
                o.put("dose", p.dose == null ? "" : p.dose);
                o.put("relation", p.relation == null ? "" : p.relation);
                o.put("repeat", p.repeat);
                o.put("weekdays", p.weekdays);
                o.put("startDay", p.startDay);
                o.put("endDay", p.endDay);
                o.put("sound", p.sound == null ? "system" : p.sound);
                o.put("doneDate", p.doneDate == null ? "" : p.doneDate);
                JSONArray ts = new JSONArray();
                for (int[] t : p.times) ts.put(String.format(Locale.US, "%02d:%02d", t[0], t[1]));
                o.put("times", ts);
                arr.put(o);
            }
            sp(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static int nextId(Context c) {
        return sp(c).getInt("nextId", 1);
    }

    public static void bumpId(Context c) {
        sp(c).edit().putInt("nextId", sp(c).getInt("nextId", 1) + 1).apply();
    }

    public static Plan byId(Context c, int id) {
        for (Plan p : load(c)) if (p.id == id) return p;
        return null;
    }
}
