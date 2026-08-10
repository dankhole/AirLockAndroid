package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class MonitoringHealthTest {
    @Test
    public void recoveryRequiresAnExitFromThePreviousMonitoringRun() {
        assertTrue(MonitoringHealth.shouldRecordRecovery(1_000L, 2_000L, true));
        assertFalse(MonitoringHealth.shouldRecordRecovery(0L, 2_000L, true));
        assertFalse(MonitoringHealth.shouldRecordRecovery(3_000L, 2_000L, true));
        assertFalse(MonitoringHealth.shouldRecordRecovery(1_000L, 2_000L, false));
    }

    @Test
    public void stoppedByUserSummaryDoesNotLookLikeAnUnexpectedMonitoringExit() {
        assertTrue(MonitoringHealth.processStateWasMonitoring(
                false,
                "airlock_monitoring=healthy".getBytes(StandardCharsets.UTF_8)
        ));
        assertFalse(MonitoringHealth.processStateWasMonitoring(
                true,
                "airlock_monitoring=stopped_by_user".getBytes(StandardCharsets.UTF_8)
        ));
        assertTrue(MonitoringHealth.processStateWasMonitoring(true, null));
        assertFalse(MonitoringHealth.processStateWasMonitoring(false, null));
    }
}
