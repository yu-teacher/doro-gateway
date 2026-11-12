package com.hunnit_beasts.doro.bff.core;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 워크스페이스 표준 응답 모양({@code success}, {@code code}, {@code message}, {@code status}, 중첩 {@code error})을 만든다. */
final class BffResponses {

    private BffResponses() {}

    static Map<String, Object> success() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    static Map<String, Object> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        body.put("status", status.value());
        body.put("error", Map.of("code", code, "message", message));
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
