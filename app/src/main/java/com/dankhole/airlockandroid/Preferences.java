package com.dankhole.airlockandroid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Preferences {
    static final String FILE = "airlock_prefs";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_SELECTED_PACKAGES = "selected_packages";
    static final String KEY_DAILY_LIMIT_MINUTES = "daily_limit_minutes";
    static final String KEY_ACCOUNTABILITY_NUMBER = "accountability_number";
    static final String KEY_MASTER_PIN_HASH = "master_pin_hash";
    static final String KEY_MASTER_PIN_SALT = "master_pin_salt";

    private static final String KEY_EMERGENCY_CODE_HASHES = "emergency_code_hashes";
    private static final String KEY_EMERGENCY_CODE_SALT = "emergency_code_salt";
    private static final String KEY_EMERGENCY_PAUSE_UNTIL = "emergency_pause_until";
    private static final String APPROVAL_CODES_PREFIX = "approval_codes_";
    private static final String APPROVAL_CODE_PREFIX = "approval_code_";
    private static final String APPROVAL_CODE_EXPIRY_PREFIX = "approval_code_expiry_";
    private static final String APPROVAL_CODE_MINUTES_PREFIX = "approval_code_minutes_";
    private static final String APP_LIMIT_PREFIX = "limit_minutes_";
    private static final String UNLOCK_PREFIX = "unlock_until_";
    private static final String USAGE_PREFIX = "usage_";
    private static final String KEY_LAST_USAGE_PRUNE_DAY = "last_usage_prune_day";
    private static final int USAGE_RETENTION_DAYS = 7;
    private static final int EMERGENCY_CODE_COUNT = 5;
    private static final int EMERGENCY_CODE_BOUND = 100_000_000;
    private static final int EMERGENCY_CODE_LENGTH = 8;
    private static final long EMERGENCY_PAUSE_MS = 24 * 60 * 60 * 1000L;
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

    @SuppressLint("ApplySharedPref")
    static List<String> replaceEmergencyCodes(Context context) {
        String salt = randomHex(16);
        Set<String> codes = new HashSet<>();
        while (codes.size() < EMERGENCY_CODE_COUNT) {
            codes.add(String.format(Locale.US, "%08d", RANDOM.nextInt(EMERGENCY_CODE_BOUND)));
        }

        List<String> sortedCodes = new ArrayList<>(codes);
        Collections.sort(sortedCodes);
        Set<String> hashes = new HashSet<>();
        for (String code : sortedCodes) {
            hashes.add(hashPin(salt, code));
        }
        boolean saved = prefs(context).edit()
                .putString(KEY_EMERGENCY_CODE_SALT, salt)
                .putStringSet(KEY_EMERGENCY_CODE_HASHES, hashes)
                .commit();
        return saved ? sortedCodes : Collections.emptyList();
    }

    static int emergencyCodesRemaining(Context context) {
        return prefs(context).getStringSet(
                KEY_EMERGENCY_CODE_HASHES,
                Collections.emptySet()
        ).size();
    }

    @SuppressLint("ApplySharedPref")
    static boolean consumeEmergencyCode(Context context, String enteredCode) {
        String normalized = normalizeCode(enteredCode);
        if (normalized.length() != EMERGENCY_CODE_LENGTH || isEmergencyPauseActive(context)) {
            return false;
        }

        SharedPreferences preferences = prefs(context);
        String salt = preferences.getString(KEY_EMERGENCY_CODE_SALT, "");
        Set<String> storedHashes = new HashSet<>(preferences.getStringSet(
                KEY_EMERGENCY_CODE_HASHES,
                Collections.emptySet()
        ));
        if (salt.isEmpty() || storedHashes.isEmpty()) {
            return false;
        }

        byte[] enteredHash = hashPin(salt, normalized).getBytes(StandardCharsets.UTF_8);
        String matchedHash = null;
        for (String storedHash : storedHashes) {
            if (MessageDigest.isEqual(
                    storedHash.getBytes(StandardCharsets.UTF_8),
                    enteredHash
            )) {
                matchedHash = storedHash;
                break;
            }
        }
        if (matchedHash == null) {
            return false;
        }

        storedHashes.remove(matchedHash);
        return prefs(context).edit()
                .putStringSet(KEY_EMERGENCY_CODE_HASHES, storedHashes)
                .putLong(KEY_EMERGENCY_PAUSE_UNTIL, System.currentTimeMillis() + EMERGENCY_PAUSE_MS)
                .commit();
    }

    static long emergencyPauseUntilMs(Context context) {
        return prefs(context).getLong(KEY_EMERGENCY_PAUSE_UNTIL, 0L);
    }

    static boolean isEmergencyPauseActive(Context context) {
        return emergencyPauseUntilMs(context) > System.currentTimeMillis();
    }

    static long getUsageTodayMs(Context context, String packageName) {
        return getUsageForDayMs(context, currentUsageDay(), packageName);
    }

    static long getUsageForDayMs(Context context, String day, String packageName) {
        if (day == null || day.isEmpty() || packageName == null || packageName.isEmpty()) {
            return 0L;
        }
        return prefs(context).getLong(usageKey(day, packageName), 0L);
    }

    static void saveUsageForDayMs(Context context, String day, Map<String, Long> usageByPackage) {
        if (day == null || day.isEmpty() || usageByPackage.isEmpty()) {
            return;
        }
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (Map.Entry<String, Long> entry : usageByPackage.entrySet()) {
            String packageName = entry.getKey();
            Long value = entry.getValue();
            if (packageName == null || packageName.isEmpty() || value == null || value <= 0L) {
                continue;
            }
            String key = usageKey(day, packageName);
            long current = preferences.getLong(key, 0L);
            if (value > current) {
                editor.putLong(key, value);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    static void reconcileUsageTodayMs(Context context, Map<String, Long> observedUsageByPackage) {
        saveUsageForDayMs(context, currentUsageDay(), observedUsageByPackage);
    }

    static void pruneOldUsageIfNeeded(Context context) {
        SharedPreferences preferences = prefs(context);
        String today = currentUsageDay();
        if (today.equals(preferences.getString(KEY_LAST_USAGE_PRUNE_DAY, ""))) {
            return;
        }

        Set<String> retainedDays = new HashSet<>();
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd", Locale.US);
        for (int dayOffset = 0; dayOffset < USAGE_RETENTION_DAYS; dayOffset++) {
            retainedDays.add(formatter.format(calendar.getTime()));
            calendar.add(Calendar.DAY_OF_YEAR, -1);
        }

        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_LAST_USAGE_PRUNE_DAY, today);
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(USAGE_PREFIX)
                    || key.length() < USAGE_PREFIX.length() + 8) {
                continue;
            }
            String usageDay = key.substring(USAGE_PREFIX.length(), USAGE_PREFIX.length() + 8);
            if (!retainedDays.contains(usageDay)) {
                editor.remove(key);
            }
        }
        editor.apply();
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

    private static String usageKey(String day, String packageName) {
        return USAGE_PREFIX + day + "_" + packageName;
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

    static String currentUsageDay() {
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
