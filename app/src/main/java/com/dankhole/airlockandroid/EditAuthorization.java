package com.dankhole.airlockandroid;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class EditAuthorization {
    private static final long TOKEN_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long BACKGROUND_GRACE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TokenStore TOKENS = new TokenStore(
            System::nanoTime,
            EditAuthorization::randomToken,
            TOKEN_TTL_NANOS
    );
    private static final SessionStore SESSIONS = new SessionStore(
            System::nanoTime,
            EditAuthorization::randomToken,
            BACKGROUND_GRACE_NANOS
    );

    private EditAuthorization() {
    }

    static String issue() {
        return TOKENS.issue();
    }

    static boolean consume(String token) {
        return TOKENS.consume(token);
    }

    static String consumeAndBeginSession(String token) {
        return consume(token) ? SESSIONS.begin() : "";
    }

    static boolean restoreSession(String sessionId) {
        return SESSIONS.isActive(sessionId);
    }

    static boolean resumeSession(String sessionId) {
        return SESSIONS.resume(sessionId);
    }

    static void markSessionBackgrounded(String sessionId) {
        SESSIONS.markBackgrounded(sessionId);
    }

    static void revokeSession(String sessionId) {
        SESSIONS.revoke(sessionId);
    }

    static boolean backgroundGraceExpired(long backgroundedAtNanos) {
        return backgroundGraceExpired(
                backgroundedAtNanos,
                System.nanoTime(),
                BACKGROUND_GRACE_NANOS
        );
    }

    static boolean backgroundGraceExpired(
            long backgroundedAtNanos,
            long nowNanos,
            long graceNanos
    ) {
        return backgroundedAtNanos > 0L
                && nowNanos - backgroundedAtNanos > Math.max(0L, graceNanos);
    }

    private static String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }

    static final class TokenStore {
        private final LongSupplier clock;
        private final Supplier<String> tokenSupplier;
        private final long tokenTtlNanos;
        private final Map<String, Long> expirations = new HashMap<>();

        TokenStore(LongSupplier clock, Supplier<String> tokenSupplier, long tokenTtlNanos) {
            this.clock = clock;
            this.tokenSupplier = tokenSupplier;
            this.tokenTtlNanos = Math.max(1L, tokenTtlNanos);
        }

        synchronized String issue() {
            long nowNanos = clock.getAsLong();
            removeExpired(nowNanos);
            String token;
            do {
                token = tokenSupplier.get();
            } while (token == null || token.isEmpty() || expirations.containsKey(token));
            expirations.put(token, nowNanos + tokenTtlNanos);
            return token;
        }

        synchronized boolean consume(String token) {
            if (token == null || token.isEmpty()) {
                return false;
            }
            Long expirationNanos = expirations.remove(token);
            return expirationNanos != null && expirationNanos >= clock.getAsLong();
        }

        private void removeExpired(long nowNanos) {
            Iterator<Map.Entry<String, Long>> iterator = expirations.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue() < nowNanos) {
                    iterator.remove();
                }
            }
        }
    }

    static final class SessionStore {
        private static final long FOREGROUND = Long.MIN_VALUE;

        private final LongSupplier clock;
        private final Supplier<String> tokenSupplier;
        private final long backgroundGraceNanos;
        private final Map<String, Long> backgroundedAtBySession = new HashMap<>();

        SessionStore(
                LongSupplier clock,
                Supplier<String> tokenSupplier,
                long backgroundGraceNanos
        ) {
            this.clock = clock;
            this.tokenSupplier = tokenSupplier;
            this.backgroundGraceNanos = Math.max(0L, backgroundGraceNanos);
        }

        synchronized String begin() {
            String sessionId;
            do {
                sessionId = tokenSupplier.get();
            } while (sessionId == null
                    || sessionId.isEmpty()
                    || backgroundedAtBySession.containsKey(sessionId));
            backgroundedAtBySession.put(sessionId, FOREGROUND);
            return sessionId;
        }

        synchronized boolean isActive(String sessionId) {
            return sessionId != null
                    && !sessionId.isEmpty()
                    && backgroundedAtBySession.containsKey(sessionId);
        }

        synchronized boolean resume(String sessionId) {
            Long backgroundedAt = backgroundedAtBySession.get(sessionId);
            if (backgroundedAt == null) {
                return false;
            }
            if (backgroundedAt != FOREGROUND
                    && clock.getAsLong() - backgroundedAt > backgroundGraceNanos) {
                backgroundedAtBySession.remove(sessionId);
                return false;
            }
            backgroundedAtBySession.put(sessionId, FOREGROUND);
            return true;
        }

        synchronized void markBackgrounded(String sessionId) {
            if (isActive(sessionId)) {
                backgroundedAtBySession.put(sessionId, clock.getAsLong());
            }
        }

        synchronized void revoke(String sessionId) {
            if (sessionId != null) {
                backgroundedAtBySession.remove(sessionId);
            }
        }
    }
}
