package com.dankhole.airlockandroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class Preferences {
    static final String FILE = "airlock_prefs";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_SELECTED_PACKAGES = "selected_packages";
    static final String KEY_DAILY_LIMIT_MINUTES = "daily_limit_minutes";
    static final String KEY_EXTRA_TIME_MINUTES = "extra_time_minutes";
    static final String KEY_ACCOUNTABILITY_NUMBER = "accountability_number";

    private static final String CODE_PREFIX = "code_";
    private static final String CODE_EXPIRY_PREFIX = "code_expiry_";
    private static final String UNLOCK_PREFIX = "unlock_until_";
    private static final String USAGE_PREFIX = "usage_";
    private static final long CODE_TTL_MS = 10 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Preferences() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static Set<String> selectedPackages(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_SELECTED_PACKAGES, Collections.emptySet());
        return new HashSet<>(stored);
    }

    static void saveSelectedPackages(Context context, Set<String> packageNames) {
        prefs(context).edit()
                .putStringSet(KEY_SELECTED_PACKAGES, new HashSet<>(packageNames))
                .apply();
    }

    static int dailyLimitMinutes(Context context) {
        return Math.max(1, prefs(context).getInt(KEY_DAILY_LIMIT_MINUTES, 15));
    }

    static int extraTimeMinutes(Context context) {
        return Math.max(1, prefs(context).getInt(KEY_EXTRA_TIME_MINUTES, 5));
    }

    static long getUsageTodayMs(Context context, String packageName) {
        return prefs(context).getLong(usageKey(packageName), 0L);
    }

    static void addUsageTodayMs(Context context, String packageName, long deltaMs) {
        if (deltaMs <= 0L || deltaMs > 10_000L) {
            return;
        }
        SharedPreferences preferences = prefs(context);
        String key = usageKey(packageName);
        long next = preferences.getLong(key, 0L) + deltaMs;
        preferences.edit().putLong(key, next).apply();
    }

    static boolean isOverLimit(Context context, String packageName) {
        long limitMs = dailyLimitMinutes(context) * 60_000L;
        return getUsageTodayMs(context, packageName) >= limitMs;
    }

    static boolean isTemporarilyUnlocked(Context context, String packageName) {
        return prefs(context).getLong(UNLOCK_PREFIX + packageName, 0L) > System.currentTimeMillis();
    }

    static void grantExtraTime(Context context, String packageName) {
        long until = System.currentTimeMillis() + extraTimeMinutes(context) * 60_000L;
        prefs(context).edit().putLong(UNLOCK_PREFIX + packageName, until).apply();
    }

    static String getOrCreateCode(Context context, String packageName) {
        SharedPreferences preferences = prefs(context);
        long now = System.currentTimeMillis();
        String codeKey = CODE_PREFIX + packageName;
        String expiryKey = CODE_EXPIRY_PREFIX + packageName;
        String existing = preferences.getString(codeKey, null);
        long expiry = preferences.getLong(expiryKey, 0L);
        if (existing != null && expiry > now) {
            return existing;
        }

        String code = String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        preferences.edit()
                .putString(codeKey, code)
                .putLong(expiryKey, now + CODE_TTL_MS)
                .apply();
        return code;
    }

    static boolean consumeCodeIfValid(Context context, String packageName, String enteredCode) {
        SharedPreferences preferences = prefs(context);
        String codeKey = CODE_PREFIX + packageName;
        String expiryKey = CODE_EXPIRY_PREFIX + packageName;
        String expected = preferences.getString(codeKey, "");
        long expiry = preferences.getLong(expiryKey, 0L);
        boolean valid = expected.equals(enteredCode) && expiry > System.currentTimeMillis();
        if (valid) {
            preferences.edit()
                    .remove(codeKey)
                    .remove(expiryKey)
                    .apply();
        }
        return valid;
    }

    private static String usageKey(String packageName) {
        return USAGE_PREFIX + today() + "_" + packageName;
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }
}
