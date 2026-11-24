package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

/**
 * A minimal TVBox jar spider for development: wraps the demo CMS api through the host's OkHttp helper,
 * so loading, Init, host classes and the proxy can be exercised end to end.
 */
public class DemoJar extends Spider {
    private String api = "";

    @Override
    public void init(Context context, String extend) {
        try {
            api = new JSONObject(extend).optString("api");
        } catch (Exception e) {
            api = extend;
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject o = new JSONObject(get(api));
            JSONArray list = o.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject v = list.getJSONObject(i);
                    v.put("vod_remarks", "JAR" + (Init.initialized ? " · " : " (no init) · ") + v.optString("vod_remarks"));
                }
            }
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return get(api + "?ac=detail&t=" + tid + "&pg=" + pg);
    }

    @Override
    public String detailContent(List<String> ids) {
        return get(api + "?ac=detail&ids=" + ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            return get(api + "?ac=detail&wd=" + URLEncoder.encode(key, "UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return searchContent(key, quick);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id.equals("test-java-proxy-mp4")) {
                return new JSONObject().put("parse", 0).put("url", "proxy://do=jar&fixture=mp4").toString();
            }
            return new JSONObject().put("parse", id.contains("/demo/page/") ? 1 : 0).put("url", id).toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public Object[] proxy(java.util.Map<String, String> params) {
        if (!"mp4".equals(params.get("fixture"))) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(new URL(api), "/fixture.mp4").openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            if (params.containsKey("range")) connection.setRequestProperty("Range", params.get("range"));
            int status = connection.getResponseCode();
            var headers = new HashMap<String, String>();
            for (String key : List.of("Content-Length", "Content-Range", "Accept-Ranges")) {
                String value = connection.getHeaderField(key);
                if (value != null) headers.put(key, value);
            }
            HttpURLConnection upstream = connection;
            var body = new FilterInputStream(connection.getInputStream()) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { upstream.disconnect(); }
                }
            };
            return new Object[]{status, "video/mp4", body, headers};
        } catch (IOException unavailable) {
            if (connection != null) connection.disconnect();
            return null;
        }
    }

    private static String get(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            try (var body = connection.getInputStream()) {
                return new String(body.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException unavailable) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
