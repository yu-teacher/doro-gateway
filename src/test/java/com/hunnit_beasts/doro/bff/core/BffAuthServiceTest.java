package com.hunnit_beasts.doro.bff.core;

import com.hunnit_beasts.doro.bff.core.BffTestSupport.FakeIam;
import com.hunnit_beasts.doro.bff.core.BffTestSupport.MemoryStores;
import com.hunnit_beasts.doro.bff.core.BffTestSupport.MutableClock;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.hunnit_beasts.doro.bff.core.BffTestSupport.CLIENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BFF 세션 로직: 로그인 CSRF, state 1회 사용, 갱신(동시성·IAM 장애·429), 세션 폐기, 로그아웃. */
class BffAuthServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private FakeIam iam;
    private MemoryStores stores;
    private MutableClock clock;
    private DoroTokenVerifier verifier;
    private BffAuthService service;
    private BffProperties props;
    private final List<DoroUser> hookCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        iam = new FakeIam();
        stores = new MemoryStores();
        clock = new MutableClock();
        props = BffTestSupport.props(iam.port());
        verifier = mock(DoroTokenVerifier.class);
        // access-N 토큰은 이 사용자의 이 클라이언트 토큰으로 인정한다
        when(verifier.verify(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).startsWith("access")
                ? new DoroUser(USER, "u@doro.test", UUID.randomUUID(), 0, "USER", CLIENT_ID) : null);
        BffCrypto crypto = new BffCrypto(props.getSessionEncryptionKey());
        service = new BffAuthService(props, new BffOAuthClient(props, clock), crypto, stores, stores, verifier, hookCalls::add, clock);
    }

    @AfterEach
    void tearDown() {
        iam.close();
    }

    /** 로그인을 시작하고 콜백까지 마친다. 세션 쿠키 값을 돌려준다. */
    private String login() {
        BffAuthService.StartedLogin started = service.startLogin("/app/me");
        String state = queryParam(started.authorizeUrl(), "state");
        return service.completeLogin("code-1", state, null, started.browserNonce()).sessionCookieValue();
    }

    private static String queryParam(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        throw new AssertionError(name + " not in " + url);
    }

    // ---------------------------------------------------------------- 로그인

    @Test
    @DisplayName("로그인 시작: state·PKCE challenge 가 담긴 인가 주소를 만들고, verifier 는 암호화해 저장한다")
    void startLoginBuildsAuthorizeUrl() {
        BffAuthService.StartedLogin started = service.startLogin("/app/me");

        assertThat(started.authorizeUrl()).startsWith("https://doro.example/oauth2/authorize?")
                .contains("response_type=code", "client_id=" + CLIENT_ID, "code_challenge_method=S256", "code_challenge=");
        BffLoginAttempt attempt = stores.attempts.values().iterator().next();
        assertThat(attempt.codeVerifierEnc()).startsWith("v1:").doesNotContain(started.browserNonce());
        assertThat(attempt.returnPath()).isEqualTo("/app/me");
        assertThat(attempt.browserHash()).isEqualTo(BffAuthService.sha256Hex(started.browserNonce()));
    }

    @Test
    @DisplayName("로그인 성공: 세션을 만들고 사용자 훅을 부르며, 이전 세션은 지운다(세션 고정 방지)")
    void completeLoginCreatesSession() {
        String first = login();
        BffAuthService.StartedLogin second = service.startLogin("/app/x");
        BffAuthService.LoginResult result = service.completeLogin("code-2", queryParam(second.authorizeUrl(), "state"), first, second.browserNonce());

        assertThat(result.returnPath()).isEqualTo("/app/x");
        assertThat(hookCalls).hasSize(2).allSatisfy(u -> assertThat(u.userId()).isEqualTo(USER));
        assertThat(stores.sessions).as("이전 세션은 지워졌다").hasSize(1).containsKey(BffAuthService.sha256Hex(result.sessionCookieValue()));
        assertThat(iam.tokenRequests.get(0)).containsEntry("grant_type", "authorization_code").containsEntry("client_id", CLIENT_ID).containsKey("code_verifier");
    }

    @Test
    @DisplayName("state 는 한 번만 쓸 수 있다")
    void stateIsSingleUse() {
        BffAuthService.StartedLogin started = service.startLogin("/");
        String state = queryParam(started.authorizeUrl(), "state");
        service.completeLogin("code-1", state, null, started.browserNonce());

        assertThatThrownBy(() -> service.completeLogin("code-1", state, null, started.browserNonce()))
                .isInstanceOf(BffAuthService.LoginFailedException.class);
        assertThat(iam.tokenCalls.get()).as("재사용된 state 로는 IAM 을 부르지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("로그인 CSRF: 로그인을 시작한 브라우저가 아니면 코드 교환 없이 거부하고 state 도 소진된다")
    void loginCsrfIsRejectedWithoutCallingIam() {
        BffAuthService.StartedLogin started = service.startLogin("/");
        String state = queryParam(started.authorizeUrl(), "state");

        assertThatThrownBy(() -> service.completeLogin("code-1", state, null, "someone-elses-nonce"))
                .isInstanceOf(BffAuthService.LoginFailedException.class);
        assertThat(iam.tokenCalls.get()).isZero();
        assertThatThrownBy(() -> service.completeLogin("code-1", state, null, started.browserNonce()))
                .as("공격 시도로 state 가 소진돼 정상 브라우저도 다시 쓸 수 없다").isInstanceOf(BffAuthService.LoginFailedException.class);
        assertThat(stores.sessions).isEmpty();
    }

    @Test
    @DisplayName("브라우저 nonce 가 없거나 비어 있으면 거부한다")
    void missingBrowserNonceIsRejected() {
        BffAuthService.StartedLogin started = service.startLogin("/");
        String state = queryParam(started.authorizeUrl(), "state");

        assertThatThrownBy(() -> service.completeLogin("code-1", state, null, null)).isInstanceOf(BffAuthService.LoginFailedException.class);
    }

    @Test
    @DisplayName("만료된 로그인 시도는 거부한다")
    void expiredAttemptIsRejected() {
        BffAuthService.StartedLogin started = service.startLogin("/");
        clock.advance(props.getLoginAttemptTtl().plusSeconds(1));

        assertThatThrownBy(() -> service.completeLogin("code-1", queryParam(started.authorizeUrl(), "state"), null, started.browserNonce()))
                .isInstanceOf(BffAuthService.LoginFailedException.class);
    }

    @Test
    @DisplayName("코드 교환을 IAM 이 거부하거나(400) 장애(503)여도 로그인 실패로 끝나고 세션은 만들지 않는다")
    void exchangeFailuresFailTheLogin() {
        for (int status : new int[]{400, 401, 503}) {
            iam.tokenStatus = status;
            iam.tokenBody = "{\"error\":\"x\"}";
            BffAuthService.StartedLogin started = service.startLogin("/");
            assertThatThrownBy(() -> service.completeLogin("code-1", queryParam(started.authorizeUrl(), "state"), null, started.browserNonce()))
                    .as("HTTP " + status).isInstanceOf(BffAuthService.LoginFailedException.class);
        }
        assertThat(stores.sessions).isEmpty();
    }

    @Test
    @DisplayName("이 클라이언트의 토큰이 아니면(다른 cid) 로그인 실패")
    void tokenForAnotherClientIsRejected() {
        when(verifier.verify(anyString())).thenReturn(new DoroUser(USER, "u@doro.test", UUID.randomUUID(), 0, "USER", "other-client"));
        BffAuthService.StartedLogin started = service.startLogin("/");

        assertThatThrownBy(() -> service.completeLogin("code-1", queryParam(started.authorizeUrl(), "state"), null, started.browserNonce()))
                .isInstanceOf(BffAuthService.LoginFailedException.class);
    }

    // ---------------------------------------------------------------- 요청 인증과 갱신

    @Test
    @DisplayName("유효한 세션이면 사용자를 돌려주고, 만료가 멀면 IAM 을 부르지 않는다")
    void authenticateWithoutRefresh() {
        String cookie = login();
        int callsAfterLogin = iam.tokenCalls.get();

        Optional<DoroUser> user = service.authenticate(cookie);

        assertThat(user).isPresent().get().extracting(DoroUser::userId).isEqualTo(USER);
        assertThat(iam.tokenCalls.get()).isEqualTo(callsAfterLogin);
    }

    @Test
    @DisplayName("authenticateWithToken 은 사용자와 함께 복호화한 액세스 토큰을 돌려준다(게이트웨이가 Bearer 로 붙일 값)")
    void authenticateWithTokenReturnsAccessToken() {
        String cookie = login();

        Optional<BffAuthService.Authenticated> result = service.authenticateWithToken(cookie);

        assertThat(result).isPresent();
        assertThat(result.get().user().userId()).isEqualTo(USER);
        assertThat(result.get().accessToken()).isEqualTo("access-1");
        assertThat(service.authenticateWithToken("nope")).isEmpty();
        assertThat(service.authenticateWithToken(null)).isEmpty();
    }

    @Test
    @DisplayName("알 수 없는 쿠키·빈 쿠키는 익명이다")
    void unknownCookieIsAnonymous() {
        assertThat(service.authenticate("nope")).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    @DisplayName("액세스 토큰이 곧 만료되면 갱신하고, 토큰 컬럼만 바꾸며(updateTokens) 새 리프레시 토큰으로 교체한다")
    void refreshRotatesTokens() {
        String cookie = login();
        iam.tokenBody = FakeIam.tokenJson("access-2", "refresh-2", 900);
        clock.advance(Duration.ofSeconds(900).minus(props.getRefreshMargin()).plusSeconds(1));

        assertThat(service.authenticate(cookie)).isPresent();

        assertThat(iam.tokenRequests.get(1)).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "refresh-1");
        assertThat(stores.calls).contains("updateTokens");
        BffSession stored = stores.sessions.values().iterator().next();
        assertThat(new BffCrypto(props.getSessionEncryptionKey()).decrypt(stored.refreshTokenEnc())).isEqualTo("refresh-2");
    }

    @Test
    @DisplayName("동시에 여러 요청이 와도 갱신은 한 번만 IAM 을 부른다(리프레시 토큰 회전 경쟁 방지)")
    void concurrentRequestsRefreshOnlyOnce() throws Exception {
        String cookie = login();
        iam.tokenBody = FakeIam.tokenJson("access-2", "refresh-2", 900);
        iam.tokenDelayMillis = 200;
        clock.advance(Duration.ofSeconds(900).minus(props.getRefreshMargin()).plusSeconds(1));
        int before = iam.tokenCalls.get();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<DoroUser>>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> { go.await(); return service.authenticate(cookie); }));
        }
        go.countDown();
        for (Future<Optional<DoroUser>> f : results) {
            assertThat(f.get()).isPresent();
        }
        pool.shutdown();

        assertThat(iam.tokenCalls.get() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("IAM 장애(5xx)로 갱신하지 못해도 세션은 지우지 않는다: 이번 요청만 익명이고 복구되면 다시 로그인 상태다")
    void iamOutageKeepsTheSession() {
        String cookie = login();
        clock.advance(Duration.ofSeconds(901));            // 액세스 토큰 만료
        iam.tokenStatus = 503;
        iam.tokenBody = "{}";
        when(verifier.verify("access-1")).thenReturn(null); // 만료된 토큰은 검증 실패

        assertThat(service.authenticate(cookie)).as("이번 요청은 익명").isEmpty();
        assertThat(stores.sessions).as("세션은 남는다").hasSize(1);

        iam.tokenStatus = 200;
        iam.tokenBody = FakeIam.tokenJson("access-3", "refresh-3", 900);
        assertThat(service.authenticate(cookie)).as("IAM 이 돌아오면 같은 쿠키로 다시 로그인 상태").isPresent();
    }

    @Test
    @DisplayName("IAM 이 429(요청 제한)나 408 을 줘도 세션을 지우지 않는다")
    void rateLimitedRefreshKeepsTheSession() {
        for (int status : new int[]{429, 408, 403, 404}) {
            stores.sessions.clear();
            String cookie = login();
            clock.advance(Duration.ofSeconds(901));
            iam.tokenStatus = status;
            iam.tokenBody = "{}";
            when(verifier.verify("access-1")).thenReturn(null);

            assertThat(service.authenticate(cookie)).as("HTTP " + status).isEmpty();
            assertThat(stores.sessions).as("HTTP " + status + " 에도 세션 유지").hasSize(1);
            iam.tokenStatus = 200;
            iam.tokenBody = FakeIam.tokenJson("access-1", "refresh-1", 900);
            when(verifier.verify(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).startsWith("access")
                    ? new DoroUser(USER, "u@doro.test", UUID.randomUUID(), 0, "USER", CLIENT_ID) : null);
        }
    }

    @Test
    @DisplayName("IAM 이 리프레시 토큰을 명시적으로 거부하면(400/401) 세션을 끝낸다")
    void rejectedRefreshEndsTheSession() {
        for (int status : new int[]{400, 401}) {
            stores.sessions.clear();
            String cookie = login();
            clock.advance(Duration.ofSeconds(901));
            iam.tokenStatus = status;
            iam.tokenBody = "{\"error\":\"invalid_grant\"}";

            assertThat(service.authenticate(cookie)).as("HTTP " + status).isEmpty();
            assertThat(stores.sessions).as("HTTP " + status + " 이면 세션 삭제").isEmpty();
            iam.tokenStatus = 200;
            iam.tokenBody = FakeIam.tokenJson("access-1", "refresh-1", 900);
        }
    }

    @Test
    @DisplayName("IAM 세션이 폐기돼 액세스 토큰 검증이 실패하면(만료 전) 세션을 지운다")
    void revokedAccessTokenDeletesTheSession() {
        String cookie = login();
        when(verifier.verify("access-1")).thenReturn(null);

        assertThat(service.authenticate(cookie)).isEmpty();
        assertThat(stores.sessions).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 토큰이거나 이 클라이언트 토큰이 아니면 세션을 지운다")
    void mismatchedTokenDeletesTheSession() {
        String cookie = login();
        when(verifier.verify("access-1")).thenReturn(new DoroUser(UUID.randomUUID(), "x@doro.test", UUID.randomUUID(), 0, "USER", CLIENT_ID));

        assertThat(service.authenticate(cookie)).isEmpty();
        assertThat(stores.sessions).isEmpty();
    }

    @Test
    @DisplayName("세션 절대 수명이 지나면 지운다")
    void expiredSessionIsDeleted() {
        String cookie = login();
        clock.advance(props.getSessionTtl().plusSeconds(1));

        assertThat(service.authenticate(cookie)).isEmpty();
        assertThat(stores.sessions).isEmpty();
    }

    @Test
    @DisplayName("마지막 사용 시각 갱신은 1분에 한 번만, 그것도 그 컬럼만 바꾼다(토큰을 덮어쓰지 않는다)")
    void touchIsThrottledAndColumnOnly() {
        String cookie = login();
        stores.calls.clear();
        service.authenticate(cookie);
        assertThat(stores.calls).as("방금 로그인해서 아직 갱신하지 않는다").doesNotContain("touch");

        clock.advance(Duration.ofSeconds(61));
        service.authenticate(cookie);
        service.authenticate(cookie);

        assertThat(stores.calls.stream().filter("touch"::equals).count()).isEqualTo(1);
        assertThat(stores.calls).doesNotContain("updateTokens");
    }

    // ---------------------------------------------------------------- 로그아웃

    @Test
    @DisplayName("로그아웃: IAM 에 리프레시 토큰 폐기를 요청하고 로컬 세션을 지운다")
    void logoutRevokesAtIamAndDeletesLocally() {
        String cookie = login();

        service.logout(cookie);

        assertThat(iam.revokeRequests).hasSize(1);
        assertThat(iam.revokeRequests.get(0)).containsEntry("token", "refresh-1").containsEntry("client_id", CLIENT_ID);
        assertThat(stores.sessions).isEmpty();
    }

    @Test
    @DisplayName("IAM 폐기 호출이 실패해도 로컬 로그아웃은 끝난다")
    void logoutSucceedsLocallyEvenIfIamFails() {
        String cookie = login();
        iam.revokeStatus = 503;

        service.logout(cookie);

        assertThat(stores.sessions).isEmpty();
    }

    // ---------------------------------------------------------------- 복귀 경로

    @Test
    @DisplayName("복귀 경로: 사이트 안의 올바른 경로만 허용하고 나머지는 첫 화면으로 보낸다")
    void safeReturnPath() {
        String fallback = "/app/";
        assertThat(BffAuthService.safeReturnPath("/app/@user/post", fallback)).isEqualTo("/app/@user/post");
        assertThat(BffAuthService.safeReturnPath("/a?x=1&y=%ED%95%9C", fallback)).isEqualTo("/a?x=1&y=%ED%95%9C");
        for (String bad : new String[]{null, "", "  ", "relative", "//evil.example", "https://evil.example", "/\\evil", "/x://y",
                "/a\nb", "/a\u0000b", "/a|b", "/a b", "/a\"b", "/a%zz", "/" + "a".repeat(600)}) {
            assertThat(BffAuthService.safeReturnPath(bad, fallback)).as("값: " + bad).isEqualTo(fallback);
        }
        assertThat(BffAuthService.safeReturnPath("bad")).isEqualTo("/");
    }

    @Test
    @DisplayName("공개 경로 모드(기본): 받은 경로를 그대로 쓴다 — 이미 웹 기준 경로가 붙어 있다고 본다")
    void returnPathIsUsedAsIsByDefault() {
        BffAuthService.StartedLogin started = service.startLogin("/app/maps/42");

        assertThat(service.completeLogin("c", queryParam(started.authorizeUrl(), "state"), null, started.browserNonce()).returnPath())
                .isEqualTo("/app/maps/42");
    }

    @Test
    @DisplayName("앱 내부 경로 모드: 검증을 통과한 경로 앞에 웹 기준 경로를 붙이고, 거부된 값은 첫 화면으로 보낸다")
    void relativeModePrefixesTheWebBasePath() {
        props.setReturnPathRelativeToWebBase(true);

        for (String[] c : new String[][]{{"/maps/42", "/app/maps/42"}, {"/maps?x=1", "/app/maps?x=1"}, {null, "/app/"}, {"//evil", "/app/"},
                {"https://evil.example", "/app/"}, {"/a|b", "/app/"}}) {
            BffAuthService.StartedLogin started = service.startLogin(c[0]);
            assertThat(service.completeLogin("c", queryParam(started.authorizeUrl(), "state"), null, started.browserNonce()).returnPath())
                    .as("요청: " + c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    @DisplayName("세션 쿠키 Path 설정이 비어 있으면 루트다")
    void blankCookiePathMeansRoot() {
        props.setCookiePath("");
        assertThat(props.getCookiePath()).isEqualTo("/");
        props.setCookiePath(null);
        assertThat(props.getCookiePath()).isEqualTo("/");
        props.setCookiePath("/party");
        assertThat(props.getCookiePath()).isEqualTo("/party");
    }

    @Test
    @DisplayName("URI 문법에 맞지 않는 복귀 경로로 로그인해도 콜백이 오류 없이 끝나고 첫 화면으로 간다")
    void invalidReturnPathFallsBackToTheFirstScreen() {
        BffAuthService.StartedLogin started = service.startLogin("/a|b");
        BffAuthService.LoginResult result = service.completeLogin("code-1", queryParam(started.authorizeUrl(), "state"), null, started.browserNonce());

        assertThat(result.returnPath()).isEqualTo("/app/");
        assertThat(URI.create(result.returnPath())).isNotNull();
    }
}
