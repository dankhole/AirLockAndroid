package com.dankhole.airlockandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class EmergencyCodeTest {
    private static final String HASHES_KEY = "emergency_code_hashes";

    @Test
    public void replacementCreatesThreeCodesAndRevokesPreviousBatch() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        AtomicInteger sequence = new AtomicInteger(1);

        List<String> firstBatch = Preferences.replaceEmergencyCodes(
                preferences,
                () -> String.format("%08d", sequence.getAndIncrement())
        );
        List<String> replacementBatch = Preferences.replaceEmergencyCodes(
                preferences,
                () -> String.format("%08d", sequence.getAndIncrement())
        );

        assertEquals(Preferences.EMERGENCY_CODE_COUNT, firstBatch.size());
        assertEquals(3, replacementBatch.size());
        Set<String> storedHashes = preferences.getStringSet(
                HASHES_KEY,
                Collections.emptySet()
        );
        assertEquals(3, storedHashes.size());
        for (String oldCode : firstBatch) {
            assertFalse(Preferences.consumeEmergencyCode(preferences, oldCode, 1_000L));
        }
        assertTrue(Preferences.consumeEmergencyCode(
                preferences,
                replacementBatch.get(0),
                1_000L
        ));
        assertEquals(2, preferences.getStringSet(
                HASHES_KEY,
                Collections.emptySet()
        ).size());
    }
}
