package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApprovalCodePolicyTest {
    @Test
    public void approvalCodeUsesMiddleFourDigitsOfProduct() {
        assertEquals("3352", ApprovalCodePolicy.approvalCodeForRequestAndPin("4321", "6789"));
    }

    @Test
    public void approvalCodePreservesLeadingZeroes() {
        assertEquals("0066", ApprovalCodePolicy.approvalCodeForRequestAndPin("1234", "5678"));
        assertEquals("0010", ApprovalCodePolicy.approvalCodeForRequestAndPin("1000", "0001"));
    }

    @Test
    public void approvalCodeRequiresFourAsciiDigits() {
        assertFalse(ApprovalCodePolicy.isValidMasterPin("0000"));
        assertTrue(ApprovalCodePolicy.isValidRequestCode("0000"));
        assertTrue(ApprovalCodePolicy.isValidRequestCode("9999"));
        assertFalse(ApprovalCodePolicy.isValidMasterPin("123"));
        assertFalse(ApprovalCodePolicy.isValidMasterPin("12345"));
        assertFalse(ApprovalCodePolicy.isValidMasterPin("12a4"));
        assertFalse(ApprovalCodePolicy.isValidMasterPin("١٢٣٤"));
        assertFalse(ApprovalCodePolicy.isValidRequestCode("999"));
        assertEquals("0000", ApprovalCodePolicy.approvalCodeForRequestAndPin("0000", "6789"));
        assertEquals("", ApprovalCodePolicy.approvalCodeForRequestAndPin("1234", "0000"));
    }

    @Test
    public void approvalTableMatchesDirectCalculation() {
        String table = ApprovalCodePolicy.approvalTableForPin("6789");

        assertEquals(ApprovalCodePolicy.APPROVAL_TABLE_LENGTH, table.length());
        assertTrue(ApprovalCodePolicy.isValidApprovalTable(table));
        assertEquals("3352", ApprovalCodePolicy.approvalCodeFromTable(table, "4321"));
        assertEquals(
                ApprovalCodePolicy.approvalCodeForRequestAndPin("9999", "6789"),
                ApprovalCodePolicy.approvalCodeFromTable(table, "9999")
        );
    }

    @Test
    public void plusFiveTestingOverrideStillTransformsEachDigit() {
        assertEquals("6789", ApprovalCodePolicy.testingApprovalCodeForRequest("1234"));
        assertEquals("5678", ApprovalCodePolicy.testingApprovalCodeForRequest("0123"));
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
