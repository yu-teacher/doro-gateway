package com.hunnit_beasts.doro.bff.core;

import com.hunnit_beasts.doro.bff.core.BffTestSupport.FakeIam;
import com.hunnit_beasts.doro.bff.core.BffTestSupport.MemoryStores;
import com.hunnit_beasts.doro.bff.core.BffTestSupport.MutableClock;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.hunnit_beasts.doro.bff.core.BffTestSupport.CLIENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BFF 엔드포인트(로그인·콜백·로그아웃)의 쿠키·CSRF 규칙. 게이트웨이 요청의 CSRF 는 BffGatewayTest 가 본다. */
class BffWebTest {

    private static final UUID USER = UUID.randomUUID();

    /** 운영에서 경로를 잡는 BffGatewayController 처럼, 시험용으로 한 서비스의 BffController 를 /api/v1/bff 에 이어 준다. */
    @RestController
    @RequestMapping("/api/v1/bff")
    record Endpoints(BffController bff) {
        @GetMapping("/login")
        ResponseEntity<Void> login(@RequestParam(name = "return", required = false) String returnPath) {
            return bff.login(returnPath);
        }

        @GetMapping("/callback")
        ResponseEntity<Void> callback(HttpServletRequest request,
                                      @RequestParam(name = "code", required = false) String code,
                                      @RequestParam(name = "state", required = false) String state,
                                      @RequestParam(name = "error", required = false) String error) {
            return bff.callback(request, code, state, error);
        }

