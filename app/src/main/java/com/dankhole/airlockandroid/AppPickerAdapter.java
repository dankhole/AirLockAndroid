package com.dankhole.airlockandroid;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AppPickerAdapter extends BaseAdapter {
    interface SelectionListener {
        void onSelectionChanged();
    }

    private static final int TYPE_APP = 0;
    private static final int TYPE_EMPTY = 1;

    private final Context context;
    private final List<AppCatalogLoader.Entry> allApps;
    private final List<AppCatalogLoader.Entry> visibleApps = new ArrayList<>();
    private final Set<String> selectedPackages;
    private final SelectionListener selectionListener;

    AppPickerAdapter(
            Context context,
            List<AppCatalogLoader.Entry> apps,
            Set<String> selectedPackages,
            SelectionListener selectionListener
    ) {
        this.context = context;
        this.allApps = new ArrayList<>(apps);
        this.selectedPackages = selectedPackages;
        this.selectionListener = selectionListener;
        visibleApps.addAll(apps);
    }

    void setQuery(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.US);
        visibleApps.clear();
        for (AppCatalogLoader.Entry app : allApps) {
            if (normalized.isEmpty()
                    || app.label.toLowerCase(Locale.US).contains(normalized)
                    || app.packageName.toLowerCase(Locale.US).contains(normalized)) {
                visibleApps.add(app);
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return visibleApps.isEmpty() ? 1 : visibleApps.size();
    }

    @Override
    public AppCatalogLoader.Entry getItem(int position) {
        return visibleApps.isEmpty() ? null : visibleApps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return visibleApps.isEmpty() ? TYPE_EMPTY : TYPE_APP;
    }

    @Override
    public boolean isEnabled(int position) {
        return !visibleApps.isEmpty();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (getItemViewType(position) == TYPE_EMPTY) {
            return emptyView(convertView);
        }

        RowHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof RowHolder)) {
            holder = createRow();
            convertView = holder.wrapper;
            convertView.setTag(holder);
        } else {
            holder = (RowHolder) convertView.getTag();
        }
        bindRow(holder, getItem(position));
        return convertView;
    }

    private View emptyView(View convertView) {
        TextView empty;
        if (convertView instanceof TextView) {
            empty = (TextView) convertView;
        } else {
            empty = UiStyle.statusText(context);
            empty.setPadding(
                    UiStyle.dp(context, 12),
                    UiStyle.dp(context, 16),
                    UiStyle.dp(context, 12),
                    UiStyle.dp(context, 16)
            );
            UiStyle.setStatus(empty, UiStyle.STATUS_NEUTRAL);
        }
        empty.setText(R.string.app_search_empty);
        return empty;
    }

    private RowHolder createRow() {
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setPadding(
                UiStyle.dp(context, 20),
                0,
                UiStyle.dp(context, 20),
                UiStyle.dp(context, 10)
        );

        LinearLayout row = new LinearLayout(context);
        row.setId(R.id.app_picker_row);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiStyle.dp(context, 88));
        row.setPadding(
                UiStyle.dp(context, 12),
                UiStyle.dp(context, 10),
                UiStyle.dp(context, 8),
                UiStyle.dp(context, 10)
        );
        row.setClickable(true);
        row.setFocusable(true);
        wrapper.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ImageView icon = new ImageView(context);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                UiStyle.dp(context, 44),
                UiStyle.dp(context, 44)
        );
        iconParams.setMargins(0, 0, UiStyle.dp(context, 12), 0);
        row.addView(icon, iconParams);

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );

        TextView label = UiStyle.sectionTitle(context, "");
        label.setTextSize(16);
        textColumn.addView(label);
        TextView detail = UiStyle.helperText(context, "");
        detail.setPadding(0, UiStyle.dp(context, 2), 0, 0);
        textColumn.addView(detail);
        TextView selectedBadge = UiStyle.badge(
                context,
                context.getString(R.string.app_selected_badge),
                UiStyle.STATUS_READY
        );
        selectedBadge.setVisibility(View.INVISIBLE);
        selectedBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        badgeParams.setMargins(0, UiStyle.dp(context, 6), 0, 0);
        textColumn.addView(selectedBadge, badgeParams);
        row.addView(textColumn, textParams);

        CheckBox checkBox = new CheckBox(context);
        checkBox.setId(R.id.app_picker_checkbox);
        checkBox.setMinWidth(UiStyle.dp(context, 48));
        checkBox.setMinHeight(UiStyle.dp(context, 48));
        checkBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        checkBox.setFocusable(false);
        row.addView(checkBox);

        RowHolder holder = new RowHolder(
                wrapper,
                row,
                icon,
                label,
                detail,
                selectedBadge,
                checkBox
        );
        row.setOnClickListener(view -> holder.checkBox.setChecked(!holder.checkBox.isChecked()));
        return holder;
    }

    private void bindRow(RowHolder holder, AppCatalogLoader.Entry app) {
        holder.boundApp = app;
        holder.icon.setImageDrawable(app.icon);
        holder.label.setText(app.label);
        if (app.isLimited()) {
            holder.detail.setText(context.getResources().getQuantityString(
                    R.plurals.app_daily_limit,
                    app.dailyLimitMinutes,
                    app.dailyLimitMinutes
            ));
            holder.detail.setTextColor(UiStyle.COLOR_READY);
        } else {
            holder.detail.setText(R.string.app_no_daily_limit);
            holder.detail.setTextColor(UiStyle.COLOR_TEXT_MUTED);
        }

        holder.checkBox.setOnCheckedChangeListener(null);
        boolean selected = selectedPackages.contains(app.packageName);
        holder.checkBox.setChecked(selected);
        holder.checkBox.setOnCheckedChangeListener((button, checked) -> {
            AppCatalogLoader.Entry boundApp = holder.boundApp;
            if (boundApp == null) {
                return;
            }
            if (checked) {
                selectedPackages.add(boundApp.packageName);
            } else {
                selectedPackages.remove(boundApp.packageName);
            }
            bindSelection(holder, boundApp, checked);
            selectionListener.onSelectionChanged();
        });
        bindSelection(holder, app, selected);
    }

    private void bindSelection(
            RowHolder holder,
            AppCatalogLoader.Entry app,
            boolean selected
    ) {
        UiStyle.styleSelectableRow(holder.row, selected);
        holder.selectedBadge.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        String limitDescription = app.isLimited()
                ? context.getResources().getQuantityString(
                        R.plurals.app_row_limit_description,
                        app.dailyLimitMinutes,
                        app.dailyLimitMinutes
                )
                : context.getString(R.string.app_row_no_limit_description);
        holder.row.setContentDescription(context.getString(
                R.string.app_row_content_description,
                app.label,
                limitDescription,
                context.getString(selected ? R.string.selected : R.string.not_selected)
        ));
        holder.row.setSelected(selected);
        holder.icon.setSelected(selected);
        holder.label.setSelected(selected);
        holder.detail.setSelected(selected);
    }

    private static final class RowHolder {
        final FrameLayout wrapper;
        final LinearLayout row;
        final ImageView icon;
        final TextView label;
        final TextView detail;
        final TextView selectedBadge;
        final CheckBox checkBox;
        AppCatalogLoader.Entry boundApp;

        RowHolder(
                FrameLayout wrapper,
                LinearLayout row,
                ImageView icon,
                TextView label,
                TextView detail,
                TextView selectedBadge,
                CheckBox checkBox
        ) {
            this.wrapper = wrapper;
            this.row = row;
            this.icon = icon;
            this.label = label;
            this.detail = detail;
            this.selectedBadge = selectedBadge;
            this.checkBox = checkBox;
        }
    }
}
