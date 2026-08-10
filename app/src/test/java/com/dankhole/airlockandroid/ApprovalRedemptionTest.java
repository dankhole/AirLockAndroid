package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ApprovalRedemptionTest {
    private static final String PACKAGE_NAME = "example.blocked";
    private static final String APPROVAL_CODE = "654321";
    private static final String SECOND_APPROVAL_CODE = "111111";
    private static final long NOW_MS = 1_000L;

    @Test
    public void redemptionConsumesCodeAndGrantsStoredMinutesInOneCommit() {
        TestSharedPreferences preferences = validApprovalPreferences();

        int result = Preferences.redeemApprovalCodeAndGrantMinutes(
                preferences,
                PACKAGE_NAME,
                APPROVAL_CODE,
                NOW_MS
        );

        assertEquals(17, result);
        assertEquals(1, preferences.commitCount());
        assertTrue(preferences.getStringSet(approvalCodesKey(), Collections.emptySet()).isEmpty());
        assertFalse(preferences.contains(approvalExpiryKey()));
        assertFalse(preferences.contains(approvalMinutesKey()));
        assertEquals(1_021_000L, preferences.getLong(unlockKey(), 0L));
    }

    @Test
    public void failedCommitRestoresCodeAndPreviousUnlockState() {
        TestSharedPreferences preferences = validApprovalPreferences();
        preferences.edit().putLong(unlockKey(), 1_500L).apply();
        preferences.failNextCommit();

        int result = Preferences.redeemApprovalCodeAndGrantMinutes(
                preferences,
                PACKAGE_NAME,
                APPROVAL_CODE,
                NOW_MS
        );

        assertEquals(Preferences.APPROVAL_REDEMPTION_SAVE_FAILED, result);
        assertTrue(preferences.getStringSet(approvalCodesKey(), Collections.emptySet())
                .contains(APPROVAL_CODE));
        assertEquals(2_000L, preferences.getLong(approvalExpiryKey(), 0L));
        assertEquals(17, preferences.getInt(approvalMinutesKey(), -1));
        assertEquals(1_500L, preferences.getLong(unlockKey(), 0L));
    }

    @Test
    public void redeemingOnePendingCodeLeavesOtherRequestsAvailable() {
        TestSharedPreferences preferences = validApprovalPreferences();
        Set<String> pendingCodes = new HashSet<>(preferences.getStringSet(
                approvalCodesKey(),
                Collections.emptySet()
        ));
        pendingCodes.add(SECOND_APPROVAL_CODE);
        preferences.edit()
                .putStringSet(approvalCodesKey(), pendingCodes)
                .putLong(approvalExpiryKey(SECOND_APPROVAL_CODE), 3_000L)
                .putInt(approvalMinutesKey(SECOND_APPROVAL_CODE), 29)
                .apply();

        int result = Preferences.redeemApprovalCodeAndGrantMinutes(
                preferences,
                PACKAGE_NAME,
                APPROVAL_CODE,
                NOW_MS
        );

        assertEquals(17, result);
        assertEquals(Collections.singleton(SECOND_APPROVAL_CODE),
                preferences.getStringSet(approvalCodesKey(), Collections.emptySet()));
        assertEquals(3_000L, preferences.getLong(approvalExpiryKey(SECOND_APPROVAL_CODE), 0L));
        assertEquals(29, preferences.getInt(approvalMinutesKey(SECOND_APPROVAL_CODE), -1));
    }

    @Test
    public void invalidCodeDoesNotConsumePendingRequestOrGrantTime() {
        TestSharedPreferences preferences = validApprovalPreferences();

        int result = Preferences.redeemApprovalCodeAndGrantMinutes(
                preferences,
                PACKAGE_NAME,
                "000000",
                NOW_MS
        );

        assertEquals(Preferences.APPROVAL_REDEMPTION_INVALID, result);
        assertEquals(0, preferences.commitCount());
        assertTrue(preferences.getStringSet(approvalCodesKey(), Collections.emptySet())
                .contains(APPROVAL_CODE));
        assertFalse(preferences.contains(unlockKey()));
    }

    private TestSharedPreferences validApprovalPreferences() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        preferences.edit()
                .putStringSet(approvalCodesKey(), Collections.singleton(APPROVAL_CODE))
                .putLong(approvalExpiryKey(), 2_000L)
                .putInt(approvalMinutesKey(), 17)
                .apply();
        return preferences;
    }

    private String approvalCodesKey() {
        return "approval_codes_" + PACKAGE_NAME;
    }

    private String approvalExpiryKey() {
        return approvalExpiryKey(APPROVAL_CODE);
    }

    private String approvalExpiryKey(String code) {
        return "approval_code_expiry_" + PACKAGE_NAME + "_" + code;
    }

    private String approvalMinutesKey() {
        return approvalMinutesKey(APPROVAL_CODE);
    }

    private String approvalMinutesKey(String code) {
        return "approval_code_minutes_" + PACKAGE_NAME + "_" + code;
    }

    private String unlockKey() {
        return "unlock_until_" + PACKAGE_NAME;
    }
}
