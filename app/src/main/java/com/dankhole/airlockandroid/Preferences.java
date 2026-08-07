package com.dankhole.airlockandroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    static final String KEY_ACCOUNTABILITY_NUMBER = "accountability_number";
    static final String KEY_MASTER_PIN_HASH = "master_pin_hash";
    static final String KEY_MASTER_PIN_SALT = "master_pin_salt";

    private static final String APPROVAL_CODES_PREFIX = "approval_codes_";
    private static final String APPROVAL_CODE_PREFIX = "approval_code_";
    private static final String APPROVAL_CODE_EXPIRY_PREFIX = "approval_code_expiry_";
    private static final String APPROVAL_CODE_MINUTES_PREFIX = "approval_code_minutes_";
    private static final String APP_LIMIT_PREFIX = "limit_minutes_";
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

    static int dailyLimitMinutes(Context context, String packageName) {
        return Math.max(1, prefs(context).getInt(limitKey(packageName), dailyLimitMinutes(context)));
    }

    static boolean hasLimitedApps(Context context) {
        return !selectedPackages(context).isEmpty();
    }

    static void saveLimitForPackages(Context context, Set<String> packageNames, int minutes) {
        if (packageNames.isEmpty()) {
            return;
        }
        Set<String> limitedPackages = selectedPackages(context);
        limitedPackages.addAll(packageNames);

        SharedPreferences.Editor editor = prefs(context).edit()
                .putStringSet(KEY_SELECTED_PACKAGES, new HashSet<>(limitedPackages));
        int safeMinutes = Math.max(1, minutes);
        for (String packageName : packageNames) {
            editor.putInt(limitKey(packageName), safeMinutes);
        }
        editor.apply();
    }

    static void removeLimitsForPackages(Context context, Set<String> packageNames) {
        if (packageNames.isEmpty()) {
            return;
        }
        Set<String> limitedPackages = selectedPackages(context);
        limitedPackages.removeAll(packageNames);

        SharedPreferences.Editor editor = prefs(context).edit()
                .putStringSet(KEY_SELECTED_PACKAGES, new HashSet<>(limitedPackages));
        for (String packageName : packageNames) {
            editor.remove(limitKey(packageName));
        }
        editor.apply();
    }

    static boolean hasAccountabilityNumber(Context context) {
        return accountabilityPhoneNumber(context).length() == 10;
    }

    static String accountabilityPhoneNumber(Context context) {
        return normalizedPhoneNumber(prefs(context).getString(KEY_ACCOUNTABILITY_NUMBER, ""));
    }

    static String normalizedPhoneNumber(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isDigit(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    static boolean hasMasterPin(Context context) {
        SharedPreferences preferences = prefs(context);
        return preferences.contains(KEY_MASTER_PIN_HASH)
                && preferences.contains(KEY_MASTER_PIN_SALT);
    }

    static void setMasterPin(Context context, String pin) {
        String salt = randomHex(16);
        prefs(context).edit()
                .putString(KEY_MASTER_PIN_SALT, salt)
                .putString(KEY_MASTER_PIN_HASH, hashPin(salt, pin))
                .apply();
    }

    static boolean verifyMasterPin(Context context, String pin) {
        SharedPreferences preferences = prefs(context);
        String salt = preferences.getString(KEY_MASTER_PIN_SALT, "");
        String expected = preferences.getString(KEY_MASTER_PIN_HASH, "");
        if (salt.isEmpty() || expected.isEmpty() || pin == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = hashPin(salt, pin).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
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

    static void reconcileUsageTodayMs(Context context, String packageName, long observedUsageMs) {
        if (observedUsageMs <= 0L) {
            return;
        }
        SharedPreferences preferences = prefs(context);
        String key = usageKey(packageName);
        long current = preferences.getLong(key, 0L);
        if (observedUsageMs > current) {
            preferences.edit().putLong(key, observedUsageMs).apply();
        }
    }

    static boolean isOverLimit(Context context, String packageName) {
        long limitMs = dailyLimitMinutes(context, packageName) * 60_000L;
        return getUsageTodayMs(context, packageName) >= limitMs;
    }

    static boolean isTemporarilyUnlocked(Context context, String packageName) {
        return prefs(context).getLong(UNLOCK_PREFIX + packageName, 0L) > System.currentTimeMillis();
    }

    static void grantExtraTime(Context context, String packageName, int minutes) {
        long until = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
        prefs(context).edit().putLong(UNLOCK_PREFIX + packageName, until).apply();
    }

    static String createRequestCode(Context context, String packageName, int requestedMinutes) {
        SharedPreferences preferences = prefs(context);
        long now = System.currentTimeMillis();
        cleanExpiredApprovalCodes(preferences, packageName, now);
        int safeMinutes = Math.max(1, requestedMinutes);
        String requestCode;
        String approvalCode;
        Set<String> approvalCodes = pendingApprovalCodes(preferences, packageName);
        do {
            requestCode = String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
            approvalCode = approvalCodeForRequest(requestCode);
        } while (approvalCodes.contains(approvalCode));

        approvalCodes.add(approvalCode);
        preferences.edit()
                .putStringSet(pendingApprovalCodesKey(packageName), approvalCodes)
                .putLong(approvalExpiryKey(packageName, approvalCode), now + CODE_TTL_MS)
                .putInt(approvalMinutesKey(packageName, approvalCode), safeMinutes)
                .apply();
        return requestCode;
    }

    static int consumeApprovalCodeMinutesIfValid(Context context, String packageName, String enteredCode) {
        SharedPreferences preferences = prefs(context);
        long now = System.currentTimeMillis();
        cleanExpiredApprovalCodes(preferences, packageName, now);

        String normalized = normalizeCode(enteredCode);
        Set<String> approvalCodes = pendingApprovalCodes(preferences, packageName);
        if (approvalCodes.contains(normalized)) {
            long expiry = preferences.getLong(approvalExpiryKey(packageName, normalized), 0L);
            int minutes = preferences.getInt(approvalMinutesKey(packageName, normalized), -1);
            if (expiry > now && minutes > 0) {
                approvalCodes.remove(normalized);
                preferences.edit()
                        .putStringSet(pendingApprovalCodesKey(packageName), approvalCodes)
                        .remove(approvalExpiryKey(packageName, normalized))
                        .remove(approvalMinutesKey(packageName, normalized))
                        .apply();
                return minutes;
            }
        }

        int legacyMinutes = consumeLegacyApprovalCodeIfValid(preferences, packageName, normalized, now);
        if (legacyMinutes > 0) {
            return legacyMinutes;
        }
        return -1;
    }

    private static int consumeLegacyApprovalCodeIfValid(
            SharedPreferences preferences,
            String packageName,
            String enteredCode,
            long now
    ) {
        String codeKey = APPROVAL_CODE_PREFIX + packageName;
        String expiryKey = APPROVAL_CODE_EXPIRY_PREFIX + packageName;
        String minutesKey = APPROVAL_CODE_MINUTES_PREFIX + packageName;
        String expected = preferences.getString(codeKey, "");
        long expiry = preferences.getLong(expiryKey, 0L);
        int minutes = preferences.getInt(minutesKey, -1);
        if (expected.equals(enteredCode) && expiry > now && minutes > 0) {
            preferences.edit()
                    .remove(codeKey)
                    .remove(expiryKey)
                    .remove(minutesKey)
                    .apply();
            return minutes;
        }
        return -1;
    }

    private static void cleanExpiredApprovalCodes(
            SharedPreferences preferences,
            String packageName,
            long now
    ) {
        Set<String> approvalCodes = pendingApprovalCodes(preferences, packageName);
        if (approvalCodes.isEmpty()) {
            return;
        }

        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        Set<String> activeCodes = new HashSet<>(approvalCodes);
        for (String approvalCode : approvalCodes) {
            if (preferences.getLong(approvalExpiryKey(packageName, approvalCode), 0L) <= now) {
                activeCodes.remove(approvalCode);
                editor.remove(approvalExpiryKey(packageName, approvalCode));
                editor.remove(approvalMinutesKey(packageName, approvalCode));
                changed = true;
            }
        }
        if (changed) {
            editor.putStringSet(pendingApprovalCodesKey(packageName), activeCodes).apply();
        }
    }

    private static Set<String> pendingApprovalCodes(SharedPreferences preferences, String packageName) {
        Set<String> stored = preferences.getStringSet(
                pendingApprovalCodesKey(packageName),
                Collections.emptySet()
        );
        return new HashSet<>(stored);
    }

    static String approvalCodeForRequest(String requestCode) {
        String normalized = normalizeCode(requestCode);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            int digit = normalized.charAt(i) - '0';
            builder.append((digit + 5) % 10);
        }
        return builder.toString();
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char character = code.charAt(i);
            if (Character.isDigit(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private static String usageKey(String packageName) {
        return USAGE_PREFIX + today() + "_" + packageName;
    }

    private static String pendingApprovalCodesKey(String packageName) {
        return APPROVAL_CODES_PREFIX + packageName;
    }

    private static String approvalExpiryKey(String packageName, String approvalCode) {
        return APPROVAL_CODE_EXPIRY_PREFIX + packageName + "_" + approvalCode;
    }

    private static String approvalMinutesKey(String packageName, String approvalCode) {
        return APPROVAL_CODE_MINUTES_PREFIX + packageName + "_" + approvalCode;
    }

    private static String limitKey(String packageName) {
        return APP_LIMIT_PREFIX + packageName;
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static String hashPin(String salt, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
