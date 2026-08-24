package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class ApprovalRequestTest {
    private static final String PACKAGE_NAME = "example.blocked";
    private static final String MASTER_PIN = "6789";
    private static final long NOW_MS = 1_000L;
    private static final int REQUEST_4321_OFFSET = 4_321 - ApprovalCodePolicy.REQUEST_CODE_MIN;

    @Test
    public void settingPinStoresCalculatorWithoutPlaintextPin() {
        TestSharedPreferences preferences = new TestSharedPreferences();

        assertTrue(Preferences.setMasterPin(preferences, MASTER_PIN));

        assertTrue(Preferences.isApprovalCalculatorReady(preferences));
        assertTrue(Preferences.verifyMasterPin(preferences, MASTER_PIN));
        assertFalse(Preferences.verifyMasterPin(preferences, "1234"));
        assertFalse(preferences.getAll().containsValue(MASTER_PIN));
        assertEquals(
                "3352",
                ApprovalCodePolicy.approvalCodeFromTable(
                        preferences.getString(Preferences.KEY_MASTER_APPROVAL_TABLE, ""),
                        "4321"
                )
        );
    }

    @Test
    public void allZeroMasterPinIsRejected() {
        TestSharedPreferences preferences = new TestSharedPreferences();

        assertFalse(Preferences.setMasterPin(preferences, "0000"));
        assertFalse(Preferences.isApprovalCalculatorReady(preferences));
    }

    @Test
    public void pinCalculatedRequestStoresApprovalAndRequestedMinutes() {
        TestSharedPreferences preferences = readyPreferences();

        String requestCode = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                17,
                NOW_MS,
                REQUEST_4321_OFFSET,
                false
        );

        assertEquals("4321", requestCode);
        assertEquals(
                Collections.singleton("3352"),
                preferences.getStringSet(approvalCodesKey(), Collections.emptySet())
        );
        assertEquals(601_000L, preferences.getLong(approvalExpiryKey("3352"), 0L));
        assertEquals(17, preferences.getInt(approvalMinutesKey("3352"), -1));
    }

    @Test
    public void approvalOverrideRequestAdds5656() {
        TestSharedPreferences preferences = readyPreferences();

        String requestCode = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                17,
                NOW_MS,
                REQUEST_4321_OFFSET,
                true
        );

        assertEquals("4321", requestCode);
        assertEquals(
                Collections.singleton("9977"),
                preferences.getStringSet(approvalCodesKey(), Collections.emptySet())
        );
    }

    @Test
    public void generatedRequestAndApprovalPreserveLeadingZeroes() {
        TestSharedPreferences preferences = readyPreferences();

        String requestCode = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                17,
                NOW_MS,
                12,
                false
        );

        assertEquals("0012", requestCode);
        assertEquals(
                Collections.singleton("0814"),
                preferences.getStringSet(approvalCodesKey(), Collections.emptySet())
        );
    }

    @Test
    public void pinCalculatedRequestRejectsTestingOverride() {
        TestSharedPreferences preferences = readyPreferences();
        assertEquals(
                "4321",
                Preferences.createRequestCode(
                        preferences,
                        PACKAGE_NAME,
                        17,
                        NOW_MS,
                        REQUEST_4321_OFFSET,
                        false
                )
        );

        assertEquals(
                Preferences.APPROVAL_REDEMPTION_INVALID,
                Preferences.redeemApprovalCodeAndGrantMinutes(
                        preferences,
                        PACKAGE_NAME,
                        "9977",
                        NOW_MS
                )
        );
        assertEquals(
                17,
                Preferences.redeemApprovalCodeAndGrantMinutes(
                        preferences,
                        PACKAGE_NAME,
                        "3352",
                        NOW_MS
                )
        );
    }

    @Test
    public void testingOverrideRequestRejectsPinCalculation() {
        TestSharedPreferences preferences = readyPreferences();
        assertEquals(
                "4321",
                Preferences.createRequestCode(
                        preferences,
                        PACKAGE_NAME,
                        17,
                        NOW_MS,
                        REQUEST_4321_OFFSET,
                        true
                )
        );

        assertEquals(
                Preferences.APPROVAL_REDEMPTION_INVALID,
                Preferences.redeemApprovalCodeAndGrantMinutes(
                        preferences,
                        PACKAGE_NAME,
                        "3352",
                        NOW_MS
                )
        );
        assertEquals(
                17,
                Preferences.redeemApprovalCodeAndGrantMinutes(
                        preferences,
                        PACKAGE_NAME,
                        "9977",
                        NOW_MS
                )
        );
    }

    @Test
    public void pendingRequestIsSkippedButExpiredRequestMayBeReused() {
        TestSharedPreferences preferences = readyPreferences();

        String first = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                5,
                NOW_MS,
                REQUEST_4321_OFFSET,
                false
        );
        String second = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                7,
                NOW_MS,
                REQUEST_4321_OFFSET,
                false
        );
        String afterExpiry = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                9,
                601_001L,
                REQUEST_4321_OFFSET,
                false
        );

        assertEquals("4321", first);
        assertEquals("4322", second);
        assertEquals("4321", afterExpiry);
    }

    @Test
    public void redeemedRequestMayBeReused() {
        TestSharedPreferences preferences = readyPreferences();

        assertEquals(
                "4321",
                Preferences.createRequestCode(
                        preferences,
                        PACKAGE_NAME,
                        5,
                        NOW_MS,
                        REQUEST_4321_OFFSET,
                        false
                )
        );
        assertEquals(
                5,
                Preferences.redeemApprovalCodeAndGrantMinutes(
                        preferences,
                        PACKAGE_NAME,
                        "3352",
                        NOW_MS
                )
        );
        assertEquals(
                "4321",
                Preferences.createRequestCode(
                        preferences,
                        PACKAGE_NAME,
                        7,
                        NOW_MS + 1,
                        REQUEST_4321_OFFSET,
                        false
                )
        );
    }

    @Test
    public void generatorSkipsDifferentRequestsWithTheSamePendingReply() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertTrue(Preferences.setMasterPin(preferences, "0001"));

        String first = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                5,
                NOW_MS,
                0,
                false
        );
        String second = Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                7,
                NOW_MS,
                1,
                false
        );

        assertEquals("0000", first);
        assertEquals("0100", second);
        assertEquals(
                7,
                Preferences.redeemApprovalCodeAndGrantMinutes(
                        preferences,
                        PACKAGE_NAME,
                        "0001",
                        NOW_MS
                )
        );
    }

    @Test
    public void changingPinInvalidatesPendingRequestsAndReplacesCredentials() {
        TestSharedPreferences preferences = readyPreferences();
        assertEquals(
                "4321",
                Preferences.createRequestCode(
                        preferences,
                        PACKAGE_NAME,
                        17,
                        NOW_MS,
                        REQUEST_4321_OFFSET,
                        false
                )
        );

        assertTrue(Preferences.setMasterPin(preferences, "2468"));

        assertTrue(preferences.getStringSet(approvalCodesKey(), Collections.emptySet()).isEmpty());
        assertFalse(preferences.contains(approvalExpiryKey("3352")));
        assertFalse(preferences.contains(approvalMinutesKey("3352")));
        assertFalse(Preferences.verifyMasterPin(preferences, MASTER_PIN));
        assertTrue(Preferences.verifyMasterPin(preferences, "2468"));
    }

    @Test
    public void verifyingLegacyFourDigitPinBuildsCalculatorAndInvalidatesPendingRequests() {
        TestSharedPreferences preferences = readyPreferences();
        preferences.edit()
                .remove(Preferences.KEY_MASTER_APPROVAL_TABLE)
                .putStringSet(approvalCodesKey(), Collections.singleton("3352"))
                .putLong(approvalExpiryKey("3352"), 601_000L)
                .putInt(approvalMinutesKey("3352"), 17)
                .apply();

        assertFalse(Preferences.isApprovalCalculatorReady(preferences));
        assertTrue(Preferences.verifyMasterPin(preferences, MASTER_PIN));

        assertTrue(Preferences.isApprovalCalculatorReady(preferences));
        assertTrue(preferences.getStringSet(approvalCodesKey(), Collections.emptySet()).isEmpty());
        assertFalse(preferences.contains(approvalExpiryKey("3352")));
        assertFalse(preferences.contains(approvalMinutesKey("3352")));
    }

    @Test
    public void requestsFailWithoutPinCalculatorEvenWithDebugOverride() {
        TestSharedPreferences preferences = new TestSharedPreferences();

        assertEquals(
                "",
                Preferences.createRequestCode(
                        preferences,
                        PACKAGE_NAME,
                        17,
                        NOW_MS,
                        REQUEST_4321_OFFSET,
                        false
                )
        );
        assertEquals(
                "",
                Preferences.createRequestCode(
                        preferences,
                        PACKAGE_NAME,
                        17,
                        NOW_MS,
                        REQUEST_4321_OFFSET,
                        true
                )
        );
    }

    @Test
    public void pendingSummaryDescribesOneActiveRequest() {
        TestSharedPreferences preferences = readyPreferences();
        Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                17,
                NOW_MS,
                REQUEST_4321_OFFSET,
                false
        );

        Preferences.PendingApprovalSummary summary = Preferences.pendingApprovalSummary(
                preferences,
                PACKAGE_NAME,
                NOW_MS + 60_000L
        );

        assertTrue(summary.hasRequests());
        assertEquals(1, summary.count);
        assertEquals(17, summary.singleMinutes);
        assertEquals(9, summary.singleRemainingMinutes);
    }

    @Test
    public void pendingSummaryCountsMultipleRequestsWithoutGuessingOneDuration() {
        TestSharedPreferences preferences = readyPreferences();
        Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                5,
                NOW_MS,
                REQUEST_4321_OFFSET,
                false
        );
        Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                12,
                NOW_MS,
                REQUEST_4321_OFFSET + 1,
                false
        );

        Preferences.PendingApprovalSummary summary = Preferences.pendingApprovalSummary(
                preferences,
                PACKAGE_NAME,
                NOW_MS
        );

        assertEquals(2, summary.count);
        assertEquals(-1, summary.singleMinutes);
        assertEquals(-1, summary.singleRemainingMinutes);
    }

    @Test
    public void pendingSummaryDropsExpiredRequests() {
        TestSharedPreferences preferences = readyPreferences();
        Preferences.createRequestCode(
                preferences,
                PACKAGE_NAME,
                17,
                NOW_MS,
                REQUEST_4321_OFFSET,
                false
        );

        Preferences.PendingApprovalSummary summary = Preferences.pendingApprovalSummary(
                preferences,
                PACKAGE_NAME,
                NOW_MS + 600_001L
        );

        assertFalse(summary.hasRequests());
        assertTrue(preferences.getStringSet(
                approvalCodesKey(),
                Collections.emptySet()
        ).isEmpty());
    }

    private TestSharedPreferences readyPreferences() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertTrue(Preferences.setMasterPin(preferences, MASTER_PIN));
        return preferences;
    }

    private String approvalCodesKey() {
        return "approval_codes_" + PACKAGE_NAME;
    }

    private String approvalExpiryKey(String code) {
        return "approval_code_expiry_" + PACKAGE_NAME + "_" + code;
    }

    private String approvalMinutesKey(String code) {
        return "approval_code_minutes_" + PACKAGE_NAME + "_" + code;
    }
}
