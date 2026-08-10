package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.usage.UsageEvents;
import android.os.Build;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public class ForegroundEventPolicyTest {
    private static final String BLOCKED_APP = "example.blocked";
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
}
