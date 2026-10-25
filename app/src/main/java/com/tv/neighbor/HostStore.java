package com.tv.neighbor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 收藏/凭据持久化（SharedPreferences + JSON）。 */
public final class HostStore {
    private static final String PREF = "hosts";
    private static final String KEY_CREDS = "creds";

    private HostStore() {}

    private static SharedPreferences pref(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONObject all(Context c) {
        try {
            return new JSONObject(pref(c).getString(KEY_CREDS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject all) {
        pref(c).edit().putString(KEY_CREDS, all.toString()).apply();
    }

    /** 取主机凭据；没有则返回只带 host 的默认对象（匿名）。 */
    public static Cred get(Context c, String host) {
        String key = host == null ? "" : host.toLowerCase();
        JSONObject all = all(c);
        JSONObject o = all.optJSONObject(key);
        if (o != null) return Cred.fromJson(o);
        return new Cred(key);
    }

    public static void put(Context c, Cred cred) {
        try {
            JSONObject all = all(c);
            all.put(cred.host, cred.toJson());
            save(c, all);
        } catch (Exception ignored) {
        }
    }

    public static boolean isFavorite(Context c, String host) {
        return get(c, host).favorite;
    }

    public static void setFavorite(Context c, String host, boolean fav) {
        Cred cred = get(c, host);
        cred.favorite = fav;
        if (fav && cred.name == null || cred.name.isEmpty()) cred.name = host;
        put(c, cred);
    }

    public static List<Cred> favorites(Context c) {
        List<Cred> out = new ArrayList<>();
        JSONObject all = all(c);
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            Cred cred = Cred.fromJson(all.optJSONObject(it.next()));
            if (cred.favorite && cred.host != null && !cred.host.isEmpty()) out.add(cred);
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static void remove(Context c, String host) {
        try {
            JSONObject all = all(c);
            all.remove(host.toLowerCase());
            save(c, all);
        } catch (Exception ignored) {
        }
    }
}
