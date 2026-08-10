package com.dankhole.airlockandroid;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/** Debug-only deterministic host for the production blocker view. */
public final class DebugBlockerActivity extends Activity {
    private BlockerOverlayController controller;
    private String packageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiStyle.applyWindow(this);
        packageName = getIntent().getStringExtra("target_package");
        if (packageName == null || packageName.trim().isEmpty()) {
            packageName = "com.google.android.youtube";
        }
        String appLabel = getIntent().getStringExtra("app_label");
        if (appLabel == null || appLabel.trim().isEmpty()) {
            appLabel = "Smoke Test App";
        }
        int usedMinutes = Math.max(0, getIntent().getIntExtra("used_minutes", 2));
        int limitMinutes = Math.max(1, getIntent().getIntExtra("limit_minutes", 1));

        controller = new BlockerOverlayController(this, new DebugListener());
        View blocker = controller.build(packageName, appLabel, usedMinutes, limitMinutes);
        setContentView(blocker);
        controller.onAttached(blocker);
    }

    private final class DebugListener implements BlockerOverlayController.Listener {
        @Override
        public void onFormInteraction() {
        }

        @Override
        public boolean requestApproval(String requestedPackage, int requestedMinutes) {
            return !Preferences.createRequestCode(
                    DebugBlockerActivity.this,
                    requestedPackage,
                    requestedMinutes
            ).isEmpty();
        }

        @Override
        public int redeemApprovalCode(String requestedPackage, String enteredCode) {
            return Preferences.redeemApprovalCodeAndGrantMinutes(
                    DebugBlockerActivity.this,
                    requestedPackage,
                    enteredCode
            );
        }

        @Override
        public boolean consumeEmergencyCode(String enteredCode) {
            return Preferences.consumeEmergencyCode(DebugBlockerActivity.this, enteredCode);
        }

        @Override
        public void onLeaveApp() {
            finish();
        }

        @Override
        public void onUnlockCelebrationStarted() {
        }

        @Override
        public void onUnlockCelebrationFinished(int approvedMinutes) {
        }

        @Override
        public void onEmergencyCelebrationStarted() {
        }

        @Override
        public void onEmergencyCelebrationFinished() {
        }
    }
}
