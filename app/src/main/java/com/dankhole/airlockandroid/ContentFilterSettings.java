package com.dankhole.airlockandroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Local configuration only. Consent and runtime enforcement gates remain separate. */
final class ContentFilterSettings {
    static final String KEY_ENABLED_FILTERS = "content_filter_enabled_ids";
    static final String KEY_CONSENT_VERSION = "content_filter_consent_version";
    static final int CONSENT_VERSION = 1;

    private ContentFilterSettings() {
    }

    static Set<ContentFilter> configuredFilters(Context context) {
        return configuredFilters(Preferences.prefs(context));
    }

    /** Returns a fresh allowlisted set, including choices currently unavailable for safety. */
    static synchronized Set<ContentFilter> configuredFilters(SharedPreferences preferences) {
        Set<ContentFilter> result = EnumSet.noneOf(ContentFilter.class);
        try {
            Set<?> stored = preferences.getStringSet(KEY_ENABLED_FILTERS, Collections.emptySet());
            if (stored != null) {
                for (Object id : stored) {
                    ContentFilter filter = id instanceof String
                            ? ContentFilter.fromId((String) id) : null;
                    if (filter != null) {
                        result.add(filter);
                    }
                }
            }
        } catch (ClassCastException ignored) {
            // A damaged or older value with the wrong type must never enable inspection.
        }
        return result;
    }

    /** Safe configured choices, not proof of consent or service readiness. */
    static Set<ContentFilter> enabledFilters(Context context) {
        Set<ContentFilter> filters = configuredFilters(context);
        filters.removeIf(filter -> !CriticalApps.canFilterFromSnapshot(filter.packageName()));
        if (!CriticalApps.hasReliableSnapshot()) {
            filters.clear();
        }
        return filters;
    }

    // Explicit safety input keeps persistence tests independent of Android package discovery.
    static Set<ContentFilter> enabledFilters(
            SharedPreferences preferences,
            Set<String> criticalPackages
    ) {
        Set<ContentFilter> filters = configuredFilters(preferences);
        if (criticalPackages == null) {
            filters.clear();
        } else {
            filters.removeIf(filter -> criticalPackages.contains(filter.packageName()));
        }
        return filters;
    }

    static boolean setEnabled(Context context, ContentFilter filter, boolean enabled) {
        if (filter == null || (enabled && !CriticalApps.canFilterFromSnapshot(filter.packageName()))) {
            return false;
        }
        return setEnabled(Preferences.prefs(context), filter, enabled, Collections.emptySet());
    }

    static synchronized boolean setEnabled(
            SharedPreferences preferences,
            ContentFilter filter,
            boolean enabled,
            Set<String> criticalPackages
    ) {
        if (filter == null || (enabled && (criticalPackages == null
                || criticalPackages.contains(filter.packageName())))) {
            return false;
        }
        Set<ContentFilter> filters = configuredFilters(preferences);
        if (enabled) {
            filters.add(filter);
        } else {
            filters.remove(filter);
        }
        Set<String> ids = new HashSet<>();
        for (ContentFilter configured : filters) {
            ids.add(configured.id());
        }
        preferences.edit().putStringSet(KEY_ENABLED_FILTERS, ids).apply();
        return true;
    }

    static boolean hasConsent(Context context) {
        return hasConsent(Preferences.prefs(context));
    }

    static boolean hasConsent(SharedPreferences preferences) {
        try {
            return preferences.getInt(KEY_CONSENT_VERSION, 0) == CONSENT_VERSION;
        } catch (ClassCastException ignored) {
            return false;
        }
    }

    static void setConsent(Context context, boolean consent) {
        setConsent(Preferences.prefs(context), consent);
    }

    static void setConsent(SharedPreferences preferences, boolean consent) {
        SharedPreferences.Editor editor = preferences.edit();
        if (consent) {
            editor.putInt(KEY_CONSENT_VERSION, CONSENT_VERSION);
        } else {
            editor.remove(KEY_CONSENT_VERSION);
        }
        editor.apply();
    }
}