        @PostMapping("/logout")
        ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
            return bff.logout(request);
        }
    }

    private FakeIam iam;
    private MemoryStores stores;
    private BffProperties props;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        iam = new FakeIam();
        stores = new MemoryStores();
        props = BffTestSupport.props(iam.port());
        DoroTokenVerifier verifier = mock(DoroTokenVerifier.class);
        when(verifier.verify(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).startsWith("access")
                ? new DoroUser(USER, "u@doro.test", UUID.randomUUID(), 0, "USER", CLIENT_ID) : null);
        MutableClock clock = new MutableClock();
        BffAuthService service = new BffAuthService(props, new BffOAuthClient(props, clock), new BffCrypto(props.getSessionEncryptionKey()),
                stores, stores, verifier, BffUserHook.none(), clock);
        BffCookies cookies = new BffCookies(props);
        mvc = MockMvcBuilders.standaloneSetup(new Endpoints(new BffController(service, props, cookies))).build();
    }

    @AfterEach
    void tearDown() {
        iam.close();
    }

    private static String setCookie(MvcResult result, String name) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(name + "=")) {
                return header;
            }
        }
        return null;
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    /** 로그인을 끝까지 진행하고 세션 쿠키 값을 돌려준다. */
    private String loginAndGetSessionCookie() throws Exception {
        MvcResult start = mvc.perform(get("/api/v1/bff/login").param("return", "/app/me")).andExpect(status().isFound()).andReturn();
        String loginCookie = cookieValue(setCookie(start, props.getLoginCookieName()));
        String state = queryParam(start.getResponse().getHeader("Location"), "state");
        MvcResult cb = mvc.perform(get("/api/v1/bff/callback").param("code", "c1").param("state", state)
                .cookie(new Cookie(props.getLoginCookieName(), loginCookie))).andExpect(status().isFound()).andReturn();
        return cookieValue(setCookie(cb, props.getCookieName()));
    }

    private static String queryParam(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        throw new AssertionError(name);
    }

    @Test
    @DisplayName("로그인: Doro 로 302, 로그인 진행 쿠키(HttpOnly, SameSite=Lax, 로그인 경로 한정), no-store")
    void loginRedirectsAndBindsTheBrowser() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/bff/login")).andExpect(status().isFound()).andReturn();

        assertThat(result.getResponse().getHeader("Location")).startsWith("https://doro.example/oauth2/authorize?");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String cookie = setCookie(result, props.getLoginCookieName());
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/app/api/v1/bff", "Max-Age=");
    }

    @Test
    @DisplayName("콜백 성공: 세션 쿠키(HttpOnly·Secure·Lax)를 주고 로그인 쿠키는 지우며 원래 경로로 302")
    void callbackIssuesTheSessionCookie() throws Exception {
        MvcResult start = mvc.perform(get("/api/v1/bff/login").param("return", "/app/me")).andReturn();
        String loginCookie = cookieValue(setCookie(start, props.getLoginCookieName()));

        MvcResult cb = mvc.perform(get("/api/v1/bff/callback").param("code", "c1")
                        .param("state", queryParam(start.getResponse().getHeader("Location"), "state"))
                        .cookie(new Cookie(props.getLoginCookieName(), loginCookie)))
                .andExpect(status().isFound()).andReturn();

        assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/app/me");
        assertThat(setCookie(cb, props.getCookieName())).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/");
        assertThat(setCookie(cb, props.getLoginCookieName())).contains("Max-Age=0");
    }

    @Test
    @DisplayName("콜백 실패: 로그인 쿠키만 지우고 이미 있는 세션 쿠키는 건드리지 않는다(가짜 콜백 링크로 로그아웃시킬 수 없다)")
    void failedCallbackDoesNotTouchTheSessionCookie() throws Exception {
        String session = loginAndGetSessionCookie();

        MvcResult result = mvc.perform(get("/api/v1/bff/callback").param("code", "x").param("state", "forged")
                        .cookie(new Cookie(props.getCookieName(), session)))
                .andExpect(status().isFound()).andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/app/?login_error=failed");
        assertThat(setCookie(result, props.getCookieName())).as("세션 쿠키는 건드리지 않는다").isNull();
        assertThat(setCookie(result, props.getLoginCookieName())).contains("Max-Age=0");
        assertThat(stores.sessions).as("서버 세션도 그대로").hasSize(1);
    }

    @Test
    @DisplayName("사용자가 인가를 취소하면(error 파라미터) 사유를 싣지 않고 첫 화면으로 보낸다")
    void cancelledAuthorizationRedirectsWithoutDetail() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/bff/callback").param("error", "access_denied")).andExpect(status().isFound()).andReturn();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/app/?login_error=cancelled");
    }

    @Test
    @DisplayName("CSRF 거부 응답은 워크스페이스 표준 오류 모양이다")
    void csrfRejectionBodyHasTheStandardShape() throws Exception {
        String session = loginAndGetSessionCookie();

        String body = mvc.perform(post("/api/v1/bff/logout").cookie(new Cookie(props.getCookieName(), session))).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"success\":false", "\"code\":\"AUTH-403-03\"", "\"status\":403", "\"error\":{");
    }

    @Test
    @DisplayName("로그아웃: CSRF 헤더가 있어야 하고, 성공하면 세션 쿠키를 지우고 서버 세션과 IAM 세션을 끝낸다")
    void logout() throws Exception {
        String session = loginAndGetSessionCookie();
        Cookie cookie = new Cookie(props.getCookieName(), session);

        mvc.perform(post("/api/v1/bff/logout").cookie(cookie)).andExpect(status().isForbidden());
        assertThat(stores.sessions).hasSize(1);

        MvcResult ok = mvc.perform(post("/api/v1/bff/logout").cookie(cookie).header(props.getCsrfHeader(), "1")).andExpect(status().isOk()).andReturn();
        assertThat(setCookie(ok, props.getCookieName())).contains("Max-Age=0");
        assertThat(stores.sessions).isEmpty();
        assertThat(iam.revokeRequests).hasSize(1);
    }

    @Test
    @DisplayName("쿠키 없는 로그아웃은 지울 것이 없으므로 성공한다")
    void logoutWithoutCookieSucceeds() throws Exception {
        mvc.perform(post("/api/v1/bff/logout")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("서비스가 하위 경로에만 쓰면 세션 쿠키 Path 를 그 경로로 좁힐 수 있다")
    void cookiePathCanBeNarrowed() {
        props.setCookiePath("/party");

        assertThat(new BffCookies(props).session("v").toString()).contains("Path=/party");
        assertThat(new BffCookies(props).clearedSession().toString()).contains("Path=/party", "Max-Age=0");
    }

    @Test
    @DisplayName("BffCrypto: 왕복 복호화, 변조·다른 키·잘못된 형식은 거부, 키 검증")
    void crypto() {
        BffCrypto crypto = new BffCrypto(props.getSessionEncryptionKey());
        String enc = crypto.encrypt("secret-token");

        assertThat(enc).startsWith("v1:").doesNotContain("secret-token");
        assertThat(crypto.decrypt(enc)).isEqualTo("secret-token");
        assertThat(crypto.encrypt("secret-token")).as("IV 가 매번 달라 같은 입력도 다른 암호문").isNotEqualTo(enc);

        String tampered = enc.substring(0, enc.length() - 4) + (enc.endsWith("AAAA") ? "BBBB" : "AAAA");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BffCrypto(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})).decrypt(enc))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crypto.decrypt("plain")).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BffCrypto(null)).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BffCrypto("not-base64!!")).isInstanceOf(IllegalStateException.class);
        BffCrypto other = new BffCrypto(java.util.Base64.getEncoder().encodeToString(new byte[32]).replace("A", "B"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> other.decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("deleteAllByUserId 는 그 사용자의 세션만 지운다(탈퇴 익명화 계약)")
    void deleteAllByUserIdOnlyRemovesThatUser() throws Exception {
        loginAndGetSessionCookie();
        UUID other = UUID.randomUUID();
        stores.sessions.put("other", new BffSession("other", other, "a", props.getSessionTtl() == null ? null : java.time.Instant.now(), "r",
                java.time.Instant.now(), java.time.Instant.now(), java.time.Instant.now().plusSeconds(60)));

        assertThat(stores.deleteAllByUserId(USER)).isEqualTo(1);
        assertThat(List.copyOf(stores.sessions.keySet())).containsExactly("other");
    }
}
