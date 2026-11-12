package com.hunnit_beasts.doro.bff.core;

import java.time.Instant;
import java.util.UUID;

/**
 * BFF 로그인 세션. 쿠키에는 세션 식별자 원문만 있고 저장소에는 그 해시와 암호화된 토큰이 있다.
 *
 * @param accessTokenEnc  {@link BffCrypto} 로 암호화한 액세스 토큰
 * @param refreshTokenEnc {@link BffCrypto} 로 암호화한 리프레시 토큰(회전되므로 갱신 때마다 바뀐다)
 */
public record BffSession(String sessionHash, UUID userId, String accessTokenEnc, Instant accessExpiresAt,
                         String refreshTokenEnc, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
