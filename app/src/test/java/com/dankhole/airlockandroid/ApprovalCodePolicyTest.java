package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ApprovalCodePolicyTest {
    @Test
    public void approvalCodeTransformsEveryRequestDigit() {
        assertEquals("567890", ApprovalCodePolicy.approvalCodeForNormalizedRequest("012345"));
    }

    @Test
    public void validApprovalReturnsStoredRequestedMinutes() {
        assertEquals(17, ApprovalCodePolicy.approvedMinutes(true, 2_000L, 17, 1_000L));
    }

    @Test
    public void expiredOrMissingApprovalIsRejected() {
        assertEquals(-1, ApprovalCodePolicy.approvedMinutes(false, 2_000L, 17, 1_000L));
        assertEquals(-1, ApprovalCodePolicy.approvedMinutes(true, 1_000L, 17, 1_000L));
        assertEquals(-1, ApprovalCodePolicy.approvedMinutes(true, 2_000L, 0, 1_000L));
    }

    @Test
    public void unlockDeadlineUsesApprovedMinutes() {
        assertEquals(1_021_000L, ApprovalCodePolicy.unlockUntilMs(1_000L, 17));
    }
}
