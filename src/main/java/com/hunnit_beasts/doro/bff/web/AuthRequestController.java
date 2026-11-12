package com.hunnit_beasts.doro.bff.web;

import com.hunnit_beasts.doro.bff.config.BffServiceRegistry;
import com.hunnit_beasts.doro.bff.config.ServiceBff;
import com.hunnit_beasts.doro.bff.core.BffAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.Set;

/**
 * 게이트웨이(nginx {@code auth_request})가 API 요청마다 부르는 내부 엔드포인트. 세션 쿠키를 액세스 토큰으로 바꿔 돌려준다.
 *
 * <p>응답: {@code 204} + {@code X-Doro-Access-Token}(로그인한 세션) 또는 {@code 204} 만(익명: 쿠키 없음·만료·폐기·IAM 장애로 갱신 못 함),
 * {@code 403}(쿠키로 상태를 바꾸는데 CSRF 증명 없음). 게이트웨이는 토큰이 있을 때만 백엔드로 {@code Authorization: Bearer} 를 붙이고,
 * 클라이언트가 보낸 {@code Authorization}·{@code X-Doro-*} 는 항상 지운다.
 *
 * <p>이 경로는 외부에 노출하지 않는다(게이트웨이의 {@code internal} location 에서만 호출).
 */
@RestController
public class AuthRequestController {

    private static final Logger log = LoggerFactory.getLogger(AuthRequestController.class);
    public static final String SERVICE_HEADER = "X-Doro-Service";
    public static final String ORIGINAL_METHOD_HEADER = "X-Original-Method";
    public static final String TOKEN_HEADER = "X-Doro-Access-Token";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final BffServiceRegistry registry;

    public AuthRequestController(BffServiceRegistry registry) {
        this.registry = registry;
    }

    @RequestMapping("/internal/auth")
    public ResponseEntity<Void> authenticate(HttpServletRequest request) {
        Optional<ServiceBff> service = registry.find(request.getHeader(SERVICE_HEADER));
        if (service.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        ServiceBff svc = service.get();
        String cookieValue = svc.cookies().sessionValue(request);
        if (cookieValue == null) {
            return anonymous();
        }

        String method = request.getHeader(ORIGINAL_METHOD_HEADER);
        if (method != null && !SAFE_METHODS.contains(method.toUpperCase()) && !passesCsrfCheck(svc, request)) {
            log.warn("Rejected a cookie-authenticated request without a valid CSRF proof: service={}, method={}", svc.name(), method);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        if (method == null) {
            // 게이트웨이 설정 오류: 메서드를 모르면 CSRF 검사를 할 수 없다. 로그인으로 인정하지 않는다.
            log.error("auth_request without {} header: service={}", ORIGINAL_METHOD_HEADER, svc.name());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }

        Optional<BffAuthService.Authenticated> auth = svc.auth().authenticateWithToken(cookieValue);
        if (auth.isEmpty()) {
            return anonymous();
        }
        return ResponseEntity.noContent().header(TOKEN_HEADER, auth.get().accessToken())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static ResponseEntity<Void> anonymous() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /** 쿠키로 상태를 바꾸는 요청: (1) 커스텀 헤더가 있어야 하고(다른 사이트는 CORS 허용 없이 붙일 수 없다) (2) Origin 이 있다면 이 사이트와 같아야 한다. */
    private static boolean passesCsrfCheck(ServiceBff svc, HttpServletRequest request) {
        String header = request.getHeader(svc.props().getCsrfHeader());
        if (header == null || header.isBlank()) {
            return false;
        }
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(svc.props().publicOrigin());
    }
}
