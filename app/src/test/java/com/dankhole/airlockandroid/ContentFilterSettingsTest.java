package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public class ContentFilterSettingsTest {
    @Test
    public void newInstallationHasNoChoicesOrConsent() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
        assertTrue(ContentFilterSettings.enabledFilters(preferences, Collections.emptySet()).isEmpty());
        assertFalse(ContentFilterSettings.hasConsent(preferences));
    }

    @Test
    public void everyCatalogChoiceCanBeStoredAndDisabledIndependently() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        for (ContentFilter filter : ContentFilter.values()) {
            assertTrue(set(preferences, filter, true));
        }
        assertEquals(EnumSet.allOf(ContentFilter.class), ContentFilterSettings.configuredFilters(preferences));
        assertFalse(ContentFilterSettings.hasConsent(preferences));
        for (ContentFilter filter : ContentFilter.values()) {
            assertTrue(set(preferences, filter, false));
            assertFalse(ContentFilterSettings.configuredFilters(preferences).contains(filter));
        }
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
    }

    @Test
    public void unknownNoncanonicalAndNullIdsCannotBecomeFilters() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putStringSet(ContentFilterSettings.KEY_ENABLED_FILTERS,
                new HashSet<>(Arrays.asList("youtube_shorts", "instagram_explore", "YOUTUBE_SHORTS",
                        "facebook_reels ", "", "com.android.settings", null))).apply();
        assertEquals(Collections.singleton(ContentFilter.YOUTUBE_SHORTS),
                ContentFilterSettings.configuredFilters(preferences));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void malformedSetMembersAreIgnoredWithoutTypeCastFailures() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        Set damaged = new HashSet();
        damaged.add("youtube_shorts");
        damaged.add(123);
        damaged.add(true);
        preferences.edit().putStringSet(ContentFilterSettings.KEY_ENABLED_FILTERS, damaged).apply();
        assertEquals(Collections.singleton(ContentFilter.YOUTUBE_SHORTS),
                ContentFilterSettings.configuredFilters(preferences));
    }

    @Test
    public void wrongPreferenceTypesFailOff() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putString(ContentFilterSettings.KEY_ENABLED_FILTERS, "youtube_shorts")
                .putString(ContentFilterSettings.KEY_CONSENT_VERSION, "1").apply();
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
        assertFalse(ContentFilterSettings.hasConsent(preferences));
        preferences.edit().putBoolean(ContentFilterSettings.KEY_ENABLED_FILTERS, true)
                .putBoolean(ContentFilterSettings.KEY_CONSENT_VERSION, true).apply();
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
        assertFalse(ContentFilterSettings.hasConsent(preferences));
    }

    @Test
    public void androidStyleWrongTypeExceptionsFailOff() {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                    throw new ClassCastException("malformed preference");
                });
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
        assertFalse(ContentFilterSettings.hasConsent(preferences));
    }

    @Test
    public void readsAndWritesDoNotShareMutableCollections() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        Set<String> source = new HashSet<>(Collections.singleton("youtube_shorts"));
        preferences.edit().putStringSet(ContentFilterSettings.KEY_ENABLED_FILTERS, source).apply();
        source.clear();
        Set<ContentFilter> configured = ContentFilterSettings.configuredFilters(preferences);
        configured.clear();
        configured.add(ContentFilter.FACEBOOK_REELS);
        Set<ContentFilter> enabled = ContentFilterSettings.enabledFilters(preferences, Collections.emptySet());
        enabled.clear();
        assertEquals(Collections.singleton(ContentFilter.YOUTUBE_SHORTS),
                ContentFilterSettings.configuredFilters(preferences));
    }

    @Test
    public void criticalPackageFilteringPreservesConfiguredChoicesForStatus() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        for (ContentFilter filter : ContentFilter.values()) {
            set(preferences, filter, true);
        }
        Set<String> critical = new HashSet<>(Arrays.asList("com.instagram.android", "com.snapchat.android"));
        assertEquals(EnumSet.of(ContentFilter.YOUTUBE_SHORTS, ContentFilter.FACEBOOK_REELS),
                ContentFilterSettings.enabledFilters(preferences, critical));
        assertEquals(EnumSet.allOf(ContentFilter.class), ContentFilterSettings.configuredFilters(preferences));
        assertEquals(2, critical.size());
    }

    @Test
    public void criticalPackageCannotBeEnabledButCanAlwaysBeDisabled() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        Set<String> critical = Collections.singleton(ContentFilter.SNAPCHAT_SPOTLIGHT.packageName());
        assertFalse(ContentFilterSettings.setEnabled(preferences, ContentFilter.SNAPCHAT_SPOTLIGHT,
                true, critical));
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
        set(preferences, ContentFilter.SNAPCHAT_SPOTLIGHT, true);
        assertTrue(ContentFilterSettings.setEnabled(preferences, ContentFilter.SNAPCHAT_SPOTLIGHT,
                false, critical));
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
    }

    @Test
    public void unavailableSafetySnapshotNeverAuthorizesEnabling() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertFalse(ContentFilterSettings.setEnabled(preferences, ContentFilter.YOUTUBE_SHORTS, true, null));
        set(preferences, ContentFilter.YOUTUBE_SHORTS, true);
        assertTrue(ContentFilterSettings.enabledFilters(preferences, null).isEmpty());
        assertTrue(ContentFilterSettings.setEnabled(preferences, ContentFilter.YOUTUBE_SHORTS, false, null));
    }

    @Test
    public void nullFilterIsRejectedWithoutErasingOtherSettings() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        set(preferences, ContentFilter.YOUTUBE_SHORTS, true);
        assertFalse(set(preferences, null, true));
        assertFalse(set(preferences, null, false));
        assertEquals(Collections.singleton(ContentFilter.YOUTUBE_SHORTS),
                ContentFilterSettings.configuredFilters(preferences));
    }

    @Test
    public void ordinaryMutationSanitizesStoredIdsWithoutGrantingConsent() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit().putStringSet(ContentFilterSettings.KEY_ENABLED_FILTERS,
                new HashSet<>(Arrays.asList("youtube_shorts", "unknown"))).apply();
        set(preferences, ContentFilter.INSTAGRAM_REELS, true);
        assertEquals(new HashSet<>(Arrays.asList("youtube_shorts", "instagram_reels")),
                preferences.getStringSet(ContentFilterSettings.KEY_ENABLED_FILTERS, Collections.emptySet()));
        assertFalse(ContentFilterSettings.hasConsent(preferences));
    }

    @Test
    public void onlyExactCurrentVersionCountsAsConsent() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        for (int invalid : new int[] {-1, 0, ContentFilterSettings.CONSENT_VERSION + 1, Integer.MAX_VALUE}) {
            preferences.edit().putInt(ContentFilterSettings.KEY_CONSENT_VERSION, invalid).apply();
            assertFalse(ContentFilterSettings.hasConsent(preferences));
        }
        preferences.edit().putInt(ContentFilterSettings.KEY_CONSENT_VERSION,
                ContentFilterSettings.CONSENT_VERSION).apply();
        assertTrue(ContentFilterSettings.hasConsent(preferences));
    }

    @Test
    public void consentNeverEnablesAChoiceAndRevocationIsImmediate() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        ContentFilterSettings.setConsent(preferences, true);
        assertTrue(ContentFilterSettings.hasConsent(preferences));
        assertTrue(ContentFilterSettings.configuredFilters(preferences).isEmpty());
        set(preferences, ContentFilter.YOUTUBE_SHORTS, true);
        ContentFilterSettings.setConsent(preferences, false);
        assertFalse(ContentFilterSettings.hasConsent(preferences));
        assertFalse(preferences.contains(ContentFilterSettings.KEY_CONSENT_VERSION));
        // Configuration survives for recovery UI; it never substitutes for consent.
        assertEquals(Collections.singleton(ContentFilter.YOUTUBE_SHORTS),
                ContentFilterSettings.configuredFilters(preferences));
        ContentFilterSettings.setConsent(preferences, true);
        assertTrue(ContentFilterSettings.hasConsent(preferences));
    }

    @Test
    public void onlyAllowlistedIdsAndVersionPersist() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        set(preferences, ContentFilter.YOUTUBE_SHORTS, true);
        ContentFilterSettings.setConsent(preferences, true);
        assertEquals(new HashSet<>(Arrays.asList(ContentFilterSettings.KEY_ENABLED_FILTERS,
                ContentFilterSettings.KEY_CONSENT_VERSION)), preferences.getAll().keySet());
    }

    private static boolean set(TestSharedPreferences preferences, ContentFilter filter, boolean enabled) {
        return ContentFilterSettings.setEnabled(preferences, filter, enabled, Collections.emptySet());
    }
}
