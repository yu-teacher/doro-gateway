package com.hunnit_beasts.doro.bff.core;

import java.time.Instant;
import java.util.Optional;

/** 로그인 시도 저장소. 서비스(클라이언트)마다 BFF 의 DB 로 구현한다. 각 메서드는 그 자체로 원자적이어야 한다. */
public interface BffLoginAttemptStore {

    void save(BffLoginAttempt attempt);

    /**
     * state 는 한 번만 쓸 수 있다: 이 호출이 행을 **실제로 지운 경우에만** 값을 돌려준다. 동시에 두 번 호출되면 하나만 성공한다.
     */
    Optional<BffLoginAttempt> consume(String stateHash);

    int deleteExpired(Instant now);
}
