package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContentActionPolicyTest {
    private static ContentActionPolicy.Match match(int window, long generation, long at) {
        return new ContentActionPolicy.Match("com.google.android.youtube", window,
                ContentFilter.YOUTUBE_SHORTS, generation, at);
    }

    @Test
    public void freshAuthorityRequiresPublicCacheControlsAndVerifiedCacheDisableAndClear() {
        assertFalse(ContentActionPolicy.supportsFreshAuthority(26));
        assertFalse(ContentActionPolicy.supportsFreshAuthority(32));
        assertTrue(ContentActionPolicy.supportsFreshAuthority(33));
        assertTrue(ContentActionPolicy.supportsFreshAuthority(36));
        assertTrue(ContentActionPolicy.hasFreshAuthority(33, true, false, true));
        assertFalse(ContentActionPolicy.hasFreshAuthority(32, true, false, true));
        assertFalse(ContentActionPolicy.hasFreshAuthority(33, false, false, true));
        assertFalse(ContentActionPolicy.hasFreshAuthority(33, true, true, true));
        assertFalse(ContentActionPolicy.hasFreshAuthority(33, true, false, false));
    }

    @Test
    public void descriptionsAreReadOnlyForVisibleBoundedNoneditableNonpasswordFacebookNodes() {
        String facebook = ContentFilter.FACEBOOK_REELS.packageName();
        assertTrue(ContentActionPolicy.shouldReadDescription(facebook, true, true, false, false));
        assertFalse(ContentActionPolicy.shouldReadDescription(facebook, false, true, false, false));
        assertFalse(ContentActionPolicy.shouldReadDescription(facebook, true, false, false, false));
        assertFalse(ContentActionPolicy.shouldReadDescription(facebook, true, true, true, false));
        assertFalse(ContentActionPolicy.shouldReadDescription(facebook, true, true, false, true));
        assertFalse(ContentActionPolicy.shouldReadDescription(
                ContentFilter.YOUTUBE_SHORTS.packageName(), true, true, false, false));
        assertFalse(ContentActionPolicy.shouldReadDescription(null, true, true, false, false));
    }

    @Test
    public void monotonicFreshnessRejectsFutureOldAndInvalidTimes() {
        assertTrue(ContentActionPolicy.isFresh(100, 350, 250));
        assertFalse(ContentActionPolicy.isFresh(100, 351, 250));
        assertFalse(ContentActionPolicy.isFresh(101, 100, 250));
        assertFalse(ContentActionPolicy.isFresh(-1, 100, 250));
        assertFalse(ContentActionPolicy.isFresh(0, 1501, ContentActionPolicy.WORK_TIMEOUT_MS));
    }

    @Test
    public void bothMatchesMustHaveCurrentGenerationAndSamePackageWindowAndFilter() {
        ContentActionPolicy.Match first = match(8, 3, 1000);
        ContentActionPolicy.Match fresh = match(8, 3, 1100);
        assertTrue(ContentActionPolicy.sameTarget(first, fresh, 3, 1200));
        assertFalse(ContentActionPolicy.sameTarget(first, fresh, 4, 1200));
        assertFalse(ContentActionPolicy.sameTarget(first, match(9, 3, 1100), 3, 1200));
        assertFalse(ContentActionPolicy.sameTarget(first, new ContentActionPolicy.Match(
                "com.android.settings", 8, ContentFilter.YOUTUBE_SHORTS, 3, 1100), 3, 1200));
        assertFalse(ContentActionPolicy.sameTarget(first, new ContentActionPolicy.Match(
                "com.google.android.youtube", 8, ContentFilter.INSTAGRAM_REELS, 3, 1100), 3, 1200));
        assertFalse(ContentActionPolicy.sameTarget(first, null, 3, 1200));
        assertFalse(ContentActionPolicy.sameTarget(match(-1, 3, 1000), match(-1, 3, 1100),
                3, 1200));
    }

    @Test
    public void traversalAndFinalChecksCannotOutliveFreshEvidenceOrWorkDeadline() {
        assertFalse(ContentActionPolicy.sameTarget(match(8, 3, 0), match(8, 3, 1500),
                3, 1501));
        assertFalse(ContentActionPolicy.sameTarget(match(8, 3, 1000), match(8, 3, 1100),
                3, 1351));
        assertFalse(ContentActionPolicy.sameTarget(match(8, 3, 1000), match(8, 3, 1201),
                3, 1200));
    }

    @Test
    public void closedConsentDutyPauseAccessOrSafetyGatesNeverConsumeActionBudget() {
        ContentActionPolicy policy = new ContentActionPolicy();
        ContentActionPolicy.Match target = match(8, 3, 1000);
        assertFalse(policy.reserve(target, target, 3, 1000, false));
        assertTrue(policy.reserve(target, target, 3, 1000, true));
    }

    @Test
    public void unchangedFeedCannotCauseRepeatedBackEvenAfterCooldownOrBurstWindow() {
        ContentActionPolicy policy = new ContentActionPolicy();
        assertTrue(policy.reserve(match(8, 3, 1000), match(8, 3, 1000), 3, 1000, true));
        assertFalse(policy.reserve(match(8, 3, 4000), match(8, 3, 4000), 3, 4000, true));
        assertFalse(policy.reserve(match(8, 3, 62000), match(8, 3, 62000), 3, 62000, true));
        policy.observedClear("com.instagram.android", 8);
        assertFalse(policy.reserve(match(8, 3, 65000), match(8, 3, 65000), 3, 65000, true));
        policy.observedClear("com.google.android.youtube", 9);
        assertFalse(policy.reserve(match(8, 3, 68000), match(8, 3, 68000), 3, 68000, true));
        policy.observedClear("com.google.android.youtube", 8);
        assertTrue(policy.reserve(match(8, 3, 71000), match(8, 3, 71000), 3, 71000, true));
    }

    @Test
    public void globalCooldownAndRollingBurstFuseSurviveWindowSwitchesAndRearming() {
        ContentActionPolicy policy = new ContentActionPolicy();
        assertTrue(policy.reserve(match(1, 1, 1000), match(1, 1, 1000), 1, 1000, true));
        assertFalse(policy.reserve(match(2, 1, 2999), match(2, 1, 2999), 1, 2999, true));
        assertTrue(policy.reserve(match(2, 1, 3000), match(2, 1, 3000), 1, 3000, true));
        assertTrue(policy.reserve(match(3, 1, 5000), match(3, 1, 5000), 1, 5000, true));
        policy.observedClear("com.google.android.youtube", 1);
        assertFalse(policy.reserve(match(1, 2, 7000), match(1, 2, 7000), 2, 7000, true));
        assertFalse(policy.reserve(match(4, 2, 60999), match(4, 2, 60999), 2, 60999, true));
        assertTrue(policy.reserve(match(4, 2, 61000), match(4, 2, 61000), 2, 61000, true));
    }

    @Test
    public void preflightAvoidsMoreTraversalForLatchedCooldownOrStaleTargets() {
        ContentActionPolicy policy = new ContentActionPolicy();
        assertTrue(policy.canConsider(match(8, 3, 1000), 1000));
        assertTrue(policy.canConsider(match(8, 3, 1000), 1000));
        assertTrue(policy.reserve(match(8, 3, 1000), match(8, 3, 1000), 3, 1000, true));
        assertFalse(policy.canConsider(match(9, 3, 2000), 2000));
        assertFalse(policy.canConsider(match(8, 3, 4000), 4000));
        assertTrue(policy.canConsider(match(9, 3, 4000), 4000));
        assertFalse(policy.canConsider(match(9, 3, 4000), 5501));
        assertFalse(policy.canConsider(null, 4000));
    }

    @Test
    public void failedBackAttemptIsStillLatchedAndBudgetedRatherThanRetriedBlindly() {
        ContentActionPolicy policy = new ContentActionPolicy();
        ContentActionPolicy.Match target = match(8, 3, 1000);
        assertTrue(policy.reserve(target, target, 3, 1000, true));
        // Runtime calls Back only after reserve, regardless of whether Android returns success.
        assertFalse(policy.reserve(match(8, 3, 5000), match(8, 3, 5000), 3, 5000, true));
    }
}
