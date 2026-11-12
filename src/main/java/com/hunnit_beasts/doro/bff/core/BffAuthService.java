package com.hunnit_beasts.doro.bff.core;

import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BFF 로그인: Doro OAuth(인가 코드 + PKCE)로 로그인하고, 토큰은 서버에만 두며 브라우저에는 세션 쿠키만 준다.
 * IAM 호출은 저장소 호출(트랜잭션) 밖에서 하고, 저장소 메서드는 각각 짧고 원자적이다.
 */
public class BffAuthService {

    private static final Logger log = LoggerFactory.getLogger(BffAuthService.class);

    /** 로그인에 실패했다(만료·재사용된 state, 코드 교환 거부, 검증 실패). 사유는 호출자에게 자세히 알리지 않는다. */
    public static class LoginFailedException extends RuntimeException {
        public LoginFailedException(String message) {
            super(message);
        }
    }

    public record LoginResult(String sessionCookieValue, String returnPath) {}

    /** @param browserNonce 로그인을 시작한 브라우저에 쿠키로 심을 값(원문은 서버에 저장하지 않는다) */
    public record StartedLogin(String authorizeUrl, String browserNonce) {}

    private static final int RANDOM_BYTES = 32;
    private static final int LOCK_STRIPES = 64;
    /** last_used_at 갱신은 요청마다 쓰지 않고 이 간격으로만 한다. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_RETURN_PATH_LENGTH = 500;

    private final BffProperties props;
    private final BffOAuthClient oauth;
    private final BffCrypto crypto;
    private final BffSessionStore sessions;
    private final BffLoginAttemptStore attempts;
    private final DoroTokenVerifier verifier;
    private final BffUserHook userHook;
    private final Clock clock;

    private final SecureRandom random = new SecureRandom();
    /** 같은 세션의 동시 갱신을 직렬화한다. 리프레시 토큰은 회전되므로 두 요청이 같은 토큰을 쓰면 IAM 이 탈취로 판단한다. */
    private final ReentrantLock[] refreshLocks = newLocks();

    public BffAuthService(BffProperties props, BffOAuthClient oauth, BffCrypto crypto, BffSessionStore sessions,
                          BffLoginAttemptStore attempts, DoroTokenVerifier verifier, BffUserHook userHook, Clock clock) {
        this.props = props;
        this.oauth = oauth;
        this.crypto = crypto;
        this.sessions = sessions;
        this.attempts = attempts;
        this.verifier = verifier;
        this.userHook = userHook;
        this.clock = clock;
    }

    private static ReentrantLock[] newLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    // ------------------------------------------------------------------ 로그인 시작

    /** 인가 요청 주소를 만든다. state 와 PKCE verifier 는 서버에 보관하고, 브라우저에는 state 와 challenge 만 간다. */
    public StartedLogin startLogin(String returnPath) {
        String state = randomToken();
        String verifier = randomToken();
        // 이 로그인을 시작한 브라우저에만 알려 주는 값. 콜백이 같은 브라우저에서 오는지 확인하는 데 쓴다.
        String browserNonce = randomToken();
        Instant now = clock.instant();
        attempts.save(new BffLoginAttempt(sha256Hex(state), crypto.encrypt(verifier), resolveReturnPath(returnPath),
                sha256Hex(browserNonce), now, now.plus(props.getLoginAttemptTtl())));
        return new StartedLogin(oauth.buildAuthorizeUrl(state, codeChallenge(verifier)), browserNonce);
    }

    /**
     * 요청받은 복귀 경로를 검증해 저장할 최종 공개 경로로 만든다. 검증을 통과하지 못하면 웹의 첫 화면이다.
     * 앱 내부 경로 모드(returnPathRelativeToWebBase)에서는 검증을 통과한 경로 앞에 웹 기준 경로를 붙인다(설정에서 온 신뢰할 수 있는 값).
     */
    private String resolveReturnPath(String requested) {
        String valid = safeReturnPath(requested, null);
        if (valid == null) {
            return props.webPath("/");
        }
        return props.isReturnPathRelativeToWebBase() ? props.webPath(valid) : valid;
    }

    // ------------------------------------------------------------------ 콜백

