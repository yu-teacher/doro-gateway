package com.hunnit_beasts.doro.bff.core;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * BFF 세션 저장소. 서비스(클라이언트)마다 하나씩, BFF 의 DB 로 구현한다({@code store.ServiceStores}). 이 계층은 트랜잭션을 열지 않으므로
 * 각 메서드는 그 자체로 원자적이어야 한다(구현체에 {@code @Transactional} 을 붙인다).
 */
public interface BffSessionStore {

    Optional<BffSession> find(String sessionHash);

    /** 새 세션을 저장하고, previousSessionHash 가 있으면 같은 트랜잭션에서 지운다(새 로그인 때 옛 세션을 폐기해 세션 고정을 막는다). */
    void create(BffSession session, String previousSessionHash);

    /**
     * 토큰 갱신 결과만 반영한다. 엔티티를 통째로 저장하지 않는다: 그사이 다른 요청이 쓴 값을 덮어쓰면 안 된다.
     * (리프레시 토큰이 회전되므로 옛 값으로 되돌려 쓰면 IAM 이 탈취로 판단해 세션이 끊긴다)
     */
    void updateTokens(String sessionHash, String accessTokenEnc, Instant accessExpiresAt, String refreshTokenEnc);

    /** 마지막 사용 시각 한 컬럼만 바꾼다. 위와 같은 이유로 다른 컬럼은 건드리지 않는다. */
    void touchLastUsed(String sessionHash, Instant now);

    void delete(String sessionHash);

    /** 탈퇴 익명화: 사용자의 모든 BFF 세션(암호화된 토큰 포함)을 지운다. 지운 개수를 돌려준다. */
    int deleteAllByUserId(UUID userId);

    int deleteExpired(Instant now);
}
