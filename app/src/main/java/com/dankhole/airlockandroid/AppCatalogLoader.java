package com.dankhole.airlockandroid;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AppCatalogLoader {
    private AppCatalogLoader() {
    }

    static List<Entry> load(Context context) {
        Context appContext = context.getApplicationContext();
        CriticalApps.refresh(appContext);

        PackageManager packageManager = appContext.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        Set<String> limitedPackages = Preferences.selectedPackages(appContext);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        List<Entry> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (CriticalApps.isCritical(appContext, packageName)
                    || !seenPackages.add(packageName)) {
                continue;
            }

            CharSequence label = info.loadLabel(packageManager);
            Drawable icon = info.loadIcon(packageManager);
            if (icon == null) {
                icon = packageManager.getDefaultActivityIcon();
            }
            boolean limited = limitedPackages.contains(packageName);
            apps.add(new Entry(
                    label == null ? packageName : label.toString(),
                    packageName,
                    icon,
                    limited ? Preferences.dailyLimitMinutes(appContext, packageName) : 0
            ));
        }
        apps.sort(Comparator
                .comparing((Entry app) -> !app.isLimited())
                .thenComparing(app -> app.label.toLowerCase(Locale.US)));
        return apps;
    }

    static final class Entry {
        final String label;
        final String packageName;
        final Drawable icon;
        final int dailyLimitMinutes;

        Entry(String label, String packageName, Drawable icon, int dailyLimitMinutes) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
            this.dailyLimitMinutes = dailyLimitMinutes;
        }

        boolean isLimited() {
            return dailyLimitMinutes > 0;
        }
    }
}
