package com.whatsbb.app;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferencesManager {
    private static final String PREF_NAME = "whatsbb_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "http://192.168.1.5:3000";

    private final SharedPreferences prefs;

    public PreferencesManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public void setServerUrl(String url) {
        if (url != null) {
            url = url.trim();
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        prefs.edit().putString(KEY_SERVER_URL, url).commit();
    }
}
