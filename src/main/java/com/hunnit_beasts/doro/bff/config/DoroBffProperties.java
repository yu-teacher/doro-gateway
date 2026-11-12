package com.hunnit_beasts.doro.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BFF 설정({@code doro.bff.*}). IAM 주소·암호화 키·쿠키 정책은 모든 서비스가 공유하고, 서비스마다 다른 것(OAuth 클라이언트, 쿠키 이름·경로,
 * 복귀 경로 규칙, CSRF 헤더)은 {@code doro.bff.services.<이름>.*} 에 둔다. 서비스 이름은 게이트웨이가 {@code X-Doro-Service} 로 넘기는 값이다.
 */
@ConfigurationProperties(prefix = "doro.bff")
public class DoroBffProperties {

    /** 브라우저가 이동하는 IAM 인가 엔드포인트(공개 주소) */
    private String authorizeUrl = "http://localhost:28080/oauth2/authorize";
    /** 서버가 직접 호출하는 IAM 토큰 엔드포인트(내부 주소 가능) */
    private String tokenUrl = "http://localhost:28080/oauth2/token";
    private String revokeUrl = "http://localhost:28080/oauth2/revoke";
    /** 저장하는 토큰을 암호화하는 키(base64 32바이트). 기본값 없음: openssl rand -base64 32 */
    private String sessionEncryptionKey;
    private boolean cookieSecure = true;
    private Duration sessionTtl = Duration.ofDays(30);
    private Duration loginAttemptTtl = Duration.ofMinutes(10);
    private Duration refreshMargin = Duration.ofSeconds(60);
    private Duration httpConnectTimeout = Duration.ofSeconds(3);
    private Duration httpReadTimeout = Duration.ofSeconds(5);
    private Map<String, Service> services = new LinkedHashMap<>();

    public static class Service {
        /** Doro 에 등록한 이 서비스의 OAuth 클라이언트 ID (필수) */
        private String clientId;
        private String cookieName;
        private String loginCookieName;
        private String cookiePath = "/";
        /** 웹이 마운트된 공개 하위 경로(예: /blog). 루트면 비운다. */
        private String webBasePath = "";
        /** Doro 에 등록한 redirect_uri(공개 주소). 서비스의 콜백 주소와 정확히 같아야 한다. */
        private String redirectUri;
        private String csrfHeader;
        private boolean returnPathRelativeToWebBase = false;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getCookieName() { return cookieName; }
        public void setCookieName(String cookieName) { this.cookieName = cookieName; }
        public String getLoginCookieName() { return loginCookieName; }
        public void setLoginCookieName(String loginCookieName) { this.loginCookieName = loginCookieName; }
        public String getCookiePath() { return cookiePath; }
        public void setCookiePath(String cookiePath) { this.cookiePath = cookiePath; }
        public String getWebBasePath() { return webBasePath; }
        public void setWebBasePath(String webBasePath) { this.webBasePath = webBasePath; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
        public String getCsrfHeader() { return csrfHeader; }
        public void setCsrfHeader(String csrfHeader) { this.csrfHeader = csrfHeader; }
        public boolean isReturnPathRelativeToWebBase() { return returnPathRelativeToWebBase; }
        public void setReturnPathRelativeToWebBase(boolean v) { this.returnPathRelativeToWebBase = v; }
    }

    public String getAuthorizeUrl() { return authorizeUrl; }
    public void setAuthorizeUrl(String authorizeUrl) { this.authorizeUrl = authorizeUrl; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getRevokeUrl() { return revokeUrl; }
    public void setRevokeUrl(String revokeUrl) { this.revokeUrl = revokeUrl; }
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
    public Duration getHttpConnectTimeout() { return httpConnectTimeout; }
    public void setHttpConnectTimeout(Duration d) { this.httpConnectTimeout = d; }
    public Duration getHttpReadTimeout() { return httpReadTimeout; }
    public void setHttpReadTimeout(Duration d) { this.httpReadTimeout = d; }
    public Map<String, Service> getServices() { return services; }
    public void setServices(Map<String, Service> services) { this.services = services; }
}
