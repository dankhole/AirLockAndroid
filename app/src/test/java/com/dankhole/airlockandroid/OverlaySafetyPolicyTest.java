package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverlaySafetyPolicyTest {
    @Test
    public void slowConfirmationCannotKeepAcceleratedPollingForever() {
        OverlaySafetyPolicy window = new OverlaySafetyPolicy(1_000L);
        assertFalse(window.needsFastConfirmation(999L));
        assertTrue(window.needsFastConfirmation(1_000L));
        assertTrue(window.needsFastConfirmation(3_999L));
        assertFalse(window.canAttach(3_500L, 4_100L));
        assertFalse(window.needsFastConfirmation(4_000L));
        assertFalse(window.needsFastConfirmation(60_000L));
        // Expiring accelerated polling must still allow a later fresh result.
        assertTrue(window.canAttach(60_000L, 60_100L));
    }

    @Test
    public void constructionSnapshotCannotAuthorizeAttachment() {
        OverlaySafetyPolicy window = new OverlaySafetyPolicy(1_000L);
        assertFalse(window.canAttach(900L, 1_100L));
        assertTrue(window.canAttach(1_200L, 1_300L));
        assertFalse(window.canAttach(1_200L, 1_701L));
        assertFalse(window.canAttach(1_200L, 1_199L));
    }

    @Test
    public void hungQueryDoesNotExtendVisibleWindowAuthority() {
        OverlaySafetyPolicy window = new OverlaySafetyPolicy(0L);
        window.attached(100L, 150L);
        window.recordFocus(true);
        window.confirmForeground(1_100L);
        // The next query starts but never completes: the last confirmed snapshot expires.
        assertFalse(window.evidenceExpired(3_100L));
        assertTrue(window.evidenceExpired(3_101L));
    }

    @Test
    public void freshEvidenceExtendsAuthorityButOlderEvidenceCannot() {
        OverlaySafetyPolicy window = new OverlaySafetyPolicy(0L);
        window.attached(100L, 150L);
        window.confirmForeground(1_100L);
        window.confirmForeground(100L);
        assertFalse(window.evidenceExpired(3_000L));
        window.confirmForeground(2_100L);
        assertFalse(window.evidenceExpired(4_100L));
        assertTrue(window.evidenceExpired(4_101L));
    }

    @Test
    public void windowThatNeverGainsFocusHasBoundedLifetime() {
        OverlaySafetyPolicy window = new OverlaySafetyPolicy(0L);
        window.attached(100L, 150L);
        assertFalse(window.focusUnavailable(649L, false));
        assertTrue(window.focusUnavailable(650L, false));
    }

    @Test
    public void focusLossAfterAcquisitionNeedsNoGracePeriod() {
        OverlaySafetyPolicy window = new OverlaySafetyPolicy(0L);
        window.attached(100L, 150L);
        assertFalse(window.focusUnavailable(160L, true));
        assertTrue(window.focusUnavailable(161L, false));
    }

    @Test
    public void replacementWindowDoesNotInheritOldFocusOrEvidence() {
        OverlaySafetyPolicy oldWindow = new OverlaySafetyPolicy(0L);
        oldWindow.attached(100L, 150L);
        oldWindow.recordFocus(true);
        OverlaySafetyPolicy replacement = new OverlaySafetyPolicy(300L);
        assertFalse(replacement.canAttach(100L, 350L));
        replacement.attached(400L, 450L);
        assertFalse(replacement.focusUnavailable(451L, false));
        assertTrue(replacement.focusUnavailable(950L, false));
    }
}
