package com.dankhole.airlockandroid;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.service.autofill.AutofillService;
import android.telecom.TelecomManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CriticalApps {
    private static final long CACHE_DURATION_MS = 5 * 60_000L;
    private static final String CREDENTIAL_PROVIDER_SERVICE_INTERFACE =
            "android.service.credentials.CredentialProviderService";

    private static final FilterSnapshot UNAVAILABLE_FILTER_SNAPSHOT =
            new FilterSnapshot(Collections.emptySet(), false, 0L);
    private static final DiscoveryCache discoveryCache = new DiscoveryCache();

    private CriticalApps() {
    }

    static boolean isCritical(Context context, String packageName) {
        return packageName != null && packages(context).contains(packageName);
    }

    static boolean removeCritical(Context context, Set<String> packageNames) {
        return packageNames.removeAll(packages(context));
    }

    /** No discovery/Binder work: incomplete, cold or expired snapshots are not authority. */
    static boolean hasReliableSnapshot() {
        FilterSnapshot snapshot = discoveryCache.snapshot().filterSnapshot;
        return snapshot.isReliable(SystemClock.elapsedRealtime());
    }

    /** Content-only eligibility: no discovery, Binder calls or discovery-monitor wait. */
    static boolean canFilterFromSnapshot(String packageName) {
        FilterSnapshot snapshot = discoveryCache.snapshot().filterSnapshot;
        return snapshot.canFilter(packageName, SystemClock.elapsedRealtime());
    }

    static final class FilterSnapshot {
        private final Set<String> packages;
        private final boolean complete;
        private final long capturedAtMs;

        FilterSnapshot(Set<String> packages, boolean complete, long capturedAtMs) {
            this.packages = Collections.unmodifiableSet(new HashSet<>(packages));
            this.complete = complete;
            this.capturedAtMs = capturedAtMs;
        }

        boolean isReliable(long elapsedNowMs) {
            return isSnapshotReliable(complete, capturedAtMs, elapsedNowMs);
        }

        boolean canFilter(String packageName, long elapsedNowMs) {
            return packageName != null && !packageName.isEmpty() && isReliable(elapsedNowMs)
                    && !packages.contains(packageName);
        }
    }

    static boolean isSnapshotReliable(boolean complete, long capturedAtMs, long elapsedNowMs) {
        return complete && capturedAtMs >= 0L && elapsedNowMs >= capturedAtMs
                && elapsedNowMs - capturedAtMs < CACHE_DURATION_MS;
    }

    /**
     * Only reservation/publication synchronize; no discovery holds a reader lock.
     * An optional stuck Binder call therefore cannot stall legacy cached reads.
     */
    static void refresh(Context context) {
        long generation = discoveryCache.beginRefresh();
        PackageManager packageManager = context.getPackageManager();
        Set<String> packages = new HashSet<>();
        packages.add(context.getPackageName());
        packages.add("android");
        packages.add("com.android.settings");
        packages.add("com.android.systemui");
        packages.add("com.android.phone");
        packages.add("com.android.server.telecom");

        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        boolean complete = addActivityPackages(packageManager, home, packages);

        complete &= addActivityPackages(
                packageManager,
                new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                packages
        );
        complete &= addActivityPackages(
                packageManager,
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                packages
        );
        complete &= addActivityPackages(
                packageManager,
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                packages
        );
        complete &= addServicePackages(
                packageManager,
                new Intent(AutofillService.SERVICE_INTERFACE),
                packages
        );
        complete &= addServicePackages(
                packageManager,
                new Intent(CREDENTIAL_PROVIDER_SERVICE_INTERFACE),
                packages
        );
        complete &= addResolvedActivityPackage(
                packageManager,
                new Intent(Settings.ACTION_SETTINGS),
                packages
        );

        try {
            TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
            if (telecomManager != null) {
                addPackage(packages, telecomManager.getDefaultDialerPackage());
            }
        } catch (RuntimeException ignored) {
            complete = false;
            // Other independently resolved categories remain protected.
        }

        try {
            addPackage(packages, Telephony.Sms.getDefaultSmsPackage(context));
        } catch (RuntimeException ignored) {
            complete = false;
            // The SENDTO query above still protects visible messaging handlers.
        }

        discoveryCache.publish(generation, packages, complete, SystemClock.elapsedRealtime());
    }

    /** Immutable publication shared by legacy readers and content-only readers. */
    static final class PublishedSnapshot {
        final Set<String> packages;
        final long capturedAtMs;
        final FilterSnapshot filterSnapshot;

        private PublishedSnapshot(Set<String> packages, long capturedAtMs,
                FilterSnapshot filterSnapshot) {
            this.packages = packages;
            this.capturedAtMs = capturedAtMs;
            this.filterSnapshot = filterSnapshot;
        }
    }

    /** Pure publication coordination; platform discovery always runs outside its monitor. */
    static final class DiscoveryCache {
        private long generation;
        private volatile PublishedSnapshot published = new PublishedSnapshot(
                Collections.emptySet(), 0L, UNAVAILABLE_FILTER_SNAPSHOT);

        PublishedSnapshot snapshot() {
            return published;
        }

        synchronized long beginRefresh() {
            // Keep known critical packages available to legacy readers while making
            // content eligibility fail closed, even if discovery never returns.
            PublishedSnapshot previous = published;
            published = new PublishedSnapshot(previous.packages, previous.capturedAtMs,
                    UNAVAILABLE_FILTER_SNAPSHOT);
            return ++generation;
        }

        synchronized boolean publish(long completedGeneration, Set<String> discovered,
                boolean complete, long capturedAtMs) {
            if (completedGeneration != generation) {
                // A newer failed/in-flight lookup cannot erase a positive safety
                // discovery from another caller. Keep the restriction for legacy
                // app limits, but never let stale work grant content authority.
                if (!published.filterSnapshot.complete
                        && !published.packages.containsAll(discovered)) {
                    Set<String> conservative = new HashSet<>(published.packages);
                    conservative.addAll(discovered);
                    published = new PublishedSnapshot(Collections.unmodifiableSet(conservative),
                            published.capturedAtMs, UNAVAILABLE_FILTER_SNAPSHOT);
                }
                return false;
            }
            Set<String> packages = new HashSet<>(discovered);
            if (!complete) {
                // Failed lookups retain previously known critical handlers. Only
                // a complete later discovery can retire previous discoveries.
                packages.addAll(published.packages);
            }
            FilterSnapshot filters = new FilterSnapshot(packages, complete, capturedAtMs);
            published = new PublishedSnapshot(filters.packages, capturedAtMs, filters);
            return true;
        }
    }

    private static Set<String> packages(Context context) {
        PublishedSnapshot snapshot = discoveryCache.snapshot();
        long elapsedNow = SystemClock.elapsedRealtime();
        if (snapshot.packages.isEmpty()
                || elapsedNow - snapshot.capturedAtMs >= CACHE_DURATION_MS) {
            refresh(context.getApplicationContext());
        }
        return discoveryCache.snapshot().packages;
    }

    private static boolean addActivityPackages(
            PackageManager packageManager,
            Intent intent,
            Set<String> packages
    ) {
        try {
            List<ResolveInfo> resolved = packageManager.queryIntentActivities(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
            );
            for (ResolveInfo info : resolved) {
                if (info != null && info.activityInfo != null) {
                    addPackage(packages, info.activityInfo.packageName);
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            // Continue with the other independently resolved safety categories.
            return false;
        }
    }

    private static boolean addServicePackages(
            PackageManager packageManager,
            Intent intent,
            Set<String> packages
    ) {
        try {
            List<ResolveInfo> resolved = packageManager.queryIntentServices(intent, 0);
            for (ResolveInfo info : resolved) {
                if (info != null && info.serviceInfo != null) {
                    addPackage(packages, info.serviceInfo.packageName);
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            // Continue with the other independently resolved safety categories.
            return false;
        }
    }

    private static boolean addResolvedActivityPackage(
            PackageManager packageManager,
            Intent intent,
            Set<String> packages
    ) {
        try {
            ResolveInfo resolved = packageManager.resolveActivity(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
            );
            if (resolved != null && resolved.activityInfo != null) {
                addPackage(packages, resolved.activityInfo.packageName);
            }
            return true;
        } catch (RuntimeException ignored) {
            // Known package names still protect the platform Settings app.
            return false;
        }
    }

    private static void addPackage(Set<String> packages, String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            packages.add(packageName);
        }
    }
}
