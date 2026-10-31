package com.dankhole.airlockandroid;

import java.util.List;

/** Pure recognition only; the platform owner validates freshness, gates and action budgets. */
final class ContentDetectionPolicy {
    /*
     * Copyright (C) 2026 Scrolless
     * Selectors adapted from Scrolless / Duarte Barbosa, GPL-3.0-or-later:
     * https://github.com/duartebarbosadev/Scrolless
     * commit 9bc0dd0edc022c7be2a8a9af1181064c6d73014c,
     * core/domain/src/main/java/com/scrolless/app/core/model/BlockableApp.kt.
     * Unlike upstream's generic Facebook fallback, only positive feed markers
     * below are accepted. Selectors are source-backed, not device-qualified.
     */
    static final int MAX_DESCRIPTION_LENGTH = 128;

    private ContentDetectionPolicy() {
    }

    /** Ephemeral selector evidence: no node text, content, URL or persistent tree. */
    static final class NodeEvidence {
        final String packageName;
        final String viewId;
        final String description;
        final boolean visible;
        final boolean positiveBounds;
        final boolean selected;

        NodeEvidence(
                String packageName,
                String viewId,
                String description,
                boolean visible,
                boolean positiveBounds,
                boolean selected
        ) {
            this.packageName = packageName;
            this.viewId = viewId;
            // Other packages never need descriptions. Retain only a bounded prefix.
            this.description = ContentFilter.FACEBOOK_REELS.packageName().equals(packageName)
                    && description != null
                    ? description.substring(0, Math.min(description.length(), MAX_DESCRIPTION_LENGTH))
                    : null;
            this.visible = visible;
            this.positiveBounds = positiveBounds;
            this.selected = selected;
        }
    }

    static boolean matches(ContentFilter filter, List<NodeEvidence> evidence) {
        if (filter == null || evidence == null) {
            return false;
        }
        for (NodeEvidence node : evidence) {
            if (node == null || !node.visible || !node.positiveBounds
                    || !filter.packageName().equals(node.packageName)) {
                continue;
            }
            if (filter == ContentFilter.FACEBOOK_REELS) {
                if (matchesFacebook(node)) {
                    return true;
                }
            } else if (viewId(filter).equals(node.viewId)) {
                return true;
            }
        }
        return false;
    }

    private static String viewId(ContentFilter filter) {
        switch (filter) {
            case YOUTUBE_SHORTS:
                return "com.google.android.youtube:id/reel_player_page_container";
            case INSTAGRAM_REELS:
                return "com.instagram.android:id/clips_viewer_view_pager";
            case INSTAGRAM_STORIES:
                return "com.instagram.android:id/reel_viewer_root";
            case SNAPCHAT_SPOTLIGHT:
                return "com.snapchat.android:id/spotlight_container";
            default:
                return "";
        }
    }

    private static boolean matchesFacebook(NodeEvidence node) {
        String description = node.description;
        return description != null && (
                description.equalsIgnoreCase("FbShortsComposerAttachmentComponentSpec_STICKER")
                        || description.equalsIgnoreCase("FbShortsComposerAttachmentComponentSpec_GIF")
                        || (node.selected && description.regionMatches(true, 0, "Reels,", 0, 6))
        );
    }
}
