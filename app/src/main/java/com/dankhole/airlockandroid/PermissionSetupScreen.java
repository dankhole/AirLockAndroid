package com.dankhole.airlockandroid;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class PermissionSetupScreen {
    private final Context context;
    private final ScrollView root;
    private final TextView progress;
    private final AccessItem usageAccess;
    private final AccessItem overlayAccess;
    private final AccessItem notifications;

    PermissionSetupScreen(
            Context context,
            Runnable openUsageAccess,
            Runnable openOverlayAccess,
            Runnable openNotificationAccess
    ) {
        this.context = context;
        root = UiStyle.screenScroll(context);
        root.setId(R.id.permission_setup_scroll);

        LinearLayout content = UiStyle.screenRoot(context);
        UiStyle.applyScreenInsetsPadding(root, content, 20, 20, 20, 20);
        UiStyle.attachScreenContent(root, content);

        content.addView(
                UiStyle.screenTitle(context, context.getString(R.string.permission_setup_title)),
                UiStyle.fullWidth(context, 2)
        );
        content.addView(
                UiStyle.bodyText(context, context.getString(R.string.permission_setup_intro)),
                UiStyle.fullWidth(context, 18)
        );
        content.addView(new GooseMascotView(context), UiStyle.gooseBannerParams(context));

        progress = UiStyle.statusText(context);
        progress.setId(R.id.permission_setup_progress);
        progress.setTextSize(16);
        content.addView(progress, UiStyle.fullWidth(context, 16));

        usageAccess = accessItem(
                R.string.permission_usage_title,
                R.string.permission_usage_detail,
                R.string.open_usage_access,
                R.id.permission_usage_status,
                R.id.permission_usage_button,
                openUsageAccess
        );
        content.addView(usageAccess.card, UiStyle.fullWidth(context, 12));

        overlayAccess = accessItem(
                R.string.permission_overlay_title,
                R.string.permission_overlay_detail,
                R.string.open_overlay_access,
                R.id.permission_overlay_status,
                R.id.permission_overlay_button,
                openOverlayAccess
        );
        content.addView(overlayAccess.card, UiStyle.fullWidth(context, 12));

        notifications = accessItem(
                R.string.permission_notifications_title,
                R.string.permission_notifications_detail,
                R.string.allow_notifications,
                R.id.permission_notifications_status,
                R.id.permission_notifications_button,
                openNotificationAccess
        );
        content.addView(notifications.card, UiStyle.fullWidth(context, 12));

        content.addView(
                UiStyle.helperText(context, context.getString(R.string.privacy_local_summary)),
                UiStyle.fullWidth(context, 6)
        );
        Button privacyButton = UiStyle.quietButton(
                context,
                context.getString(R.string.open_privacy_policy)
        );
        privacyButton.setId(R.id.permission_privacy_button);
        privacyButton.setOnClickListener(view -> AppLinks.openPrivacyPolicy(context));
        content.addView(privacyButton, UiStyle.buttonParams(context));
    }

    ScrollView view() {
        return root;
    }

    void render(boolean hasUsageAccess, boolean hasOverlayAccess, boolean hasNotifications) {
        int readyCount = RequiredAccessPolicy.readyCount(
                hasUsageAccess,
                hasOverlayAccess,
                hasNotifications
        );
        boolean complete = readyCount == RequiredAccessPolicy.REQUIREMENT_COUNT;
        progress.setText(complete
                ? context.getString(R.string.permission_setup_complete)
                : context.getResources().getQuantityString(
                        R.plurals.permission_setup_progress,
                        readyCount,
                        readyCount,
                        RequiredAccessPolicy.REQUIREMENT_COUNT
                ));
        UiStyle.setStatus(
                progress,
                complete ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED
        );
        renderItem(usageAccess, hasUsageAccess);
        renderItem(overlayAccess, hasOverlayAccess);
        renderItem(notifications, hasNotifications);
    }

    private AccessItem accessItem(
            int titleResource,
            int detailResource,
            int actionResource,
            int statusId,
            int buttonId,
            Runnable action
    ) {
        LinearLayout card = UiStyle.card(context);
        card.addView(
                UiStyle.sectionTitle(context, context.getString(titleResource)),
                UiStyle.fullWidth(context, 8)
        );

        TextView status = UiStyle.statusText(context);
        status.setId(statusId);
        card.addView(status, UiStyle.fullWidth(context, 8));

        card.addView(
                UiStyle.bodyText(context, context.getString(detailResource)),
                UiStyle.fullWidth(context, 8)
        );

        Button button = UiStyle.primaryButton(context, context.getString(actionResource));
        button.setId(buttonId);
        button.setOnClickListener(view -> action.run());
        card.addView(button, UiStyle.buttonParams(context));
        return new AccessItem(card, status, button);
    }

    private void renderItem(AccessItem item, boolean ready) {
        item.status.setText(ready
                ? R.string.permission_setup_done
                : R.string.permission_setup_not_done);
        UiStyle.setStatus(
                item.status,
                ready ? UiStyle.STATUS_READY : UiStyle.STATUS_REQUIRED
        );
        item.action.setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    private static final class AccessItem {
        final LinearLayout card;
        final TextView status;
        final Button action;

        AccessItem(LinearLayout card, TextView status, Button action) {
            this.card = card;
            this.status = status;
            this.action = action;
        }
    }
}
