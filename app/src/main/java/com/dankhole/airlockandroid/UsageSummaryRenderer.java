package com.dankhole.airlockandroid;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class UsageSummaryRenderer {
    static final int DUTY_OFF = 0;
    static final int DUTY_ACTIVE = 1;
    static final int DUTY_PAUSED = 2;
    static final int DUTY_ATTENTION = 3;

    private final Context context;
    private final PackageManager packageManager;

    UsageSummaryRenderer(Context context) {
        this.context = context;
        packageManager = context.getPackageManager();
    }

    void render(
            LinearLayout usageList,
            TextView usageSummary,
            Set<String> trackedPackages,
            boolean hasUsageAccess,
            int dutyState
    ) {
        usageList.removeAllViews();
        if (trackedPackages.isEmpty()) {
            usageSummary.setText(R.string.usage_no_guarded_apps);
            UiStyle.setStatus(usageSummary, UiStyle.STATUS_NEUTRAL);
            return;
        }

        usageSummary.setText(!hasUsageAccess
                ? R.string.usage_summary_saved
                : dutyState == DUTY_OFF
                ? R.string.usage_summary_live_duty_off
                : dutyState == DUTY_PAUSED
                ? R.string.usage_summary_live_duty_paused
                : dutyState == DUTY_ATTENTION
                ? R.string.usage_summary_live_duty_attention
                : R.string.usage_summary_live);
        UiStyle.setStatus(
                usageSummary,
                !hasUsageAccess
                        ? UiStyle.STATUS_REQUIRED
                        : dutyState == DUTY_ACTIVE
                        ? UiStyle.STATUS_READY
                        : UiStyle.STATUS_WARNING
        );

        List<String> packages = new ArrayList<>(trackedPackages);
        packages.sort((left, right) -> appLabel(left).compareToIgnoreCase(appLabel(right)));
        for (String packageName : packages) {
            usageList.addView(usageRow(packageName, dutyState), UiStyle.fullWidth(context, 10));
        }
    }

    private LinearLayout usageRow(String packageName, int dutyState) {
        long usedMs = Preferences.getUsageTodayMs(context, packageName);
        int limitMinutes = Preferences.dailyLimitMinutes(context, packageName);
        long limitMs = limitMinutes * 60_000L;
        boolean overLimit = usedMs >= limitMs;
        long remainingMs = Math.max(0L, limitMs - usedMs);
        String label = appLabel(packageName);
        String usedDuration = formatDuration(usedMs);

        LinearLayout row = UiStyle.usageRow(context, overLimit);
        row.setId(R.id.usage_app_row);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiStyle.sectionTitle(context, label);
        title.setTextSize(16);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView status = UiStyle.badge(
                context,
                context.getString(overLimit
                        ? R.string.usage_badge_over_limit
                        : R.string.usage_badge_available),
                overLimit ? UiStyle.STATUS_REQUIRED : UiStyle.STATUS_READY
        );
        header.addView(status);
        row.addView(header, UiStyle.fullWidth(context, 8));

        TextView detail = UiStyle.bodyText(
                context,
                context.getResources().getQuantityString(
                        R.plurals.usage_row_detail,
                        limitMinutes,
                        usedDuration,
                        limitMinutes
                )
        );
        row.addView(detail, UiStyle.fullWidth(context, 8));

        ProgressBar progress = new ProgressBar(
                context,
                null,
                android.R.attr.progressBarStyleHorizontal
        );
        progress.setMax(100);
        progress.setProgress(usageProgress(usedMs, limitMs));
        progress.setContentDescription(context.getResources().getQuantityString(
                R.plurals.usage_progress_description,
                limitMinutes,
                usedDuration,
                limitMinutes,
                label
        ));
        progress.setProgressTintList(ColorStateList.valueOf(overLimit
                ? UiStyle.COLOR_DANGER
                : UiStyle.COLOR_READY));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(UiStyle.COLOR_OUTLINE));
        row.addView(progress, progressParams());

        TextView remaining = UiStyle.helperText(
                context,
                overLimit
                        ? context.getString(overLimitMessage(dutyState))
                        : context.getString(
                                R.string.usage_time_remaining,
                                formatDuration(remainingMs)
                        )
        );
        remaining.setId(R.id.usage_app_enforcement_status);
        remaining.setTextColor(overLimit ? UiStyle.COLOR_DANGER : UiStyle.COLOR_TEXT_MUTED);
        row.addView(remaining);
        return row;
    }

    private int overLimitMessage(int dutyState) {
        if (dutyState == DUTY_OFF) {
            return R.string.usage_limit_reached_duty_off;
        }
        if (dutyState == DUTY_PAUSED) {
            return R.string.usage_limit_reached_duty_paused;
        }
        if (dutyState == DUTY_ATTENTION) {
            return R.string.usage_limit_reached_duty_attention;
        }
        return R.string.usage_limit_reached;
    }

    private LinearLayout.LayoutParams progressParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiStyle.dp(context, 10)
        );
        params.setMargins(0, 0, 0, UiStyle.dp(context, 8));
        return params;
    }

    private int usageProgress(long usedMs, long limitMs) {
        if (usedMs <= 0L || limitMs <= 0L) {
            return 0;
        }
        int progress = (int) Math.min(100L, usedMs * 100L / limitMs);
        return Math.max(3, progress);
    }

    private String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return context.getString(R.string.usage_duration_zero);
        }
        if (durationMs < 60_000L) {
            return context.getString(R.string.usage_duration_under_minute);
        }
        long minutes = durationMs / 60_000L;
        if (minutes < 60L) {
            return context.getResources().getQuantityString(
                    R.plurals.usage_duration_minutes,
                    (int) minutes,
                    minutes
            );
        }
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        if (remainingMinutes == 0L) {
            return context.getResources().getQuantityString(
                    R.plurals.usage_duration_hours,
                    (int) hours,
                    hours
            );
        }
        return context.getString(
                R.string.usage_duration_hours_minutes,
                hours,
                remainingMinutes
        );
    }

    private String appLabel(String packageName) {
        try {
            CharSequence label = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
            );
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
