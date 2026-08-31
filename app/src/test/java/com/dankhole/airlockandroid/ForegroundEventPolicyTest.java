package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.usage.UsageEvents;
import android.os.Build;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
    public void olderOverlappingLifecycleEventIsDeferredToTimedReducer() {
        assertTrue(ForegroundEventPolicy.shouldApplyLifecycleEvent(
                99L,
                UsageEvents.Event.ACTIVITY_RESUMED,
                BLOCKED_APP,
                100L,
                Collections.emptySet(),
                Build.VERSION_CODES.Q
        ));
    }

    @Test
    public void olderBlockedResumeCannotUndoNewerPause() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        BLOCKED_APP,
                        100L,
                        100L,
                        Collections.emptyMap()
                );
        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                200L,
                Build.VERSION_CODES.Q
        );
        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                BLOCKED_APP,
                100L,
                Build.VERSION_CODES.Q
        );

        assertTrue(state.known);
        assertNull(state.packageName);
    }

    @Test
    public void delayedOtherResumeSurvivesNewerUnrelatedPause() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        BLOCKED_APP,
                        100L,
                        100L,
                        Collections.emptyMap()
                );
        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                200L,
                Build.VERSION_CODES.Q
        );
        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                OTHER_APP,
                150L,
                Build.VERSION_CODES.Q
        );

        assertTrue(state.known);
        assertEquals(OTHER_APP, state.packageName);
    }

    @Test
    public void delayedBlockedResumeCannotReplaceNewerOtherForeground() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        OTHER_APP,
                        200L,
                        200L,
                        Collections.emptyMap()
                );
        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                BLOCKED_APP,
                150L,
                Build.VERSION_CODES.Q
        );

        assertEquals(OTHER_APP, state.packageName);
    }

    @Test
    public void sameTimestampForegroundAfterPauseRestoresPackage() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        BLOCKED_APP,
                        100L,
                        100L,
                        Collections.emptyMap()
                );
        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                200L,
                Build.VERSION_CODES.Q
        );
        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                BLOCKED_APP,
                200L,
                Build.VERSION_CODES.Q
        );

        assertEquals(BLOCKED_APP, state.packageName);
    }

    @Test
    public void staleBackgroundEvidenceIsPrunedOutsideOverlap() {
        Map<String, Long> backgroundTimestamps = new HashMap<>();
        backgroundTimestamps.put(BLOCKED_APP, 100L);
        backgroundTimestamps.put(OTHER_APP, 200L);
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        OTHER_APP,
                        250L,
                        250L,
                        backgroundTimestamps
                );

        state = ForegroundEventPolicy.pruneTimedEvidence(state, 150L);

        assertFalse(state.latestBackgroundEventTimestamps.containsKey(BLOCKED_APP));
        assertEquals(Long.valueOf(200L), state.latestBackgroundEventTimestamps.get(OTHER_APP));
    }

    @Test
    public void aggregateSeedYieldsToDelayedLifecycleEvidence() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.seedTimedCandidate(
                        ForegroundEventPolicy.unknownTimedCandidate(),
                        BLOCKED_APP
                );

        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                OTHER_APP,
                150L,
                Build.VERSION_CODES.Q
        );

        assertEquals(OTHER_APP, state.packageName);
    }

    @Test
    public void explicitExitBoundaryRejectsOldResumeAndAcceptsRealReturn() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        null,
                        200L,
                        200L,
                        Collections.emptyMap()
                );

        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                BLOCKED_APP,
                150L,
                Build.VERSION_CODES.Q
        );
        assertNull(state.packageName);

        state = ForegroundEventPolicy.applyTimedLifecycleEvent(
                state,
                UsageEvents.Event.ACTIVITY_RESUMED,
                BLOCKED_APP,
                250L,
                Build.VERSION_CODES.Q
        );
        assertEquals(BLOCKED_APP, state.packageName);
    }

    @Test
    public void celebrationStaysOnlyOverItsKnownForegroundPackageBeforeDeadline() {
        assertTrue(ForegroundEventPolicy.shouldKeepCelebration(
                BLOCKED_APP,
                ForegroundEventPolicy.knownCandidate(BLOCKED_APP),
                100L,
                200L
        ));
        assertFalse(ForegroundEventPolicy.shouldKeepCelebration(
                BLOCKED_APP,
                ForegroundEventPolicy.knownCandidate(OTHER_APP),
                100L,
                200L
        ));
        assertFalse(ForegroundEventPolicy.shouldKeepCelebration(
                BLOCKED_APP,
                ForegroundEventPolicy.knownCandidate(null),
                100L,
                200L
        ));
        assertFalse(ForegroundEventPolicy.shouldKeepCelebration(
                BLOCKED_APP,
                ForegroundEventPolicy.unknownCandidate(),
                100L,
                200L
        ));
        assertFalse(ForegroundEventPolicy.shouldKeepCelebration(
                BLOCKED_APP,
                ForegroundEventPolicy.knownCandidate(BLOCKED_APP),
                200L,
                200L
        ));
    }

    @Test
    public void timedReducerMatchesChronologicalPolicyAcrossDelayedDelivery() {
        Random random = new Random(0xA17C0DEL);
        String[] packages = {BLOCKED_APP, OTHER_APP, SYSTEM_UI};
        int[] eventTypes = {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED
        };

        for (int scenario = 0; scenario < 2_000; scenario++) {
            List<TimedEvent> chronological = new ArrayList<>();
            int eventCount = 1 + random.nextInt(8);
            long timestamp = 10L;
            for (int index = 0; index < eventCount; index++) {
                timestamp += 1L + random.nextInt(5);
                chronological.add(new TimedEvent(
                        timestamp,
                        eventTypes[random.nextInt(eventTypes.length)],
                        packages[random.nextInt(packages.length)]
                ));
            }

            int initialChoice = random.nextInt(packages.length + 2);
            String initialPackage = initialChoice >= packages.length
                    ? null
                    : packages[initialChoice];
            boolean initialKnown = initialChoice != packages.length;
            ForegroundEventPolicy.CandidateState expected = initialKnown
                    ? ForegroundEventPolicy.knownCandidate(initialPackage)
                    : ForegroundEventPolicy.unknownCandidate();
            for (TimedEvent event : chronological) {
                expected = ForegroundEventPolicy.applyLifecycleEvent(
                        expected,
                        event.type,
                        event.packageName,
                        Build.VERSION_CODES.Q
                );
            }

            List<TimedEvent> delivered = new ArrayList<>(chronological);
            Collections.shuffle(delivered, random);
            ForegroundEventPolicy.TimedCandidateState actual = initialKnown
                    ? ForegroundEventPolicy.knownTimedCandidate(
                            initialPackage,
                            0L,
                            0L,
                            Collections.emptyMap()
                    )
                    : ForegroundEventPolicy.unknownTimedCandidate();
            for (TimedEvent event : delivered) {
                actual = ForegroundEventPolicy.applyTimedLifecycleEvent(
                        actual,
                        event.type,
                        event.packageName,
                        event.timestampMs,
                        Build.VERSION_CODES.Q
                );
            }
            for (int replay = 0; replay < 3; replay++) {
                for (TimedEvent event : chronological) {
                    actual = ForegroundEventPolicy.applyTimedLifecycleEvent(
                            actual,
                            event.type,
                            event.packageName,
                            event.timestampMs,
                            Build.VERSION_CODES.Q
                    );
                }
            }

            String scenarioMessage = "scenario " + scenario
                    + " chronological=" + chronological
                    + " delivered=" + delivered;
            assertEquals(scenarioMessage, expected.known, actual.known);
            assertEquals(
                    scenarioMessage,
                    expected.packageName,
                    actual.packageName
            );
        }
    }

    @Test
    public void duplicateLifecycleEventAtWatermarkIsNotReapplied() {
        Set<String> processedKeys = Collections.singleton(
                ForegroundEventPolicy.lifecycleEventKey(
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        BLOCKED_APP
                )
        );

        assertFalse(ForegroundEventPolicy.shouldApplyLifecycleEvent(
                100L,
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP,
                100L,
                processedKeys,
                Build.VERSION_CODES.Q
        ));
    }

    @Test
    public void newlyDeliveredLifecycleEventAtWatermarkIsApplied() {
        Set<String> processedKeys = new HashSet<>();
        processedKeys.add(ForegroundEventPolicy.lifecycleEventKey(
                UsageEvents.Event.ACTIVITY_PAUSED,
                BLOCKED_APP
        ));

        assertTrue(ForegroundEventPolicy.shouldApplyLifecycleEvent(
                100L,
                UsageEvents.Event.ACTIVITY_RESUMED,
                OTHER_APP,
                100L,
                processedKeys,
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

    private static final class TimedEvent {
        final long timestampMs;
        final int type;
        final String packageName;

        private TimedEvent(long timestampMs, int type, String packageName) {
            this.timestampMs = timestampMs;
            this.type = type;
            this.packageName = packageName;
        }

        @Override
        public String toString() {
            return timestampMs + ":" + type + ":" + packageName;
        }
    }
}
