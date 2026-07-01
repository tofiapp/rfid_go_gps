package com.rfidw.app.auth;

import android.content.SharedPreferences;

public final class UserSession {

    public static final String PREFS_NAME = "rfidgogps";
    public static final String PREF_USER_ID = "userId";

    private UserSession() {}

    public static boolean isLoggedIn(SharedPreferences prefs) {
        return !getUserId(prefs).isEmpty();
    }

    public static String getUserId(SharedPreferences prefs) {
        String id = prefs.getString(PREF_USER_ID, "");
        return id == null ? "" : id.trim();
    }

    public static void login(SharedPreferences prefs, String userId) {
        prefs.edit().putString(PREF_USER_ID, userId == null ? "" : userId.trim()).apply();
    }

    public static void logout(SharedPreferences prefs) {
        prefs.edit().remove(PREF_USER_ID).apply();
    }
}
