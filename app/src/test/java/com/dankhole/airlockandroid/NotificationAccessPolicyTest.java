package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationAccessPolicyTest {
    @Test
    public void visibleOnlyWhenEveryNotificationLayerAllowsIt() {
        assertTrue(NotificationAccessPolicy.isVisible(true, true, false, false));
        assertFalse(NotificationAccessPolicy.isVisible(false, true, false, false));
        assertFalse(NotificationAccessPolicy.isVisible(true, false, false, false));
        assertFalse(NotificationAccessPolicy.isVisible(true, true, true, false));
        assertFalse(NotificationAccessPolicy.isVisible(true, true, false, true));
    }
}
