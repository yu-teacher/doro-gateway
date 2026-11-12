package com.hunnit_beasts.doro.bff.core;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.Map;

/**
 * 한 서비스의 Doro OAuth 로그인(BFF) 동작. 브라우저는 login 으로 이동해 Doro 에서 로그인하고, callback 에서 세션 쿠키를 받는다.
 * 토큰은 서버에만 있고 JavaScript 는 볼 수 없다. 로그인 상태 조회(/session)는 서비스가 자기 프로필 모양으로 직접 제공한다.
 * 스프링 빈이 아니다: 서비스마다 구성({@code config.BffServiceRegistry})이 하나씩 만들고, HTTP 경로는 {@code web.BffGatewayController} 가 맡는다.
 */
public class BffController {

    private static final Logger log = LoggerFactory.getLogger(BffController.class);

    /** 로그인 실패 시 돌려보내는 곳의 쿼리 값. 상세 사유는 노출하지 않는다. */
    private static final String LOGIN_ERROR_PARAM = "login_error";

    private final BffAuthService authService;
    private final BffProperties props;
    private final BffCookies cookies;

    public BffController(BffAuthService authService, BffProperties props, BffCookies cookies) {
        this.authService = authService;
        this.props = props;
        this.cookies = cookies;
    }

    /** 로그인 시작: Doro 로그인 화면으로 이동한다. return 은 로그인 후 돌아올 사이트 내 경로. */
    public ResponseEntity<Void> login(String returnPath) {
        BffAuthService.StartedLogin started = authService.startLogin(returnPath);
        // 이 브라우저를 로그인 시도에 묶는다: 콜백이 같은 브라우저에서 와야 끝낼 수 있다(남이 시작한 로그인을 이 사람에게 끝내게 하는 공격 방지)
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, started.authorizeUrl())
                .header(HttpHeaders.SET_COOKIE, cookies.login(started.browserNonce()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /** 로그인 콜백: Doro 가 인가 코드와 함께 돌려보내는 곳. 세션 쿠키를 발급하고 원래 페이지로 이동한다. */
    public ResponseEntity<Void> callback(
            HttpServletRequest request,
            String code,
            String state,
            String error) {
        if (error != null) {
            // 사용자가 취소했거나 Doro 가 거부했다. 사유를 화면에 그대로 싣지 않는다.
            log.info("BFF login ended with an authorization error from IAM: {}", error);
            return redirect(props.webPath("/") + "?" + LOGIN_ERROR_PARAM + "=cancelled", cookies.clearedLogin());
        }
        try {
            BffAuthService.LoginResult result = authService.completeLogin(code, state, cookies.sessionValue(request), cookies.loginValue(request));
            return redirect(result.returnPath(), cookies.session(result.sessionCookieValue()), cookies.clearedLogin());
        } catch (BffAuthService.LoginFailedException e) {
            log.warn("BFF login failed: {}", e.getMessage());
            // 이미 로그인한 사용자의 세션 쿠키는 건드리지 않는다. 가짜 콜백 링크 하나로 남을 로그아웃시킬 수 없게 한다.
            return redirect(props.webPath("/") + "?" + LOGIN_ERROR_PARAM + "=failed", cookies.clearedLogin());
        }
    }

    /** 로그아웃: 세션과 Doro 세션을 종료한다. 상태를 바꾸는 요청이므로 CSRF 헤더가 필요하다. */
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        String cookieValue = cookies.sessionValue(request);
        String csrf = request.getHeader(props.getCsrfHeader());
        if (cookieValue != null && (csrf == null || csrf.isBlank())) {
            // 필터의 CSRF 검사와 같은 규칙. (쿠키 없이 호출하면 지울 것이 없으므로 그냥 성공)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(BffResponses.error(HttpStatus.FORBIDDEN, "AUTH-403-03", "요청을 확인할 수 없습니다."));
        }
        authService.logout(cookieValue);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clearedSession().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(BffResponses.success());
    }

    private ResponseEntity<Void> redirect(String path, ResponseCookie... cookieList) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, URI.create(path).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        for (ResponseCookie cookie : cookieList) {
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }
}
