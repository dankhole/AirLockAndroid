package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class CriticalAppsTest {
    @Test
    public void coldOrIncompleteSnapshotIsNeverReliableEvenWhenFresh() {
        assertFalse(CriticalApps.isSnapshotReliable(false, 0L, 0L));
        assertFalse(CriticalApps.isSnapshotReliable(false, 1_000L, 1_001L));
        assertFalse(CriticalApps.isSnapshotReliable(false, 1_000L, 300_000L));
    }

    @Test
    public void completeSnapshotIsReliableOnlyBeforeFiveMinuteExpiry() {
        assertTrue(CriticalApps.isSnapshotReliable(true, 0L, 0L));
        assertTrue(CriticalApps.isSnapshotReliable(true, 1_000L, 1_000L));
        assertTrue(CriticalApps.isSnapshotReliable(true, 1_000L, 300_999L));
        assertFalse(CriticalApps.isSnapshotReliable(true, 1_000L, 301_000L));
        assertFalse(CriticalApps.isSnapshotReliable(true, 1_000L, 301_001L));
    }

    @Test
    public void completeFreshSnapshotExcludesCriticalPackages() {
        CriticalApps.FilterSnapshot snapshot = new CriticalApps.FilterSnapshot(
                Collections.singleton("com.instagram.android"), true, 1_000L);
        assertTrue(snapshot.isReliable(1_001L));
        assertFalse(snapshot.canFilter("com.instagram.android", 1_001L));
        assertTrue(snapshot.canFilter("com.google.android.youtube", 1_001L));
    }

    @Test
    public void coldAndIncompleteSnapshotsCannotAuthorizeFiltering() {
        CriticalApps.FilterSnapshot cold = new CriticalApps.FilterSnapshot(
                Collections.emptySet(), false, 0L);
        CriticalApps.FilterSnapshot incomplete = new CriticalApps.FilterSnapshot(
                Collections.singleton("com.android.settings"), false, 1_000L);
        assertFalse(cold.canFilter("com.google.android.youtube", 0L));
        assertFalse(incomplete.canFilter("com.google.android.youtube", 1_001L));
    }

    @Test
    public void staleOrRegressedSnapshotCannotAuthorizeFiltering() {
        CriticalApps.FilterSnapshot snapshot = new CriticalApps.FilterSnapshot(
                Collections.emptySet(), true, 1_000L);
        assertTrue(snapshot.canFilter("com.google.android.youtube", 300_999L));
        assertFalse(snapshot.canFilter("com.google.android.youtube", 301_000L));
        assertFalse(snapshot.canFilter("com.google.android.youtube", 999L));
    }

    @Test
    public void snapshotMembershipCannotBeChangedByItsSourceCollection() {
        Set<String> packages = new HashSet<>(Collections.singleton("com.instagram.android"));
        CriticalApps.FilterSnapshot snapshot = new CriticalApps.FilterSnapshot(packages, true, 1_000L);
        packages.clear();
        packages.add("com.google.android.youtube");
        assertFalse(snapshot.canFilter("com.instagram.android", 1_001L));
        assertTrue(snapshot.canFilter("com.google.android.youtube", 1_001L));
    }

    @Test
    public void missingPackageIdentityCannotAuthorizeFiltering() {
        CriticalApps.FilterSnapshot snapshot = new CriticalApps.FilterSnapshot(
                Collections.emptySet(), true, 1_000L);
        assertFalse(snapshot.canFilter(null, 1_001L));
        assertFalse(snapshot.canFilter("", 1_001L));
    }

    @Test
    public void refreshInvalidationPreservesLegacySnapshotWithoutContentAuthority() {
        CriticalApps.DiscoveryCache cache = new CriticalApps.DiscoveryCache();
        assertTrue(cache.snapshot().packages.isEmpty());
        assertFalse(cache.snapshot().filterSnapshot.isReliable(0L));
        assertTrue(cache.publish(cache.beginRefresh(), Collections.singleton("camera"), true, 100L));
        assertTrue(cache.snapshot().filterSnapshot.isReliable(101L));

        cache.beginRefresh();

        assertEquals(Collections.singleton("camera"), cache.snapshot().packages);
        assertEquals(100L, cache.snapshot().capturedAtMs);
        assertFalse(cache.snapshot().filterSnapshot.isReliable(101L));
    }

    @Test
    public void incompleteDiscoveryRetainsKnownCriticalPackagesUntilCompleteReplacement() {
        CriticalApps.DiscoveryCache cache = new CriticalApps.DiscoveryCache();
        cache.publish(cache.beginRefresh(), Collections.singleton("old.camera"), true, 100L);
        cache.publish(cache.beginRefresh(), Collections.singleton("new.camera"), false, 200L);
        assertTrue(cache.snapshot().packages.contains("old.camera"));
        assertTrue(cache.snapshot().packages.contains("new.camera"));
        assertFalse(cache.snapshot().filterSnapshot.canFilter("other", 201L));

        cache.publish(cache.beginRefresh(), Collections.singleton("new.camera"), true, 300L);
        assertEquals(Collections.singleton("new.camera"), cache.snapshot().packages);
        assertTrue(cache.snapshot().filterSnapshot.canFilter("old.camera", 301L));
    }

    @Test
    public void oldCompleteDiscoveryCannotOverrideNewInvalidationOrFailure() {
        CriticalApps.DiscoveryCache cache = new CriticalApps.DiscoveryCache();
        cache.publish(cache.beginRefresh(), Collections.singleton("known.camera"), true, 100L);
        long oldComplete = cache.beginRefresh();
        long newFailed = cache.beginRefresh();
        assertFalse(cache.publish(oldComplete, Collections.emptySet(), true, 201L));
        assertFalse(cache.snapshot().filterSnapshot.isReliable(201L));
        assertEquals(Collections.singleton("known.camera"), cache.snapshot().packages);

        assertTrue(cache.publish(newFailed, Collections.singleton("discovered.camera"), false, 202L));
        assertFalse(cache.publish(oldComplete, Collections.emptySet(), true, 203L));
        assertFalse(cache.snapshot().filterSnapshot.isReliable(203L));
        assertTrue(cache.snapshot().packages.contains("known.camera"));
        assertTrue(cache.snapshot().packages.contains("discovered.camera"));
        assertEquals(202L, cache.snapshot().capturedAtMs);
    }

    @Test
    public void overlappingDiscoveryRetainsNewCriticalHandlerWhileLatestLookupIsUncertain() {
        CriticalApps.DiscoveryCache cache = new CriticalApps.DiscoveryCache();
        cache.publish(cache.beginRefresh(), Collections.singleton("known.camera"), true, 100L);
        long monitorDiscovery = cache.beginRefresh();
        long optionalDiscovery = cache.beginRefresh(); // May be stuck in Binder.

        assertFalse(cache.publish(monitorDiscovery, Collections.singleton("new.camera"), true, 200L));
        assertTrue(cache.snapshot().packages.contains("new.camera"));
        assertTrue(cache.snapshot().packages.contains("known.camera"));
        assertEquals(100L, cache.snapshot().capturedAtMs);
        assertFalse(cache.snapshot().filterSnapshot.isReliable(201L));

        cache.publish(optionalDiscovery, Collections.emptySet(), false, 300L);
        assertTrue(cache.snapshot().packages.contains("new.camera"));
        assertFalse(cache.snapshot().filterSnapshot.canFilter("other", 301L));
    }

    @Test
    public void olderIncompleteDiscoveryCannotReplaceNewerCompleteSnapshot() {
        CriticalApps.DiscoveryCache cache = new CriticalApps.DiscoveryCache();
        long oldFailed = cache.beginRefresh();
        long newComplete = cache.beginRefresh();
        assertTrue(cache.publish(newComplete, Collections.singleton("current.camera"), true, 200L));
        assertFalse(cache.publish(oldFailed, Collections.singleton("retired.camera"), false, 300L));
        assertEquals(Collections.singleton("current.camera"), cache.snapshot().packages);
        assertEquals(200L, cache.snapshot().capturedAtMs);
        assertTrue(cache.snapshot().filterSnapshot.isReliable(301L));
    }

    @Test
    public void publicationsDefensivelyCopyPackagesAndExposeOnlyImmutableSets() {
        CriticalApps.DiscoveryCache cache = new CriticalApps.DiscoveryCache();
        Set<String> discovered = new HashSet<>(Collections.singleton("camera"));
        cache.publish(cache.beginRefresh(), discovered, true, 100L);
        discovered.clear();
        assertEquals(Collections.singleton("camera"), cache.snapshot().packages);
        assertThrows(UnsupportedOperationException.class, () -> cache.snapshot().packages.clear());
    }

    @Test
    public void stalledOldDiscoveryDoesNotHoldPublicationOrReaderMonitor() throws Exception {
        CriticalApps.DiscoveryCache cache = new CriticalApps.DiscoveryCache();
        cache.publish(cache.beginRefresh(), Collections.singleton("known.camera"), true, 100L);
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        CountDownLatch finishDiscovery = new CountDownLatch(1);
        FutureTask<Boolean> oldDiscovery = new FutureTask<>(() -> {
            long generation = cache.beginRefresh();
            discoveryStarted.countDown();
            finishDiscovery.await(); // Represents an independently stalled platform lookup.
            return cache.publish(generation, Collections.emptySet(), true, 300L);
        });
        Thread discoveryThread = new Thread(oldDiscovery, "critical-apps-publication-test");
        discoveryThread.start();
        try {
            assertTrue(discoveryStarted.await(2L, TimeUnit.SECONDS));
            assertEquals(Collections.singleton("known.camera"), cache.snapshot().packages);
            assertFalse(cache.snapshot().filterSnapshot.isReliable(101L));
            assertTrue(cache.publish(cache.beginRefresh(), Collections.singleton("new.camera"),
                    false, 200L));
            finishDiscovery.countDown();
            assertFalse(oldDiscovery.get(2L, TimeUnit.SECONDS));
            assertTrue(cache.snapshot().packages.contains("known.camera"));
            assertTrue(cache.snapshot().packages.contains("new.camera"));
            assertFalse(cache.snapshot().filterSnapshot.isReliable(301L));
        } finally {
            finishDiscovery.countDown();
            discoveryThread.interrupt();
            discoveryThread.join(2_000L);
        }
        assertFalse(discoveryThread.isAlive());
    }

    @Test
    public void invalidOrRegressedClockCannotMakeSnapshotReliable() {
        assertFalse(CriticalApps.isSnapshotReliable(true, -1L, 0L));
        assertFalse(CriticalApps.isSnapshotReliable(true, 100L, 99L));
        assertFalse(CriticalApps.isSnapshotReliable(true, Long.MIN_VALUE, Long.MAX_VALUE));
        assertFalse(CriticalApps.isSnapshotReliable(true, 0L, Long.MAX_VALUE));
    }
}
