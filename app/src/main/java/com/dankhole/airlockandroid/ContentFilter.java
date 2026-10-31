package com.dankhole.airlockandroid;

/** Fixed allowlist: stored IDs are independent of enum names and display copy. */
enum ContentFilter {
    YOUTUBE_SHORTS("youtube_shorts", "com.google.android.youtube"),
    INSTAGRAM_REELS("instagram_reels", "com.instagram.android"),
    FACEBOOK_REELS("facebook_reels", "com.facebook.katana"),
    SNAPCHAT_SPOTLIGHT("snapchat_spotlight", "com.snapchat.android"),
    INSTAGRAM_STORIES("instagram_stories", "com.instagram.android");

    private final String id;
    private final String packageName;

    ContentFilter(String id, String packageName) {
        this.id = id;
        this.packageName = packageName;
    }

    String id() {
        return id;
    }

    String packageName() {
        return packageName;
    }

    static ContentFilter fromId(String id) {
        for (ContentFilter filter : values()) {
            if (filter.id.equals(id)) {
                return filter;
            }
        }
        return null;
    }
}
