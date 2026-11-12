package com.hunnit_beasts.doro.bff;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** 시험용 가짜 IAM: 토큰·폐기 엔드포인트. 토큰 응답은 시험이 정하고, 받은 요청을 기록한다. */
final class FakeIam implements AutoCloseable {

    private final HttpServer server;
    final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> revokeRequests = new CopyOnWriteArrayList<>();
    volatile int tokenStatus = 200;
    volatile String tokenBody = tokenJson("access-1", "refresh-1", 900);

    FakeIam() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/oauth2/token", ex -> {
            tokenRequests.add(form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(ex, tokenStatus, tokenBody);
        });
        server.createContext("/oauth2/revoke", ex -> {
            revokeRequests.add(form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(ex, 200, "{}");
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    static String tokenJson(String access, String refresh, int expiresIn) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
