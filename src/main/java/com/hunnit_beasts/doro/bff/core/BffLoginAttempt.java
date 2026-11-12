package com.hunnit_beasts.doro.bff.core;

import java.time.Instant;

/**
 * 진행 중인 로그인(인가 요청을 보냈고 콜백을 기다리는 상태). state 해시로 찾고 한 번 쓰면 지운다.
 *
 * @param codeVerifierEnc {@link BffCrypto} 로 암호화한 PKCE verifier
 * @param browserHash     로그인을 시작한 브라우저에 심은 일회용 값의 해시(콜백이 그 브라우저에서 왔는지 대조: 로그인 CSRF 방지)
 */
public record BffLoginAttempt(String stateHash, String codeVerifierEnc, String returnPath, String browserHash,
                              Instant createdAt, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
