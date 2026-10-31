package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ContentFilterAccessTest {
    @Test
    public void statusKeepsOptionalAccessSeparateFromOrdinaryDutyAndEmergencyPause() {
        assertEquals(ContentFilterAccess.State.DISABLED, ContentFilterAccess.resolveState(
                false, true, false, false, false, false, false));
        assertEquals(ContentFilterAccess.State.DUTY_OFF, ContentFilterAccess.resolveState(
                true, false, false, false, false, false, false));
        assertEquals(ContentFilterAccess.State.PAUSED, ContentFilterAccess.resolveState(
                true, true, true, false, false, false, false));
        assertEquals(ContentFilterAccess.State.CONSENT_REQUIRED, ContentFilterAccess.resolveState(
                true, true, false, false, true, true, true));
        assertEquals(ContentFilterAccess.State.ACCESS_REQUIRED, ContentFilterAccess.resolveState(
                true, true, false, true, false, true, true));
        assertEquals(ContentFilterAccess.State.PREREQUISITES_REQUIRED, ContentFilterAccess.resolveState(
                true, true, false, true, true, false, true));
        assertEquals(ContentFilterAccess.State.UNAVAILABLE, ContentFilterAccess.resolveState(
                true, true, false, true, true, true, false));
        assertEquals(ContentFilterAccess.State.READY, ContentFilterAccess.resolveState(
                true, true, false, true, true, true, true));
    }

    @Test
    public void unsupportedPlatformNeverReportsAnOptionalFilterReadyOrNeedingOnlyAccess() {
        assertEquals(ContentFilterAccess.State.UNSUPPORTED, ContentFilterAccess.resolveState(
                true, true, false, true, ContentFilterAccess.AndroidAccessState.ENABLED,
                true, true, false));
        assertEquals(ContentFilterAccess.State.DISABLED, ContentFilterAccess.resolveState(
                false, true, false, true, ContentFilterAccess.AndroidAccessState.ENABLED,
                true, true, false));
        assertTrue(ContentFilterAccess.needsAttention(ContentFilterAccess.State.UNSUPPORTED));
    }

    @Test
    public void missingFailedStaleAndFutureAccessProbesRemainUnknownInsteadOfDisabled() {
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.accessForSnapshot(null, 1000, 1000, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                        1000, 1000, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.ENABLED,
                        1000, 31001, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.DISABLED,
                        1000, 31001, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.ENABLED,
                        1001, 1000, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.ENABLED,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.ENABLED,
                        1000, 31000, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.DISABLED,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.DISABLED,
                        1000, 1000, false));
        assertEquals(ContentFilterAccess.State.UNAVAILABLE, ContentFilterAccess.resolveState(
                true, true, false, true, ContentFilterAccess.AndroidAccessState.UNKNOWN,
                true, true, true));
    }

    @Test
    public void accessRenewalGraceIsBoundedAndCannotTurnAFailedProbeIntoEnabled() {
        assertEquals(ContentFilterAccess.AndroidAccessState.ENABLED,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.ENABLED,
                        1000, 32500, true));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.ENABLED,
                        1000, 32501, true));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.accessForSnapshot(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                        1000, 1000, true));
    }

    @Test
    public void criticalSavedSelectionWarnsWithoutDisablingAnIndependentSafeSubset() {
        Set<ContentFilter> configured = EnumSet.of(ContentFilter.YOUTUBE_SHORTS,
                ContentFilter.SNAPCHAT_SPOTLIGHT);
        Set<ContentFilter> safe = EnumSet.of(ContentFilter.YOUTUBE_SHORTS);
        assertTrue(ContentFilterAccess.executionAllowed(
                ContentFilterAccess.AndroidAccessState.ENABLED, true, safe));
        assertFalse(ContentFilterAccess.configurationComplete(configured, safe));
        assertEquals(ContentFilterAccess.State.PARTIAL, ContentFilterAccess.configurationState(
                ContentFilterAccess.State.READY, configured, safe));
        assertEquals(ContentFilterAccess.State.UNAVAILABLE, ContentFilterAccess.configurationState(
                ContentFilterAccess.State.UNAVAILABLE, configured, safe));
        assertEquals(ContentFilterAccess.State.PREREQUISITES_REQUIRED,
                ContentFilterAccess.configurationState(
                        ContentFilterAccess.State.PREREQUISITES_REQUIRED, configured, safe));
        assertTrue(ContentFilterAccess.needsAttention(ContentFilterAccess.configurationState(
                ContentFilterAccess.State.READY, configured, safe)));
        assertFalse(ContentFilterAccess.executionAllowed(
                ContentFilterAccess.AndroidAccessState.ENABLED, true, Collections.emptySet()));
        assertFalse(ContentFilterAccess.executionAllowed(
                ContentFilterAccess.AndroidAccessState.UNKNOWN, true, safe));
        assertFalse(ContentFilterAccess.executionAllowed(
                ContentFilterAccess.AndroidAccessState.DISABLED, true, safe));
        assertFalse(ContentFilterAccess.executionAllowed(
                ContentFilterAccess.AndroidAccessState.ENABLED, false, safe));
        assertTrue(ContentFilterAccess.configurationComplete(safe, safe));
        assertEquals(ContentFilterAccess.State.READY, ContentFilterAccess.configurationState(
                ContentFilterAccess.State.READY, safe, safe));
    }

    @Test
    public void configuredAndroidSettingDoesNotDependOnSuppressedOperationalServiceLists() {
        assertEquals(ContentFilterAccess.AndroidAccessState.ENABLED,
                ContentFilterAccess.configuredAndroidAccess("1", true, true));
        assertEquals(ContentFilterAccess.AndroidAccessState.ENABLED,
                ContentFilterAccess.configuredAndroidAccess("1", true, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.DISABLED,
                ContentFilterAccess.configuredAndroidAccess("0", true, true));
        assertEquals(ContentFilterAccess.AndroidAccessState.DISABLED,
                ContentFilterAccess.configuredAndroidAccess("1", false, true));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.configuredAndroidAccess("1", false, false));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.configuredAndroidAccess(null, true, true));
        assertEquals(ContentFilterAccess.AndroidAccessState.UNKNOWN,
                ContentFilterAccess.configuredAndroidAccess("unexpected", true, true));
    }

    @Test
    public void androidEnablementAloneIsNeverALiveService() {
        Object owner = new Object();
        ContentFilterAccess.connected(owner);
        try {
            assertFalse(ContentFilterAccess.serviceAvailable(1000));
            ContentFilterAccess.workFinished(owner, true);
            assertTrue(ContentFilterAccess.serviceAvailable(1000));
            ContentFilterAccess.workFinished(owner, false);
            assertFalse(ContentFilterAccess.serviceAvailable(1000));
        } finally {
            ContentFilterAccess.disconnected(owner);
        }
    }

    @Test
    public void workerDeadlineReportsUnavailableWithoutWaitingForAMainHandlerWatchdog() {
        Object owner = new Object();
        ContentFilterAccess.connected(owner);
        try {
            ContentFilterAccess.workFinished(owner, true);
            ContentFilterAccess.workStarted(owner, 1000);
            assertTrue(ContentFilterAccess.serviceAvailable(2500));
            assertFalse(ContentFilterAccess.serviceAvailable(2501));
            assertFalse(ContentFilterAccess.serviceAvailable(999));
        } finally {
            ContentFilterAccess.disconnected(owner);
        }
    }

    @Test
    public void oldOwnerCompletionAndTeardownCannotHealOrDisconnectANewerService() {
        Object oldOwner = new Object();
        Object currentOwner = new Object();
        ContentFilterAccess.connected(oldOwner);
        ContentFilterAccess.connected(currentOwner);
        try {
            ContentFilterAccess.workFinished(oldOwner, true);
            assertFalse(ContentFilterAccess.serviceAvailable(1000));
            ContentFilterAccess.workFinished(currentOwner, true);
            ContentFilterAccess.disconnected(oldOwner);
            assertTrue(ContentFilterAccess.serviceAvailable(1000));
        } finally {
            ContentFilterAccess.disconnected(currentOwner);
        }
        assertFalse(ContentFilterAccess.serviceAvailable(1000));
    }

    @Test
    public void serviceReconnectCannotReplaceAStalledWorkerOrQueueAnotherTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Object oldOwner = new Object();
        Object newOwner = new Object();
        ContentFilterAccess.connected(oldOwner);
        try {
            assertTrue(ContentFilterAccess.WORKER.tryExecute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            ContentFilterAccess.disconnected(oldOwner);
            ContentFilterAccess.connected(newOwner);
            assertFalse(ContentFilterAccess.WORKER.tryExecute(() -> {
                throw new AssertionError("Must never be queued or start a replacement worker");
            }));
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            ContentFilterAccess.disconnected(oldOwner);
            ContentFilterAccess.disconnected(newOwner);
        }
    }

    @Test
    public void warningsExistOnlyForConfiguredProtectionNeedingAttention() {
        assertFalse(ContentFilterAccess.needsAttention(ContentFilterAccess.State.DISABLED));
        assertFalse(ContentFilterAccess.needsAttention(ContentFilterAccess.State.DUTY_OFF));
        assertFalse(ContentFilterAccess.needsAttention(ContentFilterAccess.State.PAUSED));
        assertFalse(ContentFilterAccess.needsAttention(ContentFilterAccess.State.READY));
        assertTrue(ContentFilterAccess.needsAttention(ContentFilterAccess.State.CONSENT_REQUIRED));
        assertTrue(ContentFilterAccess.needsAttention(ContentFilterAccess.State.ACCESS_REQUIRED));
        assertTrue(ContentFilterAccess.needsAttention(ContentFilterAccess.State.PREREQUISITES_REQUIRED));
        assertTrue(ContentFilterAccess.needsAttention(ContentFilterAccess.State.UNAVAILABLE));
        assertTrue(ContentFilterAccess.needsAttention(ContentFilterAccess.State.PARTIAL));
    }

    @Test
    public void preferenceChangesInvalidateAuthorizationButUsageWritesDoNotTriggerWork() {
        assertTrue(ContentFilterAccess.affectsConfiguration("content_filter_consent_version"));
        assertTrue(ContentFilterAccess.affectsConfiguration("content_filter_enabled_ids"));
        assertTrue(ContentFilterAccess.affectsConfiguration("enabled"));
        assertTrue(ContentFilterAccess.affectsConfiguration("selected_packages"));
        assertTrue(ContentFilterAccess.affectsConfiguration("master_pin_hash"));
        assertTrue(ContentFilterAccess.affectsConfiguration("master_approval_table_v1"));
        assertTrue(ContentFilterAccess.affectsConfiguration("accountability_number"));
        assertTrue(ContentFilterAccess.affectsConfiguration("emergency_pause_until"));
        assertFalse(ContentFilterAccess.affectsConfiguration("usage_2026-09-14_example"));
        assertFalse(ContentFilterAccess.affectsConfiguration("monitoring_healthy_at"));
        assertFalse(ContentFilterAccess.affectsConfiguration("unlock_until_example"));
    }
}
