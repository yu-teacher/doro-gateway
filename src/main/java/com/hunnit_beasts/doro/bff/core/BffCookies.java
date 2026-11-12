package com.hunnit_beasts.doro.bff.core;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

/** BFF 쿠키 만들기·읽기. 세션 쿠키와 '로그인 진행 중' 쿠키 모두 HttpOnly + SameSite=Lax. */
public class BffCookies {

    private final BffProperties props;

    public BffCookies(BffProperties props) {
        this.props = props;
    }

    public ResponseCookie session(String value) {
        return ResponseCookie.from(props.getCookieName(), value)
                .httpOnly(true).secure(props.isCookieSecure()).sameSite("Lax")
                .path(props.getCookiePath()).maxAge(props.getSessionTtl()).build();
    }

    public ResponseCookie clearedSession() {
        return ResponseCookie.from(props.getCookieName(), "")
                .httpOnly(true).secure(props.isCookieSecure()).sameSite("Lax")
                .path(props.getCookiePath()).maxAge(0).build();
    }

    /** 로그인을 시작한 브라우저를 표시하는 쿠키. 콜백이 올 때까지(로그인 시도 수명)만 살아 있고, 로그인 API 경로에서만 전송된다. */
    public ResponseCookie login(String value) {
        return ResponseCookie.from(props.getLoginCookieName(), value)
                .httpOnly(true).secure(props.isCookieSecure()).sameSite("Lax")
                .path(props.loginCookiePath()).maxAge(props.getLoginAttemptTtl()).build();
    }

    public ResponseCookie clearedLogin() {
        return ResponseCookie.from(props.getLoginCookieName(), "")
                .httpOnly(true).secure(props.isCookieSecure()).sameSite("Lax")
                .path(props.loginCookiePath()).maxAge(0).build();
    }

    public String sessionValue(HttpServletRequest request) {
        return value(request, props.getCookieName());
    }

    public String loginValue(HttpServletRequest request) {
        return value(request, props.getLoginCookieName());
    }

    private static String value(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
