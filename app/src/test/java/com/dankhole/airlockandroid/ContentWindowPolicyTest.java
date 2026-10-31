package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ContentWindowPolicyTest {
    private static ContentWindowPolicy.Window app(boolean active, boolean focused) {
        return window(ContentWindowPolicy.Kind.APPLICATION, active, focused, 1, 0, 100, 1000, 1900);
    }

    private static ContentWindowPolicy.Window window(ContentWindowPolicy.Kind kind,
            boolean active, boolean focused, int layer, int left, int top, int right, int bottom) {
        return new ContentWindowPolicy.Window(kind, active, focused, layer,
                left, top, right, bottom);
    }

    @Test
    public void requiresExactlyOneUnambiguousActiveFocusedApplication() {
        assertEquals(0, ContentWindowPolicy.actionWindowIndex(Collections.singletonList(app(true, true))));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(null));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Collections.emptyList()));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Collections.singletonList(app(false, true))));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Collections.singletonList(app(true, false))));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Collections.singletonList(app(false, false))));
    }

    @Test
    public void rejectsSplitScreenPipAndDialogAmbiguityWithoutFetchingOtherRoots() {
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Arrays.asList(
                app(true, true), app(false, false))));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Arrays.asList(
                app(true, true), app(true, true))));
    }

    @Test
    public void rejectsImeEvenWhenItIsNotMarkedFocusedOrActive() {
        ContentWindowPolicy.Window keyboard = window(ContentWindowPolicy.Kind.INPUT_METHOD,
                false, false, 2, 0, 1200, 1000, 2000);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Arrays.asList(app(true, true), keyboard)));
    }

    @Test
    public void rejectsShadeSystemFocusAndOverlappingNonfocusableOverlays() {
        ContentWindowPolicy.Window shade = window(ContentWindowPolicy.Kind.OTHER,
                true, true, 3, 0, 0, 1000, 2000);
        ContentWindowPolicy.Window overlay = window(ContentWindowPolicy.Kind.OTHER,
                false, false, 3, 0, 500, 1000, 800);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Arrays.asList(app(true, true), shade)));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Arrays.asList(app(true, true), overlay)));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Collections.singletonList(shade)));
    }

    @Test
    public void permitsOnlyDisjointSystemDecorationsOrWindowsBehindTheTarget() {
        ContentWindowPolicy.Window statusBar = window(ContentWindowPolicy.Kind.OTHER,
                false, false, 3, 0, 0, 1000, 100);
        ContentWindowPolicy.Window navigationBar = window(ContentWindowPolicy.Kind.OTHER,
                false, false, 3, 0, 1900, 1000, 2000);
        ContentWindowPolicy.Window behind = window(ContentWindowPolicy.Kind.OTHER,
                false, false, 0, 0, 0, 1000, 2000);
        assertEquals(1, ContentWindowPolicy.actionWindowIndex(Arrays.asList(
                statusBar, app(true, true), navigationBar, behind)));
    }

    @Test
    public void rejectsUnknownBoundsForPotentiallyObscuringWindows() {
        ContentWindowPolicy.Window unknown = window(ContentWindowPolicy.Kind.OTHER,
                false, false, 3, 0, 0, 0, 0);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Arrays.asList(app(true, true), unknown)));
    }

    private static ContentWindowPolicy.SystemBars portraitBars() {
        return new ContentWindowPolicy.SystemBars(0, 0, 1000, 2000,
                new ContentWindowPolicy.Insets(0, 100, 0, 0),
                new ContentWindowPolicy.Insets(0, 0, 0, 100));
    }

    @Test
    public void fullDisplayEdgeToEdgeAppAllowsPreciselyValidatedPersistentSystemBars() {
        ContentWindowPolicy.Window fullDisplay = window(ContentWindowPolicy.Kind.APPLICATION,
                true, true, 1, 0, 0, 1000, 2000);
        ContentWindowPolicy.Window status = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 0, 0, 1000, 100);
        ContentWindowPolicy.Window navigation = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 0, 1900, 1000, 2000);
        assertEquals(1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(status, fullDisplay, navigation), portraitBars()));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(status, fullDisplay, navigation)));
    }

    @Test
    public void landscapeSideNavigationCanBeValidatedWithoutIgnoringArbitraryOverlays() {
        ContentWindowPolicy.SystemBars bars = new ContentWindowPolicy.SystemBars(0, 0, 3000, 1200,
                new ContentWindowPolicy.Insets(0, 60, 0, 0),
                new ContentWindowPolicy.Insets(0, 0, 90, 0));
        ContentWindowPolicy.Window fullDisplay = window(ContentWindowPolicy.Kind.APPLICATION,
                true, true, 1, 0, 0, 3000, 1200);
        ContentWindowPolicy.Window sideBar = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 2910, 0, 3000, 1200);
        assertEquals(0, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, sideBar), bars));
        ContentWindowPolicy.Window overlay = window(ContentWindowPolicy.Kind.OTHER,
                false, false, 3, 2910, 0, 3000, 1200);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, overlay), bars));
    }

    @Test
    public void barGeometryNeverExemptsFocusedShadeExpandedPanelOrIme() {
        ContentWindowPolicy.Window fullDisplay = window(ContentWindowPolicy.Kind.APPLICATION,
                true, true, 1, 0, 0, 1000, 2000);
        ContentWindowPolicy.Window focusedBar = window(ContentWindowPolicy.Kind.SYSTEM,
                true, true, 3, 0, 0, 1000, 100);
        ContentWindowPolicy.Window panel = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 0, 0, 1000, 600);
        ContentWindowPolicy.Window keyboard = window(ContentWindowPolicy.Kind.INPUT_METHOD,
                false, false, 3, 0, 1900, 1000, 2000);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, focusedBar), portraitBars()));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, panel), portraitBars()));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, keyboard), portraitBars()));
    }

    @Test
    public void barGeometryCannotAuthorizeMultipleApplicationWindowsOrPip() {
        ContentWindowPolicy.Window status = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 0, 0, 1000, 100);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(app(true, true), app(false, false), status), portraitBars()));
    }

    @Test
    public void additionalSystemOverlayCoveringAValidatedBarIsStillAmbiguous() {
        ContentWindowPolicy.Window fullDisplay = window(ContentWindowPolicy.Kind.APPLICATION,
                true, true, 1, 0, 0, 1000, 2000);
        ContentWindowPolicy.Window bar = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 0, 0, 1000, 100);
        ContentWindowPolicy.Window overlay = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 2, 0, 0, 1000, 100);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, bar, overlay), portraitBars()));
    }

    @Test
    public void aSingleReportedPipWindowCannotAuthorizeBack() {
        ContentWindowPolicy.Window pip = new ContentWindowPolicy.Window(
                ContentWindowPolicy.Kind.APPLICATION, true, true, true,
                1, 0, 0, 1000, 2000);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Collections.singletonList(pip), portraitBars()));
    }

    @Test
    public void unknownHiddenOrImplausibleBarInsetsCannotExemptAnOverlappingWindow() {
        ContentWindowPolicy.Window fullDisplay = window(ContentWindowPolicy.Kind.APPLICATION,
                true, true, 1, 0, 0, 1000, 2000);
        ContentWindowPolicy.Window status = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 0, 0, 1000, 100);
        ContentWindowPolicy.SystemBars missing = new ContentWindowPolicy.SystemBars(
                0, 0, 1000, 2000, null, null);
        ContentWindowPolicy.SystemBars hidden = new ContentWindowPolicy.SystemBars(
                0, 0, 1000, 2000, new ContentWindowPolicy.Insets(0, 0, 0, 0), null);
        ContentWindowPolicy.SystemBars invalid = new ContentWindowPolicy.SystemBars(
                0, 0, 1000, 2000, new ContentWindowPolicy.Insets(-1, 100, 0, 0), null);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, status), missing));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, status), hidden));
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, status), invalid));
        ContentWindowPolicy.Window panel = window(ContentWindowPolicy.Kind.SYSTEM,
                false, false, 3, 0, 0, 1000, 1000);
        ContentWindowPolicy.SystemBars huge = new ContentWindowPolicy.SystemBars(
                0, 0, 1000, 2000, new ContentWindowPolicy.Insets(0, 1000, 0, 0), null);
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(
                Arrays.asList(fullDisplay, panel), huge));
    }

    @Test
    public void rejectsMissingOrEmptyApplicationBounds() {
        assertEquals(-1, ContentWindowPolicy.actionWindowIndex(Collections.singletonList(
                window(ContentWindowPolicy.Kind.APPLICATION, true, true, 1, 0, 0, 0, 2000))));
    }
}
