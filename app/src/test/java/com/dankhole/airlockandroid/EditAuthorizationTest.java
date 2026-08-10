package com.dankhole.airlockandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class EditAuthorizationTest {
    private static final long TOKEN_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    @Test
    public void issuedTokenCanBeConsumedOnlyOnce() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger sequence = new AtomicInteger();
        EditAuthorization.TokenStore store = new EditAuthorization.TokenStore(
                clock::get,
                () -> "token-" + sequence.incrementAndGet(),
                TOKEN_TTL_NANOS
        );

        String token = store.issue();

        assertTrue(store.consume(token));
        assertFalse(store.consume(token));
    }

    @Test
    public void expiredTokenIsRejected() {
        AtomicLong clock = new AtomicLong(100L);
        EditAuthorization.TokenStore store = new EditAuthorization.TokenStore(
                clock::get,
                () -> "token",
                TOKEN_TTL_NANOS
        );
        String token = store.issue();

        clock.addAndGet(TOKEN_TTL_NANOS + 1L);

        assertFalse(store.consume(token));
    }

    @Test
    public void newProcessStoreCannotConsumeOldToken() {
        AtomicLong clock = new AtomicLong(100L);
        EditAuthorization.TokenStore oldProcess = new EditAuthorization.TokenStore(
                clock::get,
                () -> "old-token",
                TOKEN_TTL_NANOS
        );
        String token = oldProcess.issue();
        EditAuthorization.TokenStore recreatedProcess = new EditAuthorization.TokenStore(
                clock::get,
                () -> "new-token",
                TOKEN_TTL_NANOS
        );

        assertFalse(recreatedProcess.consume(token));
    }

    @Test
    public void issuedTokensAreDistinct() {
        AtomicInteger sequence = new AtomicInteger();
        EditAuthorization.TokenStore store = new EditAuthorization.TokenStore(
                () -> 100L,
                () -> "token-" + sequence.incrementAndGet(),
                TOKEN_TTL_NANOS
        );

        assertNotEquals(store.issue(), store.issue());
    }

    @Test
    public void backgroundAuthorizationExpiresOnlyAfterGracePeriod() {
        long backgroundedAt = TimeUnit.SECONDS.toNanos(10);
        long grace = TimeUnit.SECONDS.toNanos(30);

        assertFalse(EditAuthorization.backgroundGraceExpired(
                backgroundedAt,
                backgroundedAt + grace,
                grace
        ));
        assertTrue(EditAuthorization.backgroundGraceExpired(
                backgroundedAt,
                backgroundedAt + grace + 1L,
                grace
        ));
    }

    @Test
    public void editorSessionSurvivesRecreationAndShortBackground() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger sequence = new AtomicInteger();
        EditAuthorization.SessionStore sessions = new EditAuthorization.SessionStore(
                clock::get,
                () -> "session-" + sequence.incrementAndGet(),
                TOKEN_TTL_NANOS
        );
        String sessionId = sessions.begin();

        assertTrue(sessions.isActive(sessionId));
        sessions.markBackgrounded(sessionId);
        clock.addAndGet(TOKEN_TTL_NANOS);

        assertTrue(sessions.resume(sessionId));
        assertTrue(sessions.isActive(sessionId));
    }

    @Test
    public void editorSessionExpiresAfterBackgroundGrace() {
        AtomicLong clock = new AtomicLong(100L);
        EditAuthorization.SessionStore sessions = new EditAuthorization.SessionStore(
                clock::get,
                () -> "session",
                TOKEN_TTL_NANOS
        );
        String sessionId = sessions.begin();
        sessions.markBackgrounded(sessionId);
        clock.addAndGet(TOKEN_TTL_NANOS + 1L);

        assertFalse(sessions.resume(sessionId));
        assertFalse(sessions.isActive(sessionId));
    }

    @Test
    public void recreatedProcessCannotRestoreEditorSession() {
        AtomicLong clock = new AtomicLong(100L);
        EditAuthorization.SessionStore oldProcess = new EditAuthorization.SessionStore(
                clock::get,
                () -> "old-session",
                TOKEN_TTL_NANOS
        );
        String sessionId = oldProcess.begin();
        EditAuthorization.SessionStore recreatedProcess = new EditAuthorization.SessionStore(
                clock::get,
                () -> "new-session",
                TOKEN_TTL_NANOS
        );

        assertFalse(recreatedProcess.isActive(sessionId));
        assertFalse(recreatedProcess.resume(sessionId));
    }
}
