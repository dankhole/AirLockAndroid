package com.dankhole.airlockandroid;

import java.util.List;

/** Pure window-inventory checks; never fetch roots from unrelated application windows. */
final class ContentWindowPolicy {
    enum Kind { APPLICATION, INPUT_METHOD, SYSTEM, OTHER }

    static final class Window {
        final Kind kind;
        final boolean active;
        final boolean focused;
        final boolean pictureInPicture;
        final int layer;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Window(Kind kind, boolean active, boolean focused, int layer,
                int left, int top, int right, int bottom) {
            this(kind, active, focused, false, layer, left, top, right, bottom);
        }

        Window(Kind kind, boolean active, boolean focused, boolean pictureInPicture,
                int layer, int left, int top, int right, int bottom) {
            this.kind = kind;
            this.active = active;
            this.focused = focused;
            this.pictureInPicture = pictureInPicture;
            this.layer = layer;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    static final class Insets {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Insets(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /** Current visible status/navigation-bar insets, obtained from WindowManager, not titles. */
    static final class SystemBars {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final Insets status;
        final Insets navigation;

        SystemBars(int left, int top, int right, int bottom, Insets status, Insets navigation) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.status = status;
            this.navigation = navigation;
        }

        boolean isDecoration(Window window) {
            if (window.kind != Kind.SYSTEM || window.active || window.focused
                    || right <= left || bottom <= top) return false;
            return matches(window, status) || matches(window, navigation);
        }

        private boolean matches(Window window, Insets insets) {
            if (insets == null || insets.left < 0 || insets.top < 0
                    || insets.right < 0 || insets.bottom < 0) return false;
            // A missing/implausibly large inset cannot turn a shade or panel into decoration.
            int maximumThickness = Math.min(right - left, bottom - top) / 4;
            if (insets.left > maximumThickness || insets.top > maximumThickness
                    || insets.right > maximumThickness || insets.bottom > maximumThickness) return false;
            return (insets.top > 0 && sameBounds(window, left, top, right, top + insets.top))
                    || (insets.bottom > 0 && sameBounds(window,
                            left, bottom - insets.bottom, right, bottom))
                    || (insets.left > 0 && sameBounds(window, left, top, left + insets.left, bottom))
                    || (insets.right > 0 && sameBounds(window,
                            right - insets.right, top, right, bottom));
        }

        private static boolean sameBounds(Window window, int left, int top, int right, int bottom) {
            return window.left == left && window.top == top && window.right == right
                    && window.bottom == bottom;
        }
    }

    private ContentWindowPolicy() {
    }

    static int actionWindowIndex(List<Window> windows) {
        return actionWindowIndex(windows, null);
    }

    static int actionWindowIndex(List<Window> windows, SystemBars bars) {
        if (windows == null || windows.isEmpty()) return -1;
        int applications = 0;
        int target = -1;
        for (int index = 0; index < windows.size(); index++) {
            Window window = windows.get(index);
            if (window == null || window.kind == Kind.INPUT_METHOD || window.pictureInPicture) return -1;
            if (window.kind == Kind.APPLICATION) applications++;
            if (window.active || window.focused) {
                if (!window.active || !window.focused || window.kind != Kind.APPLICATION
                        || target >= 0) return -1;
                target = index;
            }
        }
        if (applications != 1 || target < 0) return -1;
        Window app = windows.get(target);
        if (app.right <= app.left || app.bottom <= app.top) return -1;
        for (int index = 0; index < windows.size(); index++) {
            if (index == target) continue;
            Window other = windows.get(index);
            if (other.layer >= app.layer
                    && (other.right <= other.left || other.bottom <= other.top)) return -1;
            if (other.layer >= app.layer && other.left < app.right && other.right > app.left
                    && other.top < app.bottom && other.bottom > app.top) {
                if (bars == null || !bars.isDecoration(other)) return -1;
                // A second surface covering a bar strip is not validated decoration.
                for (int previous = 0; previous < index; previous++) {
                    if (previous == target) continue;
                    Window candidate = windows.get(previous);
                    if (candidate.layer >= app.layer && candidate.left == other.left
                            && candidate.top == other.top && candidate.right == other.right
                            && candidate.bottom == other.bottom) return -1;
                }
            }
        }
        return target;
    }
}
