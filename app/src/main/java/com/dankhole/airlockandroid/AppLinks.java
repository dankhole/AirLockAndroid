package com.dankhole.airlockandroid;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

final class AppLinks {
    static final String PRIVACY_POLICY_URL =
            "https://dankhole.github.io/AirLockAndroid/privacy/";

    private AppLinks() {
    }

    static void openPrivacyPolicy(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL));
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(context, R.string.privacy_policy_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
