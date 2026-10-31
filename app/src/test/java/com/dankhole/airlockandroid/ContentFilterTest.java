package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ContentFilterTest {
    @Test
    public void catalogIsFixedAndIdsRoundTripUniquely() {
        assertEquals(5, ContentFilter.values().length);
        Set<String> ids = new HashSet<>();
        for (ContentFilter filter : ContentFilter.values()) {
            assertTrue(ids.add(filter.id()));
            assertEquals(filter, ContentFilter.fromId(filter.id()));
        }
    }

    @Test
    public void unknownNullOrNoncanonicalIdsAreNotAccepted() {
        assertNull(ContentFilter.fromId(null));
        assertNull(ContentFilter.fromId(""));
        assertNull(ContentFilter.fromId("instagram_explore"));
        assertNull(ContentFilter.fromId("YOUTUBE_SHORTS"));
        assertNull(ContentFilter.fromId(" youtube_shorts"));
        assertNull(ContentFilter.fromId("youtube_shorts "));
        assertNull(ContentFilter.fromId("com.google.android.youtube"));
    }

    @Test
    public void catalogPackagesAreExactAndStoriesShareOnlyInstagramPackage() {
        assertEquals("com.google.android.youtube", ContentFilter.YOUTUBE_SHORTS.packageName());
        assertEquals("com.instagram.android", ContentFilter.INSTAGRAM_REELS.packageName());
        assertEquals("com.instagram.android", ContentFilter.INSTAGRAM_STORIES.packageName());
        assertEquals("com.facebook.katana", ContentFilter.FACEBOOK_REELS.packageName());
        assertEquals("com.snapchat.android", ContentFilter.SNAPCHAT_SPOTLIGHT.packageName());
    }
}
