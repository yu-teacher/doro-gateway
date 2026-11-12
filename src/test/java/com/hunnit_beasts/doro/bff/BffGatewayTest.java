package com.hunnit_beasts.doro.bff;

import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게이트웨이용 BFF 의 웹 계약: 로그인·콜백·로그아웃(서비스별), 그리고 nginx auth_request 가 부르는 /internal/auth.
 * IAM 은 가짜 서버, DB 는 H2, 토큰 검증기는 "tok:<clientId>:<userId>" 형식을 해석하는 대역이다(검증 로직 자체는 SDK 시험이 맡는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BffGatewayTest {

    private static FakeIam iam;

    @BeforeAll
    static void startIam() throws IOException {
        iam = new FakeIam();
    }

    @AfterAll
    static void stopIam() {
        iam.close();
    }

    @DynamicPropertySource
    static void iamProps(DynamicPropertyRegistry registry) throws IOException {
        if (iam == null) {
            iam = new FakeIam();
        }
        registry.add("doro.bff.token-url", () -> "http://127.0.0.1:" + iam.port() + "/oauth2/token");
        registry.add("doro.bff.revoke-url", () -> "http://127.0.0.1:" + iam.port() + "/oauth2/revoke");
        registry.add("doro.bff.authorize-url", () -> "https://doro.example/oauth2/authorize");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DoroTokenVerifier verifier;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM login_attempts");
        iam.tokenRequests.clear();
        iam.revokeRequests.clear();
        iam.tokenStatus = 200;
        when(verifier.verify(anyString())).thenAnswer(inv -> {
            String[] parts = ((String) inv.getArgument(0)).split(":");
            if (parts.length != 3 || !parts[0].equals("tok")) {
                throw new IllegalArgumentException("bad token");
            }
            return new DoroUser(UUID.fromString(parts[2]), "u@example.com", UUID.randomUUID(), 0, "USER", parts[1]);
        });
    }

    private String token(String clientId, String suffix) {
        return "tok:" + clientId + ":" + userId + (suffix.isEmpty() ? "" : "");
    }

    // ------------------------------------------------------------------ 헬퍼

    /** 로그인을 끝까지 진행하고 세션 쿠키 값을 돌려준다. */
    private String login(String service, String clientId) throws Exception {
        iam.tokenBody = FakeIam.tokenJson(token(clientId, ""), "refresh-" + service, 900);
        MvcResult started = mvc.perform(get("/bff/" + service + "/login")).andExpect(status().isFound()).andReturn();
        String location = started.getResponse().getHeader(HttpHeaders.LOCATION);
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        Cookie loginCookie = cookie(started, service + "_login");
        MvcResult done = mvc.perform(get("/bff/" + service + "/callback").param("code", "c").param("state", state).cookie(loginCookie))
                .andExpect(status().isFound()).andReturn();
        Cookie session = cookie(done, service + "_session");
        assertThat(session).as("세션 쿠키가 발급돼야 한다").isNotNull();
        return session.getValue();
    }

    private static Cookie cookie(MvcResult result, String nameSuffix) {
        for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            String[] nameValue = header.split(";", 2)[0].split("=", 2);
            if (nameValue[0].endsWith(nameSuffix) && nameValue.length == 2 && !nameValue[1].isEmpty()) {
                return new Cookie(nameValue[0], nameValue[1]);
            }
        }
        return null;
    }

    private MvcResult authRequest(String service, String method, Cookie... cookies) throws Exception {
        var req = get("/internal/auth").header("X-Doro-Service", service);
        if (method != null) {
            req = req.header("X-Original-Method", method);
        }
        if (cookies.length > 0) {
            req = req.cookie(cookies);
        }
        return mvc.perform(req).andReturn();
    }

    // ------------------------------------------------------------------ 시험

    @Test
    @DisplayName("로그인하면 세션 쿠키가 발급되고, auth_request 가 그 쿠키를 액세스 토큰으로 바꿔 준다")
    void loginThenAuthRequestReturnsToken() throws Exception {
        String session = login("blog", "doro-blog");

        MvcResult res = authRequest("blog", "GET", new Cookie("doro_blog_session", session));
        assertThat(res.getResponse().getStatus()).isEqualTo(204);
        assertThat(res.getResponse().getHeader("X-Doro-Access-Token")).isEqualTo(token("doro-blog", ""));
        assertThat(res.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    @DisplayName("쿠키가 없거나 모르는 쿠키면 익명(204, 토큰 헤더 없음)이다")
    void anonymousWithoutValidSession() throws Exception {
        MvcResult none = authRequest("blog", "GET");
        assertThat(none.getResponse().getStatus()).isEqualTo(204);
        assertThat(none.getResponse().getHeader("X-Doro-Access-Token")).isNull();

        MvcResult unknown = authRequest("blog", "GET", new Cookie("doro_blog_session", "not-a-session"));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(204);
        assertThat(unknown.getResponse().getHeader("X-Doro-Access-Token")).isNull();
    }

    @Test
    @DisplayName("서비스 격리: 한 서비스의 세션 값을 다른 서비스 쿠키로 보내도 통하지 않고, 원래 세션도 지워지지 않는다")
    void sessionsAreIsolatedPerService() throws Exception {
        String blogSession = login("blog", "doro-blog");

        MvcResult asParty = authRequest("party", "GET", new Cookie("doro_party_session", blogSession));
        assertThat(asParty.getResponse().getStatus()).isEqualTo(204);
        assertThat(asParty.getResponse().getHeader("X-Doro-Access-Token")).isNull();

        MvcResult stillBlog = authRequest("blog", "GET", new Cookie("doro_blog_session", blogSession));
        assertThat(stillBlog.getResponse().getHeader("X-Doro-Access-Token")).isNotNull();
    }

    @Test
    @DisplayName("한 서비스의 로그인 state 는 다른 서비스 콜백에서 쓸 수 없다")
    void stateCannotCrossServices() throws Exception {
        iam.tokenBody = FakeIam.tokenJson(token("doro-party", ""), "r", 900);
        MvcResult started = mvc.perform(get("/bff/blog/login")).andExpect(status().isFound()).andReturn();
        String state = UriComponentsBuilder.fromUriString(started.getResponse().getHeader(HttpHeaders.LOCATION))
                .build().getQueryParams().getFirst("state");

        MvcResult cross = mvc.perform(get("/bff/party/callback").param("code", "c").param("state", state)
                .cookie(new Cookie("doro_party_login", cookie(started, "blog_login").getValue()))).andReturn();
        assertThat(cross.getResponse().getHeader(HttpHeaders.LOCATION)).contains("login_error=failed");
        assertThat(cookie(cross, "party_session")).isNull();
        assertThat(iam.tokenRequests).as("코드 교환까지 가지 않는다").isEmpty();
    }

    @Test
    @DisplayName("로그인 CSRF: 로그인을 시작한 브라우저가 아니면(로그인 쿠키 없음) 콜백이 거부되고 세션이 생기지 않는다")
    void callbackWithoutLoginCookieIsRejected() throws Exception {
        MvcResult started = mvc.perform(get("/bff/blog/login")).andExpect(status().isFound()).andReturn();
        String state = UriComponentsBuilder.fromUriString(started.getResponse().getHeader(HttpHeaders.LOCATION))
                .build().getQueryParams().getFirst("state");

        MvcResult res = mvc.perform(get("/bff/blog/callback").param("code", "c").param("state", state)).andReturn();
        assertThat(res.getResponse().getHeader(HttpHeaders.LOCATION)).contains("login_error=failed");
        assertThat(cookie(res, "blog_session")).isNull();
        assertThat(iam.tokenRequests).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).isZero();
    }

    @Test
    @DisplayName("쿠키로 상태를 바꾸는 요청은 CSRF 헤더와 같은 Origin 이 있어야 한다")
    void unsafeMethodsRequireCsrfProof() throws Exception {
        Cookie session = new Cookie("doro_blog_session", login("blog", "doro-blog"));

        assertThat(authRequestWith(session, "POST", null, null).getResponse().getStatus()).as("헤더 없음").isEqualTo(403);
        assertThat(authRequestWith(session, "POST", "1", "https://evil.example").getResponse().getStatus()).as("다른 Origin").isEqualTo(403);
        assertThat(authRequestWith(session, "POST", "1", "https://doro.example").getResponse().getStatus()).as("정상").isEqualTo(204);
        assertThat(authRequestWith(session, "DELETE", "1", null).getResponse().getStatus()).as("Origin 없음(비브라우저)").isEqualTo(204);
        assertThat(authRequestWith(session, "GET", null, null).getResponse().getStatus()).as("읽기는 헤더 불필요").isEqualTo(204);
    }

    private MvcResult authRequestWith(Cookie session, String method, String csrf, String origin) throws Exception {
        var req = get("/internal/auth").header("X-Doro-Service", "blog").header("X-Original-Method", method).cookie(session);
        if (csrf != null) {
            req = req.header("X-Blog-Csrf", csrf);
        }
        if (origin != null) {
            req = req.header("Origin", origin);
        }
        return mvc.perform(req).andReturn();
    }

    @Test
    @DisplayName("게이트웨이 설정 오류는 로그인으로 인정하지 않는다: 서비스 헤더가 없거나 모르는 서비스면 400, 쿠키가 있는데 메서드를 모르면 400")
    void misconfiguredGatewayIsNeverTreatedAsLogin() throws Exception {
        Cookie session = new Cookie("doro_blog_session", login("blog", "doro-blog"));

        assertThat(mvc.perform(get("/internal/auth").cookie(session)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(authRequest("nope", "GET", session).getResponse().getStatus()).isEqualTo(400);
        MvcResult noMethod = authRequest("blog", null, session);
        assertThat(noMethod.getResponse().getStatus()).isEqualTo(400);
        assertThat(noMethod.getResponse().getHeader("X-Doro-Access-Token")).isNull();
    }

    @Test
    @DisplayName("액세스 토큰이 곧 만료되면 auth_request 가 IAM 에서 갱신해 새 토큰을 준다")
    void refreshesExpiringToken() throws Exception {
        iam.tokenBody = FakeIam.tokenJson(token("doro-blog", ""), "refresh-old", 30); // 갱신 여유(60초) 안이라 바로 갱신 대상
        String session = loginKeepingBody("blog");
        iam.tokenRequests.clear();
        iam.tokenBody = FakeIam.tokenJson("tok:doro-blog:" + userId, "refresh-new", 900);

        MvcResult res = authRequest("blog", "GET", new Cookie("doro_blog_session", session));
        assertThat(iam.tokenRequests).hasSize(1);
        assertThat(iam.tokenRequests.get(0)).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "refresh-old");
        assertThat(res.getResponse().getHeader("X-Doro-Access-Token")).isEqualTo("tok:doro-blog:" + userId);
    }

    @Test
    @DisplayName("IAM 장애로 갱신하지 못해도 세션은 유지하고 이번 요청만 익명이며, IAM 이 돌아오면 같은 쿠키로 다시 로그인 상태다")
    void iamOutageKeepsSession() throws Exception {
        iam.tokenBody = FakeIam.tokenJson(token("doro-blog", ""), "refresh-old", 30);
        String session = loginKeepingBody("blog");
        Cookie cookie = new Cookie("doro_blog_session", session);
        // 액세스 토큰이 이미 만료된 상태를 만든다
        jdbc.update("UPDATE auth_sessions SET access_expires_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)));
        doThrow(new IllegalArgumentException("expired")).when(verifier).verify(anyString());

        iam.tokenStatus = 500;
        MvcResult down = authRequest("blog", "GET", cookie);
        assertThat(down.getResponse().getStatus()).isEqualTo(204);
        assertThat(down.getResponse().getHeader("X-Doro-Access-Token")).as("이번 요청만 익명").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).as("세션은 남는다").isEqualTo(1);

        iam.tokenStatus = 200;
        iam.tokenBody = FakeIam.tokenJson("tok:doro-blog:" + userId, "refresh-new", 900);
        doAnswer(inv -> new DoroUser(userId, "u@example.com", UUID.randomUUID(), 0, "USER", "doro-blog")).when(verifier).verify(anyString());
        MvcResult back = authRequest("blog", "GET", cookie);
        assertThat(back.getResponse().getHeader("X-Doro-Access-Token")).isEqualTo("tok:doro-blog:" + userId);
    }

    private String loginKeepingBody(String service) throws Exception {
        MvcResult started = mvc.perform(get("/bff/" + service + "/login")).andExpect(status().isFound()).andReturn();
        String state = UriComponentsBuilder.fromUriString(started.getResponse().getHeader(HttpHeaders.LOCATION))
                .build().getQueryParams().getFirst("state");
        MvcResult done = mvc.perform(get("/bff/" + service + "/callback").param("code", "c").param("state", state)
                .cookie(cookie(started, service + "_login"))).andExpect(status().isFound()).andReturn();
        return cookie(done, service + "_session").getValue();
    }

    @Test
    @DisplayName("로그아웃: 세션과 IAM 세션(refresh 폐기)이 끝나고 그 쿠키는 더 이상 통하지 않는다. CSRF 헤더가 없으면 403")
    void logoutEndsSession() throws Exception {
        String session = login("blog", "doro-blog");
        Cookie cookie = new Cookie("doro_blog_session", session);

        mvc.perform(post("/bff/blog/logout").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/bff/blog/logout").cookie(cookie).header("X-Blog-Csrf", "1")).andExpect(status().isOk());

        assertThat(iam.revokeRequests).hasSize(1);
        assertThat(authRequest("blog", "GET", cookie).getResponse().getHeader("X-Doro-Access-Token")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).isZero();
    }

    @Test
    @DisplayName("모르는 서비스의 로그인 주소는 404")
    void unknownServiceLoginIs404() throws Exception {
        mvc.perform(get("/bff/nope/login")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("로그인 주소는 서비스별 설정(클라이언트·redirect_uri)으로 IAM 인가 요청을 만든다")
    void loginUsesServiceSettings() throws Exception {
        MvcResult res = mvc.perform(get("/bff/party/login").param("return", "/maps/42")).andExpect(status().isFound()).andReturn();
        var q = UriComponentsBuilder.fromUriString(res.getResponse().getHeader(HttpHeaders.LOCATION)).build().getQueryParams();
        assertThat(q.getFirst("client_id")).isEqualTo("doro-party");
        assertThat(q.getFirst("redirect_uri")).contains("https://doro.example/party/api/v1/bff/callback");
        assertThat(q.getFirst("code_challenge_method")).isEqualTo("S256");
        String loginCookie = String.join(";", res.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(loginCookie).contains("doro_party_login=").contains("HttpOnly").contains("Path=/party/api/v1/bff");
    }

    @Test
    @DisplayName("블로그는 /blog 아래에 마운트된다: 로그인 시도 쿠키는 /blog/api/v1/bff, 세션 쿠키는 루트(/), 복귀 경로는 /blog 기준이다")
    void blogMountedUnderBasePath() throws Exception {
        MvcResult res = mvc.perform(get("/bff/blog/login")).andExpect(status().isFound()).andReturn();
        var q = UriComponentsBuilder.fromUriString(res.getResponse().getHeader(HttpHeaders.LOCATION)).build().getQueryParams();
        assertThat(q.getFirst("client_id")).isEqualTo("doro-blog");
        assertThat(q.getFirst("redirect_uri")).contains("https://doro.example/blog/api/v1/bff/callback");
        assertThat(String.join(";", res.getResponse().getHeaders(HttpHeaders.SET_COOKIE))).contains("doro_blog_login=").contains("Path=/blog/api/v1/bff");

        String session = login("blog", "doro-blog");
        MvcResult done = mvc.perform(get("/bff/blog/login")).andReturn();
        assertThat(session).isNotBlank();
        String state = UriComponentsBuilder.fromUriString(done.getResponse().getHeader(HttpHeaders.LOCATION)).build().getQueryParams().getFirst("state");
        MvcResult cb = mvc.perform(get("/bff/blog/callback").param("code", "c").param("state", state).cookie(cookie(done, "blog_login"))).andReturn();
        assertThat(String.join(";", cb.getResponse().getHeaders(HttpHeaders.SET_COOKIE))).contains("doro_blog_session=").contains("Path=/;");
        assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).as("복귀 경로가 없으면 /blog/ 첫 화면").isEqualTo("/blog/");
    }

    @Test
    @DisplayName("파티·게임 웹은 라우터 기준 경로를 보낸다: 서버가 웹 기준 경로(/party, /games)를 붙여 이동시키고, 세션 쿠키 Path 도 그 경로다")
    void partyAndGamesReturnPathsAreRelativeToWebBase() throws Exception {
        for (String[] c : new String[][] {{"games", "doro-games", "/g/2048", "/games/g/2048", "/games"},
                                          {"party", "doro-party", "/maps/42", "/party/maps/42", "/party"}}) {
            iam.tokenBody = FakeIam.tokenJson(token(c[1], ""), "r", 900);
            MvcResult started = mvc.perform(get("/bff/" + c[0] + "/login").param("return", c[2])).andExpect(status().isFound()).andReturn();
            String state = UriComponentsBuilder.fromUriString(started.getResponse().getHeader(HttpHeaders.LOCATION))
                    .build().getQueryParams().getFirst("state");
            MvcResult done = mvc.perform(get("/bff/" + c[0] + "/callback").param("code", "c").param("state", state)
                    .cookie(cookie(started, c[0] + "_login"))).andReturn();
            assertThat(done.getResponse().getHeader(HttpHeaders.LOCATION)).as(c[0] + " 복귀 경로").isEqualTo(c[3]);
            assertThat(String.join(";", done.getResponse().getHeaders(HttpHeaders.SET_COOKIE)))
                    .as(c[0] + " 세션 쿠키 Path").contains("doro_" + c[0] + "_session=").contains("Path=" + c[4] + ";");
        }
    }

    @Test
    @DisplayName("계정이 영구 탈퇴하면 모든 서비스의 세션이 지워진다(탈퇴 동기화 핸들러)")
    void deletedUserSessionsAreRemovedFromAllServices() throws Exception {
        login("blog", "doro-blog");
        login("party", "doro-party");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).isEqualTo(2);

        com.hunnit_beasts.doro.sdk.sync.DeletedUserHandler handler = ctxHandler;
        assertThat(handler.onDeleted(userId, java.time.Instant.now())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).isZero();
        assertThat(handler.onDeleted(userId, java.time.Instant.now())).as("멱등").isFalse();
    }

    @Autowired com.hunnit_beasts.doro.sdk.sync.DeletedUserHandler ctxHandler;
}
