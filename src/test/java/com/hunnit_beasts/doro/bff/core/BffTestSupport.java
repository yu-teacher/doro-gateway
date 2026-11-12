package com.hunnit_beasts.doro.bff.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** BFF 시험용 도구: 메모리 저장소, 가짜 IAM(JDK HttpServer), 조절 가능한 시계. */
final class BffTestSupport {

    static final String CLIENT_ID = "doro-test";
    static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    private BffTestSupport() {}

    static BffProperties props(int iamPort) {
        BffProperties p = new BffProperties();
        p.setClientId(CLIENT_ID);
        p.setAuthorizeUrl("https://doro.example/oauth2/authorize");
        p.setTokenUrl("http://127.0.0.1:" + iamPort + "/oauth2/token");
        p.setRevokeUrl("http://127.0.0.1:" + iamPort + "/oauth2/revoke");
        p.setRedirectUri("https://svc.example/api/v1/bff/callback");
        p.setSessionEncryptionKey(KEY);
        p.setCsrfHeader("X-Test-Csrf");
        p.setWebBasePath("/app");
        p.setHttpReadTimeout(Duration.ofSeconds(2));
        return p;
    }

    /** 시간을 직접 움직이는 시계. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-15T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** 가짜 IAM: 토큰 엔드포인트 응답을 시험이 정하고, 받은 요청을 기록한다. */
    static final class FakeIam implements AutoCloseable {
        private final HttpServer server;
        final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> revokeRequests = new CopyOnWriteArrayList<>();
        final AtomicInteger tokenCalls = new AtomicInteger();
        volatile int tokenStatus = 200;
        volatile String tokenBody = tokenJson("access-1", "refresh-1", 900);
        volatile int revokeStatus = 200;
        volatile long tokenDelayMillis = 0;

        FakeIam() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
            server.createContext("/oauth2/token", ex -> {
                tokenCalls.incrementAndGet();
                tokenRequests.add(form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                sleep(tokenDelayMillis);
                respond(ex, tokenStatus, tokenBody);
            });
            server.createContext("/oauth2/revoke", ex -> {
                revokeRequests.add(form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                respond(ex, revokeStatus, "{}");
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        static String tokenJson(String access, String refresh, int expiresIn) {
            return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}";
        }

        private static void sleep(long ms) {
            if (ms > 0) {
                try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }

        private static Map<String, String> form(String body) {
            Map<String, String> map = new java.util.LinkedHashMap<>();
            for (String pair : body.split("&")) {
                int i = pair.indexOf('=');
                if (i > 0) {
                    map.put(java.net.URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
            return map;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** 메모리 저장소. 호출을 기록해 "통째 저장 없이 필요한 컬럼만 바꾸는지"를 볼 수 있다. */
    static final class MemoryStores implements BffSessionStore, BffLoginAttemptStore {
        final Map<String, BffSession> sessions = new ConcurrentHashMap<>();
        final Map<String, BffLoginAttempt> attempts = new ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override public Optional<BffSession> find(String hash) { return Optional.ofNullable(sessions.get(hash)); }

        @Override public void create(BffSession s, String previous) {
            calls.add("create");
            sessions.put(s.sessionHash(), s);
            if (previous != null) {
                sessions.remove(previous);
            }
        }

        @Override public void updateTokens(String hash, String accessEnc, Instant accessExpires, String refreshEnc) {
            calls.add("updateTokens");
            sessions.computeIfPresent(hash, (k, s) -> new BffSession(s.sessionHash(), s.userId(), accessEnc, accessExpires,
                    refreshEnc, s.createdAt(), s.lastUsedAt(), s.expiresAt()));
        }

        @Override public void touchLastUsed(String hash, Instant now) {
            calls.add("touch");
            sessions.computeIfPresent(hash, (k, s) -> new BffSession(s.sessionHash(), s.userId(), s.accessTokenEnc(), s.accessExpiresAt(),
                    s.refreshTokenEnc(), s.createdAt(), now, s.expiresAt()));
        }

        @Override public void delete(String hash) { calls.add("delete"); sessions.remove(hash); }

        @Override public int deleteAllByUserId(UUID userId) {
            List<String> keys = new ArrayList<>();
            sessions.forEach((k, s) -> { if (s.userId().equals(userId)) keys.add(k); });
            keys.forEach(sessions::remove);
            return keys.size();
        }

        @Override public int deleteExpired(Instant now) {
            int before = sessions.size() + attempts.size();
            sessions.values().removeIf(s -> s.isExpired(now));
            attempts.values().removeIf(a -> a.isExpired(now));
            return before - sessions.size() - attempts.size();
        }

        @Override public void save(BffLoginAttempt a) { attempts.put(a.stateHash(), a); }

        @Override public Optional<BffLoginAttempt> consume(String stateHash) { return Optional.ofNullable(attempts.remove(stateHash)); }
    }
}
