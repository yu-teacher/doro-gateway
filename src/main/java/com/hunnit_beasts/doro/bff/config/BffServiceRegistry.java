package com.hunnit_beasts.doro.bff.config;

import com.hunnit_beasts.doro.bff.store.BffStorage;
import com.hunnit_beasts.doro.bff.store.ServiceStores;
import com.hunnit_beasts.doro.bff.core.BffAuthService;
import com.hunnit_beasts.doro.bff.core.BffController;
import com.hunnit_beasts.doro.bff.core.BffCookies;
import com.hunnit_beasts.doro.bff.core.BffCrypto;
import com.hunnit_beasts.doro.bff.core.BffOAuthClient;
import com.hunnit_beasts.doro.bff.core.BffProperties;
import com.hunnit_beasts.doro.bff.core.BffUserHook;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 설정에 등록된 서비스마다 SDK 의 BFF 구성 요소(OAuth 클라이언트, 쿠키, 인증 서비스, 컨트롤러)를 한 벌씩 만든다.
 * 로그인·세션·갱신 규칙은 SDK 코드 그대로이고, 이 클래스는 서비스 이름으로 그것들을 찾아 주는 일만 한다.
 */
@Component
public class BffServiceRegistry {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private final Map<String, ServiceBff> services = new LinkedHashMap<>();

    public BffServiceRegistry(DoroBffProperties config, BffStorage storage, DoroTokenVerifier verifier) {
        if (config.getServices().isEmpty()) {
            throw new IllegalStateException("doro.bff.services 에 서비스가 하나도 없다");
        }
        BffCrypto crypto = new BffCrypto(config.getSessionEncryptionKey());
        Map<String, String> clientOwners = new LinkedHashMap<>();
        Map<String, String> cookieOwners = new LinkedHashMap<>();
        config.getServices().forEach((name, svc) -> {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException("서비스 이름은 소문자·숫자·하이픈이다: " + name);
            }
            require(svc.getClientId(), name, "client-id");
            require(svc.getCookieName(), name, "cookie-name");
            require(svc.getLoginCookieName(), name, "login-cookie-name");
            require(svc.getRedirectUri(), name, "redirect-uri");
            require(svc.getCsrfHeader(), name, "csrf-header");
            // 서비스끼리 OAuth 클라이언트나 쿠키 이름이 겹치면 한 서비스의 세션이 다른 서비스에서 통할 수 있다.
            unique(clientOwners, svc.getClientId(), name, "client-id");
            unique(cookieOwners, svc.getCookieName(), name, "cookie-name");
            unique(cookieOwners, svc.getLoginCookieName(), name, "login-cookie-name");

            BffProperties p = new BffProperties();
            p.setClientId(svc.getClientId());
            p.setAuthorizeUrl(config.getAuthorizeUrl());
            p.setTokenUrl(config.getTokenUrl());
            p.setRevokeUrl(config.getRevokeUrl());
            p.setRedirectUri(svc.getRedirectUri());
            p.setCookieName(svc.getCookieName());
            p.setLoginCookieName(svc.getLoginCookieName());
            p.setCookiePath(svc.getCookiePath());
            p.setWebBasePath(svc.getWebBasePath());
            p.setReturnPathRelativeToWebBase(svc.isReturnPathRelativeToWebBase());
            p.setSessionEncryptionKey(config.getSessionEncryptionKey());
            p.setCookieSecure(config.isCookieSecure());
            p.setSessionTtl(config.getSessionTtl());
            p.setLoginAttemptTtl(config.getLoginAttemptTtl());
            p.setRefreshMargin(config.getRefreshMargin());
            p.setCsrfHeader(svc.getCsrfHeader());
            p.setHttpConnectTimeout(config.getHttpConnectTimeout());
            p.setHttpReadTimeout(config.getHttpReadTimeout());

            ServiceStores stores = new ServiceStores(name, storage);
            BffCookies cookies = new BffCookies(p);
            BffAuthService auth = new BffAuthService(p, new BffOAuthClient(p), crypto, stores, stores, verifier,
                    BffUserHook.none(), Clock.systemUTC());
            services.put(name, new ServiceBff(name, p, cookies, auth, new BffController(auth, p, cookies)));
        });
    }

    public Optional<ServiceBff> find(String name) {
        return Optional.ofNullable(name == null ? null : services.get(name));
    }

    public Map<String, ServiceBff> all() {
        return services;
    }

    private static void require(String value, String service, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("doro.bff.services." + service + "." + key + " 설정이 필요하다");
        }
    }

    private static void unique(Map<String, String> owners, String value, String service, String key) {
        String previous = owners.putIfAbsent(value, service);
        if (previous != null) {
            throw new IllegalStateException("doro.bff.services 의 " + key + " 값이 겹친다: " + value + " (" + previous + ", " + service + ")");
        }
    }
}
