package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ForegroundPollPolicyTest {
    @Test
    public void overlapsThePreviousQueryInsteadOfSkippingASchedulerGap() {
        assertEquals(60_000L, ForegroundPollPolicy.queryStartMs(
                100_000L, 70_000L, 0L, false));
    }

    @Test
    public void startupLookbackCannotReachThePreviousBoot() {
        assertEquals(90_000L, ForegroundPollPolicy.queryStartMs(
                100_000L, 99_000L, 90_000L, true));
    }

    @Test
    public void recoveryCannotReplayTheSessionBeforeScreenOffOrExplicitExit() {
        assertEquals(98_000L, ForegroundPollPolicy.queryStartMs(
                100_000L, 99_000L, 98_000L, true));
    }

    @Test
    public void longGapsAndClockChangesKeepQueriesBounded() {
        assertEquals(300_000L, ForegroundPollPolicy.queryStartMs(
                600_000L, 1L, 0L, false));
        assertEquals(300_000L, ForegroundPollPolicy.queryStartMs(
                600_000L, 700_000L, 0L, false));
    }

    @Test
    public void lateResultsCannotAuthorizeAnOverlay() {
        assertTrue(ForegroundPollPolicy.isResultFresh(100L, 2_100L));
        assertFalse(ForegroundPollPolicy.isResultFresh(100L, 2_101L));
        assertFalse(ForegroundPollPolicy.isResultFresh(100L, 99L));
    }
}
