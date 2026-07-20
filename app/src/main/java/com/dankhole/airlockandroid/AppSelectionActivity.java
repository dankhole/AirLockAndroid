package com.dankhole.airlockandroid;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectionActivity extends Activity {
    private final Set<String> selectedPackages = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedPackages.addAll(Preferences.selectedPackages(this));
        setContentView(buildContent());
    }

    private ScrollView buildContent() {
        int padding = dp(20);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Select Apps");
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView helper = new TextView(this);
        helper.setText("Choose launchable apps that should count toward the daily AirLock limit.");
        helper.setTextSize(15);
        helper.setPadding(0, dp(8), 0, dp(16));
        root.addView(helper);

        for (AppEntry app : loadLaunchableApps()) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(app.label + "\n" + app.packageName);
            checkBox.setTextSize(15);
            checkBox.setChecked(selectedPackages.contains(app.packageName));
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    selectedPackages.add(app.packageName);
                } else {
                    selectedPackages.remove(app.packageName);
                }
                Preferences.saveSelectedPackages(this, selectedPackages);
            });
            root.addView(checkBox);
        }

        Button doneButton = new Button(this);
        doneButton.setText("Done");
        doneButton.setAllCaps(false);
        doneButton.setOnClickListener(v -> {
            Preferences.saveSelectedPackages(this, selectedPackages);
            finish();
        });
        root.addView(doneButton);

        return scrollView;
    }

    private List<AppEntry> loadLaunchableApps() {
        PackageManager packageManager = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName) || !seenPackages.add(packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(packageManager);
            apps.add(new AppEntry(label == null ? packageName : label.toString(), packageName));
        }
        apps.sort(Comparator.comparing(app -> app.label.toLowerCase()));
        return apps;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
