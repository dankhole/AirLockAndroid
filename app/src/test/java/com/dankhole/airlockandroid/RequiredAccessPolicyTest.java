package com.dankhole.airlockandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequiredAccessPolicyTest {
    @Test
    public void countsReadyRequirements() {
        assertEquals(0, RequiredAccessPolicy.readyCount(false, false, false));
        assertEquals(1, RequiredAccessPolicy.readyCount(true, false, false));
        assertEquals(2, RequiredAccessPolicy.readyCount(true, true, false));
        assertEquals(3, RequiredAccessPolicy.readyCount(true, true, true));
    }

    @Test
    public void requiresEveryPermission() {
        assertFalse(RequiredAccessPolicy.isComplete(false, true, true));
        assertFalse(RequiredAccessPolicy.isComplete(true, false, true));
        assertFalse(RequiredAccessPolicy.isComplete(true, true, false));
        assertTrue(RequiredAccessPolicy.isComplete(true, true, true));
    }
}
