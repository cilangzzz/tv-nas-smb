package com.tv.neighbor;

import org.json.JSONObject;

/** 一台 SMB 主机的连接凭据（含显示名与协议偏好）。 */
public class Cred implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public String host;      // 主机键（ip 或主机名，小写）
    public String name;      // 显示名
    public String domain = "";
    public String user = "";
    public String pass = "";
    public boolean anonymous = true; // 访客/匿名
    public boolean allowSmb1 = false; // 老旧设备才需要
    public boolean favorite = false;

    public Cred() {}

    public Cred(String host) {
        this.host = host.toLowerCase();
        this.name = host;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("host", host);
            o.put("name", name);
            o.put("domain", domain);
            o.put("user", user);
            o.put("pass", pass);
            o.put("anonymous", anonymous);
            o.put("allowSmb1", allowSmb1);
            o.put("favorite", favorite);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Cred fromJson(JSONObject o) {
        Cred c = new Cred();
        c.host = o.optString("host", "").toLowerCase();
        c.name = o.optString("name", c.host);
        c.domain = o.optString("domain", "");
        c.user = o.optString("user", "");
        c.pass = o.optString("pass", "");
        c.anonymous = o.optBoolean("anonymous", true);
        c.allowSmb1 = o.optBoolean("allowSmb1", false);
        c.favorite = o.optBoolean("favorite", false);
        return c;
    }
}
