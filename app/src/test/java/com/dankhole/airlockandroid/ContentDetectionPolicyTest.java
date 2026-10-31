package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

public class ContentDetectionPolicyTest {
    private static final ContentFilter[] ID_FILTERS = {
            ContentFilter.YOUTUBE_SHORTS,
            ContentFilter.INSTAGRAM_REELS,
            ContentFilter.INSTAGRAM_STORIES,
            ContentFilter.SNAPCHAT_SPOTLIGHT
    };
    private static final String[] IDS = {
            "com.google.android.youtube:id/reel_player_page_container",
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/reel_viewer_root",
            "com.snapchat.android:id/spotlight_container"
    };

    @Test
    public void exactActivePlayerIdsMatchWithoutDescriptionOrSelection() {
        for (int i = 0; i < ID_FILTERS.length; i++) {
            assertTrue(matches(ID_FILTERS[i], node(ID_FILTERS[i], IDS[i], null, true, true, false)));
        }
    }

    @Test
    public void hiddenAndZeroBoundsNodesNeverMatch() {
        for (int i = 0; i < ID_FILTERS.length; i++) {
            assertFalse(matches(ID_FILTERS[i], node(ID_FILTERS[i], IDS[i], null, false, true, true)));
            assertFalse(matches(ID_FILTERS[i], node(ID_FILTERS[i], IDS[i], null, true, false, true)));
        }
        for (String marker : facebookMarkers()) {
            assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, null, marker, false, true, true)));
            assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, null, marker, true, false, true)));
        }
    }

    @Test
    public void matchingIdsFromAnotherPackageNeverMatch() {
        for (int i = 0; i < ID_FILTERS.length; i++) {
            for (String wrongPackage : Arrays.asList(null, "", "android", "com.example.clone",
                    ID_FILTERS[i].packageName() + ".beta", ID_FILTERS[i].packageName().toUpperCase(Locale.ROOT))) {
                assertFalse(matches(ID_FILTERS[i], new ContentDetectionPolicy.NodeEvidence(
                        wrongPackage, IDS[i], null, true, true, true)));
            }
        }
    }

    @Test
    public void viewIdsMustBeExactAndFullyQualified() {
        for (int i = 0; i < ID_FILTERS.length; i++) {
            for (String wrongId : Arrays.asList(null, "", IDS[i] + "_preview", " " + IDS[i],
                    IDS[i].toUpperCase(Locale.ROOT), IDS[i].substring(IDS[i].indexOf(":id/") + 4))) {
                assertFalse(matches(ID_FILTERS[i], node(ID_FILTERS[i], wrongId, null, true, true, true)));
            }
        }
    }

    @Test
    public void reelsAndStoriesCannotEnableEachOther() {
        assertFalse(matches(ContentFilter.INSTAGRAM_REELS,
                node(ContentFilter.INSTAGRAM_REELS, IDS[2], null, true, true, true)));
        assertFalse(matches(ContentFilter.INSTAGRAM_STORIES,
                node(ContentFilter.INSTAGRAM_STORIES, IDS[1], null, true, true, true)));
    }

    @Test
    public void navigationTabsSearchTilesAndGenericPlayersAreNotEvidence() {
        for (ContentFilter filter : ContentFilter.values()) {
            for (String suffix : Arrays.asList("reels_tab", "shorts_tab", "spotlight_tab", "search",
                    "search_results", "recommendation_tile", "video_player", "recycler_view",
                    "clips_tab", "profile", "direct_inbox", "story_tray")) {
                assertFalse(matches(filter, node(filter, filter.packageName() + ":id/" + suffix,
                        "Reels", true, true, true)));
            }
        }
    }

    @Test
    public void genericFacebookRecyclerViewButtonAndSurfaceViewDoNotMatch() {
        for (String id : Arrays.asList("androidx.recyclerview.widget.RecyclerView",
                "android.widget.Button", "android.view.SurfaceView", "com.facebook.katana:id/recycler_view")) {
            assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, id, null, true, true, true)));
        }
    }

    @Test
    public void facebookExactComposerMarkersMatchCaseInsensitively() {
        for (String marker : Arrays.asList("FbShortsComposerAttachmentComponentSpec_STICKER",
                "FbShortsComposerAttachmentComponentSpec_GIF")) {
            assertTrue(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, null, marker, true, true, false)));
            assertTrue(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, null, marker.toLowerCase(Locale.ROOT), true, true, false)));
            assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, null, marker + " preview", true, true, true)));
        }
    }

    @Test
    public void facebookReelsDescriptionRequiresSelectionAndCommaPrefix() {
        assertTrue(matches(ContentFilter.FACEBOOK_REELS,
                node(ContentFilter.FACEBOOK_REELS, null, "Reels, selected", true, true, true)));
        assertTrue(matches(ContentFilter.FACEBOOK_REELS,
                node(ContentFilter.FACEBOOK_REELS, null, "rEeLs, 1 of 5", true, true, true)));
        assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                node(ContentFilter.FACEBOOK_REELS, null, "Reels, tab", true, true, false)));
        for (String nearMiss : Arrays.asList("Reels", "Reels selected", " Reels, selected",
                "Watch Reels, selected", "Reels preview", "Reels , selected", "")) {
            assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, null, nearMiss, true, true, true)));
        }
    }

    @Test
    public void facebookDescriptionsNeverApplyAcrossPackagesOrSurfaces() {
        for (String marker : facebookMarkers()) {
            for (ContentFilter filter : ID_FILTERS) {
                assertFalse(matches(filter, node(filter, null, marker, true, true, true)));
                assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                        node(filter, null, marker, true, true, true)));
            }
        }
    }

    @Test
    public void resourceIdsAreLocaleIndependentButUnverifiedTranslatedFacebookLabelsDoNotMatch() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (int i = 0; i < ID_FILTERS.length; i++) {
                assertTrue(matches(ID_FILTERS[i], node(ID_FILTERS[i], IDS[i], null, true, true, false)));
            }
            assertTrue(matches(ContentFilter.FACEBOOK_REELS,
                    node(ContentFilter.FACEBOOK_REELS, null, "REELS, seleccionado", true, true, true)));
            assertTrue(matches(ContentFilter.FACEBOOK_REELS, node(ContentFilter.FACEBOOK_REELS,
                    null, "FBSHORTSCOMPOSERATTACHMENTCOMPONENTSPEC_GIF", true, true, false)));
            for (String label : Arrays.asList("Vidéos courtes, sélectionné", "Kurzvideos, ausgewählt",
                    "リール, 選択済み", "ريلز, محدد")) {
                assertFalse(matches(ContentFilter.FACEBOOK_REELS,
                        node(ContentFilter.FACEBOOK_REELS, null, label, true, true, true)));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void nullAndEmptyEvidenceNeverMatch() {
        assertFalse(ContentDetectionPolicy.matches(null, Collections.emptyList()));
        assertFalse(ContentDetectionPolicy.matches(ContentFilter.YOUTUBE_SHORTS, null));
        for (ContentFilter filter : ContentFilter.values()) {
            assertFalse(ContentDetectionPolicy.matches(filter, Collections.emptyList()));
            assertFalse(ContentDetectionPolicy.matches(filter, Collections.singletonList(null)));
            assertFalse(matches(filter, node(filter, null, null, true, true, true)));
        }
    }

    @Test
    public void unrelatedAndNullNodesDoNotObscureAValidMatch() {
        assertTrue(ContentDetectionPolicy.matches(ContentFilter.YOUTUBE_SHORTS, Arrays.asList(
                null, node(ContentFilter.YOUTUBE_SHORTS, "search", null, true, true, true),
                node(ContentFilter.YOUTUBE_SHORTS, IDS[0], null, true, true, false))));
    }

    @Test
    public void descriptionRetentionIsBoundedAndAbsentOutsideFacebook() {
        String longDescription = "Reels," + String.join("", Collections.nCopies(1_000, "x"));
        ContentDetectionPolicy.NodeEvidence facebook = node(ContentFilter.FACEBOOK_REELS,
                null, longDescription, true, true, true);
        assertEquals(ContentDetectionPolicy.MAX_DESCRIPTION_LENGTH, facebook.description.length());
        assertTrue(matches(ContentFilter.FACEBOOK_REELS, facebook));
        assertNull(node(ContentFilter.YOUTUBE_SHORTS, IDS[0], longDescription,
                true, true, false).description);
    }

    private static String[] facebookMarkers() {
        return new String[] {"FbShortsComposerAttachmentComponentSpec_STICKER",
                "FbShortsComposerAttachmentComponentSpec_GIF", "Reels, selected"};
    }

    private static ContentDetectionPolicy.NodeEvidence node(ContentFilter filter, String viewId,
            String description, boolean visible, boolean positiveBounds, boolean selected) {
        return new ContentDetectionPolicy.NodeEvidence(filter.packageName(), viewId, description,
                visible, positiveBounds, selected);
    }

    private static boolean matches(ContentFilter filter, ContentDetectionPolicy.NodeEvidence node) {
        return ContentDetectionPolicy.matches(filter, Collections.singletonList(node));
    }
}
