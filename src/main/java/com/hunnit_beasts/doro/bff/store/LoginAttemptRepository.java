package com.hunnit_beasts.doro.bff.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LoginAttemptRepository extends JpaRepository<LoginAttemptEntity, String> {

    /** state 는 한 번만 쓸 수 있다: 지운 행이 1 이어야만 그 콜백이 유효하다(동시에 두 번 오면 하나만 성공). */
    @Modifying
    @Query("DELETE FROM LoginAttemptEntity a WHERE a.service = :service AND a.stateHash = :hash")
    int deleteByServiceAndStateHash(@Param("service") String service, @Param("hash") String hash);

    @Modifying
    @Query("DELETE FROM LoginAttemptEntity a WHERE a.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
