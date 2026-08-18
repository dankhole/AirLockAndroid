package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.usage.UsageEvents;
import android.os.Build;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public class ForegroundEventPolicyTest {
    private static final String BLOCKED_APP = "example.blocked";
    private static final String OTHER_APP = "example.other";
    private static final String SYSTEM_UI = "com.android.systemui";

    @Test
    public void resumedAliasIsRecognizedAcrossSupportedVersions() {
        assertTrue(ForegroundEventPolicy.isForegroundEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                Build.VERSION_CODES.P
        ));
        assertTrue(ForegroundEventPolicy.isForegroundEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                Build.VERSION_CODES.Q
        ));
    }

    @Test
    public void stoppedEventsAreUsedOnlyWhereAndroidSupportsThem() {
        assertFalse(ForegroundEventPolicy.isBackgroundEvent(
                UsageEvents.Event.ACTIVITY_STOPPED,
                Build.VERSION_CODES.P
        ));
        assertTrue(ForegroundEventPolicy.isBackgroundEvent(
                UsageEvents.Event.ACTIVITY_STOPPED,
                Build.VERSION_CODES.Q
        ));
    }

    @Test
    public void blockedAppLifecycleEventMarksOverlayInterrupted() {
        assertTrue(ForegroundEventPolicy.isOverlayInterruptionEvent(
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                BLOCKED_APP,
                Collections.singleton(SYSTEM_UI),
                Build.VERSION_CODES.Q
        ));
    }

    @Test
    public void transientForegroundSurfaceMarksOverlayInterrupted() {
        Set<String> transientPackages = Collections.singleton(SYSTEM_UI);

        assertTrue(ForegroundEventPolicy.isOverlayInterruptionEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                SYSTEM_UI,
                BLOCKED_APP,
                transientPackages,
                Build.VERSION_CODES.Q
        ));
    }

    @Test
    public void unrelatedForegroundAppDoesNotMarkOverlayInterrupted() {
        assertFalse(ForegroundEventPolicy.isOverlayInterruptionEvent(
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                "example.other",
                BLOCKED_APP,
                Collections.singleton(SYSTEM_UI),
                Build.VERSION_CODES.Q
        ));
    }

    @Test
    public void backgroundingCurrentAppCreatesKnownTransitionState() {
        ForegroundEventPolicy.CandidateState state = ForegroundEventPolicy.applyLifecycleEvent(
                ForegroundEventPolicy.knownCandidate(BLOCKED_APP),
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                Build.VERSION_CODES.Q
        );

        assertTrue(state.known);
        assertNull(state.packageName);
    }

    @Test
    public void delayedForegroundEventCompletesTransitionWithoutKeepingStaleApp() {
        ForegroundEventPolicy.CandidateState state = ForegroundEventPolicy.applyLifecycleEvent(
                ForegroundEventPolicy.knownCandidate(BLOCKED_APP),
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                Build.VERSION_CODES.Q
        );

        assertNull(state.packageName);

        state = ForegroundEventPolicy.applyLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                OTHER_APP,
                Build.VERSION_CODES.Q
        );

        assertEquals(OTHER_APP, state.packageName);
    }

    @Test
    public void canceledRecentsReturnsOnlyAfterBlockedAppActuallyResumes() {
        ForegroundEventPolicy.CandidateState state = ForegroundEventPolicy.applyLifecycleEvent(
                ForegroundEventPolicy.knownCandidate(BLOCKED_APP),
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                Build.VERSION_CODES.Q
        );
        state = ForegroundEventPolicy.applyLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                BLOCKED_APP,
                Build.VERSION_CODES.Q
        );

        assertTrue(state.known);
        assertEquals(BLOCKED_APP, state.packageName);
    }

    @Test
    public void exitingTransientSurfaceDoesNotGuessBlockedAppReturned() {
        ForegroundEventPolicy.CandidateState state = ForegroundEventPolicy.applyLifecycleEvent(
                ForegroundEventPolicy.knownCandidate(SYSTEM_UI),
                UsageEvents.Event.ACTIVITY_STOPPED,
                SYSTEM_UI,
                Build.VERSION_CODES.Q
        );

        assertTrue(state.known);
        assertNull(state.packageName);
    }

    @Test
    public void unrelatedBackgroundEventDoesNotClearCurrentCandidate() {
        ForegroundEventPolicy.CandidateState state = ForegroundEventPolicy.applyLifecycleEvent(
                ForegroundEventPolicy.knownCandidate(OTHER_APP),
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                Build.VERSION_CODES.Q
        );

        assertEquals(OTHER_APP, state.packageName);
    }

    @Test
    public void usageSummaryOnlySeedsAnUnknownForegroundAtSanityCheck() {
        assertTrue(ForegroundEventPolicy.shouldSeedFromUsageSummary(
                ForegroundEventPolicy.unknownCandidate(),
                true
        ));

        assertFalse(ForegroundEventPolicy.shouldSeedFromUsageSummary(
                ForegroundEventPolicy.knownCandidate(SYSTEM_UI),
                true
        ));
        assertFalse(ForegroundEventPolicy.shouldSeedFromUsageSummary(
                ForegroundEventPolicy.knownCandidate(BLOCKED_APP),
                true
        ));
        assertFalse(ForegroundEventPolicy.shouldSeedFromUsageSummary(
                ForegroundEventPolicy.knownCandidate(null),
                true
        ));
        assertFalse(ForegroundEventPolicy.shouldSeedFromUsageSummary(
                ForegroundEventPolicy.unknownCandidate(),
                false
        ));
    }
}
