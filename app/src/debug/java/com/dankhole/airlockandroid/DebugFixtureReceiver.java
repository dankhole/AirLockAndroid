package com.dankhole.airlockandroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Debug-only deterministic state setup for emulator smoke tests. */
public final class DebugFixtureReceiver extends BroadcastReceiver {
    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_TARGET_PACKAGE = "target_package";
    static final String EXTRA_LIMIT_MINUTES = "limit_minutes";
    static final String EXTRA_USED_MINUTES = "used_minutes";
    static final String EXTRA_MONITORING = "monitoring";
    static final String EXTRA_REQUEST_MINUTES = "request_minutes";
    static final String EXTRA_EMERGENCY_CODES = "emergency_codes";
    static final String EXTRA_SANITY_TOKEN = "sanity_token";

    private static final String COMMAND_RESET = "reset";
    private static final String COMMAND_SEED = "seed";
    private static final String COMMAND_FORCE_FOREGROUND_SANITY = "force_foreground_sanity";
    private static final String DEFAULT_TARGET_PACKAGE = "com.google.android.youtube";
    private static final String FIXTURE_PHONE_NUMBER = "5555551212";
    private static final String FIXTURE_MASTER_PIN = "1234";

    @Override
    public void onReceive(Context context, Intent intent) {
        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (COMMAND_RESET.equals(command)) {
            reset(context);
            succeed("reset");
            return;
        }
        if (COMMAND_SEED.equals(command)) {
            seed(context, intent);
            return;
        }
        if (COMMAND_FORCE_FOREGROUND_SANITY.equals(command)) {
            forceForegroundSanityCheck(context, intent);
            return;
        }
        fail("Unknown fixture command: " + command);
    }

    @SuppressLint("ApplySharedPref")
    private void reset(Context context) {
        context.stopService(new Intent(context, MonitoringService.class));
        Preferences.prefs(context).edit().clear().commit();
    }

    private void seed(Context context, Intent intent) {
        reset(context);
        String targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            targetPackage = DEFAULT_TARGET_PACKAGE;
        }
        int limitMinutes = Math.max(1, intent.getIntExtra(EXTRA_LIMIT_MINUTES, 1));
        int usedMinutes = Math.max(
                limitMinutes + 1,
                intent.getIntExtra(EXTRA_USED_MINUTES, limitMinutes + 1)
        );
        boolean monitoring = intent.getBooleanExtra(EXTRA_MONITORING, false);
        int requestMinutes = Math.max(0, intent.getIntExtra(EXTRA_REQUEST_MINUTES, 0));
        boolean createEmergencyCodes = intent.getBooleanExtra(EXTRA_EMERGENCY_CODES, false);

        SharedPreferences preferences = Preferences.prefs(context);
        boolean phoneSaved = preferences.edit()
                .putString(Preferences.KEY_ACCOUNTABILITY_NUMBER, FIXTURE_PHONE_NUMBER)
                .commit();
        boolean pinSaved = Preferences.setMasterPin(context, FIXTURE_MASTER_PIN);
        Set<String> packages = Collections.singleton(targetPackage);
        Preferences.saveLimitForPackages(context, packages, limitMinutes);
        Map<String, Long> usage = new HashMap<>();
        usage.put(targetPackage, usedMinutes * 60_000L);
        Preferences.saveUsageForDayMs(context, Preferences.currentUsageDay(), usage);
        boolean monitoringSaved = Preferences.setMonitoringRequested(context, monitoring);
        if (!phoneSaved
                || !pinSaved
                || !monitoringSaved
                || !Preferences.hasLimitedApps(context)) {
            fail("Fixture state could not be saved for " + targetPackage);
            return;
        }
        StringBuilder result = new StringBuilder("seeded:").append(targetPackage);
        if (requestMinutes > 0) {
            String requestCode = Preferences.createRequestCode(
                    context,
                    targetPackage,
                    requestMinutes
            );
            if (requestCode.isEmpty()) {
                fail("Fixture approval request could not be saved");
                return;
            }
            result.append(";request=").append(requestCode)
                    .append(";approval=")
                    .append(Preferences.configuredApprovalCodeForRequest(context, requestCode));
        }
        if (createEmergencyCodes) {
            List<String> codes = Preferences.replaceEmergencyCodes(context);
            if (codes.size() != Preferences.EMERGENCY_CODE_COUNT) {
                fail("Fixture emergency codes could not be saved");
                return;
            }
            result.append(";emergency=").append(String.join(",", codes));
        }
        succeed(result.toString());
    }

    private void forceForegroundSanityCheck(Context context, Intent intent) {
        if (!Preferences.isMonitoringRequested(context)) {
            fail("Monitoring must be requested before forcing a foreground sanity check");
            return;
        }
        Intent serviceIntent = new Intent(context, MonitoringService.class);
        serviceIntent.setAction(MonitoringService.ACTION_DEBUG_FORCE_FOREGROUND_SANITY);
        serviceIntent.putExtra(
                MonitoringService.EXTRA_DEBUG_SANITY_TOKEN,
                intent.getStringExtra(EXTRA_SANITY_TOKEN)
        );
        try {
            context.startService(serviceIntent);
            succeed("foreground sanity requested");
        } catch (RuntimeException exception) {
            fail("Foreground sanity request failed: " + exception.getClass().getSimpleName());
        }
    }

    private void succeed(String message) {
        setResultCode(Activity.RESULT_OK);
        setResultData(message);
    }

    private void fail(String message) {
        setResultCode(Activity.RESULT_CANCELED);
        setResultData(message);
    }
}
