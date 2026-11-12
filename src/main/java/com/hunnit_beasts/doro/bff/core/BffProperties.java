package com.hunnit_beasts.doro.bff.core;

import java.net.URI;
import java.time.Duration;

/**
 * BFF 로그인(Doro OAuth 인가 코드 + PKCE, 토큰은 서버에만 두고 브라우저에는 세션 쿠키만 준다) 설정.
 *
 * <p>서비스가 자기 접두사로 바인딩한 빈을 하나 등록한다(그래야 기존 환경변수 이름이 그대로다).
 * <pre>{@code
 * @Bean @ConfigurationProperties(prefix = "blog.auth")
 * BffProperties bffProperties() { return new BffProperties(); }
 * }</pre>
 * URL 과 비밀은 환경변수로만 바꾼다.
 */
public class BffProperties {

    /** Doro 에 등록한 이 서비스의 OAuth 클라이언트 ID (필수) */
    private String clientId;
    /** 브라우저가 이동하는 인가 엔드포인트(공개 주소) */
    private String authorizeUrl = "http://localhost:28080/oauth2/authorize";
    /** 서버가 직접 호출하는 토큰 엔드포인트(내부 주소 가능) */
    private String tokenUrl = "http://localhost:28080/oauth2/token";
    /** 서버가 직접 호출하는 토큰 폐기 엔드포인트(로그아웃 시 IAM 세션 종료) */
    private String revokeUrl = "http://localhost:28080/oauth2/revoke";
    /** Doro 에 등록한 redirect_uri. 이 서비스의 콜백 주소(공개 주소)와 정확히 같아야 한다. */
    private String redirectUri = "http://localhost:5173/api/v1/bff/callback";
    private String scope = "openid profile email";
    /** 세션 쿠키 이름 (서비스마다 달라야 한다) */
    private String cookieName = "doro_session";
    /** 로그인을 시작한 브라우저를 콜백에서 다시 알아보기 위한 짧은 수명의 쿠키 이름 */
    private String loginCookieName = "doro_login";
    /** 세션 쿠키의 Path. 서비스가 하위 경로(/party 등)에만 쓰면 그 경로로 좁힌다. */
    private String cookiePath = "/";
    /**
     * 웹이 마운트된 공개 하위 경로(예: /blog). 루트면 빈 문자열. 로그인 시도 쿠키의 Path 와 로그인 실패 시 이동 위치, 복귀 경로가 없을 때의
     * 기본 위치가 이 값을 따른다. 게이트웨이가 이 접두사를 떼고 백엔드로 전달하므로 브라우저가 보는 공개 경로와 백엔드 경로가 다르다.
     */
    private String webBasePath = "";
    /**
     * 로그인 후 돌아갈 경로({@code return} 파라미터)를 웹 기준 경로({@link #webBasePath}) 기준의 앱 내부 경로로 볼지.
     * false(기본): 이미 브라우저가 보는 공개 경로(예: {@code /blog/@alice})를 받는다 — blog 웹이 이렇게 보낸다.
     * true: 라우터 기준 경로(예: {@code /maps/42})를 받아 {@link #webBasePath} 를 앞에 붙인다 — party/games 웹이 이렇게 보낸다.
     */
    private boolean returnPathRelativeToWebBase = false;
    /** AES-256 세션 암호화 키(base64 32바이트). 생성: openssl rand -base64 32 */
    private String sessionEncryptionKey;
    /** HTTPS 에서만 쿠키를 보낸다. 로컬 HTTP 개발에서만 false. */
    private boolean cookieSecure = true;
    /** 로그인 후 세션의 절대 수명 */
    private Duration sessionTtl = Duration.ofDays(30);
    /** 인가 요청을 보낸 뒤 콜백이 돌아와야 하는 시간 */
    private Duration loginAttemptTtl = Duration.ofMinutes(10);
    /** 액세스 토큰 만료 이 시간 전부터 미리 갱신한다 */
    private Duration refreshMargin = Duration.ofSeconds(60);
    /** 쿠키 인증으로 상태를 바꾸는 요청에 반드시 붙어야 하는 헤더(CSRF 방어: 다른 사이트는 커스텀 헤더를 붙일 수 없다) */
    private String csrfHeader = "X-Doro-Csrf";
    private Duration httpConnectTimeout = Duration.ofSeconds(3);
    private Duration httpReadTimeout = Duration.ofSeconds(5);

    /** 앱 내부 경로(/ , /@user 등)를 브라우저가 이동할 실제 공개 경로로 바꾼다. 루트 마운트면 그대로다. */
    public String webPath(String appPath) {
        return (webBasePath == null ? "" : webBasePath.replaceAll("/+$", "")) + appPath;
    }

    /** 로그인 시도 쿠키의 Path. 브라우저가 콜백을 부르는 공개 경로(웹 기준 경로 + /api/v1/bff)와 같아야 쿠키가 따라온다. */
    public String loginCookiePath() {
        return webPath("/api/v1/bff");
    }

    /** redirect_uri 의 origin(scheme://host[:port]). Origin 헤더 검증에 쓴다. */
    public String publicOrigin() {
        URI uri = URI.create(redirectUri);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getAuthorizeUrl() { return authorizeUrl; }
    public void setAuthorizeUrl(String authorizeUrl) { this.authorizeUrl = authorizeUrl; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getRevokeUrl() { return revokeUrl; }
    public void setRevokeUrl(String revokeUrl) { this.revokeUrl = revokeUrl; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getCookieName() { return cookieName; }
    public void setCookieName(String cookieName) { this.cookieName = cookieName; }
    public String getLoginCookieName() { return loginCookieName; }
    public void setLoginCookieName(String loginCookieName) { this.loginCookieName = loginCookieName; }
    /** 세션 쿠키 Path. 설정이 비어 있으면(환경변수를 빈 값으로 둔 경우 포함) 루트 "/" 이다. */
    public String getCookiePath() { return cookiePath == null || cookiePath.isBlank() ? "/" : cookiePath; }
    public void setCookiePath(String cookiePath) { this.cookiePath = cookiePath; }
    public boolean isReturnPathRelativeToWebBase() { return returnPathRelativeToWebBase; }
    public void setReturnPathRelativeToWebBase(boolean returnPathRelativeToWebBase) { this.returnPathRelativeToWebBase = returnPathRelativeToWebBase; }
    public String getWebBasePath() { return webBasePath; }
    public void setWebBasePath(String webBasePath) { this.webBasePath = webBasePath; }
    public String getSessionEncryptionKey() { return sessionEncryptionKey; }
    public void setSessionEncryptionKey(String sessionEncryptionKey) { this.sessionEncryptionKey = sessionEncryptionKey; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
    public Duration getLoginAttemptTtl() { return loginAttemptTtl; }
    public void setLoginAttemptTtl(Duration loginAttemptTtl) { this.loginAttemptTtl = loginAttemptTtl; }
    public Duration getRefreshMargin() { return refreshMargin; }
    public void setRefreshMargin(Duration refreshMargin) { this.refreshMargin = refreshMargin; }
    public String getCsrfHeader() { return csrfHeader; }
    public void setCsrfHeader(String csrfHeader) { this.csrfHeader = csrfHeader; }
    public Duration getHttpConnectTimeout() { return httpConnectTimeout; }
    public void setHttpConnectTimeout(Duration httpConnectTimeout) { this.httpConnectTimeout = httpConnectTimeout; }
    public Duration getHttpReadTimeout() { return httpReadTimeout; }
    public void setHttpReadTimeout(Duration httpReadTimeout) { this.httpReadTimeout = httpReadTimeout; }
}
