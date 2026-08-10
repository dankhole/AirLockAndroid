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

    private static Set<String> cachedPackages = Collections.emptySet();
    private static long cachedAtElapsedMs;

    private CriticalApps() {
    }

    static boolean isCritical(Context context, String packageName) {
        return packageName != null && packages(context).contains(packageName);
    }

    static boolean removeCritical(Context context, Set<String> packageNames) {
        return packageNames.removeAll(packages(context));
    }

    static synchronized void refresh(Context context) {
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
        addActivityPackages(packageManager, home, packages);

        addActivityPackages(
                packageManager,
                new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                packages
        );
        addActivityPackages(
                packageManager,
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                packages
        );
        addActivityPackages(
                packageManager,
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                packages
        );
        addServicePackages(
                packageManager,
                new Intent(AutofillService.SERVICE_INTERFACE),
                packages
        );
        addServicePackages(
                packageManager,
                new Intent(CREDENTIAL_PROVIDER_SERVICE_INTERFACE),
                packages
        );
        addResolvedActivityPackage(
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
            // Other independently resolved categories remain protected.
        }

        try {
            addPackage(packages, Telephony.Sms.getDefaultSmsPackage(context));
        } catch (RuntimeException ignored) {
            // The SENDTO query above still protects visible messaging handlers.
        }

        cachedPackages = Collections.unmodifiableSet(packages);
        cachedAtElapsedMs = SystemClock.elapsedRealtime();
    }

    private static synchronized Set<String> packages(Context context) {
        long elapsedNow = SystemClock.elapsedRealtime();
        if (cachedPackages.isEmpty()
                || elapsedNow - cachedAtElapsedMs >= CACHE_DURATION_MS) {
            refresh(context.getApplicationContext());
        }
        return cachedPackages;
    }

    private static void addActivityPackages(
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
        } catch (RuntimeException ignored) {
            // Continue with the other independently resolved safety categories.
        }
    }

    private static void addServicePackages(
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
        } catch (RuntimeException ignored) {
            // Continue with the other independently resolved safety categories.
        }
    }

    private static void addResolvedActivityPackage(
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
        } catch (RuntimeException ignored) {
            // Known package names still protect the platform Settings app.
        }
    }

    private static void addPackage(Set<String> packages, String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            packages.add(packageName);
        }
    }
}