    /**
     * 인가 코드를 토큰으로 교환하고 세션을 만든다.
     *
     * @param previousCookie 이미 가진 세션 쿠키(있으면 새 로그인 때 폐기해 세션 고정을 막는다)
     * @param browserNonce   콜백을 연 브라우저가 가진 '로그인 진행 중' 쿠키 값. 로그인을 시작한 브라우저와 같은지 대조한다
     */
    public LoginResult completeLogin(String code, String state, String previousCookie, String browserNonce) {
        if (isBlank(code) || isBlank(state)) {
            throw new LoginFailedException("code/state 누락");
        }
        // state 는 한 번만 쓴다: 실제로 지운 요청만 통과한다.
        BffLoginAttempt attempt = attempts.consume(sha256Hex(state)).orElse(null);
        if (attempt == null || attempt.isExpired(clock.instant())) {
            throw new LoginFailedException("알 수 없거나 만료되었거나 이미 사용된 state");
        }
        // 로그인 CSRF 방지: 공격자가 자기 계정으로 시작해 인증한 로그인의 콜백 주소를 피해자가 열게 해도, 피해자 브라우저에는
        // 그 로그인을 시작할 때 심은 쿠키가 없으므로 여기서 거부된다(코드 교환도 하지 않는다).
        if (isBlank(browserNonce) || attempt.browserHash() == null
                || !MessageDigest.isEqual(sha256Hex(browserNonce).getBytes(StandardCharsets.UTF_8),
                        attempt.browserHash().getBytes(StandardCharsets.UTF_8))) {
            throw new LoginFailedException("로그인을 시작한 브라우저가 아님");
        }

        BffOAuthClient.TokenSet tokens;
        try {
            tokens = oauth.exchangeCode(code, crypto.decrypt(attempt.codeVerifierEnc()));
        } catch (BffOAuthClient.RejectedException e) {
            throw new LoginFailedException("코드 교환 거부: " + e.getMessage());
        } catch (BffOAuthClient.UnavailableException e) {
            throw new LoginFailedException("코드 교환 중 IAM 에 닿지 못함: " + e.getMessage());
        }

        DoroUser user = verifyAccessToken(tokens.accessToken());
        if (user == null || !user.isAuthenticated() || !props.getClientId().equals(user.clientId())) {
            throw new LoginFailedException("이 클라이언트의 유효한 액세스 토큰이 아님");
        }
        userHook.onLogin(user);

        String cookieValue = randomToken();
        Instant now = clock.instant();
        sessions.create(new BffSession(sha256Hex(cookieValue), user.userId(),
                crypto.encrypt(tokens.accessToken()), tokens.accessExpiresAt(),
                crypto.encrypt(tokens.refreshToken()), now, now, now.plus(props.getSessionTtl())),
                isBlank(previousCookie) ? null : sha256Hex(previousCookie));
        log.info("BFF login completed: userId={}", user.userId());
        return new LoginResult(cookieValue, attempt.returnPath());
    }

    // ------------------------------------------------------------------ 요청 인증

    /** 인증된 세션의 사용자와, 그 사용자의 (복호화한) 액세스 토큰. 토큰은 게이트웨이가 백엔드 요청에 Bearer 로 붙일 때 쓴다. */
    public record Authenticated(DoroUser user, String accessToken) {}

    /** 세션 쿠키를 사용자로 바꾼다. 만료·폐기·검증 실패면 비어 있다. 액세스 토큰이 곧 만료되면 먼저 갱신한다. */
    public Optional<DoroUser> authenticate(String cookieValue) {
        return authenticateWithToken(cookieValue).map(Authenticated::user);
    }

    /** {@link #authenticate} 와 같되 액세스 토큰도 돌려준다. */
    public Optional<Authenticated> authenticateWithToken(String cookieValue) {
        if (isBlank(cookieValue)) {
            return Optional.empty();
        }
        String hash = sha256Hex(cookieValue);
        BffSession session = sessions.find(hash).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (session.isExpired(now)) {
            sessions.delete(hash);
            return Optional.empty();
        }

        boolean refreshSkippedBecauseIamIsDown = false;
        if (needsRefresh(session, now)) {
            RefreshOutcome outcome = refreshUnderLock(hash);
            if (outcome.session() == null) {
                return Optional.empty();
            }
            session = outcome.session();
            refreshSkippedBecauseIamIsDown = outcome.iamUnavailable();
        }

        String accessToken;
        try {
            accessToken = crypto.decrypt(session.accessTokenEnc());
        } catch (IllegalStateException e) {
            log.error("BFF session token could not be decrypted (encryption key changed or data corrupted); dropping the session", e);
            sessions.delete(hash);
            return Optional.empty();
        }

        DoroUser user = verifyAccessToken(accessToken);
        if (user == null || !user.isAuthenticated() || !props.getClientId().equals(user.clientId())
                || !user.userId().equals(session.userId())) {
            // 갱신해야 하는데 IAM 이 응답하지 않아 만료된 토큰이 그대로 남은 경우: 세션이 폐기된 게 아니다.
            // 지우면 IAM 이 잠깐 죽은 사이 모든 사용자가 영구히 로그아웃되므로, 이번 요청만 익명으로 두고 세션은 남긴다.
            if (refreshSkippedBecauseIamIsDown && !session.accessExpiresAt().isAfter(now)) {
                log.warn("BFF session kept while IAM is unavailable (access token expired, refresh skipped)");
                return Optional.empty();
            }
            // IAM 세션이 폐기됐거나(다른 곳에서 로그아웃) 토큰이 맞지 않는다: 이 세션은 더 쓸 수 없다.
            sessions.delete(hash);
            return Optional.empty();
        }
        touchIfStale(session, now);
        return Optional.of(new Authenticated(user, accessToken));
    }

