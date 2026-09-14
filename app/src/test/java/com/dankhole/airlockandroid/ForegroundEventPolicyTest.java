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
    public void sameTimestampForegroundAfterPauseCannotResurrectPackage() {
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

        assertNull(state.packageName);
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
    public void explicitExitBoundaryRejectsOldResumeAndAcceptsRealReturn() {
        ForegroundEventPolicy.TimedCandidateState state =
                knownEmptyAt(200L);

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
    public void globalBoundariesAreRecognizedBeforePackageNameFiltering() {
        int[] boundaryTypes = {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN,
                UsageEvents.Event.DEVICE_STARTUP
        };
        for (int type : boundaryTypes) {
            assertTrue(ForegroundEventPolicy.shouldApplyLifecycleEvent(
                    200L, type, null, 100L, Collections.emptySet(), Build.VERSION_CODES.Q
            ));
            assertTrue(ForegroundEventPolicy.isOverlayInterruptionEvent(
                    type, null, BLOCKED_APP, Collections.emptySet(), Build.VERSION_CODES.Q
            ));
            ForegroundEventPolicy.TimedCandidateState state =
                    ForegroundEventPolicy.knownTimedCandidate(
                            BLOCKED_APP, 100L, 100L, Collections.emptyMap()
                    );
            state = apply(state, new TimedEvent(200L, type, null));
            assertTrue(state.known);
            assertNull(state.packageName);
            assertEquals(200L, state.latestBoundaryEventTimestampMs);
        }
        assertFalse(ForegroundEventPolicy.isForegroundBoundaryEvent(
                UsageEvents.Event.DEVICE_STARTUP, Build.VERSION_CODES.P
        ));
        assertFalse(ForegroundEventPolicy.isForegroundBoundaryEvent(
                UsageEvents.Event.KEYGUARD_SHOWN, Build.VERSION_CODES.O
        ));
    }

    @Test
    public void bootCutoffRejectsPriorBootHistoryWithoutInventingForeground() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.unknownTimedCandidate(200L);
        state = apply(state, new TimedEvent(100L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        state = apply(state, new TimedEvent(200L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        assertFalse(state.known);
        assertNull(state.packageName);
        state = apply(state, new TimedEvent(201L, UsageEvents.Event.ACTIVITY_RESUMED, OTHER_APP));
        assertEquals(OTHER_APP, state.packageName);
    }

    @Test
    public void explicitExitRejectsSameMillisecondResumeAndAcceptsLaterReturn() {
        ForegroundEventPolicy.TimedCandidateState state =
                knownEmptyAt(200L);
        state = apply(state, new TimedEvent(200L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        assertNull(state.packageName);
        state = apply(state, new TimedEvent(201L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        assertEquals(BLOCKED_APP, state.packageName);
    }

    @Test
    public void delayedGlobalBoundaryDoesNotEraseLaterForegroundEvidence() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        OTHER_APP, 300L, 300L, Collections.emptyMap()
                );
        state = apply(state, new TimedEvent(200L, UsageEvents.Event.DEVICE_STARTUP, null));
        state = apply(state, new TimedEvent(100L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        assertEquals(OTHER_APP, state.packageName);
        assertEquals(200L, state.latestBoundaryEventTimestampMs);
    }

    @Test
    public void rebootBoundariesRejectPriorSessionAcrossDeliveryOrdersAndReplay() {
        List<TimedEvent> events = new ArrayList<>();
        events.add(new TimedEvent(100L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        events.add(new TimedEvent(200L, UsageEvents.Event.DEVICE_SHUTDOWN, null));
        events.add(new TimedEvent(250L, UsageEvents.Event.DEVICE_STARTUP, null));
        assertAcrossDeliveryOrders(events, null);
        events.add(new TimedEvent(300L, UsageEvents.Event.ACTIVITY_RESUMED, OTHER_APP));
        assertAcrossDeliveryOrders(events, OTHER_APP);
    }

    @Test
    public void sameMillisecondGlobalBoundaryWinsEveryDeliveryOrder() {
        List<TimedEvent> events = new ArrayList<>();
        events.add(new TimedEvent(200L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        events.add(new TimedEvent(200L, UsageEvents.Event.KEYGUARD_SHOWN, null));
        assertAcrossDeliveryOrders(events, null);
    }

    @Test
    public void equalTimestampForegroundPackagesRemainAmbiguousThroughReplay() {
        List<TimedEvent> events = new ArrayList<>();
        events.add(new TimedEvent(200L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        events.add(new TimedEvent(200L, UsageEvents.Event.ACTIVITY_RESUMED, OTHER_APP));
        events.add(new TimedEvent(300L, UsageEvents.Event.ACTIVITY_PAUSED, SYSTEM_UI));
        assertAcrossDeliveryOrders(events, null);
        events.add(new TimedEvent(301L, UsageEvents.Event.ACTIVITY_RESUMED, OTHER_APP));
        assertAcrossDeliveryOrders(events, OTHER_APP);
    }

    @Test
    public void sameTimestampResumeAndBackgroundStayClosedAcrossReplay() {
        List<TimedEvent> events = new ArrayList<>();
        events.add(new TimedEvent(200L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        events.add(new TimedEvent(200L, UsageEvents.Event.ACTIVITY_PAUSED, BLOCKED_APP));
        events.add(new TimedEvent(300L, UsageEvents.Event.ACTIVITY_PAUSED, OTHER_APP));
        assertAcrossDeliveryOrders(events, null);
    }

    @Test
    public void newestResumeCloseSurvivesPruningAndLateReplay() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.unknownTimedCandidate();
        TimedEvent resume = new TimedEvent(100L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP);
        state = apply(state, resume);
        state = apply(state, new TimedEvent(100L, UsageEvents.Event.ACTIVITY_PAUSED, BLOCKED_APP));
        state = ForegroundEventPolicy.pruneTimedEvidence(state, 300L);
        state = apply(state, resume);
        assertNull(state.packageName);
        assertEquals(Long.valueOf(100L), state.latestBackgroundEventTimestamps.get(BLOCKED_APP));
    }

    @Test
    public void repeatedSlowSnapshotsRetainExitEvidenceAfterItLeavesTheQueryWindow() {
        ForegroundEventPolicy.TimedCandidateState state =
                ForegroundEventPolicy.knownTimedCandidate(
                        BLOCKED_APP, 10_000L, 10_000L, Collections.emptyMap()
                );
        List<TimedEvent> events = new ArrayList<>();
        events.add(new TimedEvent(10_000L, UsageEvents.Event.ACTIVITY_RESUMED, BLOCKED_APP));
        events.add(new TimedEvent(15_000L, UsageEvents.Event.ACTIVITY_PAUSED, BLOCKED_APP));
        events.add(new TimedEvent(15_001L, UsageEvents.Event.ACTIVITY_RESUMED, OTHER_APP));
        long previousEnd = 12_000L;
        long[] queryEnds = {20_000L, 27_000L, 34_000L, 41_000L};
        for (int query = 0; query < queryEnds.length; query++) {
            long end = queryEnds[query];
            long start = ForegroundPollPolicy.queryStartMs(end, previousEnd, 0L, false);
            for (TimedEvent event : events) {
                if (event.timestampMs >= start && event.timestampMs < end) {
                    state = apply(state, event);
                }
            }
            // Snapshot metadata is retained even when rendering from its result
            // is forbidden. Later overlap windows will no longer contain this exit.
            state = ForegroundEventPolicy.knownTimedCandidate(
                    state.packageName, state.candidateEventTimestampMs,
                    state.latestForegroundEventTimestampMs, state.latestForegroundPackageName,
                    state.latestBoundaryEventTimestampMs, state.latestBackgroundEventTimestamps
            );
            boolean fresh = ForegroundPollPolicy.isResultFresh(
                    end, end + (query == queryEnds.length - 1 ? 100L : 3_000L)
            );
            assertEquals(query == queryEnds.length - 1, fresh);
            assertEquals(OTHER_APP, state.packageName);
            assertEquals(15_001L, state.latestForegroundEventTimestampMs);
            previousEnd = end;
        }
    }

    private static ForegroundEventPolicy.TimedCandidateState knownEmptyAt(long boundaryMs) {
        return ForegroundEventPolicy.knownTimedCandidate(
                null, boundaryMs, Long.MIN_VALUE, null, boundaryMs, Collections.emptyMap()
        );
    }

    private static void assertAcrossDeliveryOrders(List<TimedEvent> events, String expectedPackage) {
        Random random = new Random(0xB007L);
        for (int scenario = 0; scenario < 100; scenario++) {
            List<TimedEvent> delivered = new ArrayList<>(events);
            Collections.shuffle(delivered, random);
            ForegroundEventPolicy.TimedCandidateState state =
                    ForegroundEventPolicy.unknownTimedCandidate();
            for (TimedEvent event : delivered) {
                state = apply(state, event);
            }
            assertTrue(state.known);
            assertEquals("delivery=" + delivered, expectedPackage, state.packageName);
            for (int replay = 0; replay < 3; replay++) {
                Collections.shuffle(delivered, random);
                for (TimedEvent event : delivered) {
                    state = apply(state, event);
                    assertEquals("replay=" + delivered, expectedPackage, state.packageName);
                }
            }
        }
    }

    private static ForegroundEventPolicy.TimedCandidateState apply(
            ForegroundEventPolicy.TimedCandidateState state,
            TimedEvent event
    ) {
        return ForegroundEventPolicy.applyTimedLifecycleEvent(
                state, event.type, event.packageName, event.timestampMs, Build.VERSION_CODES.Q
        );
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
