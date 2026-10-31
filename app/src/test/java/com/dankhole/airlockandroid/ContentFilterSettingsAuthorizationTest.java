package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContentFilterSettingsAuthorizationTest {
    @Test
    public void unknownSessionAndMissingPinIdentityCannotAuthorize() {
        ContentFilterSettingsActivity.SessionPinIdentities identities =
                new ContentFilterSettingsActivity.SessionPinIdentities();
        assertFalse(identities.matches("untrusted", "salt:hash"));
        identities.remember("session", "");
        assertFalse(identities.matches("session", ""));
    }

    @Test
    public void sameProcessIdentitySurvivesActivityRecreation() {
        ContentFilterSettingsActivity.SessionPinIdentities identities =
                new ContentFilterSettingsActivity.SessionPinIdentities();
        identities.remember("session", "salt:hash");
        assertTrue(identities.contains("session"));
        assertTrue(identities.matches("session", "salt:hash"));
        assertTrue(identities.matches("session", "salt:hash"));
    }

    @Test
    public void pinReplacementIrrevocablyInvalidatesSessionIdentity() {
        ContentFilterSettingsActivity.SessionPinIdentities identities =
                new ContentFilterSettingsActivity.SessionPinIdentities();
        identities.remember("session", "old-salt:old-hash");
        assertFalse(identities.matches("session", "new-salt:new-hash"));
        assertFalse(identities.matches("session", "old-salt:old-hash"));
    }

    @Test
    public void recreatedProcessCannotRestoreIdentityFromSavedSessionId() {
        ContentFilterSettingsActivity.SessionPinIdentities previousProcess =
                new ContentFilterSettingsActivity.SessionPinIdentities();
        previousProcess.remember("session", "salt:hash");
        ContentFilterSettingsActivity.SessionPinIdentities newProcess =
                new ContentFilterSettingsActivity.SessionPinIdentities();
        assertFalse(newProcess.contains("session"));
        assertFalse(newProcess.matches("session", "salt:hash"));
    }

    @Test
    public void finishingActivityRevokesItsIdentity() {
        ContentFilterSettingsActivity.SessionPinIdentities identities =
                new ContentFilterSettingsActivity.SessionPinIdentities();
        identities.remember("session", "salt:hash");
        identities.remove("session");
        assertFalse(identities.matches("session", "salt:hash"));
    }
}