    private boolean needsRefresh(BffSession session, Instant now) {
        return !session.accessExpiresAt().minus(props.getRefreshMargin()).isAfter(now);
    }

    /** 같은 세션의 동시 갱신 중 하나만 IAM 을 호출한다. 나머지는 기다렸다가 갱신된 토큰을 읽는다. */
    private RefreshOutcome refreshUnderLock(String hash) {
        ReentrantLock lock = refreshLocks[Math.floorMod(hash.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            BffSession current = sessions.find(hash).orElse(null);
            if (current == null) {
                return new RefreshOutcome(null, false);
            }
            if (!needsRefresh(current, clock.instant())) {
                return new RefreshOutcome(current, false); // 기다리는 동안 다른 요청이 이미 갱신했다
            }
            BffOAuthClient.TokenSet tokens;
            try {
                tokens = oauth.refresh(crypto.decrypt(current.refreshTokenEnc()));
            } catch (BffOAuthClient.RejectedException e) {
                log.info("BFF session refresh rejected by IAM; ending the session: {}", e.getMessage());
                sessions.delete(hash);
                return new RefreshOutcome(null, false);
            } catch (BffOAuthClient.UnavailableException e) {
                log.warn("BFF session refresh skipped (IAM unavailable): {}", e.getMessage());
                // 세션은 유지한다. 만료된 액세스 토큰은 아래 검증에서 걸러져 이번 요청만 익명이 된다.
                return new RefreshOutcome(current, true);
            } catch (IllegalStateException e) {
                log.error("BFF refresh token could not be decrypted; dropping the session", e);
                sessions.delete(hash);
                return new RefreshOutcome(null, false);
            }
            String accessEnc = crypto.encrypt(tokens.accessToken());
            String refreshEnc = crypto.encrypt(tokens.refreshToken());
            sessions.updateTokens(hash, accessEnc, tokens.accessExpiresAt(), refreshEnc);
            return new RefreshOutcome(new BffSession(current.sessionHash(), current.userId(), accessEnc, tokens.accessExpiresAt(),
                    refreshEnc, current.createdAt(), current.lastUsedAt(), current.expiresAt()), false);
        } finally {
            lock.unlock();
        }
    }

    /** 갱신 결과. session 이 null 이면 세션이 없어졌거나 폐기된 것, iamUnavailable 이면 IAM 이 응답하지 않아 갱신을 건너뛴 것이다. */
    private record RefreshOutcome(BffSession session, boolean iamUnavailable) {}

    private void touchIfStale(BffSession session, Instant now) {
        if (session.lastUsedAt().plus(TOUCH_INTERVAL).isAfter(now)) {
            return;
        }
        sessions.touchLastUsed(session.sessionHash(), now);
    }

    private DoroUser verifyAccessToken(String accessToken) {
        try {
            return verifier.verify(accessToken);
        } catch (RuntimeException e) {
            log.warn("BFF access token rejected: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 로그아웃

    /** 세션을 지우고 IAM 세션도 끝낸다(RFC 7009). IAM 호출이 실패해도 로컬 로그아웃은 항상 완료한다. */
    public void logout(String cookieValue) {
        if (isBlank(cookieValue)) {
            return;
        }
        String hash = sha256Hex(cookieValue);
        sessions.find(hash).ifPresent(session -> {
            try {
                oauth.revoke(crypto.decrypt(session.refreshTokenEnc()));
            } catch (RuntimeException e) {
                log.warn("IAM session revocation failed during logout (local session is removed anyway): {}", e.getMessage());
            }
        });
        sessions.delete(hash);
    }

    // ------------------------------------------------------------------ 보조

    /** 로그인 후 돌아갈 곳. 사이트 안의 경로만 허용한다 (오픈 리다이렉트 방지). 허용되지 않으면 "/" 로 보낸다. */
    public static String safeReturnPath(String path) {
        return safeReturnPath(path, "/");
    }

    /**
     * 위와 같되, 허용되지 않는 값은 fallback(웹이 하위 경로에 마운트되면 그 경로의 첫 화면)으로 보낸다.
     * fallback 은 설정에서 온 신뢰할 수 있는 값이다.
     */
    public static String safeReturnPath(String path, String fallback) {
        if (path == null || path.isBlank() || path.length() > MAX_RETURN_PATH_LENGTH
                || !path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.contains("://")) {
            return fallback;
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                return fallback;
            }
        }
        // 콜백에서 이 값으로 302 Location 을 만든다. URI 문법에 맞지 않는 값(공백, |, ", {, 잘못된 %XX 등)이 통과하면 그 단계에서
        // 예외가 나, 세션은 이미 저장됐는데 쿠키 없이 오류가 된다. 그런 값은 첫 화면으로 보낸다.
        try {
            new URI(path);
        } catch (URISyntaxException e) {
            return fallback;
        }
        return path;
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
