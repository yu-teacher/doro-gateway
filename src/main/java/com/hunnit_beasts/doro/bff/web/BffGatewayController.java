package com.hunnit_beasts.doro.bff.web;

import com.hunnit_beasts.doro.bff.config.BffServiceRegistry;
import com.hunnit_beasts.doro.bff.config.ServiceBff;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 서비스별 로그인·콜백·로그아웃. 동작은 {@code core.BffController} 가 맡고, 서비스 이름으로 그 서비스의 구성을 골라 위임한다.
 * 게이트웨이가 브라우저가 보는 기존 주소(예: /party/api/v1/bff/login)를 이 경로(/bff/party/login)로 이어 준다.
 */
@RestController
@RequestMapping("/bff/{service}")
public class BffGatewayController {

    private final BffServiceRegistry registry;

    public BffGatewayController(BffServiceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login(@PathVariable String service,
                                      @RequestParam(name = "return", required = false) String returnPath) {
        return svc(service).controller().login(returnPath);
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@PathVariable String service, HttpServletRequest request,
                                         @RequestParam(name = "code", required = false) String code,
                                         @RequestParam(name = "state", required = false) String state,
                                         @RequestParam(name = "error", required = false) String error) {
        return svc(service).controller().callback(request, code, state, error);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@PathVariable String service, HttpServletRequest request) {
        return svc(service).controller().logout(request);
    }

    private ServiceBff svc(String service) {
        return registry.find(service).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
